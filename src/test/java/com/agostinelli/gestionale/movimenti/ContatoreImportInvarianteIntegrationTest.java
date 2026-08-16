package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.ContatoreImportDTO;
import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.importlayer.ContatoreImportService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * R7 — L'invariante del contatore (SPEC import-v2 §5) chiude <b>al centesimo e per direzione</b>
 * sulle fixture dell'import di luglio 2026:
 *
 * <pre>
 *   Σ righe banca lette = Σ a libro + Σ da catalogare + Σ fuori dai conti
 *                       + Σ escluso di proposito + Σ duplicate + Σ partite di giro
 * </pre>
 *
 * <p>Il termine sinistro è misurato all'import sulle righe normalizzate dei due file banca
 * (import_log.righe_banca/banca_entrate/banca_uscite), il destro è derivato dallo stato attuale
 * delle tabelle: sono due misure INDIPENDENTI, quindi il test può davvero fallire.
 *
 * <p>Gira su {@code agosdb_test}; pulisce prima e dopo perché la suite condivide il database
 * (stessa trappola documentata in {@link TermometroLuglioIntegrationTest}).
 */
@QuarkusTest
class ContatoreImportInvarianteIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path DIR = Path.of("..", "dati_luglio");
    static final String BILLY = "corrispettivi-12 (1).csv";
    static final String BPM = "MovimentiCC_OnLine_07_08_2026_05.51.09.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_08_07_054028.csv";

    /** Oracoli §1/R7: 154 righe banca, entrate 42.359,14 €, uscite 49.336,83 €. */
    static final int O_RIGHE_BANCA = 154;
    static final BigDecimal O_ENTRATE = new BigDecimal("42359.14");
    static final BigDecimal O_USCITE = new BigDecimal("49336.83");

    @Inject MovimentoImportService importService;
    @Inject ContatoreImportService contatore;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(DIR), "Cartella dati_luglio assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(DIR.resolve(f)), "Fixture assente: " + f);
        }
    }

    @BeforeEach
    void before() { reset(); }

    @AfterEach
    void after() { reset(); }

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
        });
    }

    @Test
    void invarianteChiudeAlCentesimoPerDirezione() throws Exception {
        UUID logId = importa().importLogId();
        ContatoreImportDTO c = contatore.calcola(logId);
        System.out.println(stampa(c));

        Assertions.assertEquals(O_RIGHE_BANCA, c.lette().righe(), "righe banca lette");
        Assertions.assertEquals(0, O_ENTRATE.compareTo(c.lette().entrate()), "entrate lette");
        Assertions.assertEquals(0, O_USCITE.compareTo(c.lette().uscite()), "uscite lette");

        Assertions.assertEquals(0, c.scartoEntrate().signum(),
                "l'invariante non chiude sulle ENTRATE: scarto " + c.scartoEntrate() + " €\n" + stampa(c));
        Assertions.assertEquals(0, c.scartoUscite().signum(),
                "l'invariante non chiude sulle USCITE: scarto " + c.scartoUscite() + " €\n" + stampa(c));
        Assertions.assertTrue(c.quadra());

        // Ogni riga ha una e una sola casa: i conteggi righe devono tornare, non solo gli euro.
        long righeCollocate = c.aLibro().righe() + c.daCatalogare().righe() + c.fuoriDaiConti().righe()
                + c.esclusi().righe() + c.duplicate().righe() + c.partiteDiGiro().righe();
        Assertions.assertEquals(O_RIGHE_BANCA, righeCollocate,
                "154 righe lette ma " + righeCollocate + " collocate\n" + stampa(c));
    }

    /**
     * I passi mostrati a schermo durante l'import devono essere quelli che il server esegue
     * DAVVERO, con la loro durata misurata: è la condizione che separa un riscontro onesto da una
     * progress bar a tempo. Vive qui perché è lo stesso import reale di luglio già montato sopra.
     */
    @Test
    void iPassiMostratiSonoQuelliDavveroEseguitiEMisurati() throws Exception {
        EtlImportResponse res = importa();

        var fasi = res.fasi();
        Assertions.assertEquals(
                List.of("Lettura dei tre file", "Riconciliazione degli incassi POS",
                        "Classificazione e scrittura", "Quadratura dell'estratto conto"),
                fasi.stream().map(f -> f.nome()).toList(),
                "i passi, in ordine, sono quelli del codice di importCongiunto");

        int totale = 0;
        StringBuilder sb = new StringBuilder("\n┌── PASSI DELL'IMPORT (misurati)\n");
        for (var f : fasi) {
            Assertions.assertTrue(f.millis() >= 0, "durata negativa su «" + f.nome() + "»");
            Assertions.assertNotNull(f.dettaglio());
            Assertions.assertFalse(f.dettaglio().isBlank(),
                    "il passo «" + f.nome() + "» non porta nessun numero: sarebbe una promessa");
            totale += f.millis();
            sb.append(String.format("│ %-34s %5d ms  %s%n", f.nome(), f.millis(), f.dettaglio()));
        }
        sb.append(String.format("└── totale misurato: %d ms%n", totale));
        System.out.println(sb);

        Assertions.assertTrue(totale > 0,
                "l'elaborazione non può essere durata 0 ms su 154 righe bancarie");
        // La riga «154 righe bancarie» dell'ultimo passo è lo stesso universo del contatore (R7):
        // se le due misure divergessero, la schermata mostrerebbe un numero che i conti non hanno.
        Assertions.assertTrue(fasi.get(3).dettaglio().startsWith(O_RIGHE_BANCA + " righe bancarie"),
                "la quadratura mostrata non è quella misurata: " + fasi.get(3).dettaglio());
    }

    /**
     * {@code /import/badge} sostituisce 6 liste chieste con {@code size=1} solo per leggerne il
     * totale: deve dire gli STESSI numeri, altrimenti è una seconda verità che diverge in silenzio.
     * Questo test fallirebbe se qualcuno cambiasse la clausola di una lista senza cambiare il badge.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"ADMIN"})
    void badgeDiceGliStessiNumeriDelleListeCheSostituisce() throws Exception {
        importa();

        var badge = given().when().get("/api/movimenti/import/badge").then().statusCode(200).extract();

        for (String[] c : new String[][]{
                {"catalogare",        "/api/movimenti/import/transitori?page=0&size=1"},
                {"ricorrenti",        "/api/movimenti/import/ricorrenti?stato=DA_RICONCILIARE&page=0&size=1"},
                {"eventi",            "/api/movimenti/import/eventi?stato=DA_RICONCILIARE&page=0&size=1"},
                {"matchingDifferiti", "/api/movimenti/import/matching-differiti?stato=DA_RICONCILIARE&page=0&size=1"},
                {"scartati",          "/api/movimenti/import/scartati?stato=DA_VEDERE&page=0&size=1"},
        }) {
            int daLista = given().when().get(c[1]).then().statusCode(200)
                    .extract().path("totalElements");
            Assertions.assertEquals(daLista, (int) badge.path(c[0]),
                    "il badge «" + c[0] + "» non coincide con " + c[1]);
        }
        Assertions.assertNotNull(badge.path("ultimoImportId"),
                "il badge deve portare l'id dell'ultimo import (sostituisce /import/history)");
    }

    /**
     * R10 — risolvere una riga NON muove il totale: la sposta da «da catalogare»/«fuori dai conti»
     * ad «a libro» o «escluso di proposito». Un percorso per volta, con l'invariante ri-verificato
     * dopo ognuno.
     */
    @Test
    void risolvereUnaRigaNonMuoveIlTotale() throws Exception {
        UUID logId = importa().importLogId();
        ContatoreImportDTO prima = contatore.calcola(logId);

        // I quattro percorsi di risoluzione, uno per volta. Le fixture di luglio hanno 0 righe in
        // import_scartati (0 scartati, §1), quindi quel ramo si esercita solo se c'è: il quinto
        // percorso ha il suo test dedicato in RigheFuoriDaiContiIntegrationTest.
        int percorsi = 0;
        percorsi += classificaUnTransitorio() ? 1 : 0;      // spesa/ricavo  → a libro
        percorsi += riconciliaUnEvento() ? 1 : 0;           // incasso evento → a libro
        percorsi += confermaUnaRicorrente() ? 1 : 0;        // rata          → a libro
        percorsi += scartaUnEvento() ? 1 : 0;               // esclusione motivata → escluso
        percorsi += ignoraUnaRicorrente() ? 1 : 0;          // esclusione motivata → escluso
        percorsi += risolviUnoScartato("CONTABILIZZA") ? 1 : 0;
        percorsi += risolviUnoScartato("IGNORA") ? 1 : 0;

        ContatoreImportDTO dopo = contatore.calcola(logId);
        System.out.println("percorsi esercitati: " + percorsi + "\n" + stampa(dopo));

        Assertions.assertTrue(percorsi >= 4,
                "servono almeno 4 percorsi esercitati, ne ho trovati " + percorsi
                + " (le fixture non hanno righe in tutte le code)");
        Assertions.assertTrue(dopo.quadra(),
                "dopo le risoluzioni l'invariante non chiude più\n" + stampa(dopo));
        Assertions.assertEquals(prima.lette().righe(), dopo.lette().righe(), "il totale letto non cambia");
        Assertions.assertEquals(0, prima.lette().entrate().compareTo(dopo.lette().entrate()));
        Assertions.assertEquals(0, prima.lette().uscite().compareTo(dopo.lette().uscite()));
        Assertions.assertTrue(dopo.aLibro().righe() > prima.aLibro().righe(),
                "almeno una riga deve essere passata «a libro»");
        Assertions.assertTrue(dopo.esclusi().righe() > prima.esclusi().righe(),
                "almeno una riga deve essere passata a «escluso di proposito»");
    }

    /**
     * R21/R22 — il registro elenca TUTTE le righe bancarie, una volta e una sola, ognuna con lo
     * stato scritto in parola. È la stessa lettura del contatore: se divergessero, una delle due
     * schermate mentirebbe.
     */
    @Test
    void ilRegistroElencaOgniRigaUnaVoltaSola_conLoStatoInParola() throws Exception {
        UUID logId = importa().importLogId();
        ContatoreImportDTO c = contatore.calcola(logId);

        var reg = contatore.registro(logId, null, null, null, null, null, 0, 5000);
        var pagina = reg.pagina();
        Assertions.assertEquals(O_RIGHE_BANCA, pagina.totalElements(),
                "il registro deve contenere tutte e sole le 154 righe banca");

        // I totali del registro sono quelli letti dalle banche: se divergessero, l'intestazione
        // del registro e il contatore direbbero due cose diverse sullo stesso insieme.
        Assertions.assertEquals(0, O_ENTRATE.compareTo(reg.totaleEntrate()), "totale entrate del registro");
        Assertions.assertEquals(0, O_USCITE.compareTo(reg.totaleUscite()), "totale uscite del registro");
        Assertions.assertEquals(O_RIGHE_BANCA, reg.righeEntrate() + reg.righeUscite());

        java.util.Set<String> ids = new java.util.HashSet<>();
        for (var r : pagina.content()) {
            Assertions.assertTrue(ids.add(r.origine() + "|" + r.id()),
                    "riga elencata due volte: " + r.origine() + " " + r.id());
            Assertions.assertNotNull(r.statoParola(), "R22: il badge porta sempre la parola");
            Assertions.assertNotNull(r.tipo(), "la direzione serve al contatore");
            Assertions.assertNotNull(r.importo());
        }

        // Il filtro per stato è coerente col bucket del contatore, riga per riga.
        var soloDaCatalogare = contatore.registro(logId, "DA_CATALOGARE", null, null, null, null, 0, 5000);
        Assertions.assertEquals(c.daCatalogare().righe(), soloDaCatalogare.pagina().totalElements());
        Assertions.assertEquals(c.aLibro().righe(),
                contatore.registro(logId, "A_LIBRO", null, null, null, null, 0, 5000).pagina().totalElements());

        // Filtrando, i totali seguono il filtro: sono quelli del bucket, non del tutto.
        Assertions.assertEquals(0, c.daCatalogare().entrate().compareTo(soloDaCatalogare.totaleEntrate()));
        Assertions.assertEquals(0, c.daCatalogare().uscite().compareTo(soloDaCatalogare.totaleUscite()));
    }

    /**
     * Gli endpoint esistono e rispondono: il servizio è testato sopra, qui si verifica il
     * cablaggio JAX-RS (path, ruoli, serializzazione) — l'unico pezzo che i test di servizio
     * non toccano.
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void gliEndpointDelContatoreEDelRegistroRispondono() throws Exception {
        UUID logId = importa().importLogId();

        given().when().get("/api/movimenti/import/" + logId + "/contatore")
            .then().statusCode(200)
            .body("quadra", equalTo(true))
            .body("lette.righe", equalTo(O_RIGHE_BANCA))
            .body("lette.entrate", equalTo(O_ENTRATE.floatValue()))
            .body("aLibro", notNullValue());

        given().when().get("/api/movimenti/import/" + logId + "/righe?size=5")
            .then().statusCode(200)
            .body("pagina.totalElements", equalTo(O_RIGHE_BANCA))
            .body("pagina.content.size()", equalTo(5))
            .body("pagina.content[0].statoParola", notNullValue())
            .body("totaleEntrate", equalTo(O_ENTRATE.floatValue()))
            .body("totaleUscite", equalTo(O_USCITE.floatValue()));

        given().when().get("/api/movimenti/import/00000000-0000-0000-0000-000000000000/contatore")
            .then().statusCode(404).body("code", equalTo("IMPORT_NON_TROVATO"));
    }

    /**
     * «Non è una spesa, è un incasso evento»: la riga cambia coda ma NON cambia bucket né totale.
     * È il caso reale del 13/08: un bonifico con causale «8RIST 14/06/26» — nessuna keyword evento,
     * quindi il Gate B non lo vede e finisce fra le spese, dove la risposta giusta non esiste.
     */
    @Test
    void spostareUnaRigaInUnAltraCodaNonMuoveIlTotale() throws Exception {
        UUID logId = importa().importLogId();
        ContatoreImportDTO prima = contatore.calcola(logId);

        UUID mov = primoId("SELECT m.id FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id "
                + "WHERE p.codice = '39.99.999' AND m.stato <> 'ANNULLATO' AND m.conto_bancario_id IS NOT NULL");
        Assumptions.assumeTrue(mov != null, "nessun ricavo sul transitorio nelle fixture");
        java.math.BigDecimal importo = (java.math.BigDecimal) em.createNativeQuery(
                "SELECT importo_lordo FROM movimenti WHERE id = :id").setParameter("id", mov).getSingleResult();

        QuarkusTransaction.requiringNew().run(() -> triage().spostaInCoda(mov,
                new com.agostinelli.gestionale.movimenti.dto.SpostaRigaRequest(
                        "EVENTO", "e' un incasso evento: la causale non ha keyword"), TEST_USER));

        ContatoreImportDTO dopo = contatore.calcola(logId);
        Assertions.assertTrue(dopo.quadra(), "spostare non deve aprire un buco\n" + stampa(dopo));
        Assertions.assertEquals(prima.daCatalogare().righe(), dopo.daCatalogare().righe(),
                "la riga resta «da catalogare»: cambia coda, non bucket");
        Assertions.assertEquals(0, prima.daCatalogare().entrate().compareTo(dopo.daCatalogare().entrate()));
        Assertions.assertEquals(0, prima.aLibro().righe() == dopo.aLibro().righe() ? 0 : 1,
                "«a libro» non c'entra nulla con questo spostamento");

        // Il movimento sul transitorio non esiste più, e la riga è in coda eventi col suo importo.
        Assertions.assertEquals(0, conta("SELECT count(*) FROM movimenti WHERE id = '" + mov + "'"));
        Assertions.assertEquals(1, conta("SELECT count(*) FROM eventi_da_riconciliare "
                + "WHERE stato = 'DA_RICONCILIARE' AND importo = " + importo.toPlainString()
                + " AND raw_data->>'_ricostruito' = 'true'"));
        // Cancellazione, non annullamento: un ANNULLATO uscirebbe da ogni bucket e aprirebbe un buco.
        Assertions.assertTrue(conta("SELECT count(*) FROM audit_log WHERE tabella LIKE 'movimenti%' "
                + "AND operazione = 'DELETE' AND record_id = '" + mov + "'") >= 1,
                "il prima/dopo dello spostamento resta in audit_log");
    }

    /** Una riga già risolta da una coda ha già la sua casa: spostarla creerebbe un doppione. */
    @Test
    void unaRigaGiaRisoltaDaUnaCodaNonSiSposta() throws Exception {
        importa();
        UUID ric = primoId("SELECT id FROM ricorrenti_da_riconciliare "
                + "WHERE stato = 'DA_RICONCILIARE' AND tipo = 'USCITA'");
        Assumptions.assumeTrue(ric != null, "nessuna rata in coda");
        Integer coge = intero("SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01.001'");
        QuarkusTransaction.requiringNew().run(() -> triage().risolviRicorrente(ric,
                new com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest(
                        "CONFERMA", coge, null, null, "test"), TEST_USER));

        UUID mov = primoId("SELECT movimento_id FROM ricorrenti_da_riconciliare WHERE id = '" + ric + "'");
        var ex = Assertions.assertThrows(
                com.agostinelli.gestionale.infrastructure.exception.ApiException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> triage().spostaInCoda(mov,
                        new com.agostinelli.gestionale.movimenti.dto.SpostaRigaRequest("EVENTO", null),
                        TEST_USER)));
        Assertions.assertEquals("MOVIMENTO_GIA_IN_UNA_CODA", ex.getCode());
    }

    /** R9 — «escluso di proposito» senza motivo scritto è rifiutato dal SERVIZIO, non dalla UI. */
    @Test
    void esclusioneSenzaMotivoScrittoRifiutata() throws Exception {
        importa();
        UUID ricorrente = primoId("SELECT id FROM ricorrenti_da_riconciliare WHERE stato = 'DA_RICONCILIARE'");
        UUID evento = primoId("SELECT id FROM eventi_da_riconciliare WHERE stato = 'DA_RICONCILIARE'");
        Assumptions.assumeTrue(ricorrente != null && evento != null, "code vuote nelle fixture");

        var e1 = Assertions.assertThrows(
                com.agostinelli.gestionale.infrastructure.exception.ApiException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> triage().risolviRicorrente(ricorrente,
                        new com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest(
                                "IGNORA", null, null, null, "  "), TEST_USER)));
        Assertions.assertEquals("MOTIVO_OBBLIGATORIO", e1.getCode());

        var e2 = Assertions.assertThrows(
                com.agostinelli.gestionale.infrastructure.exception.ApiException.class,
                () -> QuarkusTransaction.requiringNew().run(() -> triage().risolviEvento(evento,
                        new com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest(
                                "SCARTA", null, null, null, null, null, false), TEST_USER)));
        Assertions.assertEquals("MOTIVO_OBBLIGATORIO", e2.getCode());

        // La riga NON è stata chiusa: un rifiuto non deve lasciare stato a metà.
        Assertions.assertEquals(1, conta("SELECT count(*) FROM ricorrenti_da_riconciliare "
                + "WHERE id = '" + ricorrente + "' AND stato = 'DA_RICONCILIARE'"));
    }

    /** R8 — l'import rigiocato non perde le duplicate: lasciano una riga, non solo un contatore. */
    @Test
    void leDuplicateLascianoUnaTraccia() throws Exception {
        importa();
        UUID secondo = importa().importLogId();

        long tracce = conta("SELECT count(*) FROM import_scartati WHERE import_log_id = '" + secondo
                + "' AND motivo = 'DUPLICATA'");
        long contate = conta("SELECT COALESCE(righe_duplicate,0) FROM import_log WHERE id = '" + secondo + "'");
        Assertions.assertTrue(contate > 0, "il secondo import non ha prodotto duplicate: fixture inattese");
        Assertions.assertEquals(contate, tracce,
                "righe_duplicate = " + contate + " ma tracce in import_scartati = " + tracce);

        ContatoreImportDTO c = contatore.calcola(secondo);
        System.out.println("RE-IMPORT degli stessi file:\n" + stampa(c));
        Assertions.assertEquals(contate, c.duplicate().righe());
        Assertions.assertTrue(c.quadra(), "invariante rotto sul re-import\n" + stampa(c));
    }

    // ── i quattro/cinque percorsi ─────────────────────────────────────────────────

    private boolean classificaUnTransitorio() {
        UUID mov = primoId("SELECT m.id FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id "
                + "WHERE p.codice IN ('39.99.999','49.99.999') AND m.stato <> 'ANNULLATO' AND m.tipo = 'USCITA'");
        if (mov == null) return false;
        Integer coge = intero("SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05.002'");
        QuarkusTransaction.requiringNew().run(() -> triage().classificaTransitorio(mov,
                new com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest(
                        coge, (short) 5, null, false, "test contatore")));
        return true;
    }

    private boolean risolviUnoScartato(String azione) {
        UUID id = primoId("SELECT id FROM import_scartati WHERE stato = 'DA_VEDERE'");
        if (id == null) return false;
        Integer coge = intero("SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05.002'");
        QuarkusTransaction.requiringNew().run(() -> triage().risolviScartato(id,
                new com.agostinelli.gestionale.movimenti.dto.RisolviScartatoRequest(
                        azione, coge, "verifica invariante del contatore"), TEST_USER));
        return true;
    }

    private boolean confermaUnaRicorrente() {
        UUID id = primoId("SELECT id FROM ricorrenti_da_riconciliare "
                + "WHERE stato = 'DA_RICONCILIARE' AND tipo = 'USCITA'");
        if (id == null) return false;
        Integer coge = intero("SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01.001'");
        QuarkusTransaction.requiringNew().run(() -> triage().risolviRicorrente(id,
                new com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest(
                        "CONFERMA", coge, null, null, "verifica invariante"), TEST_USER));
        return true;
    }

    private boolean ignoraUnaRicorrente() {
        UUID id = primoId("SELECT id FROM ricorrenti_da_riconciliare WHERE stato = 'DA_RICONCILIARE'");
        if (id == null) return false;
        QuarkusTransaction.requiringNew().run(() -> triage().risolviRicorrente(id,
                new com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest(
                        "IGNORA", null, null, null, "non e' una rata di un piano nostro"), TEST_USER));
        return true;
    }

    private boolean scartaUnEvento() {
        UUID id = primoId("SELECT id FROM eventi_da_riconciliare WHERE stato = 'DA_RICONCILIARE'");
        if (id == null) return false;
        QuarkusTransaction.requiringNew().run(() -> triage().risolviEvento(id,
                new com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest(
                        "SCARTA", null, null, null, "non e' un incasso evento", null, false), TEST_USER));
        return true;
    }

    private boolean riconciliaUnEvento() {
        UUID id = primoId("SELECT id FROM eventi_da_riconciliare "
                + "WHERE stato = 'DA_RICONCILIARE' AND conto_bancario_id IS NOT NULL");
        if (id == null) return false;
        QuarkusTransaction.requiringNew().run(() -> triage().risolviEvento(id,
                new com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest(
                        "RICONCILIA", null, null, null, "verifica invariante", "SALDO", true), TEST_USER));
        return true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    @Inject com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService triageService;

    private com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService triage() {
        return triageService;
    }

    private UUID primoId(String sql) {
        List<?> r = em.createNativeQuery(sql + " LIMIT 1").getResultList();
        return r.isEmpty() ? null : (r.get(0) instanceof UUID u ? u : UUID.fromString(r.get(0).toString()));
    }

    private Integer intero(String sql) {
        List<?> r = em.createNativeQuery(sql).getResultList();
        return r.isEmpty() ? null : ((Number) r.get(0)).intValue();
    }

    private long conta(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private EtlImportResponse importa() throws Exception {
        try (InputStream b = new FileInputStream(DIR.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(DIR.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(DIR.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }

    private String stampa(ContatoreImportDTO c) {
        StringBuilder o = new StringBuilder("\n┌── CONTATORE import ").append(c.importLogId()).append('\n');
        riga(o, "estratto dalle banche", c.lette());
        riga(o, "  a libro", c.aLibro());
        riga(o, "  da catalogare", c.daCatalogare());
        riga(o, "  fuori dai conti", c.fuoriDaiConti());
        riga(o, "  escluso di proposito", c.esclusi());
        riga(o, "  duplicate", c.duplicate());
        riga(o, "  partite di giro", c.partiteDiGiro());
        o.append(String.format("│ %-24s entrate %12s   uscite %12s%n", "SCARTO",
                c.scartoEntrate().toPlainString(), c.scartoUscite().toPlainString()));
        for (var v : c.fuoriUniverso()) {
            o.append(String.format("│ fuori universo: %s — %d righe, %s €%n",
                    v.etichetta(), v.righe(), v.importo().toPlainString()));
        }
        return o.append("└──").toString();
    }

    private void riga(StringBuilder o, String etichetta, ContatoreImportDTO.Bucket b) {
        o.append(String.format("│ %-24s %4d righe  entrate %12s   uscite %12s%n",
                etichetta, b.righe(), b.entrate().toPlainString(), b.uscite().toPlainString()));
    }
}
