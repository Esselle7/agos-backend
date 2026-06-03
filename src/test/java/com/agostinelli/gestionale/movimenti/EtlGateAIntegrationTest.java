package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test del Gate A (ETL v2 §4) sui file di esempio reali in
 * ../esempi_input_per_ETL/. Verifica le esclusioni deterministiche:
 *  - nessun incasso POS/Satispay bancario importato come movimento (SKIP_POS);
 *  - giroconto interno CA→BPM lato uscita escluso e simmetrico (SKIP_GIROCONTO);
 *  - spese ricorrenti/finanziamenti tracciate ma non contabilizzate (SKIP_RICORRENTE);
 *  - Billy non è soggetto al Gate A (resta la fonte originale degli incassi).
 *
 * Richiede PostgreSQL test attivo (profilo %test, clean-at-start). I file di
 * esempio sono fuori dal modulo: se assenti il test viene saltato (Assumptions).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EtlGateAIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path ESEMPI = Path.of("..", "esempi_input_per_ETL");
    static final String BILLY = "dati_report_billy 01Jan_30Apr2026.xlsx";
    static final String CA = "Credit Agricole.csv";
    static final String BPM = "Bpm.csv";

    @Inject MovimentoImportService importService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(ESEMPI),
                "Cartella esempi_input_per_ETL assente: test saltato");
    }

    /** Stato ETL isolato: rimuove gli esiti di import prima e dopo (no pollution cross-classe). */
    @BeforeEach
    void resetBefore() { cleanEtl(); }

    @AfterEach
    void resetAfter() { cleanEtl(); }

    void cleanEtl() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN ('IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
        });
    }

    // ── Crédit Agricole ──────────────────────────────────────────────────────
    @Test
    @Order(1)
    void testGateA_creditAgricole() throws Exception {
        EtlImportResponse resp = importFixture(CA, "IMPORT_BANCA_CA");
        UUID logId = resp.importLogId();

        assertTrue(resp.scartati() > 0, "Il Gate A deve scartare almeno una riga CA");

        // SKIP_GIROCONTO simmetrico: le 3 disposizioni verso "…AGRICOLA AGOSTINELLI SRL [BPM]"
        // (-5000/-5000/-10000). Il pagamento a "PIETRO AGOSTINELLI" (persona) NON è giroconto.
        assertEquals(3, skipCount(logId, "SKIP_GIROCONTO"),
                "Atteso esattamente il giroconto interno CA→BPM lato uscita (3 righe)");

        assertTrue(skipCount(logId, "SKIP_POS") >= 10,
                "Gli incassi POS/Numia CA devono essere esclusi");
        assertTrue(skipCount(logId, "SKIP_RICORRENTE") >= 5,
                "Bolli/canoni/assicurazioni/finanziamenti CA devono essere esclusi");

        // Nessun incasso POS finito tra i movimenti importati di questo import.
        assertEquals(0, importedWithDesc(logId, "%INCASSO POS%"),
                "Nessun INCASSO POS deve diventare un movimento");
        assertEquals(0, importedWithDesc(logId, "%NUMIA%"),
                "Nessun accredito Numia deve diventare un movimento");
    }

    // ── Banco BPM ──────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    void testGateA_bancoBpm() throws Exception {
        EtlImportResponse resp = importFixture(BPM, "IMPORT_BANCA_BPM");
        UUID logId = resp.importLogId();

        assertTrue(skipCount(logId, "SKIP_POS") >= 4,
                "Gli incassi POS BPM (090/092/Numia) devono essere esclusi");
        assertEquals(0, importedWithDesc(logId, "%NUMIA%"),
                "Nessun accredito Numia BPM deve diventare un movimento");

        // Stripe NON è POS: resta un ricavo importato (il tag Alveare arriva in F3).
        assertTrue(importedWithDesc(logId, "%STRIPE%") >= 1,
                "Gli incassi Stripe restano movimenti (non sono POS)");
    }

    // ── Billy ───────────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    void testGateA_billyNonScartato() throws Exception {
        EtlImportResponse resp = importFixture(BILLY, "IMPORT_BILLY");

        assertEquals(0, resp.scartati(),
                "Billy è la fonte originale: il Gate A non deve scartarne le righe");
        assertTrue(resp.importati() > 0, "Billy deve importare almeno un movimento");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    EtlImportResponse importFixture(String filename, String fonteStr) throws Exception {
        Path p = ESEMPI.resolve(filename);
        Assumptions.assumeTrue(Files.isRegularFile(p), "File assente: " + p);
        try (InputStream in = new FileInputStream(p.toFile())) {
            return importService.importFile(in, filename, fonteStr, TEST_USER);
        }
    }

    long skipCount(UUID logId, String motivo) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM import_scartati WHERE import_log_id = :id AND motivo = :m")
                .setParameter("id", logId)
                .setParameter("m", motivo)
                .getSingleResult()).longValue();
    }

    long importedWithDesc(UUID logId, String like) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM movimenti WHERE fonte_importazione_id = :id AND descrizione LIKE :like")
                .setParameter("id", logId)
                .setParameter("like", like)
                .getSingleResult()).longValue();
    }
}
