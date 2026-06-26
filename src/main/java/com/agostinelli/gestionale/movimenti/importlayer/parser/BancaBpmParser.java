package com.agostinelli.gestionale.movimenti.importlayer.parser;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoParser;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parser Banco BPM, header-driven: legge per nome di colonna (non per posizione)
 * sia il CSV nativo ("MovimentiCC OnLine": {@code Data contabile;Data valuta;Importo;
 * Divisa;Causale;Descrizione;Canale}) sia eventuali export arricchiti/Excel con
 * colonne extra (CHECK/chiave/Banca) o riordinate.
 *
 * Le colonne obbligatorie sono Data contabile, Importo, Causale; chiave (per la
 * dedup) e le altre sono opzionali. La conversione di formato (data → dd/MM/yyyy,
 * importo → italiano) è demandata a {@link Valori} così che il normalizzatore resti
 * invariato.
 */
@ApplicationScoped
public class BancaBpmParser implements MovimentoParser {

    private static final Set<String> ANCHORS = Set.of("Data contabile", "Importo", "Causale");

    @Override
    public List<RawRow> parse(InputStream file) {
        try {
            Tabella t = TabularReader.read(file, ANCHORS, null);
            List<RawRow> rows = new java.util.ArrayList<>();
            int rigaOut = 0;
            for (Tabella.Riga r : t.righe()) {
                String dataContabile = t.valore(r, "Data contabile");
                if (dataContabile == null) continue; // riga non-dato / continuazione

                Map<String, String> campi = new LinkedHashMap<>();
                campi.put(Sorgente.KEY, Sorgente.BPM);
                campi.put("DATA_CONTABILE", Valori.toItSlash(dataContabile));
                campi.put("DATA_VALUTA", Valori.toItSlash(t.valore(r, "Data valuta")));
                campi.put("IMPORTO", Valori.toItalianNumber(t.valore(r, "Importo")));
                campi.put("DIVISA", t.valore(r, "Divisa"));
                campi.put("CAUSALE", t.valore(r, "Causale"));
                campi.put("DESCRIZIONE", t.valore(r, "Descrizione"));
                campi.put("CHIAVE", t.valore(r, "chiave", "Chiave"));
                campi.put("CANALE", t.valore(r, "Canale"));

                rows.add(new RawRow(++rigaOut, campi));
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "BPM_PARSE_ERROR",
                    "Impossibile leggere il file Banco BPM: " + e.getMessage());
        }
    }
}
