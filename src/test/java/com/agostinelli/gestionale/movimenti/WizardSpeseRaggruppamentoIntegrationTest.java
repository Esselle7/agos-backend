package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Wizard «Che spesa è questa?» (docs/specs/wizard-spese-da-sistemare.md, audit §7.1/§7.5/§7.6).
 *
 * <p>Due cose sole, ma misurate sul corpus reale {@code esempi_dati_storici/}: (a) il
 * raggruppamento per esercente riduce davvero le decisioni — 187 righe → 89 gruppi, e le 48 righe
 * POS orfane contano per UNA; (b) il guard-rail server rifiuta la catalogazione su un conto
 * ricavi-evento, che è il modo con cui si creavano ricavi invisibili nel bilancio degli eventi.
 */
@QuarkusTest
class WizardSpeseRaggruppamentoIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path STORICI = Path.of("..", "esempi_dati_storici");
    static final String BILLY = "corrispettivi-12.csv";
    static final String BPM = "MovimentiCC_OnLine_10_06_2026_11.56.28.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_06_10_115335.csv";

    @Inject MovimentoImportService importService;
    @Inject ImportTriageService triageService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(STORICI), "Cartella esempi_dati_storici assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(STORICI.resolve(f)), "Fixture assente: " + f);
        }
    }

    @BeforeEach
    void resetBefore() { cleanEtl(); }

    @AfterEach
    void resetAfter() { cleanEtl(); }

    void cleanEtl() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN "
                    + "('IMPORT_CONGIUNTO','IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
        });
    }

    /**
     * R1: si raggruppa SOLO su una controparte vera. È il valore della feature (stesso fornitore =
     * una domanda sola) ma anche il suo limite: righe diverse non si mettono mai insieme.
     */
    @Test
    void siRaggruppaSoloSullaControparteVera() throws Exception {
        importaCongiunto();
        List<TransitorioDTO> righe = triageService.listTransitori(null, 0, 2000).content();

        assertTrue(righe.size() >= 150,
                "il corpus di 6 mesi deve produrre la coda vera, non una manciata: " + righe.size());

        Map<String, Integer> gruppi = new LinkedHashMap<>();
        int daSole = 0;
        for (TransitorioDTO r : righe) {
            if (r.gruppo() == null) { daSole++; continue; }
            assertNotNull(r.controparteEstratta(),
                    "una riga raggruppata senza controparte: " + r.descrizione());
            gruppi.merge(r.gruppo(), 1, Integer::sum);
        }

        int decisioni = gruppi.size() + daSole;
        assertTrue(decisioni < righe.size(),
                "raggruppare gli esercenti ripetuti deve togliere qualche decisione: "
                        + righe.size() + " righe → " + decisioni + " decisioni");

        // Il fornitore che torna più volte è UNA domanda sola: è per questo che il gruppo esiste.
        assertTrue(gruppi.values().stream().anyMatch(n -> n >= 4),
                "sul corpus reale ci sono esercenti con 4+ righe: devono stare insieme");
    }

    /**
     * Ogni incasso POS è una decisione a sé: sono giornate, importi e circuiti diversi, e
     * applicare una voce sola a tutti significherebbe rispondere una volta per cose diverse.
     * Al loro posto la riga porta i dati dell'estratto conto + il riscontro Billy di giornata.
     */
    @Test
    void ogniIncassoPosEUnaDecisioneASe_conIDatiDellaRigaBancaria() throws Exception {
        importaCongiunto();
        List<TransitorioDTO> pos = triageService.listTransitori(null, 0, 2000).content().stream()
                .filter(r -> r.circuitoPos() != null)
                .toList();

        assertTrue(pos.size() >= 40, "il corpus ha ~48 righe POS sul transitorio: " + pos.size());
        for (TransitorioDTO r : pos) {
            assertNull(r.gruppo(), "una riga POS non si raggruppa mai: " + r.descrizione());
            assertNotNull(r.circuitoPos(), "il circuito si legge dalla causale");
            assertNotNull(r.contoBancarioId(), "la riga viene dall'estratto conto: la banca c'è");
        }

        // Il riscontro Billy c'è dove Billy ha registrato qualcosa quel giorno su quel conto:
        // è indicativo (di giornata), quindi si verifica la COERENZA del calcolo, non un valore.
        long conRiscontro = pos.stream().filter(r -> r.riscontroBilly() != null).count();
        assertTrue(conRiscontro > 0, "su questo corpus alcune righe POS hanno un riscontro Billy");
        for (TransitorioDTO r : pos) {
            if (r.riscontroBilly() == null) continue;
            assertTrue(r.riscontroBilly().scontrini() > 0, "un riscontro senza righe non esiste");
            assertEquals(0, r.importo().subtract(r.riscontroBilly().totale())
                            .compareTo(r.riscontroBilly().scarto()),
                    "lo scarto è importo banca − totale Billy, sempre");
        }
    }

    /** R6: la coda RiBa separata non esiste più — quelle righe sono uscite da catalogare come le altre. */
    @Test
    void leRigheEffettiRibaSonoNellaCodaUnica() throws Exception {
        importaCongiunto();
        List<TransitorioDTO> righe = triageService.listTransitori(null, 0, 2000).content();

        long riba = righe.stream()
                .filter(r -> r.descrizione() != null)
                .filter(r -> r.descrizione().toUpperCase().contains("EFFETTI")
                        || r.descrizione().toUpperCase().contains("RIBA"))
                .count();
        assertTrue(riba > 0, "le righe EFFETTI/RiBa devono comparire qui, non in una coda a parte");
    }

    /** R3: è il guard-rail §7.6 — senza, un incasso finisce sui ricavi-evento senza evento collegato. */
    @Test
    void catalogareSuUnRicavoEventoVieneRifiutato() throws Exception {
        importaCongiunto();
        TransitorioDTO r = primaRiga();
        Integer cogeEvento = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '30.02.%' ORDER BY codice LIMIT 1")
                .getSingleResult()).intValue();

        ApiException e = assertThrows(ApiException.class, () -> triageService.classificaTransitorio(
                r.id(), new ClassificaTransitorioRequest(cogeEvento, (short) 2, null, false, null)));

        assertEquals("COGE_RISERVATO_EVENTI", e.getCode());
        assertEquals(r.cogeCodiceAttuale(), cogeDi(r.id()), "il rifiuto non deve muovere il movimento");
    }

    /** R4: un id inesistente è un errore del chiamante (400), non un 500 dalla foreign key. */
    @Test
    void unContoInesistenteDaErroreLeggibile() throws Exception {
        importaCongiunto();
        TransitorioDTO r = primaRiga();

        ApiException e = assertThrows(ApiException.class, () -> triageService.classificaTransitorio(
                r.id(), new ClassificaTransitorioRequest(999_999, (short) 2, null, false, null)));

        assertEquals("COGE_NON_TROVATO", e.getCode());
        assertEquals(r.cogeCodiceAttuale(), cogeDi(r.id()));
    }

    /**
     * Happy path: catalogare toglie la riga dalla coda. E ri-catalogarla è LEGITTIMO (SPEC
     * import-v2 R13, decisione n.4 del 12/08/2026): fino a quel giorno la seconda chiamata dava
     * 409 NON_TRANSITORIO, cioè quello che il motore aveva scritto restava definitivo.
     */
    @Test
    void catalogareTogliLaRigaDallaCoda_eResaCorreggibile() throws Exception {
        importaCongiunto();
        TransitorioDTO r = primaRiga();
        int quante = triageService.listTransitori(null, 0, 2000).content().size();
        Integer coge = cogeId("40.05.002");   // assicurazioni: un conto vero, non transitorio

        triageService.classificaTransitorio(r.id(),
                new ClassificaTransitorioRequest(coge, (short) 2, null, false, null));

        assertEquals("40.05.002", cogeDi(r.id()));
        assertEquals(quante - 1, triageService.listTransitori(null, 0, 2000).content().size());

        // Seconda passata: la riga non è più sul transitorio, ma la correzione resta aperta.
        Integer altro = cogeId("40.02.002");
        triageService.classificaTransitorio(r.id(),
                new ClassificaTransitorioRequest(altro, (short) 5, null, false, "mi ero sbagliato"));
        assertEquals("40.02.002", cogeDi(r.id()), "la ricatalogazione riscrive il movimento");
        assertEquals(quante - 1, triageService.listTransitori(null, 0, 2000).content().size(),
                "e non lo rimette in coda");
    }

    // ── helper ──────────────────────────────────────────────────────────────────

    TransitorioDTO primaRiga() {
        List<TransitorioDTO> c = triageService.listTransitori(null, 0, 2000).content();
        assertFalse(c.isEmpty(), "il corpus deve lasciare righe da catalogare");
        return c.get(0);
    }

    String cogeDi(UUID movimentoId) {
        return (String) em.createNativeQuery(
                "SELECT p.codice FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id "
                + "WHERE m.id = :id").setParameter("id", movimentoId).getSingleResult();
    }

    Integer cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }

    void importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(STORICI.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(STORICI.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(STORICI.resolve(CA).toFile())) {
            importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
