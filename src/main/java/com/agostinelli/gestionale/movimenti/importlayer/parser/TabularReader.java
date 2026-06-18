package com.agostinelli.gestionale.movimenti.importlayer.parser;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lettore tabellare generico che produce una {@link Tabella} (header + righe)
 * sia da CSV sia da XLSX, in modo che <b>ogni</b> tipologia di import accetti
 * indistintamente i due formati.
 *
 * Capacità:
 * <ul>
 *   <li>autodetect del formato (XLSX dal magic ZIP {@code PK\\x03\\x04}, altrimenti CSV testuale);</li>
 *   <li>autodetect del separatore CSV ({@code ;} / {@code ,} / TAB);</li>
 *   <li>normalizzazione line-ending {@code \\r\\r\\n} e rimozione BOM;</li>
 *   <li>skip del preambolo: l'header è la prima riga che contiene tutte le colonne-ancora;</li>
 *   <li>recupero del doppio-quoting (righe CSV avvolte in un ulteriore livello di
 *       virgolette, es. export "Movimenti in tempo reale" di Crédit Agricole).</li>
 * </ul>
 */
public final class TabularReader {

    private TabularReader() {}

    /**
     * @param in            stream del file (CSV o XLSX)
     * @param anchors       etichette di colonna che identificano la riga header
     *                      (case/typo-insensitive); l'header è la prima riga che le contiene tutte
     * @param sheetNamePref nome foglio preferito per XLSX (fallback: primo foglio); ignorato per CSV
     */
    public static Tabella read(InputStream in, Set<String> anchors, String sheetNamePref) {
        byte[] data;
        try {
            data = in.readAllBytes();
        } catch (Exception e) {
            throw badRequest("Impossibile leggere lo stream del file: " + e.getMessage());
        }
        Set<String> anchorsNorm = new LinkedHashSet<>();
        for (String a : anchors) anchorsNorm.add(Tabella.norm(a));

        return isXlsx(data) ? readXlsx(data, anchorsNorm, sheetNamePref)
                            : readCsv(data, anchorsNorm);
    }

    private static boolean isXlsx(byte[] d) {
        return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && d[2] == 0x03 && d[3] == 0x04;
    }

    // ── CSV ────────────────────────────────────────────────────────────────────

    private static Tabella readCsv(byte[] data, Set<String> anchorsNorm) {
        String content = new String(data, StandardCharsets.UTF_8)
                .replace("﻿", "")
                .replace("\r\r\n", "\n").replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = content.split("\n", -1);

        char delim = detectDelimiter(lines);
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(delim).build();

        // Parsa ogni riga fisica (line-by-line: robusto alle continuazioni non quotate
        // e al doppio-quoting), saltando le righe completamente vuote.
        List<List<String>> parsed = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            List<String> cells = parseLine(line, fmt, delim);
            if (cells != null && !isAllBlank(cells)) parsed.add(cells);
        }

        int headerIdx = findHeader(parsed, anchorsNorm);
        if (headerIdx < 0) throw headerNotFound(anchorsNorm);

