package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.cassa.dto.CassaMovimentoDTO;
import com.agostinelli.gestionale.reporting.dto.RiepilogoCategoriaDTO;
import com.agostinelli.gestionale.shared.dto.MovimentoDTO;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Servizio I/O puro: riceve dati già calcolati e produce byte[]/stream.
 * Zero logica di business.
 */
@ApplicationScoped
public class ExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] HEADER_MOVIMENTI = {
            "Data", "Tipo", "Importo Lordo", "Importo Netto", "IVA",
            "Descrizione", "Categoria", "Fornitore", "Business Unit", "Conto", "Stato", "Fonte"
    };

    // ── CSV ───────────────────────────────────────────────────────────────────

    public byte[] buildCsvMovimenti(List<MovimentoDTO> movimenti) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // UTF-8 BOM per Excel italiano
        baos.write(0xEF);
        baos.write(0xBB);
        baos.write(0xBF);

        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(baos, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder()
                        .setHeader(HEADER_MOVIMENTI)
                        .setDelimiter(';')
                        .build())) {

            for (MovimentoDTO m : movimenti) {
                printer.printRecord(
                        formatDate(m.dataMovimento()),
                        m.tipo(),
                        formatImporto(m.importoLordo()),
                        formatImporto(m.importo()),
                        formatImporto(m.importoIva()),
                        m.descrizione(),
                        m.categoriaNome(),
                        m.fornitoreNome(),
                        m.businessUnitNome(),
                        m.contoNome(),
                        m.stato(),
                        m.fonte()
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Errore generazione CSV", e);
        }
        return baos.toByteArray();
    }

    // ── XLSX movimenti ────────────────────────────────────────────────────────

    @SuppressWarnings("java:S2095")
    public void streamXlsxMovimenti(List<MovimentoDTO> movimenti, OutputStream out) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Movimenti");
            CellStyle headerStyle = createHeaderStyle(wb, new byte[]{0x15, 0x65, (byte) 0xC0});
            CellStyle importoStyle = createImportoStyle(wb);

            writeMovimentiSheet(sheet, movimenti, headerStyle, importoStyle, false);
            wb.write(out);
        } catch (IOException e) {
            throw new RuntimeException("Errore generazione XLSX", e);
        }
    }

    // ── XLSX commercialista (3 sheet) ─────────────────────────────────────────

    @SuppressWarnings("java:S2095")
    public void streamXlsxCommercialista(
            List<MovimentoDTO> movimenti,
            List<RiepilogoCategoriaDTO> riepilogo,
            List<CassaMovimentoDTO> cassa,
            OutputStream out) {

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            // Verde scuro #1B5E20 per header contabile
            CellStyle headerStyle  = createHeaderStyle(wb, new byte[]{0x1B, 0x5E, 0x20});
            CellStyle importoStyle = createImportoStyle(wb);
            CellStyle footerStyle  = createFooterStyle(wb);
            String footerTesto = "Generato il " + LocalDate.now().format(DATE_FMT) + " - Gestionale Agostinelli";

            // Sheet 1: Movimenti + colonna CoGe
            XSSFSheet sh1 = wb.createSheet("Movimenti");
            writeMovimentiSheet(sh1, movimenti, headerStyle, importoStyle, true);
            appendFooterRow(sh1, footerStyle, footerTesto, HEADER_MOVIMENTI.length + 1);

            // Sheet 2: Riepilogo categorie
            XSSFSheet sh2 = wb.createSheet("Riepilogo Categorie");
            writeRiepilogoSheet(sh2, riepilogo, headerStyle, importoStyle);
            appendFooterRow(sh2, footerStyle, footerTesto, 4);

            // Sheet 3: Cassa
            XSSFSheet sh3 = wb.createSheet("Cassa");
            writeCassaSheet(sh3, cassa, headerStyle, importoStyle);
            appendFooterRow(sh3, footerStyle, footerTesto, 5);

            wb.write(out);
        } catch (IOException e) {
            throw new RuntimeException("Errore generazione XLSX commercialista", e);
        }
    }

    // ── XLSX P&L BU ──────────────────────────────────────────────────────────

    @SuppressWarnings("java:S2095")
    public void streamXlsxPlBu(com.agostinelli.gestionale.reporting.dto.PlDTO pl, OutputStream out) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("P&L");
            CellStyle headerStyle = createHeaderStyle(wb, new byte[]{0x15, 0x65, (byte) 0xC0});
            CellStyle importoStyle = createImportoStyle(wb);

            int rowNum = 0;
            Row titleRow = sheet.createRow(rowNum++);
            titleRow.createCell(0).setCellValue("Periodo: " +
                    (pl.from() != null ? pl.from().format(DATE_FMT) : "") + " - " +
                    (pl.to() != null ? pl.to().format(DATE_FMT) : ""));

            rowNum++; // riga vuota
            writeSectionHeader(sheet, rowNum++, "RICAVI", headerStyle);
            for (com.agostinelli.gestionale.reporting.dto.VoceDTO v : pl.ricavi().perCategoria()) {
                Row r = sheet.createRow(rowNum++);
                setCell(r, 0, v.codiceCoge() + " " + v.categoria());
                setImportoCell(r, 1, v.importo(), importoStyle);
            }
            Row totRicavi = sheet.createRow(rowNum++);
            setCell(totRicavi, 0, "Totale Ricavi");
            setImportoCell(totRicavi, 1, pl.ricavi().totale(), importoStyle);

            rowNum++;
            writeSectionHeader(sheet, rowNum++, "COSTI OPERATIVI", headerStyle);
            for (com.agostinelli.gestionale.reporting.dto.VoceDTO v : pl.costi().perCategoria()) {
                Row r = sheet.createRow(rowNum++);
                setCell(r, 0, v.codiceCoge() + " " + v.categoria());
                setImportoCell(r, 1, v.importo(), importoStyle);
            }
            Row totCosti = sheet.createRow(rowNum++);
            setCell(totCosti, 0, "Totale Costi");
            setImportoCell(totCosti, 1, pl.costi().totale(), importoStyle);

            rowNum++;
            Row ebitdaRow = sheet.createRow(rowNum++);
            setCell(ebitdaRow, 0, "EBITDA");
            setImportoCell(ebitdaRow, 1, pl.ebitda(), importoStyle);

            Row margineRow = sheet.createRow(rowNum);
            setCell(margineRow, 0, "Margine %");
            setCell(margineRow, 1, pl.marginePct() != null ? formatImporto(pl.marginePct()) + "%" : "N/A");

            for (int i = 0; i <= 1; i++) sheet.autoSizeColumn(i);
            wb.write(out);
        } catch (IOException e) {
            throw new RuntimeException("Errore generazione XLSX P&L", e);
        }
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private void writeSectionHeader(XSSFSheet sheet, int rowNum, String title, CellStyle style) {
        Row row = sheet.createRow(rowNum);
        Cell c = row.createCell(0);
        c.setCellValue(title);
        c.setCellStyle(style);
    }

    private void writeMovimentiSheet(XSSFSheet sheet, List<MovimentoDTO> movimenti,
            CellStyle headerStyle, CellStyle importoStyle, boolean includiCodiceGe) {

        int colCount = includiCodiceGe ? HEADER_MOVIMENTI.length + 1 : HEADER_MOVIMENTI.length;
        Row header = sheet.createRow(0);
        for (int i = 0; i < HEADER_MOVIMENTI.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(HEADER_MOVIMENTI[i]);
            c.setCellStyle(headerStyle);
        }
        if (includiCodiceGe) {
            Cell c = header.createCell(HEADER_MOVIMENTI.length);
            c.setCellValue("Cod.CoGe");
            c.setCellStyle(headerStyle);
        }

        int rowNum = 1;
        for (MovimentoDTO m : movimenti) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, formatDate(m.dataMovimento()));
            setCell(row, 1, m.tipo());
            setImportoCell(row, 2, m.importoLordo(), importoStyle);
            setImportoCell(row, 3, m.importo(), importoStyle);
            setImportoCell(row, 4, m.importoIva(), importoStyle);
            setCell(row, 5, m.descrizione());
            setCell(row, 6, m.categoriaNome());
            setCell(row, 7, m.fornitoreNome());
            setCell(row, 8, m.businessUnitNome());
            setCell(row, 9, m.contoNome());
            setCell(row, 10, m.stato());
            setCell(row, 11, m.fonte());
            // Cod.CoGe non è in MovimentoDTO condiviso, lasciato vuoto (compilato via DB query separata se necessario)
        }

        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    private void writeRiepilogoSheet(XSSFSheet sheet, List<RiepilogoCategoriaDTO> riepilogo,
            CellStyle headerStyle, CellStyle importoStyle) {

        String[] hdr = {"Categoria", "N.Movimenti", "Totale Entrate", "Totale Uscite"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < hdr.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(hdr[i]);
            c.setCellStyle(headerStyle);
        }
        int rowNum = 1;
        for (RiepilogoCategoriaDTO r : riepilogo) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, r.categoria());
            row.createCell(1).setCellValue(r.nMovimenti());
            setImportoCell(row, 2, r.totaleEntrate(), importoStyle);
            setImportoCell(row, 3, r.totaleUscite(), importoStyle);
        }
        for (int i = 0; i < hdr.length; i++) sheet.autoSizeColumn(i);
    }

    private void writeCassaSheet(XSSFSheet sheet, List<CassaMovimentoDTO> cassa,
            CellStyle headerStyle, CellStyle importoStyle) {

        String[] hdr = {"Data", "Tipo", "Importo", "Descrizione", "Stato"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < hdr.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(hdr[i]);
            c.setCellStyle(headerStyle);
        }
        int rowNum = 1;
        for (CassaMovimentoDTO m : cassa) {
            Row row = sheet.createRow(rowNum++);
            setCell(row, 0, formatDate(m.dataMovimento()));
            setCell(row, 1, m.tipo());
            setImportoCell(row, 2, m.importo(), importoStyle);
            setCell(row, 3, m.descrizione());
            setCell(row, 4, m.stato());
        }
        for (int i = 0; i < hdr.length; i++) sheet.autoSizeColumn(i);
    }

    private void appendFooterRow(XSSFSheet sheet, CellStyle footerStyle, String testo, int colSpan) {
        int lastRow = sheet.getLastRowNum() + 1;
        Row footer = sheet.createRow(lastRow);
        Cell c = footer.createCell(0);
        c.setCellValue(testo);
        c.setCellStyle(footerStyle);
    }

    private CellStyle createHeaderStyle(XSSFWorkbook wb, byte[] rgb) {
        XSSFCellStyle style = wb.createCellStyle();
        XSSFColor color = new XSSFColor(rgb, null);
        style.setFillForegroundColor(color);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private CellStyle createImportoStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat df = wb.createDataFormat();
        style.setDataFormat(df.getFormat("#,##0.00 €"));
        return style;
    }

    private CellStyle createFooterStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        return style;
    }

    private void setCell(Row row, int col, String value) {
        row.createCell(col).setCellValue(value != null ? value : "");
    }

    private void setImportoCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value.doubleValue() : 0.0);
        c.setCellStyle(style);
    }

    private String formatDate(LocalDate d) {
        return d != null ? d.format(DATE_FMT) : "";
    }

    private String formatImporto(BigDecimal v) {
        if (v == null) return "";
        return String.format(Locale.ITALIAN, "%,.2f", v);
    }
}
