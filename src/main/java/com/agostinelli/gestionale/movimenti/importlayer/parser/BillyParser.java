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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser Billy (registratore di cassa), header-driven: legge per nome di colonna
 * sia l'export Excel "Corrispettivi" (con colonna {@code Tipo}: si tengono solo le
 * righe scontrino {@code Tipo='S'}) sia il CSV "Elaborazione corrispettivi" (senza
 * {@code Tipo}: si tengono le righe transazione, riconosciute dall'orario nella
 * colonna {@code Data}, scartando preambolo, riepiloghi e seconda tabella).
 *
 * Conversioni di formato delegate a {@link Valori} (data → ISO, importi → canonici)
 * così che {@code normalizeBilly} resti invariato.
 *
 * <p><b>Limite noto del CSV corrispettivi</b>: non contiene la colonna {@code Banca},
 * quindi l'attribuzione al conto bancario (BPM/CA/Cassa) non è derivabile e tali
 * righe finiscono in revisione manuale. È un limite del dato esportato, non del parser.
 */
@ApplicationScoped
public class BillyParser implements MovimentoParser {

    public static final String SORGENTE_VALUE = "BILLY";

    private static final Set<String> ANCHORS = Set.of("Data", "Importo", "Agriturismo");
    private static final String SHEET = "Corrispettivi";
    /** Suffisso metodo pagamento nel CSV: "DCW2026/9201-1006 (E)" → E / C. */
    private static final Pattern PAGAMENTO_SUFFISSO = Pattern.compile("\\(([CEce])\\)\\s*$");

    @Override
    public List<RawRow> parse(InputStream file) {
        try {
            Tabella t = TabularReader.read(file, ANCHORS, SHEET);
            boolean conTipo = t.haColonna("Tipo"); // export Excel completo vs CSV corrispettivi

            List<RawRow> rows = new ArrayList<>();
            int rigaOut = 0;
            for (Tabella.Riga r : t.righe()) {
                String data = t.valore(r, "Data");

                if (conTipo) {
                    if (!"S".equals(t.valore(r, "Tipo"))) continue; // solo scontrini reali
                } else {
                    // CSV: solo righe transazione (orario presente); esclude riepiloghi e 2ª tabella
                    if (!Valori.hasTime(data)) continue;
                }

                String numeroRaw = t.valore(r, "Numero"); // "Numero" | "Numero (Pagamento)"
                String pagamento = t.valore(r, "Pagamento");
                String numero = numeroRaw;
                if (numeroRaw != null) {
                    Matcher m = PAGAMENTO_SUFFISSO.matcher(numeroRaw);
                    if (m.find()) {
                        if (pagamento == null) pagamento = m.group(1).toUpperCase();
                        numero = numeroRaw.substring(0, m.start()).trim();
                    }
                }

                Map<String, String> campi = new LinkedHashMap<>();
                campi.put(Sorgente.KEY, SORGENTE_VALUE);
                campi.put("DATA", Valori.toIso(data));
                campi.put("NOTE", t.valore(r, "Note"));
                campi.put("CHIAVE", t.valore(r, "Chiave Aggancio", "Chiave"));
                campi.put("NUMERO", numero);
                campi.put("BANCA", t.valore(r, "Banca"));
                campi.put("DESCRIZIONE", t.valore(r, "Descrizione"));
                campi.put("IMPORTO", Valori.toCanonicalNumber(t.valore(r, "Importo")));
                campi.put("PAGAMENTO", pagamento);
                campi.put("AGRITURISMO", Valori.toCanonicalNumber(t.valore(r, "Agriturismo")));
                campi.put("ALTRO", Valori.toCanonicalNumber(t.valore(r, "Altro", "Altro No Agriturismo")));
                campi.put("CARNE_10", Valori.toCanonicalNumber(t.valore(r, "Carne 10", "Carne")));
                campi.put("ORTOFRUTTA_4", Valori.toCanonicalNumber(t.valore(r, "Ortofrutta 4", "Ortofrutta")));
                // Categoria aggiuntiva del CSV corrispettivi (IVA 4%, accorpata a 30.03.002 in
                // riconciliazione): serve al resolver mono-categoria dell'import congiunto.
                campi.put("PRODOTTI_TRASFORMATI", Valori.toCanonicalNumber(t.valore(r, "Prodotti trasformati", "Trasformati")));
                // "Servizi" (CSV corrispettivi, IVA 10%, ristorazione): serve al resolver mono-categoria.
                campi.put("SERVIZI", Valori.toCanonicalNumber(t.valore(r, "Servizi")));

                rows.add(new RawRow(++rigaOut, campi));
            }
            return rows;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "BILLY_PARSE_ERROR",
                    "Impossibile leggere il file Billy: " + e.getMessage());
        }
    }
}