        List<String> intestazioni = Tabella.normCells(parsed.get(headerIdx));
        List<Tabella.Riga> righe = new ArrayList<>();
        int n = 0;
        for (int i = headerIdx + 1; i < parsed.size(); i++) {
            righe.add(new Tabella.Riga(++n, parsed.get(i)));
        }
        return new Tabella(false, intestazioni, righe);
    }

    /** Parsa una singola riga CSV; se collassa in un'unica cella che contiene ancora
     *  il separatore, è doppio-quotata → riparsa il contenuto (unwrap di un livello). */
    private static List<String> parseLine(String line, CSVFormat fmt, char delim) {
        List<String> cells = firstRecord(line, fmt);
        if (cells.size() == 1 && cells.get(0) != null && cells.get(0).indexOf(delim) >= 0) {
            List<String> inner = firstRecord(cells.get(0), fmt);
            if (inner.size() > 1) return inner;
        }
        return cells;
    }

    private static List<String> firstRecord(String s, CSVFormat fmt) {
        try (CSVParser p = CSVParser.parse(s, fmt)) {
            for (CSVRecord r : p) {
                List<String> out = new ArrayList<>(r.size());
                for (String v : r) out.add(v);
                return out;
            }
        } catch (Exception ignored) {
            // riga malformata: trattata come singola cella
        }
        return List.of(s);
    }

    private static char detectDelimiter(String[] lines) {
        long semi = 0, comma = 0, tab = 0;
        for (String l : lines) {
            for (int i = 0; i < l.length(); i++) {
                char c = l.charAt(i);
                if (c == ';') semi++;
                else if (c == ',') comma++;
                else if (c == '\t') tab++;
            }
        }
        if (semi >= comma && semi >= tab && semi > 0) return ';';
        if (tab > comma && tab > 0) return '\t';
        return ',';
    }

    // ── XLSX ───────────────────────────────────────────────────────────────────

    private static Tabella readXlsx(byte[] data, Set<String> anchorsNorm, String sheetNamePref) {
        try (InputStream in = new ByteArrayInputStream(data);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet sheet = null;
            if (sheetNamePref != null) sheet = wb.getSheet(sheetNamePref);
            if (sheet == null) sheet = wb.getSheetAt(0);

            List<List<String>> parsed = new ArrayList<>();
            for (Row row : sheet) {
                int last = row.getLastCellNum();
                if (last <= 0) { parsed.add(List.of()); continue; }
                List<String> cells = new ArrayList<>(last);
                for (int i = 0; i < last; i++) cells.add(cellString(row.getCell(i)));
                parsed.add(cells);
            }

            int headerIdx = findHeader(parsed, anchorsNorm);
            if (headerIdx < 0) throw headerNotFound(anchorsNorm);

            List<String> intestazioni = Tabella.normCells(parsed.get(headerIdx));
            List<Tabella.Riga> righe = new ArrayList<>();
            int n = 0;
            for (int i = headerIdx + 1; i < parsed.size(); i++) {
                List<String> c = parsed.get(i);
                if (isAllBlank(c)) continue;
                righe.add(new Tabella.Riga(++n, c));
            }
            return new Tabella(true, intestazioni, righe);

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw badRequest("Impossibile leggere il file Excel (.xlsx): " + e.getMessage());
        }
    }

    /** Cella Excel → stringa canonica: date in ISO, numeri a punto decimale, testo trim. */
    private static String cellString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                    : BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
            case FORMULA -> formulaString(cell);
            default -> "";
        };
    }

    private static String formulaString(Cell cell) {
        try {
            return cell.getStringCellValue().trim();
        } catch (Exception e) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
            } catch (Exception ignored) {
                return "";
            }
        }
    }

    // ── header detection / utils ────────────────────────────────────────────────

    /** Prima riga le cui celle normalizzate contengono tutte le ancore. */
    private static int findHeader(List<List<String>> parsed, Set<String> anchorsNorm) {
        for (int i = 0; i < parsed.size(); i++) {
            Set<String> norm = new LinkedHashSet<>(Tabella.normCells(parsed.get(i)));
            if (norm.containsAll(anchorsNorm)) return i;
        }
        return -1;
    }

    private static boolean isAllBlank(List<String> cells) {
        for (String c : cells) if (c != null && !c.isBlank()) return false;
        return true;
    }

    private static ApiException headerNotFound(Set<String> anchorsNorm) {
        return badRequest("Riga di intestazione non trovata: il file deve contenere le colonne "
                + anchorsNorm + ". Verifica di aver selezionato la fonte corretta.");
    }

    private static ApiException badRequest(String msg) {
        return new ApiException(Response.Status.BAD_REQUEST, "FILE_PARSE_ERROR", msg);
    }
}
