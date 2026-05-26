package com.agostinelli.gestionale.movimenti.importlayer.parser;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoParser;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser Billy (registratore di cassa). Legge un file Excel .xlsx tramite Apache POI.
 * Foglio "Corrispettivi" (index 0); processa SOLO le righe con Tipo='S' (scontrini reali);
 * le altre righe (Tipo null) sono aggregati/riepiloghi del foglio e vanno escluse.
 *
 * Le celle numeriche Excel (data, importi) vengono convertite in stringa canonica
 * (ISO date / toPlainString) e re-interpretate dal normalizzatore SENZA parseEuroAmount.
 */
@ApplicationScoped
public class BillyParser implements MovimentoParser {

    public static final String SORGENTE_VALUE = "BILLY";

    private static final int COL_DATA = 1;
    private static final int COL_TIPO = 4;
    private static final int COL_IMPORTO = 9;

    @Override
    public List<RawRow> parse(InputStream file) {
        List<RawRow> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(file)) {
            Sheet sheet = wb.getSheetAt(0);
            int rigaOut = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // header

                // Filtro: solo Tipo='S'
                String tipo = getCellString(row, COL_TIPO);
                if (!"S".equals(tipo)) continue;

                Map<String, String> campi = new LinkedHashMap<>();
                campi.put(Sorgente.KEY, SORGENTE_VALUE);

                campi.put("DATA_ORA", getCellString(row, 0));
                campi.put("DATA", getDateString(row, COL_DATA));
                campi.put("NOTE", getCellString(row, 2));
                campi.put("CHIAVE", getCellString(row, 3));
                campi.put("TIPO", tipo);
                campi.put("NUMERO", getCellString(row, 5));
                campi.put("BANCA", getCellString(row, 6));
                campi.put("DESCRIZIONE", getCellString(row, 7));
                campi.put("RIFERIMENTO", getCellString(row, 8));
                campi.put("IMPORTO", getNumericString(row, COL_IMPORTO));
                campi.put("PAGAMENTO", getCellString(row, 10));
                campi.put("AGRITURISMO", getNumericString(row, 11));
                campi.put("ALTRO", getNumericString(row, 12));
                campi.put("CARNE_10", getNumericString(row, 13));
                campi.put("PROD_TRASF_10", getNumericString(row, 14));
                campi.put("ORTOFRUTTA_4", getNumericString(row, 15));
                campi.put("IVA_4", getNumericString(row, 16));
                campi.put("IVA_10", getNumericString(row, 17));
                campi.put("CONTANTI", getNumericString(row, 18));
                campi.put("ELETTRONICO", getNumericString(row, 19));
                campi.put("NR_SERVIZI", getNumericString(row, 20));
                campi.put("NR_BENI", getNumericString(row, 21));
                campi.put("NR_FATTURA", getNumericString(row, 22));
                campi.put("BUONI_PASTO", getNumericString(row, 23));

                rows.add(new RawRow(++rigaOut, campi));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "BILLY_PARSE_ERROR",
                    "Impossibile leggere il file Billy (.xlsx): " + e.getMessage());
        }
        return rows;
    }

    /** Cella stringa robusta (gestisce celle numeriche/booleane/formula). */
    private String getCellString(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING -> blankToNull(cell.getStringCellValue().trim());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
            }
            case FORMULA -> blankToNull(safeFormulaString(cell));
            default -> null;
        };
    }

    /** Data Excel (cella numerica formattata data) → ISO yyyy-MM-dd. */
    private String getDateString(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        // fallback: già stringa
        return getCellString(row, idx);
    }

    /** Cella numerica Excel → stringa canonica BigDecimal (NO parseEuroAmount). */
    private String getNumericString(Row row, int idx) {
        Cell cell = row.getCell(idx);
        if (cell == null) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
        }
        return getCellString(row, idx);
    }

    private String safeFormulaString(Cell cell) {
        try {
            return cell.getStringCellValue();
        } catch (Exception e) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
