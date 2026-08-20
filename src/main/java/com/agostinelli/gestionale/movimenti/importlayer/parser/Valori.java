package com.agostinelli.gestionale.movimenti.importlayer.parser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper di conversione di formato per i parser header-driven.
 *
 * <p>Il {@code MovimentoNormalizerImpl} si aspetta, per ciascuna fonte, un formato
 * canonico fisso (Billy: data ISO + importo a punto decimale; Banche: data
 * {@code dd/MM/yyyy} + importo all'italiana). I parser sono l'unico punto in cui
 * si conosce l'origine del valore (cella numerica Excel già canonica vs stringa
 * CSV all'italiana), quindi la conversione vive qui e il normalizzatore resta
 * indipendente dal formato del file di input.
 */
public final class Valori {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter IT_SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // dd-MM-yyyy o dd/MM/yyyy, con eventuale orario in coda (es. "10-06-2026 22:59:48").
    // L'anno è accettato anche a 2 cifre: un estratto conto ri-salvato da un foglio di calcolo
    // perde il secolo ("27/07/2026" → "27/07/26") e con la vecchia regex la data diventava null.
    private static final Pattern IT_DATE =
            Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2}|\\d{4})(?:[ T]\\d{1,2}:\\d{2}(?::\\d{2})?)?$");
    private static final Pattern ISO_DATE =
            Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T].*)?$");
    private static final Pattern HAS_TIME =
            Pattern.compile("\\d{1,2}:\\d{2}");

    private Valori() {}

    // ── DATE ──────────────────────────────────────────────────────────────────

    /** Qualsiasi formato data riconosciuto → ISO {@code yyyy-MM-dd}. null se non parsabile. */
    public static String toIso(String raw) {
        LocalDate d = parseAnyDate(raw);
        return d == null ? null : d.format(ISO);
    }

    /** Qualsiasi formato data riconosciuto → {@code dd/MM/yyyy} (atteso dalle banche). */
    public static String toItSlash(String raw) {
        LocalDate d = parseAnyDate(raw);
        return d == null ? raw : d.format(IT_SLASH);
    }

    /** True se la stringa contiene una componente oraria (riga transazione Billy CSV). */
    public static boolean hasTime(String raw) {
        return raw != null && HAS_TIME.matcher(raw).find();
    }

    /** Data in qualunque formato riconosciuto → {@link LocalDate}; null se non parsabile. */
    public static LocalDate parseAnyDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        Matcher mi = IT_DATE.matcher(s);
        if (mi.matches()) {
            return safeDate(anno(mi.group(3)), Integer.parseInt(mi.group(2)), Integer.parseInt(mi.group(1)));
        }
        Matcher mo = ISO_DATE.matcher(s);
        if (mo.matches()) {
            return safeDate(Integer.parseInt(mo.group(1)), Integer.parseInt(mo.group(2)), Integer.parseInt(mo.group(3)));
        }
        return null;
    }

    /**
     * Finestra del secolo per l'anno a 2 cifre: {@code yy → 2000 + yy} (99 → 2099, non 1999).
     * Scelta esplicita e non "furba": questi file sono estratti conto, non archivi storici —
     * un movimento bancario del 1999 non entra da qui, uno del 2099 nemmeno, ma se un giorno
     * entrasse la regola resta leggibile. Stessa finestra già usata da {@code extractPosDate}
     * sul "DEL gg/mm/aa" delle descrizioni POS.
     */
    private static int anno(String raw) {
        int y = Integer.parseInt(raw);
        return raw.length() == 2 ? 2000 + y : y;
    }

    private static LocalDate safeDate(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
    }

    // ── IMPORTI ────────────────────────────────────────────────────────────────

    /**
     * Importo all'italiana ("1.234,56" / "-321,76") → canonico a punto decimale
     * ("1234.56"). Lascia invariata una stringa già canonica (cella numerica Excel,
     * es. "67.27272727272727") riconosciuta dall'assenza di virgola.
     */
    public static String toCanonicalNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.indexOf(',') < 0) return s;            // già canonica (Excel) o intero
        return s.replace(".", "").replace(",", ".");  // italiana → canonica
    }

    // ── CAUSALI ────────────────────────────────────────────────────────────────

    /**
     * Codice causale banca → forma canonica a 3 cifre ("92" → "092"). Un foglio di calcolo
     * legge la colonna come numero e mangia lo zero iniziale: senza questo, {@code metodoBpm}
     * non riconosce più l'incasso POS (090/092) e la riga esce dalla riconciliazione POS.
     *
     * <p>Solo le causali <b>interamente numeriche</b> più corte di 3 caratteri vengono paddate:
     * le alfanumeriche ("78A", "ZI0", "50C") e le causali testuali del Crédit Agricole
     * ("GIROCONTO/BONIFICO") restano intatte.
     */
    public static String causaleBanca(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() >= 3) return s;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return s;
        }
        return "0".repeat(3 - s.length()) + s;
    }

    /**
     * Importo canonico a punto decimale → formato italiano a virgola ("198.53" →
     * "198,53"), atteso da {@code parseEuroAmount} nel normalizzatore banche.
     * Le celle numeriche Excel non hanno mai separatore di migliaia, quindi il
     * solo scambio punto→virgola è corretto. Stringhe già all'italiana restano tali.
     */
    public static String toItalianNumber(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.indexOf(',') >= 0) return s;  // già italiana (CSV banca)
        return s.replace('.', ',');         // canonica (Excel) → italiana
    }
}
