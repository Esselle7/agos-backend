package com.agostinelli.gestionale.movimenti.importlayer.model;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L'ipotesi che il motore ha fatto e <b>non</b> ha applicato (R4/R6): il conto che proporrebbe e
 * il <b>perché in chiaro</b> — quale firma, quale token, quale regola. Il perché è scritto per
 * l'operatore, non per il log: è la frase che gli permette di confermare o correggere in un colpo.
 *
 * <p><b>Come arriva al dato (R1).</b> Nessuna colonna nuova e nessuna tabella nuova (ADR 008): la
 * proposta viaggia dentro {@code movimenti.note} del movimento nato sul transitorio, con un
 * marcatore stabile in testa — {@code PROPOSTA[40.05.002|bu=2] la firma keyword «…»}. È l'UNICO
 * contratto fra l'import e il wizard, e vive tutto qui: {@link #marcatore()} lo scrive,
 * {@link #leggi(String)} lo rilegge.
 *
 * <p><b>Perché il ramo sta nel marcatore (13/08/2026).</b> Il motore la BU la calcola, poi la
 * sovrascrive con quella del transitorio per non far finire denaro su un ramo non confermato: fino
 * a oggi il marcatore portava solo il conto, quindi il wizard chiedeva all'operatore un ramo che il
 * sistema già conosceva. Il lavoro c'era, si perdeva nel viaggio. Il campo è opzionale: i movimenti
 * scritti prima di oggi si rileggono senza migration, semplicemente senza ramo proposto.
 *
 * <p>{@link #fornitoreId()} invece <b>non</b> viaggia qui ed è null in ciò che {@code leggi}
 * restituisce: il motore lo scrive già sulla colonna {@code movimenti.fornitore_id} del movimento
 * transitorio, e duplicarlo nella nota vorrebbe dire due fonti di verità per lo stesso dato.
 */
public record Proposta(String cogeCodice, Short bu, UUID fornitoreId, String perche) {

    private static final Pattern MARCATORE = Pattern.compile(
            "PROPOSTA\\[([0-9.]+)((?:\\|[a-z]+=[^|\\]]*)*)]\\s*(.*)", Pattern.DOTALL);

    /** Il marcatore di QUESTA proposta: il conto sempre, il ramo solo se il motore lo sa. */
    public String marcatore() {
        return "PROPOSTA[" + cogeCodice + (bu == null ? "" : "|bu=" + bu) + "]";
    }

    /**
     * Toglie la proposta dalla nota: una volta che l'utente ha deciso, quella frase mentirebbe.
     * Quello che l'utente aveva scritto di suo (prima del marcatore) resta.
     */
    public static String rimuovi(String note) {
        if (note == null) return null;
        Matcher m = MARCATORE.matcher(note);
        if (!m.find()) return note;
        String prima = note.substring(0, m.start()).trim();
        if (prima.endsWith("|")) prima = prima.substring(0, prima.length() - 1).trim();
        return prima.isEmpty() ? null : prima;
    }

    /** Rilegge la proposta scritta nella nota del movimento. null se la nota non ne porta una. */
    public static Proposta leggi(String note) {
        if (note == null) return null;
        Matcher m = MARCATORE.matcher(note);
        if (!m.find()) return null;
        String perche = m.group(3) == null ? "" : m.group(3).trim();
        Short bu = null;
        for (String campo : m.group(2).split("\\|")) {
            if (campo.startsWith("bu=")) bu = parseShort(campo.substring(3));
        }
        return new Proposta(m.group(1), bu, null, perche.isEmpty() ? null : perche);
    }

    /** Una nota malformata non deve far esplodere la coda: il campo illeggibile vale «non c'è». */
    private static Short parseShort(String s) {
        try { return Short.valueOf(s); } catch (NumberFormatException e) { return null; }
    }
}
