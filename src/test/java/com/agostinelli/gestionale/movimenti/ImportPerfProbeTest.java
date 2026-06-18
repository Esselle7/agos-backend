package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * SONDA DI PERFORMANCE (non un test di correttezza): importa i 3 file reali e cronometra le
 * chiamate che la pagina di smistamento esegue, per capire cosa rallenta. Stampa i tempi.
 */
@QuarkusTest
@Disabled("Sonda di profiling manuale: abilitare per misurare i tempi di smistamento, non gira nella suite")
class ImportPerfProbeTest {

    static final UUID U = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path NEW = Path.of("..", "esempi_input_per_ETL_new");

    @Inject MovimentoImportService importService;
    @Inject ImportTriageService triage;

    @BeforeAll
    static void check() { Assumptions.assumeTrue(Files.isDirectory(NEW), "esempi assenti"); }

    @Test
    void profileSmistamento() throws Exception {
        try (InputStream b = new FileInputStream(NEW.resolve("corrispettivi-12.csv").toFile());
             InputStream bpm = new FileInputStream(NEW.resolve("MovimentiCC_OnLine_10_06_2026_11.56.28.csv").toFile());
             InputStream ca = new FileInputStream(NEW.resolve("Movimenti_in_tempo_reale_2026_06_10_115335.csv").toFile())) {
            time("import congiunto (una tantum)", () -> { importService.importCongiunto(b, bpm, ca,
                    "corrispettivi-12.csv", "bpm.csv", "ca.csv", U); return 0; });
        }
        System.out.println("\n========== PROFILING SMISTAMENTO (ms per chiamata) ==========");
        time("getImportKpi", () -> { triage.getKpi(); return 0; });
        time("listTransitori(2000)", () -> triage.listTransitori(null, 0, 2000).totalElements());
        time("getQuadratura", () -> triage.getQuadratura(null) == null ? 0 : 1);
        time("listRibaTransitori(2000)", () -> triage.listRibaTransitori(0, 2000).totalElements());
        time("listRicorrenti(2000)", () -> triage.listRicorrenti("DA_RICONCILIARE", 0, 2000).totalElements());
        time("listEventi(2000)", () -> triage.listEventi("DA_RICONCILIARE", 0, 2000).totalElements());
        time("analisiDuplicati (O(n^2))", () -> triage.analisiDuplicati().coppie().size());
        System.out.println("=============================================================\n");
    }

    private void time(String label, LongSupplier op) {
        long t0 = System.nanoTime();
        long res = op.getAsLong();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("PERF  %-32s %6d ms   (out=%d)%n", label, ms, res);
    }
}
