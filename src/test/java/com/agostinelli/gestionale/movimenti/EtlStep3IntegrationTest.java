package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.dto.EventoParcheggiatoDTO;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
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
            // Le controparti seedate dai fornitori hanno iban NULL: qui rimuovo solo quelle apprese nel test.
            em.createNativeQuery("DELETE FROM controparti WHERE iban IS NOT NULL").executeUpdate();
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

    @Test
    void testRollbackImport_reversibile() throws Exception {
        EtlImportResponse ca = importFixture(CA, "IMPORT_BANCA_CA");
        UUID logId = ca.importLogId();

        // L'import ha prodotto movimenti + scartati prima del rollback.
        assertTrue(countMovimenti(logId) > 0, "L'import deve aver creato movimenti");
        assertTrue(countTable("import_scartati", logId) > 0, "L'import deve aver scartato righe");
        assertEquals(1, importLogExists(logId));

        java.util.Map<String, Object> res = importService.rollbackImport(logId);
        assertTrue(((Number) res.get("movimentiEliminati")).longValue() > 0);

        // Reversibilità completa: nessuna traccia residua dell'import.
        assertEquals(0, countMovimenti(logId), "I movimenti devono essere eliminati");
        assertEquals(0, countTable("import_scartati", logId), "import_scartati svuotato (cascade)");
        assertEquals(0, countTable("eventi_da_riconciliare", logId), "eventi svuotati (cascade)");
        assertEquals(0, countTable("import_ambiguita", logId), "ambiguità svuotate (cascade)");
        assertEquals(0, importLogExists(logId), "import_log eliminato");
    }

    @Test
    void testTriageTransitorio_classifica_e_apprendimento() throws Exception {
        importFixture(CA, "IMPORT_BANCA_CA");

        PagedResponse<TransitorioDTO> before = triageService.listTransitori(null, 0, 200);
        assertTrue(before.totalElements() > 0, "Devono esserci movimenti su conti transitori");
        TransitorioDTO t = before.content().get(0);
        assertTrue(t.cogeCodiceAttuale().equals("39.99.999") || t.cogeCodiceAttuale().equals("49.99.999"));

        Integer cogeTarget = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11.001'").getSingleResult()).intValue();
        triageService.classificaTransitorio(t.id(),
                new ClassificaTransitorioRequest(cogeTarget, (short) 5, null, true, "classificato in test"));

        // Il movimento è stato spostato fuori dai transitori.
        String nuovoCoge = (String) em.createNativeQuery(
                "SELECT p.codice FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id WHERE m.id = :id")
                .setParameter("id", t.id()).getSingleResult();
        assertEquals("40.11.001", nuovoCoge);

        PagedResponse<TransitorioDTO> after = triageService.listTransitori(null, 0, 200);
        assertEquals(before.totalElements() - 1, after.totalElements(),
                "Il movimento classificato non deve più comparire tra i transitori");
    }

    @Test
    void testRisolviEvento_scarta() throws Exception {
        importFixture(CA, "IMPORT_BANCA_CA");

        PagedResponse<EventoParcheggiatoDTO> before = triageService.listEventi("DA_RICONCILIARE", 0, 200);
        assertTrue(before.totalElements() > 0, "Devono esserci eventi parcheggiati");
        EventoParcheggiatoDTO ev = before.content().get(0);

        triageService.risolviEvento(ev.id(),
                new RisolviEventoRequest("SCARTA", null, null, null, "non è un evento"), TEST_USER);

        long scartato = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM eventi_da_riconciliare WHERE id = :id AND stato = 'SCARTATO'")
                .setParameter("id", ev.id()).getSingleResult()).longValue();
        assertEquals(1, scartato, "L'evento deve risultare SCARTATO");

        PagedResponse<EventoParcheggiatoDTO> after = triageService.listEventi("DA_RICONCILIARE", 0, 200);
        assertEquals(before.totalElements() - 1, after.totalElements());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    long countMovimenti(UUID logId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE fonte_importazione_id = :id")
                .setParameter("id", logId).getSingleResult()).longValue();
    }

    long countTable(String tabella, UUID logId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM " + tabella + " WHERE import_log_id = :id")
                .setParameter("id", logId).getSingleResult()).longValue();
    }

    long importLogExists(UUID logId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM import_log WHERE id = :id")
                .setParameter("id", logId).getSingleResult()).longValue();
    }

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
