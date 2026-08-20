package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest.FirmaSceltaDTO;
import com.agostinelli.gestionale.movimenti.importlayer.DescNormalizer;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correzione sempre aperta (SPEC import-v2 §3, R13–R15) e voto sulla firma (R17).
 *
 * <p>La decisione n.4 del titolare: «ricatalogare riscrive il movimento». Nessuno storno, nessun
 * concetto di periodo chiuso — il prima/dopo lo conserva {@code trg_audit_movimenti}.
 */
@QuarkusTest
class RicatalogazioneIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path DIR = Path.of("..", "dati_luglio");
    static final String BILLY = "corrispettivi-12 (1).csv";
    static final String BPM = "MovimentiCC_OnLine_07_08_2026_05.51.09.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_08_07_054028.csv";

    @Inject MovimentoImportService importService;
    @Inject ImportTriageService triage;
    @Inject EntityManager em;
    @Inject KeywordClassificazioneEngine keywordEngine;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(DIR), "Cartella dati_luglio assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(DIR.resolve(f)), "Fixture assente: " + f);
        }
    }

    @BeforeEach void before() { reset(); }
    @AfterEach  void after()  { reset(); }

    void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM eventi_da_riconciliare").executeUpdate();
            em.createNativeQuery("DELETE FROM ricorrenti_da_riconciliare").executeUpdate();
            em.createNativeQuery("DELETE FROM import_ambiguita").executeUpdate();
            em.createNativeQuery("DELETE FROM import_scartati").executeUpdate();
            em.createNativeQuery("DELETE FROM matching_differiti").executeUpdate();
            em.createNativeQuery("DELETE FROM quadratura_periodo").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN "
                    + "('IMPORT_CONGIUNTO','IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
            em.createNativeQuery("UPDATE keyword_firma SET usi_confermati = 0, usi_corretti = 0").executeUpdate();
        });
    }

    /**
     * R13/R14 — una riga già catalogata su un conto definitivo si ricataloga: UN solo movimento,
     * nessuno storno, e il prima/dopo leggibile in audit_log.
     */
    @Test
    void unaRigaGiaALibroSiRicataloga_riscrivendoIlMovimento() throws Exception {
        importa();
        UUID mov = catalogaUnTransitorio("40.05.002");
        long movimentiDopoPrimaCatalogazione = contaMovimenti();

        // Seconda passata: il movimento NON è più su un transitorio. Fino al 12/08 → 409.
        assertDoesNotThrow(() -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(mov, new ClassificaTransitorioRequest(
                        cogeId("40.02.002"), (short) 5, null, false, "corretto: era spesa bancaria"))));

        assertEquals(movimentiDopoPrimaCatalogazione, contaMovimenti(),
                "la ricatalogazione RISCRIVE: nessuno storno, nessun movimento in più");
        assertEquals(cogeId("40.02.002"), cogeDi(mov), "il conto è quello nuovo");

        // NB: movimenti è partizionata, quindi fn_audit_generic scrive TG_TABLE_NAME = il nome
        // della PARTIZIONE ("movimenti_2026"), non "movimenti". Chi cerca il prima/dopo deve
        // saperlo: un filtro `tabella = 'movimenti'` non trova niente e sembra che l'audit manchi.
        long update = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM audit_log WHERE tabella LIKE 'movimenti%' AND operazione = 'UPDATE' "
                + "AND record_id = :id AND dati_precedenti IS NOT NULL AND dati_nuovi IS NOT NULL")
                .setParameter("id", mov.toString()).getSingleResult()).longValue();
        assertTrue(update >= 2, "ogni riscrittura lascia il prima/dopo in audit_log: trovate " + update);
    }

    /** R15 — il mastro riservato agli eventi resta vietato anche in ricatalogazione. */
    @Test
    void ricatalogareSuUnRicavoEventoE409() throws Exception {
        importa();
        UUID mov = catalogaUnTransitorio("40.05.002");
        Integer cogeEvento = cogeId("30.02.002");
        Assumptions.assumeTrue(cogeEvento != null, "conto 30.02.002 assente");

        ApiException e = assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(mov, new ClassificaTransitorioRequest(
                        cogeEvento, (short) 2, null, false, null))));
        assertEquals("COGE_RISERVATO_EVENTI", e.getCode());
        assertEquals(cogeId("40.05.002"), cogeDi(mov), "il 409 non deve lasciare il movimento a metà");
    }

    /**
     * Il parcheggio dell'import non è una destinazione: 409, e la riga non si muove.
     *
     * <p>Il caso vero: il 12/08/2026 un'ENTRATA POS da 100 € è passata dal transitorio dei ricavi a
     * quello dei costi — un incasso classificato come «costo non classificato». Il server accettava
     * perché i due conti esistono e non sono {@code 30.02.*}, gli unici due controlli che c'erano.
     */
    @Test
    void catalogareSulContoDaCatalogareE409() throws Exception {
        importa();
        UUID mov = primoId("SELECT m.id FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id "
                + "WHERE p.codice = '39.99.999' AND m.stato <> 'ANNULLATO'");
        Assumptions.assumeTrue(mov != null, "nessuna riga sul transitorio ricavi nelle fixture");

        // Il caso di luglio: da un transitorio ALL'ALTRO, che è anche un cambio di segno.
        ApiException e = assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(mov, new ClassificaTransitorioRequest(
                        cogeId("49.99.999"), (short) 5, null, false, null))));
        assertEquals("COGE_TRANSITORIO", e.getCode());
        assertEquals(cogeId("39.99.999"), cogeDi(mov), "il 409 non deve lasciare il movimento a metà");

        // E nemmeno «riclassificarla» su se stessa, che sarebbe una scrittura senza significato.
        assertEquals("COGE_TRANSITORIO",
                assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                        triage.classificaTransitorio(mov, new ClassificaTransitorioRequest(
                                cogeId("39.99.999"), (short) 5, null, false, null)))).getCode());
        assertEquals(cogeId("39.99.999"), cogeDi(mov));
    }

    /** R13 — un movimento MANUALE non si tocca da qui: ha la sua strada (pagina Movimenti). */
    @Test
    void unMovimentoManualeNonSiRicatalogaDaQui() {
        UUID manuale = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO movimenti (id, data_movimento, tipo, importo_lordo, conto_coge_id, "
                + "business_unit_id, descrizione, fonte, created_by) "
                + "VALUES (:id, DATE '2026-07-15', 'USCITA', 10.00, :coge, 5, "
                + "'movimento inserito a mano', 'MANUALE', :u)")
                .setParameter("id", manuale).setParameter("coge", cogeId("49.99.999"))
                .setParameter("u", TEST_USER).executeUpdate());
        ApiException e = assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(manuale, new ClassificaTransitorioRequest(
                        cogeId("40.05.002"), (short) 5, null, false, null))));
        assertEquals("NON_DA_IMPORT", e.getCode());
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "DELETE FROM movimenti WHERE id = :id").setParameter("id", manuale).executeUpdate());
    }

    /**
     * R17 — confermare la proposta di una firma la vota a favore; correggerla la vota contro, e
     * una sola correzione le toglie per sempre la promozione (R18).
     */
    @Test
    void confermareECorreggereVotanoLaFirma() throws Exception {
        importa();
        TransitorioDTO conProposta = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> r.cogeSuggeritoId() != null)
                .filter(this::vieneDaUnaFirmaKeyword)
                .findFirst().orElse(null);
        Assumptions.assumeTrue(conProposta != null,
                "nessuna riga con proposta da firma keyword nelle fixture di agosdb_test");

        long confermatiPrima = somma("usi_confermati");
        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(conProposta.id(),
                new ClassificaTransitorioRequest(conProposta.cogeSuggeritoId(), (short) 5, null, false, null)));
        assertEquals(confermatiPrima + 1, somma("usi_confermati"),
                "confermare la proposta vota la firma a favore");
        assertEquals(0, somma("usi_corretti"));

        // La nota non deve più annunciare una proposta: l'utente ha già deciso.
        assertNull(notaDi(conProposta.id()) == null ? null
                        : (notaDi(conProposta.id()).contains("PROPOSTA[") ? "ancora presente" : null),
                "la proposta esaurita va tolta dalla nota");
    }

    @Test
    void correggereLaPropostaVotaControLaFirma() throws Exception {
        importa();
        TransitorioDTO conProposta = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> r.cogeSuggeritoId() != null)
                .filter(this::vieneDaUnaFirmaKeyword)
                .findFirst().orElse(null);
        Assumptions.assumeTrue(conProposta != null, "nessuna riga con proposta da firma keyword");

        Integer altro = cogeId("40.05.002");
        Assumptions.assumeTrue(!altro.equals(conProposta.cogeSuggeritoId()), "serve un conto diverso");
        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(conProposta.id(),
                new ClassificaTransitorioRequest(altro, (short) 5, null, false, null)));

        assertEquals(1, somma("usi_corretti"), "correggere la proposta vota la firma contro");
        assertEquals(0, somma("usi_confermati"));
    }

    /**
     * Quello che il sistema SA della riga arriva tutto al wizard (13/08/2026).
     *
     * <p>Non è un test estetico: ogni campo qui sotto era già calcolato o già a DB, e si perdeva
     * nel viaggio verso la schermata — l'operatore finiva per ridigitare a mano un dato che il
     * motore possedeva. Il test fallisce se un pezzo torna a fermarsi per strada.
     */
    @Test
    void ilTransitorioPortaAlWizardTuttoQuelloCheLaRigaSaDiSe() throws Exception {
        importa();
        List<TransitorioDTO> righe = triage.listTransitori(null, 0, 2000).content();
        Assumptions.assumeTrue(!righe.isEmpty(), "nessun transitorio nelle fixture");

        for (TransitorioDTO r : righe) {
            // Il fornitore riconosciuto dal motore arriva col NOME: un UUID non è una risposta
            // leggibile, e senza il nome la riga sembra «senza intestatario» anche quando non lo è.
            if (r.fornitoreId() != null) {
                assertNotNull(r.fornitoreNome(),
                        "fornitore " + r.fornitoreId() + " riconosciuto ma senza ragione sociale");
            }
            // Il ramo che il motore aveva calcolato non si butta: se la nota lo porta, il DTO
            // deve portarlo (è la riga che fa risparmiare una domanda all'operatore).
            String nota = notaDi(r.id());
            if (nota != null && nota.contains("|bu=")) {
                assertNotNull(r.buSuggerita(), "il ramo è scritto nella nota ma non arriva al wizard");
            }
            // Il riscontro Billy non è più un totale muto: se c'è, dice di che cosa era fatto.
            if (r.riscontroBilly() != null) {
                assertFalse(r.riscontroBilly().categorie().isEmpty(),
                        "un riscontro Billy senza ripartizione è il dato di prima, non quello nuovo");
                BigDecimal somma = r.riscontroBilly().categorie().stream()
                        .map(TransitorioDTO.VoceBillyDTO::totale)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertEquals(0, somma.compareTo(r.riscontroBilly().totale()),
                        "le voci devono ricomporre il totale della giornata, altrimenti sono due storie");
            }
        }

        // Il riferimento della banca è la chiave per ritrovare la riga sull'estratto conto: nessuna
        // riga importata deve arrivare al wizard senza. Se il parser smette di portarlo, si vede qui.
        assertEquals(righe.size(), righe.stream().filter(r -> r.riferimentoEsterno() != null).count(),
                "ogni riga d'import porta il proprio riferimento banca");

        // Il controllo sopra è per-riga: se il corpus non avesse NESSUNA riga con fornitore o con
        // ramo proposto, passerebbe senza aver verificato niente. Qui si dice quante ne esercita
        // davvero — un test che non tocca il caso che difende è un test che mente.
        System.out.printf("[wizard] %d transitori · %d con fornitore riconosciuto · %d con ramo "
                        + "proposto · %d con riscontro Billy%n",
                righe.size(),
                righe.stream().filter(r -> r.fornitoreNome() != null).count(),
                righe.stream().filter(r -> r.buSuggerita() != null).count(),
                righe.stream().filter(r -> r.riscontroBilly() != null).count());
    }

    /**
     * L'anteprima delle keyword non mente: quello che il wizard mostra è quello che verrà scritto.
     *
     * <p>È l'invariante che rende onesta la frase «Imparerò: PASINI + VERDURE». Se un giorno
     * l'anteprima e {@code KeywordLearningService.apprendi} usassero due estrazioni diverse, il
     * wizard prometterebbe una cosa e il sistema ne imparerebbe un'altra — e nessuno se ne
     * accorgerebbe fino al prossimo import che cataloga da solo qualcosa di inatteso.
     */
    @Test
    void leKeywordMostrateSonoEsattamenteQuelleCheVengonoImparate() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> !r.firmeDaImparare().isEmpty())
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga con firme da imparare nelle fixture");

        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(riga.id(),
                new ClassificaTransitorioRequest(cogeId("40.05.002"), (short) 5, null, true, null)));

        for (TransitorioDTO.FirmaDaImparareDTO f : riga.firmeDaImparare()) {
            assertEquals(1L, firmeConTokenEsatti(f.token()),
                    "mostrata la firma " + f.token() + " ma a DB non c'è (o è duplicata)");
        }
    }

    /** Dove l'anteprima è vuota non si impara: è la guardia contro le firme spurie da POS/RiBa. */
    @Test
    void doveNonCeNienteDaImparareNonNasceNessunaFirma() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> r.firmeDaImparare().isEmpty())
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga senza firme da imparare nelle fixture");

        long prima = contaFirme();
        // Anche se il client chiedesse di imparare, da qui non deve nascere niente di utile:
        // il wizard manda apprendiKeyword=false proprio perché il server dice che non c'è nulla.
        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(riga.id(),
                new ClassificaTransitorioRequest(cogeId("40.05.002"), (short) 5, null, false, null)));
        assertEquals(prima, contaFirme(), "da una causale senza intestatario non nasce nessuna firma");
    }

    // ── Keyword modificabili prima dell'apprendimento (SPEC omonima) ──────────────

    /**
     * R4 — quello che si vede è quello che si scrive. Accorciata la firma a una parola sola, a DB
     * c'è ESATTAMENTE quella e NON c'è quella che l'estrattore avrebbe prodotto.
     *
     * <p>È l'invariante che rende la feature diversa da un placebo: senza questo, spegnere un chip
     * cambierebbe solo il disegno a schermo e il sistema imparerebbe comunque la firma lunga.
     */
    @Test
    void iTokenSpentiNonFinisconoNellaFirma() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> r.firmeDaImparare().stream().anyMatch(f -> f.token().size() >= 2))
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga con una firma da >=2 token nelle fixture");

        List<String> intera = riga.firmeDaImparare().stream()
                .filter(f -> f.token().size() >= 2).findFirst().orElseThrow().token();
        List<String> accorciata = List.of(intera.get(0));

        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(riga.id(),
                new ClassificaTransitorioRequest(cogeId("40.05.002"), (short) 5, null, true,
                        List.of(new FirmaSceltaDTO(accorciata)), null)));

        assertEquals(1L, firmeConTokenEsatti(accorciata),
                "la firma accorciata " + accorciata + " deve esistere a DB, esattamente una volta");
        assertEquals(0L, firmeConTokenEsatti(intera),
                "la firma intera " + intera + " è stata spenta dall'operatore: non deve nascere");
    }

    /**
     * R5 — un token che non sta nella causale è 400, e NON lascia il movimento spostato.
     *
     * <p>Non è pedanteria: il match è in AND sul token-set della riga, quindi una firma con dentro
     * una parola che lì non c'è non scatterebbe MAI — esisterebbe a DB senza fare niente, e
     * nessuno se ne accorgerebbe.
     */
    @Test
    void unTokenInventatoE400ENonSpostaIlMovimento() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> !r.firmeDaImparare().isEmpty())
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga con firme da imparare nelle fixture");

        Integer cogePrima = cogeDi(riga.id());
        long firmePrima = contaFirme();
        ApiException e = assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(riga.id(), new ClassificaTransitorioRequest(
                        cogeId("40.05.002"), (short) 5, null, true,
                        List.of(new FirmaSceltaDTO(List.of("ZZQUESTAPAROLANONESISTE"))), null))));
        assertEquals("TOKEN_NON_NELLA_CAUSALE", e.getCode());
        assertEquals(cogePrima, cogeDi(riga.id()), "il 400 non deve lasciare il movimento spostato");
        assertEquals(firmePrima, contaFirme(), "e nemmeno scrivere firme");
    }

    /**
     * R6 — la regola di validità (§3A: ≥2 token oppure ≥1 forte) si riapplica DOPO la modifica,
     * non solo all'estrazione. Una parola generica da sola non è una firma.
     */
    @Test
    void unaFirmaRidottaAUnaParolaGenericaE400() throws Exception {
        importa();
        TransitorioDTO riga = null;
        String generica = null;
        for (TransitorioDTO r : triage.listTransitori(null, 0, 2000).content()) {
            String tk = primoTokenNormale(r);
            if (tk != null) { riga = r; generica = tk; break; }
        }
        Assumptions.assumeTrue(riga != null, "nessuna riga con un token NORMALE nelle fixture");

        final UUID id = riga.id();
        final String tk = generica;
        ApiException e = assertThrows(ApiException.class, () -> QuarkusTransaction.requiringNew().run(() ->
                triage.classificaTransitorio(id, new ClassificaTransitorioRequest(
                        cogeId("40.05.002"), (short) 5, null, true,
                        List.of(new FirmaSceltaDTO(List.of(tk))), null))));
        assertEquals("FIRMA_NON_VALIDA", e.getCode());
    }

    /** Tutti i chip spenti: la riga si cataloga lo stesso e non nasce nessuna firma. */
    @Test
    void conTutteLeFirmeSpenteNonNasceNienteMaLaRigaSiCataloga() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> !r.firmeDaImparare().isEmpty())
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga con firme da imparare nelle fixture");

        long prima = contaFirme();
        // `apprendiKeyword=true` ma lista vuota: la lista vince (I1), altrimenti due sorgenti per
        // la stessa domanda potrebbero dire cose diverse.
        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(riga.id(),
                new ClassificaTransitorioRequest(cogeId("40.05.002"), (short) 5, null, true,
                        List.of(), null)));
        assertEquals(prima, contaFirme(), "l'operatore ha spento tutto: non deve nascere nessuna firma");
        assertEquals(cogeId("40.05.002"), cogeDi(riga.id()), "ma la riga si cataloga lo stesso");
    }

    /** Il token duplicato nella lista non rompe niente: la signature è su un insieme ordinato. */
    @Test
    void unTokenRipetutoNonRompeLApprendimento() throws Exception {
        importa();
        TransitorioDTO riga = triage.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> !r.firmeDaImparare().isEmpty())
                .findFirst().orElse(null);
        Assumptions.assumeTrue(riga != null, "nessuna riga con firme da imparare nelle fixture");
        List<String> t = riga.firmeDaImparare().get(0).token();

        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(riga.id(),
                new ClassificaTransitorioRequest(cogeId("40.05.002"), (short) 5, null, true,
                        List.of(new FirmaSceltaDTO(List.of(t.get(0), t.get(0)))), null)));
        assertEquals(1L, firmeConTokenEsatti(List.of(t.get(0))));
    }

    /** Il primo token NORMALE (parola generica, non un nome) della causale di questa riga. */
    private String primoTokenNormale(TransitorioDTO r) {
        String sorgente = r.contoBancarioId() == null ? Sorgente.CA
                : (r.contoBancarioId() == 1 ? Sorgente.BPM
                : (r.contoBancarioId() == 2 ? Sorgente.CA : Sorgente.BILLY));
        return KeywordExtractor.classifica(r.descrizione(),
                        DescNormalizer.extract(r.descrizione(), sorgente),
                        keywordEngine.stopwords(), keywordEngine.domainTokens()).stream()
                .filter(t -> t.tipo() == KeywordExtractor.TipoToken.NORMALE)
                .map(KeywordExtractor.TokenTipizzato::token)
                .findFirst().orElse(null);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private long contaFirme() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM keyword_firma")
                .getSingleResult()).longValue();
    }

    /** Quante firme hanno ESATTAMENTE questo insieme di token (stessa cardinalità e contenuto). */
    private long firmeConTokenEsatti(List<String> token) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM keyword_firma f WHERE "
                + "(SELECT count(*) FROM keyword_token t WHERE t.firma_id = f.id) = :n AND "
                + "(SELECT count(*) FROM keyword_token t WHERE t.firma_id = f.id AND t.token IN (:tok)) = :n")
                .setParameter("n", (long) token.size())
                .setParameter("tok", token)
                .getSingleResult()).longValue();
    }

    /** La proposta viene da una firma keyword (votabile) e non da un alias fornitore. */
    private boolean vieneDaUnaFirmaKeyword(TransitorioDTO r) {
        return r.motivoSuggerimento() != null && r.motivoSuggerimento().contains("firma keyword");
    }

    private UUID catalogaUnTransitorio(String codiceCoge) {
        UUID mov = primoId("SELECT m.id FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id "
                + "WHERE p.codice = '49.99.999' AND m.stato <> 'ANNULLATO'");
        assertNotNull(mov, "le fixture devono lasciare almeno una riga sul transitorio costi");
        QuarkusTransaction.requiringNew().run(() -> triage.classificaTransitorio(mov,
                new ClassificaTransitorioRequest(cogeId(codiceCoge), (short) 5, null, false, null)));
        return mov;
    }

    private long somma(String colonna) {
        return ((Number) em.createNativeQuery("SELECT COALESCE(SUM(" + colonna + "),0) FROM keyword_firma")
                .getSingleResult()).longValue();
    }

    private String notaDi(UUID id) {
        List<?> r = em.createNativeQuery("SELECT note FROM movimenti WHERE id = :id")
                .setParameter("id", id).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    private Integer cogeDi(UUID id) {
        return ((Number) em.createNativeQuery("SELECT conto_coge_id FROM movimenti WHERE id = :id")
                .setParameter("id", id).getSingleResult()).intValue();
    }

    private long contaMovimenti() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE fonte_importazione_id IS NOT NULL").getSingleResult())
                .longValue();
    }

    private Integer cogeId(String codice) {
        List<?> r = em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getResultList();
        return r.isEmpty() ? null : ((Number) r.get(0)).intValue();
    }

    private UUID primoId(String sql) {
        List<?> r = em.createNativeQuery(sql + " LIMIT 1").getResultList();
        return r.isEmpty() ? null : (r.get(0) instanceof UUID u ? u : UUID.fromString(r.get(0).toString()));
    }

    private void importa() throws Exception {
        try (InputStream b = new FileInputStream(DIR.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(DIR.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(DIR.resolve(CA).toFile())) {
            importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
