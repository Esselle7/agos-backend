package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizzazione del testo e parser semantico della descrizione
 * (ETL_CLASSIFICAZIONE_v2 §3). Lavora su una descrizione GIÀ ripulita dal
 * normalizzatore di formato (UPPERCASE, spazi singoli, niente {@code <>*}).
 *
 * <p>Espone due viste su cui i gate fanno match:
 * <ul>
 *   <li><b>SPACED</b>: la descrizione con spazi singoli (frasi: {@code INCASSO POS});</li>
 *   <li><b>COMPACT</b>: la descrizione senza spazi (keyword spezzate dal word-wrap
 *       degli estratti conto: {@code AFFIT TO SALA → AFFITTOSALA}).</li>
 * </ul>
 *
 * <p>Estrae inoltre IBAN, ordinante, beneficiario e codice Stripe (§3 tabella entità).
 * Stateless: tutti i metodi sono statici.
 */
public final class DescNormalizer {

    private DescNormalizer() {}

    // IBAN italiano: IT + 2 check + 23 alfanumerici. Prima con prefisso "IBAN", poi bare.
    private static final Pattern IBAN_PREFIXED = Pattern.compile("IBAN:?\\s*(IT\\d{2}[A-Z0-9]{23})");
    private static final Pattern IBAN_BARE = Pattern.compile("\\b(IT\\d{2}[A-Z0-9]{23})\\b");

    // Codice Stripe: PO + 8 cifre (data) + suffisso alfanumerico.
    private static final Pattern STRIPE_CODE = Pattern.compile("\\b(PO\\d{8}[A-Z0-9]*)");

    // Ordinante CA: "ORD:<nome> DT.ORD"
    private static final Pattern ORD_CA = Pattern.compile("ORD:\\s*(.+?)\\s*DT\\.ORD");
    // Ordinante BPM: "BON.DA <nome>" fino al codice Stripe o fine stringa.
    private static final Pattern ORD_BPM = Pattern.compile("BON\\.?\\s*DA\\s+(.+?)(?:\\s+PO\\d{6,}|\\s+BONIFICO|$)");
    // Beneficiario CA uscita: "...AGRICOLA AGO<num> <beneficiario> (RIF|V/ORDINE|DESCR)"
    private static final Pattern BENEF_CA = Pattern.compile(
            "AGRICOLA\\s+AGO\\w*?\\d{6,}\\s+(.+?)\\s+(?:RIF\\.?|V/ORDINE|DESCR)");

    /** Vista COMPACT: rimuove tutti gli spazi (ricongiunge le parole spezzate dal word-wrap). */
    public static String compact(String spaced) {
        if (spaced == null) return null;
        return spaced.replaceAll("\\s+", "");
    }

    // Token di rumore (forme societarie / connettivi) rimossi dalla normalizzazione nomi (§7.1).
    private static final Set<String> NOISE_TOKENS = Set.of(
            "SRL", "SPA", "SNC", "SAS", "SS", "SC", "SOC", "SOCIETA", "AGRICOLA",
            "DI", "E", "DEL", "DELLA", "DELLE", "DEI", "RIF", "CRO", "V", "ORDINE", "CONTO");

    /**
     * Normalizzazione "a token" dei nomi controparte per il matching della rubrica (§7.1):
     * UPPERCASE, punteggiatura → spazio, rimozione forme societarie/connettivi, spazi singoli.
     * Es. {@code "GRUPPO ITALIANO VINI S.R.L."} → {@code "GRUPPO ITALIANO VINI"}.
     */
    public static String normalizeToken(String s) {
        if (s == null) return null;
        String up = s.toUpperCase().replaceAll("[^A-Z0-9]", " ");
        StringBuilder sb = new StringBuilder();
        for (String tok : up.trim().split("\\s+")) {
            if (tok.isEmpty() || NOISE_TOKENS.contains(tok)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    /** Estrae le entità semantiche dalla descrizione SPACED in base alla sorgente. */
    public static EntitaEstratte extract(String descSpaced, String sorgente) {
        if (descSpaced == null || descSpaced.isBlank()) return EntitaEstratte.EMPTY;

        String iban = firstGroup(IBAN_PREFIXED, descSpaced);
        if (iban == null) iban = firstGroup(IBAN_BARE, descSpaced);

        String stripe = firstGroup(STRIPE_CODE, descSpaced);

        String ordinante = null;
        String beneficiario = null;
        if (Sorgente.CA.equals(sorgente)) {
            ordinante = clean(firstGroup(ORD_CA, descSpaced));
            beneficiario = clean(firstGroup(BENEF_CA, descSpaced));
        } else if (Sorgente.BPM.equals(sorgente)) {
            ordinante = clean(firstGroup(ORD_BPM, descSpaced));
        }

        return new EntitaEstratte(iban, ordinante, beneficiario, stripe);
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
