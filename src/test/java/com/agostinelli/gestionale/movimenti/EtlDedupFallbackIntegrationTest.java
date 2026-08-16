package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.AnalisiDuplicatiDTO;
import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica l'aggancio cross-sorgente degli eventi SENZA chiave_aggancio (export nativi),
 * delegato a {@code EventoMatcher}. Caso forte e deterministico: re-importando lo stesso
 * periodo, il matcher deve riconoscere gli eventi già in coda come duplicati e NON crearne
 * di nuovi, pur in assenza della chiave di registrazione.
 *
 * <p>2026-08-11: passava da {@code importFile} (import single-file), cancellato insieme a
 * ImportStrategy/Factory — l'unica modalità di import è quella congiunta (audit §8 #10). Il
 * test è stato riportato sul percorso vero, {@code importCongiunto} sui 3 file storici.
 */
@QuarkusTest
class EtlDedupFallbackIntegrationTest {

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

    @Test
    void reimportNativo_dedupSenzaChiave() throws Exception {
        EtlImportResponse ca1 = importaCongiunto();
        assertTrue(ca1.parcheggiati() > 15,
                "il CSV CA nativo deve parcheggiare diversi eventi (caparre/acconti/saldi)");

        // Nessun evento ha chiave_aggancio: la dedup non può che passare dal matcher.
        long conChiave = scalar("SELECT count(*) FROM eventi_da_riconciliare WHERE chiave_aggancio IS NOT NULL");
        assertEquals(0, conChiave, "gli export nativi non portano la chiave di registrazione");

        long dopoPrimo = scalar("SELECT count(*) FROM eventi_da_riconciliare");

        // Re-import dello stesso file: il matcher deve riconoscere (quasi) tutto come duplicato.
        EtlImportResponse ca2 = importaCongiunto();
        long dopoSecondo = scalar("SELECT count(*) FROM eventi_da_riconciliare");

        assertTrue(ca2.duplicati() >= ca1.parcheggiati() * 0.8,
                "re-import: la grande maggioranza degli eventi va riconosciuta come duplicato ("
                        + ca2.duplicati() + " / " + ca1.parcheggiati() + ")");
        assertTrue(ca2.parcheggiati() <= ca1.parcheggiati() * 0.2,
                "re-import: pochissimi nuovi eventi (solo gli sparse ambigui): " + ca2.parcheggiati());
        assertEquals(dopoPrimo + ca2.parcheggiati(), dopoSecondo,
                "la coda cresce solo dei nuovi non-duplicati");

        // Garanzia di sicurezza: nessun evento "ricco" (nome + data-evento) duplicato in coda.
        long maxRicchiDuplicati = scalar(
                "SELECT COALESCE(MAX(c), 0) FROM (" +
                "  SELECT count(*) c FROM eventi_da_riconciliare " +
                "  WHERE controparte_nome IS NOT NULL AND data_evento_estratta IS NOT NULL " +
                "  GROUP BY controparte_nome, data_evento_estratta, importo) g");
        assertEquals(1, maxRicchiDuplicati,
                "nessun evento identificabile (nome+data-evento+importo) deve comparire due volte");
    }

    @Test
    void analisiDuplicati_espongiConfidenzaEMotivazioni() {
        // Coppia controllata: stesso incasso in Billy (cassa) e in banca, stesso
        // nominativo + data evento + importo → confidenza CERTA, motivata.
        UUID logId = UUID.randomUUID();
        LocalDate dataMov = LocalDate.of(2026, 2, 4);
        LocalDate dataEv = LocalDate.of(2026, 5, 16);
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO import_log (id, fonte, filename, stato, imported_by) " +
                            "VALUES (:id,'IMPORT_BANCA','analisi-test','IN_CORSO',:u)")
                    .setParameter("id", logId).setParameter("u", TEST_USER).executeUpdate();
            inserisciEvento(logId, "IMPORT_BILLY", "SPINELLI DARIO", dataEv, dataMov);
            inserisciEvento(logId, "IMPORT_BANCA", "SPINELLI DARIO - PARROTTO STEFANIA", dataEv, dataMov);
        });

        AnalisiDuplicatiDTO a = triageService.analisiDuplicati();
        assertEquals(2, a.eventiInCoda());
        assertEquals(1, a.coppieSospette(), "le due voci dello stesso incasso devono formare una coppia");

        AnalisiDuplicatiDTO.CoppiaSospettaDTO c = a.coppie().get(0);
        assertEquals("CERTA", c.confidenza());
        assertTrue(c.punteggio() >= 80);
        assertTrue(c.motivi().stream().anyMatch(m -> m.segnale().equals("Nominativo") && "FORTE".equals(m.tono())));
        assertTrue(c.motivi().stream().anyMatch(m -> m.segnale().equals("Data evento")));
        assertTrue(c.motivi().stream().anyMatch(m -> m.segnale().equals("Importo")));
        // confronto affiancato: una voce Billy e una banca
        assertTrue((c.eventoA().fonte() + c.eventoB().fonte()).contains("IMPORT_BILLY"));
        assertTrue((c.eventoA().fonte() + c.eventoB().fonte()).contains("IMPORT_BANCA"));
    }

    void inserisciEvento(UUID logId, String fonte, String nome, LocalDate dataEv, LocalDate dataMov) {
        em.createNativeQuery("INSERT INTO eventi_da_riconciliare " +
                        "(id, import_log_id, fonte, importo, tipo, data_movimento, controparte_nome, " +
                        "data_evento_estratta, descrizione_norm, raw_data) " +
                        "VALUES (:id,:log,:fonte,:imp,'ENTRATA',:dm,:nome,:ev,:descr, CAST('{}' AS jsonb))")
                .setParameter("id", UUID.randomUUID()).setParameter("log", logId).setParameter("fonte", fonte)
                .setParameter("imp", new BigDecimal("500.00")).setParameter("dm", dataMov)
                .setParameter("nome", nome).setParameter("ev", dataEv)
                .setParameter("descr", "CAPARRA EVENTO " + nome).executeUpdate();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    EtlImportResponse importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(STORICI.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(STORICI.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(STORICI.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }

    long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
