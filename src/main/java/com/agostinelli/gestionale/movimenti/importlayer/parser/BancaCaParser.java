package com.agostinelli.gestionale.movimenti.importlayer.parser;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoParser;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser Crédit Agricole, header-driven: legge per nome di colonna sia il CSV
 * nativo ("Movimenti in tempo reale": separatore virgola, colonne
 * {@code Data Operazione,Data valuta,Causale,Descrizione,Entrate,Uscite,Divisa},
 * righe avvolte in doppio-quoting) sia gli export arricchiti a punto-e-virgola con
 * colonne extra (CHECK/Chiave/Banca).
 *
 * Il recupero del doppio-quoting e l'autodetect del separatore sono gestiti dal
 * {@link TabularReader}. Le righe di continuazione descrizione (Data Operazione
 * vuota) vengono saltate, come nell'export originale.
 */
@ApplicationScoped
public class BancaCaParser implements MovimentoParser {

    private static final Set<String> ANCHORS = Set.of("Data Operazione", "Causale", "Entrate");

    @Override
    public List<RawRow> parse(InputStream file) {
        try {
            Tabella t = TabularReader.read(file, ANCHORS, null);
            List<RawRow> rows = new ArrayList<>();
            int rigaOut = 0;
            for (Tabella.Riga r : t.righe()) {
                String dataOp = t.valore(r, "Data Operazione");
                if (dataOp == null) continue; // continuazione / riga non-dato

                Map<String, String> campi = new LinkedHashMap<>();
                campi.put(Sorgente.KEY, Sorgente.CA);
                campi.put("DATA_OPERAZIONE", Valori.toItSlash(dataOp));
                campi.put("DATA_VALUTA", Valori.toItSlash(t.valore(r, "Data valuta")));
                campi.put("CAUSALE", t.valore(r, "Causale"));
                campi.put("DESCRIZIONE", t.valore(r, "Descrizione"));
                campi.put("ENTRATE", Valori.toItalianNumber(t.valore(r, "Entrate")));
                campi.put("USCITE", Valori.toItalianNumber(t.valore(r, "Uscite")));
                campi.put("DIVISA", t.valore(r, "Divisa"));
                campi.put("CHIAVE", t.valore(r, "Chiave"));

                rows.add(new RawRow(++rigaOut, campi));
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CA_PARSE_ERROR",
                    "Impossibile leggere il file Crédit Agricole: " + e.getMessage());
        }
    }
}
