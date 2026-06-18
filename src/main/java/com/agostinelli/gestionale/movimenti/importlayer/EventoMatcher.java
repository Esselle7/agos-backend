package com.agostinelli.gestionale.movimenti.importlayer;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Aggancio cross-sorgente degli eventi quando manca la {@code chiave_aggancio}
 * (numero di registrazione condiviso) negli export nativi.
 *
 * <p>Lo stesso incasso compare due volte — una in Billy (cassa) e una in banca
 * (bonifico) — e va riconosciuto come un unico evento. In assenza della chiave si
 * usa il <b>massimo numero di segnali</b> disponibili, con una scala di confidenza
 * deliberatamente <b>conservativa</b>: meglio lasciare un doppione gestibile a mano
 * nella coda di riconciliazione che fondere per errore due eventi distinti.
 *
 * <p>Oltre alla decisione ({@link #valuta}/{@link #isDuplicato}), il matcher produce
 * una {@link Spiegazione} con punteggio e <b>motivazioni</b> leggibili, pensate per
 * essere mostrate in UI (perché due eventi sono — o non sono — la stessa cosa).
 */
public final class EventoMatcher {

    public enum Esito { CERTA, PROBABILE, NESSUNO }

    /** Forza/segno di una motivazione, per la resa visiva (badge/chip). */
    public enum Tono { FORTE, MEDIO, DEBOLE, CONFLITTO }

    /** Una singola motivazione leggibile della decisione. */
    public record Motivo(String segnale, String dettaglio, Tono tono) {}

    /** Esito + punteggio (0-100) + motivazioni. */
    public record Spiegazione(Esito esito, int punteggio, List<Motivo> motivi) {}

    /** Segnali confrontabili di un evento. importo/tipo sono prerequisiti (già uguali). */
    public record Segnali(
            BigDecimal importo,
            String tipo,
            LocalDate dataMovimento,
            String nome,
            String iban,
            LocalDate dataEvento,
            String tipoEvento) {}

    /** Finestra (giorni) per raccogliere i candidati: bonifici e cassa possono sfasare. */
    public static final int GIORNI_FINESTRA = 9;
    /** Soglia stretta per fondere quando l'unico segnale è importo+data. */
    public static final int GIORNI_PROBABILE = 4;
    private static final int LEN_TOKEN_MIN = 4;
    private static final int LEN_TOKEN_COGNOME = 6;

    private static final DateTimeFormatter D_IT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Set<String> STOP = Set.of(
            "ORDINANTE", "DESCR", "OPERAZIONE", "EVENTO", "CAPARRA", "ACCONTO", "SALDO",
            "BONIFICO", "GIROCONTO", "TRASFERIMENTO", "GENERICO", "ISTANTANEO",
            "SOCIETA", "AGRICOLA", "AGOSTINELLI", "DELLA", "DELLO", "DEGLI",
            "CONFIRMATORIA", "CONFIRMATORIO", "SRL", "SPA", "SNC", "SAS");

    private EventoMatcher() {}

    public static Esito valuta(Segnali nuovo, Segnali esistente) {
        return spiega(nuovo, esistente).esito();
    }

    /** True se {@code nuovo} è un duplicato di un evento già in coda (vedi javadoc classe). */
    public static boolean isDuplicato(Segnali nuovo, List<Segnali> candidati) {
        int probabili = 0;
        for (Segnali e : candidati) {
            switch (valuta(nuovo, e)) {
                case CERTA -> { return true; }
                case PROBABILE -> probabili++;
                case NESSUNO -> { /* non è lo stesso evento */ }
            }
        }
        return probabili == 1;
    }

    /**
     * Confronta due eventi (importo+tipo già uguali a monte) e ne motiva l'esito.
     * I conflitti su un segnale identificativo (IBAN/data-evento/nome) escludono
     * l'identità; una corroborazione forte la conferma; in assenza di segnali resta
     * la sola vicinanza importo+data.
     */
    public static Spiegazione spiega(Segnali nuovo, Segnali esistente) {
        List<Motivo> motivi = new ArrayList<>();

        boolean importoUguale = nuovo.importo != null && esistente.importo != null
                && nuovo.importo.compareTo(esistente.importo) == 0;
        if (importoUguale) {
            motivi.add(new Motivo("Importo", "Importo identico: " + euro(nuovo.importo), Tono.FORTE));
        }

        // ── IBAN ──
        boolean ibanNoto = notBlank(nuovo.iban) && notBlank(esistente.iban);
        boolean ibanMatch = ibanNoto && nuovo.iban.equalsIgnoreCase(esistente.iban);
        boolean ibanConflitto = ibanNoto && !ibanMatch;
        if (ibanMatch) motivi.add(new Motivo("IBAN", "IBAN identico: " + maskIban(nuovo.iban), Tono.FORTE));
        else if (ibanConflitto) motivi.add(new Motivo("IBAN", "IBAN diversi", Tono.CONFLITTO));

        // ── Data evento ──
        boolean evNoto = nuovo.dataEvento != null && esistente.dataEvento != null;
        boolean evMatch = evNoto && nuovo.dataEvento.equals(esistente.dataEvento);
        boolean evConflitto = evNoto && !evMatch;
        if (evMatch) motivi.add(new Motivo("Data evento", "Stessa data evento: " + D_IT.format(nuovo.dataEvento), Tono.FORTE));
        else if (evConflitto) motivi.add(new Motivo("Data evento",
                "Date evento diverse: " + D_IT.format(nuovo.dataEvento) + " ≠ " + D_IT.format(esistente.dataEvento), Tono.CONFLITTO));

        // ── Nominativo ──
        Set<String> tA = tokens(nuovo.nome), tB = tokens(esistente.nome);
        boolean nomeNoto = !tA.isEmpty() && !tB.isEmpty();
        boolean nomeMatch = nomeNoto && nomiCoincidono(tA, tB);
        boolean nomeConflitto = nomeNoto && !nomeMatch;
        if (nomeMatch) motivi.add(new Motivo("Nominativo", "Stesso intestatario: " + condivisi(tA, tB), Tono.FORTE));
        else if (nomeConflitto) motivi.add(new Motivo("Nominativo",
                "Intestatari diversi: " + breve(nuovo.nome) + " ≠ " + breve(esistente.nome), Tono.CONFLITTO));
        else motivi.add(new Motivo("Nominativo", "Intestatario assente su un lato", Tono.DEBOLE));

        // ── Vicinanza temporale ──
        long giorni = (nuovo.dataMovimento == null || esistente.dataMovimento == null)
                ? Long.MAX_VALUE
                : Math.abs(ChronoUnit.DAYS.between(nuovo.dataMovimento, esistente.dataMovimento));
        boolean stessoGiorno = giorni == 0;
        boolean vicino = giorni <= GIORNI_PROBABILE;
        if (stessoGiorno) motivi.add(new Motivo("Data movimento",
                "Stesso giorno: " + D_IT.format(nuovo.dataMovimento), Tono.MEDIO));
        else if (giorni != Long.MAX_VALUE && vicino) motivi.add(new Motivo("Data movimento",
                "A " + giorni + " giorni di distanza", Tono.MEDIO));
        else if (giorni != Long.MAX_VALUE) motivi.add(new Motivo("Data movimento",
                "A " + giorni + " giorni (oltre la finestra)", Tono.DEBOLE));

        // ── Esito ──
        Esito esito;
        if (ibanConflitto || evConflitto || nomeConflitto) esito = Esito.NESSUNO;
        else if (ibanMatch || evMatch || nomeMatch) esito = Esito.CERTA;
        else esito = vicino ? Esito.PROBABILE : Esito.NESSUNO;

        // ── Punteggio (per il gauge) ──
        int p;
        if (esito == Esito.NESSUNO && (ibanConflitto || evConflitto || nomeConflitto)) {
            p = 8;
        } else {
            p = 50;
            if (ibanMatch) p += 25;
            if (evMatch) p += 18;
            if (nomeMatch) p += 18;
            if (stessoGiorno) p += 8; else if (vicino) p += 4;
            p = Math.min(p, 99);
            if (esito == Esito.NESSUNO) p = Math.min(p, 35);
        }
        return new Spiegazione(esito, p, motivi);
    }

    public static boolean nomiCoincidono(Set<String> a, Set<String> b) {
        Set<String> shared = new HashSet<>(a);
        shared.retainAll(b);
        if (shared.size() >= 2) return true;
        for (String s : shared) if (s.length() >= LEN_TOKEN_COGNOME) return true;
        return false;
    }

    public static Set<String> tokens(String nome) {
        if (nome == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String t : nome.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (t.length() >= LEN_TOKEN_MIN && !STOP.contains(t)) out.add(t);
        }
        return out;
    }

    // ── formattazione motivazioni ───────────────────────────────────────────────

    private static String condivisi(Set<String> a, Set<String> b) {
        Set<String> shared = new TreeSet<>(a);
        shared.retainAll(b);
        return String.join(" ", shared);
    }

    private static String breve(String nome) {
        if (nome == null) return "—";
        String s = nome.trim();
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }

    private static String maskIban(String iban) {
        String s = iban.replaceAll("\\s", "");
        if (s.length() <= 8) return s;
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }

    private static String euro(BigDecimal v) {
        return "€ " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
