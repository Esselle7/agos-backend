package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaBpmParser;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaCaParser;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BillyParser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica che i parser header-driven leggano la STESSA tipologia logica di import
 * sia dal file originale "addomesticato" sia dall'export nativo nuovo, e
 * indistintamente da CSV o da XLSX. Test di solo parsing (i parser sono POJO senza
 * dipendenze iniettate): isola la generalità di lettura dal mapping di dominio.
 *
 * Fonti:
 *  - Billy : XLSX originale (Tipo='S')         + CSV nativo "corrispettivi"
 *  - BPM   : CSV originale (con CHECK/chiave)   + CSV nativo "MovimentiCC OnLine" + XLSX sintetizzato
 *  - CA    : CSV originale (';', 10 colonne)    + CSV nativo "Movimenti in tempo reale" (',' doppio-quotato)
 */
class EtlParserGeneralitaTest {

    static final Path NEW = Path.of("..", "esempi_input_per_ETL_new");

    // ── Billy ──────────────────────────────────────────────────────────────────

    @Test
    void billy_csvNativo() throws Exception {
        List<RawRow> rows = parse(new BillyParser(), NEW.resolve("corrispettivi-12.csv"));
        assertEquals(204, rows.size(), "Billy CSV: solo le 204 transazioni (no preambolo/riepiloghi/2ª tabella)");

        RawRow r0 = rows.get(0);
        assertEquals("BILLY", r0.campi().get("_SORGENTE"));
        assertEquals("2026-06-10", r0.campi().get("DATA"), "datetime dd-MM-yyyy HH:mm:ss → data ISO");
        assertEquals("2000.00", r0.campi().get("IMPORTO"), "importo italiano → canonico");
        assertEquals("2000.00", r0.campi().get("AGRITURISMO"));
        assertEquals("E", r0.campi().get("PAGAMENTO"), "metodo estratto dal suffisso (E)/(C)");
        assertEquals("DCW2026/9201-1006", r0.campi().get("NUMERO"), "numero ripulito dal suffisso pagamento");

        for (RawRow r : rows) {
            assertIso(r.campi().get("DATA"));
            assertNull(r.campi().get("BANCA"), "il CSV corrispettivi non ha la colonna Banca (limite del dato)");
            assertTrue("E".equals(r.campi().get("PAGAMENTO")) || "C".equals(r.campi().get("PAGAMENTO")));
        }
        assertTrue(rows.stream().anyMatch(r -> r.campi().get("AGRITURISMO") != null));
    }

    // ── BPM ────────────────────────────────────────────────────────────────────

    @Test
    void bpm_csvNativo() throws Exception {
        List<RawRow> rows = parse(new BancaBpmParser(), NEW.resolve("MovimentiCC_OnLine_10_06_2026_11.56.28.csv"));
        assertEquals(441, rows.size(), "BPM nativo: 7 colonne quotate, nessun CHECK/chiave/Banca");
        RawRow r0 = rows.get(0);
        assertEquals("BPM", r0.campi().get("_SORGENTE"));
        assertEquals("10/06/2026", r0.campi().get("DATA_CONTABILE"));
        assertEquals("198,53", r0.campi().get("IMPORTO"));
        assertEquals("480", r0.campi().get("CAUSALE"));
        assertTrue(r0.campi().get("DESCRIZIONE").toLowerCase().contains("stripe"));
        assertNull(r0.campi().get("CHIAVE"), "colonna chiave assente nell'export nativo");
    }

    @Test
    void bpm_xlsxSintetizzato_stessaPipeline() throws Exception {
        // Prova che la STESSA fonte banca è leggibile da Excel: header in ordine diverso,
        // importi come celle numeriche (canoniche) → riconvertite in formato italiano.
        byte[] xlsx = costruisciXlsx(
                new String[]{"Data contabile", "Data valuta", "Importo", "Divisa", "Causale", "Descrizione", "Canale"},
                new Object[][]{
                        {"10/06/2026", "10/06/2026", 198.53, "EUR", "480", "bon.da stripe", ""},
                        {"09/06/2026", "09/06/2026", -63.50, "EUR", "118", "debit pagamento", ""},
                });
        List<RawRow> rows = new BancaBpmParser().parse(new ByteArrayInputStream(xlsx));
        assertEquals(2, rows.size());
        assertEquals("10/06/2026", rows.get(0).campi().get("DATA_CONTABILE"));
        assertEquals("198,53", rows.get(0).campi().get("IMPORTO"), "cella numerica xlsx → importo italiano");
        assertEquals("480", rows.get(0).campi().get("CAUSALE"));
        assertEquals("-63,5", rows.get(1).campi().get("IMPORTO"));
    }

    // ── CA ─────────────────────────────────────────────────────────────────────

