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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REGRESSION del FINDING F1 (docs/specs/audit-catena-import-2026-08-11.md §2): una riga POS
 * bancaria con DEL dell'anno precedente NON deve sparire.
 *
 * <p>Il caso reale è nel corpus storico: {@code 02/01/2026 · ENTRATE 230,00 · INCASSO POS NEXI
 * CORE DEL 31/12/25}. Il criterio di esclusione è l'anno solare del DEL, quindi la riga usciva
 * dalla pipeline; non stava in movimenti, né in import_scartati, né in nessuna coda. L'unica
 * traccia era una nota dentro {@code quadratura_periodo.note}, in una pagina che nessun contatore
 * obbliga ad aprire: 230,00 € di accredito bancario vero fuori dai conti e muti.
 *
 * <p>Ora resta non contabilizzata (l'incasso è di competenza dell'anno prima) ma lascia una riga
 * in {@code import_scartati} con motivo {@code SKIP_CODA_TESTA}, dove l'operatore la vede.
 */
@QuarkusTest
class CodaTestaTracciataIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path STORICI = Path.of("..", "esempi_dati_storici");
    static final String BILLY = "corrispettivi-12.csv";
    static final String BPM = "MovimentiCC_OnLine_10_06_2026_11.56.28.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_06_10_115335.csv";

    @Inject MovimentoImportService importService;
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
    void codaTesta_nonSparisce_finisceInImportScartati() throws Exception {
        EtlImportResponse r = importaCongiunto();
        UUID logId = r.importLogId();

        Object[] scartata = (Object[]) em.createNativeQuery(
                "SELECT count(*), COALESCE(sum(importo), 0) FROM import_scartati "
                + "WHERE import_log_id = :id AND motivo = 'SKIP_CODA_TESTA'")
                .setParameter("id", logId).getSingleResult();

        assertEquals(1L, ((Number) scartata[0]).longValue(),
                "la riga POS con DEL del 31/12/2025 deve lasciare una traccia visibile");
        assertEquals(0, new BigDecimal("230.00").compareTo((BigDecimal) scartata[1]),
                "e deve portarsi dietro il suo importo, non solo esistere");

        // …e resta fuori dai conti: il criterio (competenza dell'anno prima) non è cambiato.
        long contabilizzata = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE fonte_importazione_id = :id AND importo_lordo = 230.00 "
                + "AND data_movimento BETWEEN '2025-12-20' AND '2026-01-20'")
                .setParameter("id", logId).getSingleResult()).longValue();
        assertEquals(0, contabilizzata, "la coda testa non si contabilizza: la si mette in coda");
    }

    EtlImportResponse importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(STORICI.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(STORICI.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(STORICI.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
