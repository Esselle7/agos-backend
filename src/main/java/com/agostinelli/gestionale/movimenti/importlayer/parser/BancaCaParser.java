package com.agostinelli.gestionale.movimenti.importlayer.parser;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoParser;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser Crédit Agricole. CSV separatore ';', UTF-8, prima riga = header (saltata).
 * Line endings reali \r\r\n: normalizzati a \n prima del parse.
 * Header reale: Data Operazione;Data valuta;CHECK;Chiave;Causale;Banca;Descrizione;Entrate;Uscite;Divisa
 *
 * Skip righe-continuazione: causale vuota AND (chiave vuota o solo "/").
 */
@ApplicationScoped
public class BancaCaParser implements MovimentoParser {

    @Override
    public List<RawRow> parse(InputStream file) {
        List<RawRow> rows = new ArrayList<>();
        try {
            String content = new String(file.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\r\n", "\n");
            CSVFormat fmt = CSVFormat.DEFAULT.builder().setDelimiter(';').build();
            try (CSVParser parser = CSVParser.parse(new StringReader(content), fmt)) {
                int rigaOut = 0;
                boolean header = true;
                for (CSVRecord rec : parser) {
                    if (header) { header = false; continue; }
                    if (rec.size() == 0 || isAllBlank(rec)) continue;

                    String causale = at(rec, 4);
                    String chiave = at(rec, 3);
                    // Righe-continuazione CSV: causale vuota e chiave vuota o "/"
                    if (isBlank(causale) && (isBlank(chiave) || "/".equals(chiave))) continue;

                    Map<String, String> campi = new LinkedHashMap<>();
                    campi.put(Sorgente.KEY, Sorgente.CA);
                    campi.put("DATA_OPERAZIONE", at(rec, 0));
                    campi.put("DATA_VALUTA", at(rec, 1));
                    campi.put("CHECK", at(rec, 2));
                    campi.put("CHIAVE", chiave);
                    campi.put("CAUSALE", causale);
                    campi.put("BANCA", at(rec, 5));
                    campi.put("DESCRIZIONE", at(rec, 6));
                    campi.put("ENTRATE", at(rec, 7));
                    campi.put("USCITE", at(rec, 8));
                    campi.put("DIVISA", at(rec, 9));

                    rows.add(new RawRow(++rigaOut, campi));
                }
            }
        } catch (Exception e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CA_PARSE_ERROR",
                    "Impossibile leggere il file Crédit Agricole (.csv): " + e.getMessage());
        }
        return rows;
    }

    private String at(CSVRecord rec, int i) {
        if (i >= rec.size()) return null;
        String v = rec.get(i);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isAllBlank(CSVRecord rec) {
        for (String s : rec) {
            if (s != null && !s.isBlank()) return false;
        }
        return true;
    }
}