    @Test
    void ca_csvNativo_doppioQuoting() throws Exception {
        List<RawRow> rows = parse(new BancaCaParser(), NEW.resolve("Movimenti_in_tempo_reale_2026_06_10_115335.csv"));
        assertEquals(304, rows.size(), "CA nativo: 304 righe dato dopo unwrap, 10 continuazioni saltate");

        RawRow r0 = rows.get(0);
        assertEquals("CA", r0.campi().get("_SORGENTE"));
        assertEquals("10/06/2026", r0.campi().get("DATA_OPERAZIONE"));
        assertEquals("GIROCONTO/BONIFICO", r0.campi().get("CAUSALE"));
        assertEquals("1.738,00", r0.campi().get("ENTRATE"), "importo recuperato dal doppio-quoting");

        // Riga con VIRGOLA nella descrizione: l'unwrap deve preservare l'allineamento colonne
        // (la descrizione era quotata al livello interno) → Entrate corretto, non shiftato.
        RawRow gilardi = rows.stream()
                .filter(r -> r.campi().get("DESCRIZIONE") != null
                        && r.campi().get("DESCRIZIONE").contains("GILARDI MORENO, MAZZONI MICHELA"))
                .findFirst().orElseThrow(() -> new AssertionError("riga GILARDI non trovata"));
        assertEquals("430,00", gilardi.campi().get("ENTRATE"),
                "virgola nella descrizione: colonne allineate, importo non shiftato");
        assertEquals("EUR", gilardi.campi().get("DIVISA"));
    }

    // ── Riconciliazione POS: dataIncassoPos / circuitoPos (FASE 1) ────────────────

    @Test
    void bpm_posNumia_estraeCircuitoEDataReale() throws Exception {
        var norm = new com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl();
        List<RawRow> rows = parse(new BancaBpmParser(), NEW.resolve("MovimentiCC_OnLine_10_06_2026_11.56.28.csv"));
        // riga "incas. tramite p.o.s - numia-bncmt del 05/06/26" da 307,54 (causale 090)
        var pos = rows.stream()
                .map(norm::normalize)
                .filter(n -> n.descrizione() != null && n.descrizione().contains("NUMIA-BNCMT")
                        && n.importo().compareTo(new java.math.BigDecimal("307.54")) == 0)
                .findFirst().orElseThrow(() -> new AssertionError("riga POS Numia non trovata"));
        assertEquals("NUMIA", pos.circuitoPos(), "BPM POS → circuito Numia");
        assertEquals(java.time.LocalDate.of(2026, 6, 5), pos.dataIncassoPos(),
                "data reale incasso = DEL 05/06/26, non la contabile 08/06");
        assertEquals("POS_BPM", pos.metodoPagamentoCodice());

        // una riga non-POS (es. bonifico Stripe) NON ha circuito/data POS
        var nonPos = rows.stream().map(norm::normalize)
                .filter(n -> n.descrizione() != null && n.descrizione().contains("STRIPE"))
                .findFirst().orElseThrow();
        assertNull(nonPos.circuitoPos());
        assertNull(nonPos.dataIncassoPos());
    }

    @Test
    void ca_posNexi_estraeCircuitoEDataReale() throws Exception {
        var norm = new com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl();
        List<RawRow> rows = parse(new BancaCaParser(), NEW.resolve("Movimenti_in_tempo_reale_2026_06_10_115335.csv"));
        // "INCASSO POS NEXI CORE DEL 06/06/26" da 26,80
        var pos = rows.stream().map(norm::normalize)
                .filter(n -> n.descrizione() != null && n.descrizione().contains("INCASSO POS NEXI")
                        && n.importo().compareTo(new java.math.BigDecimal("26.80")) == 0)
                .findFirst().orElseThrow(() -> new AssertionError("riga POS Nexi non trovata"));
        assertEquals("NEXI", pos.circuitoPos(), "CA POS → circuito Nexi");
        assertEquals(java.time.LocalDate.of(2026, 6, 6), pos.dataIncassoPos(),
                "data reale incasso = DEL 06/06/26, non la contabile 08/06");
        assertEquals("POS_CA_NEXI", pos.metodoPagamentoCodice());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private List<RawRow> parse(com.agostinelli.gestionale.movimenti.importlayer.MovimentoParser parser, Path p)
            throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(p), "Fixture assente: " + p);
        try (InputStream in = new FileInputStream(p.toFile())) {
            return parser.parse(in);
        }
    }

    private void assertIso(String s) {
        assertNotNull(s);
        assertTrue(s.matches("\\d{4}-\\d{2}-\\d{2}"), "atteso formato ISO yyyy-MM-dd, trovato: " + s);
    }

    private byte[] costruisciXlsx(String[] header, Object[][] righe) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Movimenti");
            Row h = sh.createRow(0);
            for (int i = 0; i < header.length; i++) h.createCell(i).setCellValue(header[i]);
            for (int r = 0; r < righe.length; r++) {
                Row row = sh.createRow(r + 1);
                for (int c = 0; c < righe[r].length; c++) {
                    Object v = righe[r][c];
                    Cell cell = row.createCell(c);
                    if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setCellValue(String.valueOf(v));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
