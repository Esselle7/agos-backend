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
 * Integration test del Gate B (eventi, ETL v2 §5) e della logica F3
 * (fornitore non bloccante + tag Alveare + partite speciali) sui file reali.
 *
 * Importa Billy → CA → BPM in ordine e verifica:
 *  - eventi parcheggiati in eventi_da_riconciliare senza doppioni cross-sorgente
 *    (dedup su chiave_aggancio: ~29/54 righe Billy-agri ricompaiono in banca);
 *  - le 3 righe POS Billy e KAIROS NON vengono parcheggiate (carve-out);
 *  - Stripe taggato [ALVEARE] (descrizione + note);
 *  - nessuna uscita scartata per fornitore: le ignote vanno su 49.99.999 + triage;
 *  - partite speciali: PAC → 30.05.001, versamento socio → 90.02.001.
 */
@QuarkusTest
class EtlGateBIntegrationTest {

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

    @Test
    void testGateB_eventiF3() throws Exception {
        // ── Billy: gli eventi (Agriturismo>0) finiscono in coda, carve-out esclusi ──
        EtlImportResponse billy = importFixture(BILLY, "IMPORT_BILLY");
        assertTrue(billy.parcheggiati() > 40,
                "Billy deve parcheggiare la maggior parte delle 54 righe Agriturismo>0");

        // Carve-out (Agriturismo>0 + incasso POS puro): le 3 righe POS reali NON sono eventi.
        // NB: un evento pagato via POS (es. "INCASSO POS … CAPARRA EVENTO 18ESIMO") con
        // Agriturismo=0 resta un evento — il carve-out vale solo per le righe Agriturismo>0.
        for (String posChiave : new String[]{"46073/500", "46033/250", "46055/500"}) {
            assertEquals(0, parkedWithChiave(posChiave),
                    "La riga POS pura " + posChiave + " non deve essere parcheggiata come evento");
        }
        assertEquals(0, parkedWithDesc("%KAIROS%"), "La riga KAIROS non è un evento");
        // Le righe POS agriturismo diventano ricavo ristorazione (carve-out 30.01.001).
        assertTrue(importedOnCoge(billy.importLogId(), "30.01.001") >= 1,
                "Il carve-out POS agriturismo deve produrre ricavi 30.01.001");

        // ── CA: dedup cross-sorgente + fornitore non bloccante + PAC ──
        EtlImportResponse ca = importFixture(CA, "IMPORT_BANCA_CA");
        assertTrue(ca.parcheggiati() >= 1, "CA deve parcheggiare eventi propri");

        // Nessuna uscita scartata per fornitore: ignote su 49.99.999, mai FORNITORE_NON_RICONOSCIUTO.
        assertEquals(0, ambiguityCount("FORNITORE_NON_RICONOSCIUTO"),
                "Nessuna uscita deve essere scartata per fornitore non riconosciuto");
        assertTrue(importedOnCoge(ca.importLogId(), "49.99.999") >= 1,
                "Le uscite senza alias devono finire sul transitorio 49.99.999");
        // Partita speciale: contributo pubblico (ORGANISMO PAGATORE) → 30.05.001.
        assertTrue(importedOnCoge(ca.importLogId(), "30.05.001") >= 1,
                "Il contributo PAC deve essere mappato su 30.05.001");

        // ── BPM: tag Alveare su Stripe + versamento socio ──
        EtlImportResponse bpm = importFixture(BPM, "IMPORT_BANCA_BPM");
        assertTrue(importedOnCoge(bpm.importLogId(), "30.03.003") >= 1, "Stripe → 30.03.003");
        assertTrue(taggedAlveare(bpm.importLogId()) >= 1,
                "Gli incassi Stripe devono essere taggati [ALVEARE] in descrizione + note");
        assertTrue(importedOnCoge(bpm.importLogId(), "90.02.001") >= 1,
                "I versamenti soci devono finire su 90.02.001");

        // ── Regressione: evento con keyword debole + data TESTUALE su bonifico estero ──
        // "...patrizia casolari . acconto festa 7 marzo 2026": niente "BON.DA" (ordinante
        // non estratto) e data a parole. Deve comunque essere parcheggiato come ACCONTO,
        // non finire nei transitori. (Gate B → contesto evento via data testuale.)
        long casolari = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM eventi_da_riconciliare " +
                        "WHERE descrizione_norm LIKE '%CASOLARI%' " +
                        "AND tipo_evento_presunto = 'ACCONTO' " +
                        "AND data_evento_estratta = DATE '2026-03-07'")
                .getSingleResult()).longValue();
        assertEquals(1, casolari,
                "L'acconto festa Casolari (data testuale '7 marzo 2026') deve essere parcheggiato come ACCONTO con data 2026-03-07");

        // ── Dedup cross-sorgente: nessuna chiave_aggancio duplicata in coda ──
        long chiaviDuplicate = ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM (SELECT chiave_aggancio FROM eventi_da_riconciliare " +
                        "WHERE chiave_aggancio IS NOT NULL GROUP BY chiave_aggancio HAVING count(*) > 1) d")
                .getSingleResult()).longValue();
        assertEquals(0, chiaviDuplicate, "Nessuna chiave_aggancio deve comparire due volte in coda");

        // Almeno un evento Billy è stato ritrovato (e deduplicato) sugli estratti conto.
        assertTrue(ca.duplicati() + bpm.duplicati() > 0,
                "Eventi Billy ricomparsi in banca devono essere deduplicati sulla chiave_aggancio");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    EtlImportResponse importFixture(String filename, String fonteStr) throws Exception {
        Path p = ESEMPI.resolve(filename);
        Assumptions.assumeTrue(Files.isRegularFile(p), "File assente: " + p);
        try (InputStream in = new FileInputStream(p.toFile())) {
            return importService.importFile(in, filename, fonteStr, TEST_USER);
        }
    }

    long parkedWithDesc(String like) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM eventi_da_riconciliare WHERE descrizione_norm LIKE :like")
                .setParameter("like", like)
                .getSingleResult()).longValue();
    }

    long parkedWithChiave(String chiave) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM eventi_da_riconciliare WHERE chiave_aggancio = :c")
                .setParameter("c", chiave)
                .getSingleResult()).longValue();
    }

    long importedOnCoge(UUID logId, String cogeCodice) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                        "WHERE m.fonte_importazione_id = :id AND p.codice = :cod")
                .setParameter("id", logId)
                .setParameter("cod", cogeCodice)
                .getSingleResult()).longValue();
    }

    long taggedAlveare(UUID logId) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM movimenti WHERE fonte_importazione_id = :id " +
                        "AND descrizione LIKE '[ALVEARE]%' AND note = 'Incasso Alveare (Stripe)'")
                .setParameter("id", logId)
                .getSingleResult()).longValue();
    }

    long ambiguityCount(String motivo) {
        return ((Number) em.createNativeQuery(
                        "SELECT count(*) FROM import_ambiguita WHERE motivo = :m")
                .setParameter("m", motivo)
                .getSingleResult()).longValue();
    }
}
