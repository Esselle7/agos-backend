package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test STEP 3 (ETL v2 F4/F5/F6 backend):
 *  - F4 rubrica controparti: seed dai fornitori + matching che attacca il fornitore;
 *  - F5 regole data-driven: una regola MAP creata a runtime sovrascrive la default;
 *  - F6 KPI: metriche di qualità dell'import.
 */
@QuarkusTest
class EtlStep3IntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path ESEMPI = Path.of("..", "esempi_input_per_ETL");
    static final String CA = "Credit Agricole.csv";

    @Inject MovimentoImportService importService;
    @Inject ImportTriageService triageService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(ESEMPI), "Cartella esempi assente: test saltato");
    }

    @BeforeEach
    void resetBefore() { cleanEtl(); }

    @AfterEach
    void resetAfter() { cleanEtl(); }

    void cleanEtl() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN ('IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
            em.createNativeQuery("DELETE FROM regole_classificazione WHERE note = 'STEP3-TEST'").executeUpdate();
        });
    }

    @Test
    void testStep3_controparti_regole_kpi() throws Exception {
        // F5 — regola data-driven creata a runtime: TELEPASS (CA, uscita) → COGE inusuale 40.11.001.
        // Senza la regola, Telepass andrebbe sul suo fornitore / transitorio: mai 40.11.001.
        Integer regolaId = triageService.createRegola(new RegolaClassificazioneDTO(
                null, 1, "CA", "USCITA", "DESC_SPACED", "CONTAINS", "TELEPASS", "MAP",
                "40.11.001", (short) 5, null, null, true, "STEP3-TEST"));
        assertNotNull(regolaId);
        assertTrue(triageService.listRegole().stream().anyMatch(r -> r.id().equals(regolaId)));

        EtlImportResponse ca = importFixture(CA, "IMPORT_BANCA_CA");

        // La regola ha effetto immediato (senza redeploy): le 3 righe Telepass → 40.11.001.
        assertTrue(importedOnCoge(ca.importLogId(), "40.11.001") >= 1,
                "La regola data-driven TELEPASS→40.11.001 deve essere applicata");

        // F4 — la rubrica controparti è popolata e attacca il fornitore alle uscite note.
        assertTrue(contropartiCount() > 0, "La rubrica controparti deve essere seedata dai fornitori");
        assertTrue(usciteConFornitore(ca.importLogId()) >= 1,
                "Almeno un'uscita deve avere il fornitore attribuito (controparti/alias)");

        // F6 — KPI di qualità import.
        ImportKpiDTO kpi = triageService.getKpi();
        assertTrue(kpi.righeTotali() > 0, "Le righe totali importate devono essere > 0");
        assertTrue(kpi.coperturaFornitoriPct() >= 0 && kpi.coperturaFornitoriPct() <= 100);
        assertTrue(kpi.movimentiTransitori() >= 0);

        // Cleanup esplicito della regola di test (oltre all'@AfterEach).
        triageService.deleteRegola(regolaId);
        assertTrue(triageService.listRegole().stream().noneMatch(r -> r.id().equals(regolaId)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    EtlImportResponse importFixture(String filename, String fonteStr) throws Exception {
        Path p = ESEMPI.resolve(filename);
        Assumptions.assumeTrue(Files.isRegularFile(p), "File assente: " + p);
        try (InputStream in = new FileInputStream(p.toFile())) {
            return importService.importFile(in, filename, fonteStr, TEST_USER);
        }
    }

    long importedOnCoge(UUID logId, String cogeCodice) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                        "WHERE m.fonte_importazione_id = :id AND p.codice = :cod")
                .setParameter("id", logId).setParameter("cod", cogeCodice)
                .getSingleResult()).longValue();
    }

    long usciteConFornitore(UUID logId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM movimenti WHERE fonte_importazione_id = :id " +
                        "AND tipo = 'USCITA' AND fornitore_id IS NOT NULL")
                .setParameter("id", logId).getSingleResult()).longValue();
    }

    long contropartiCount() {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM controparti").getSingleResult()).longValue();
    }
}
