package com.agostinelli.gestionale.movimenti.importlayer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Riconosce una riga bancaria come rata di un piano ricorrente attivo.
 *
 * <p>Funzione pura (nessun I/O): i piani e le rate arrivano già caricati. Le regole e i numeri
 * sono misurati in {@code docs/specs/ricorrenti-match-strutturato.md} (79 uscite reali di luglio
 * 2026 × 9 piani ricostruiti da 6 mesi di estratti conto):
 *
 * <ul>
 *   <li><b>Quale piano</b> lo decide un segnale <b>lessicale</b> — token del nome del piano
 *       (≥4 caratteri) o il riferimento in estratto conto — più il conto bancario. Misurato:
 *       12 proposte, 12 corrette, 0 falsi positivi.</li>
 *   <li><b>La data NON decide il piano</b>: da sola produce 150 falsi positivi su 177 proposte e
 *       come filtro non ne toglie nessuno, perdendo invece un vero (Enel del 13/07, a 12 giorni
 *       dalla scadenza). Serve solo a scegliere <b>quale rata</b>, con finestra ±7 giorni
 *       (scarto massimo misurato su 41 addebiti reali: +4 giorni).</li>
 *   <li><b>L'importo</b> non è un segnale primario (i flussi variabili arrivano a 1,86×): fa solo
 *       da <b>spareggio</b> quando i candidati sono più d'uno.</li>
 * </ul>
 *
 * <p>Il matcher non decide mai da solo su un movimento di denaro: produce una proposta (o niente)
 * che l'operatore conferma dallo smistamento.
 */
public final class RataMatcher {

    /** Finestra addebito↔scadenza per proporre una rata. Vedi javadoc di classe. */
    public static final int GIORNI_FINESTRA_RATA = 7;
    /** Sotto questa soglia ci sono solo le commissioni SDD (1,00 €), che ripetono il mandato. */
    public static final BigDecimal IMPORTO_MINIMO = new BigDecimal("2.00");
    /** Tolleranza dello spareggio d'importo fra più candidati. */
    private static final BigDecimal TOLLERANZA_SPAREGGIO = new BigDecimal("0.02");
    private static final int LEN_TOKEN_MIN = 4;

    /**
     * Parole troppo generiche per identificare un piano: compaiono in mezzo estratto conto.
     * Senza questo filtro un piano chiamato "Rata mutuo" matcherebbe ogni riga con "MUTUO".
     */
    private static final Set<String> STOP = Set.of(
            "RATA", "RATE", "MUTUO", "CANONE", "LEASING", "POLIZZA", "PREMIO", "BOLLO",
            "FINANZIAMENTO", "PAGAMENTO", "ADDEBITO", "DIRETTO", "SEPA", "DEBIT", "CORE",
            "SPA", "SRL", "SNC", "SAS", "SOCIETA", "AGRICOLA", "AGOSTINELLI", "ITALIA",
            "ITALIANA", "DELLA", "DELLO", "DEGLI", "DELLE", "MENSILE", "ANNUALE", "PERIODO",
            "FATTURA", "DOCUM", "SALDO", "IMPOSTA", "CONTO", "BANCA", "MESE", "ANNO");

    /** Piano attivo confrontabile con una riga bancaria. */
    public record Piano(
            UUID id,
            String descrizione,
            String riferimentoEstrattoConto,
            Short contoBancarioId,
            BigDecimal importoRata,
            List<Rata> ratePending) {}

    /** Rata ancora da pagare (le PAID/CANCELLED/SKIPPED non sono mai candidate). */
    public record Rata(UUID id, int numeroRata, LocalDate dataScadenza, BigDecimal importo) {}

    /** Una rata compatibile, con il perché in chiaro (va mostrato in UI). */
    public record Candidato(
            UUID pianoId,
            String pianoDescrizione,
            UUID rataId,
            int numeroRata,
            LocalDate dataScadenza,
            BigDecimal importoRata,
            long scartoGiorni,
            BigDecimal scartoImporto,
            String motivo) {}

    /** Esito: la proposta a un click (null se assente/ambigua) e tutti i candidati da mostrare. */
    public record Esito(Candidato proposta, List<Candidato> candidati) {
        public boolean vuoto() { return candidati.isEmpty(); }
    }

    private RataMatcher() {}

    /**
     * True se la riga somiglia alla rata di almeno uno dei piani: è la decisione di
     * <b>routing</b> dell'import (parcheggia invece di contabilizzare). Volutamente più larga
     * della proposta: non richiede che esista una rata dentro la finestra, perché una riga
     * riconosciuta come rata non deve MAI finire in contabilità automatica anche quando la
     * rata giusta non si sa quale sia.
     */
    public static boolean somigliaARata(Short contoBancarioId, BigDecimal importo, String descrizione,
                                        List<Piano> piani) {
        for (Piano p : piani) {
            if (pianoCompatibile(p, contoBancarioId, importo, descrizione) != null) return true;
        }
        return false;
    }

    /** Candidati + proposta a un click per una riga già parcheggiata. */
    public static Esito valuta(Short contoBancarioId, BigDecimal importo, LocalDate dataAddebito,
                               String descrizione, List<Piano> piani) {
        List<Candidato> candidati = new ArrayList<>();
        for (Piano p : piani) {
            String motivo = pianoCompatibile(p, contoBancarioId, importo, descrizione);
            if (motivo == null) continue;
            for (Rata r : p.ratePending()) {
                long giorni = dataAddebito == null || r.dataScadenza() == null
                        ? Long.MAX_VALUE
                        : Math.abs(ChronoUnit.DAYS.between(dataAddebito, r.dataScadenza()));
                if (giorni > GIORNI_FINESTRA_RATA) continue;
                candidati.add(new Candidato(p.id(), p.descrizione(), r.id(), r.numeroRata(),
                        r.dataScadenza(), r.importo(), giorni,
                        importo.subtract(r.importo()), motivo));
            }
        }
        // ordine: prima l'importo più vicino, poi la scadenza più vicina — è anche l'ordine in cui
        // la UI li mostra, dal più probabile.
        candidati.sort(Comparator
                .comparing((Candidato c) -> c.scartoImporto().abs())
                .thenComparingLong(Candidato::scartoGiorni));

        return new Esito(proposta(candidati, importo), List.copyOf(candidati));
    }

    /**
     * Un solo candidato → è la proposta. Più candidati → si propone solo se ESATTAMENTE uno ha
     * l'importo entro il 2% dell'addebito (misurato: separa i due piani Confidi reali, stesso
     * conto e stesso giorno, indistinguibili per nome). Altrimenti decide l'operatore.
     */
    private static Candidato proposta(List<Candidato> candidati, BigDecimal importo) {
        if (candidati.size() == 1) return candidati.get(0);
        Candidato unico = null;
        for (Candidato c : candidati) {
            if (c.scartoImporto().abs().compareTo(c.importoRata().multiply(TOLLERANZA_SPAREGGIO)) > 0) continue;
            if (unico != null) return null; // due candidati altrettanto plausibili: non si indovina
            unico = c;
        }
        return unico;
    }

    /**
     * AUDIT-TEMP — spiega, piano per piano, perché ha agganciato o no. Non è usata dalla logica:
     * serve solo all'audit log su file. Cancellabile insieme a {@link ImportAuditLog}.
     */
    public static List<String> diagnostica(Short contoBancarioId, BigDecimal importo,
                                           String descrizione, List<Piano> piani) {
        List<String> out = new ArrayList<>();
        if (piani.isEmpty()) {
            out.add("nessun piano ATTIVO in archivio: il match strutturato non ha nulla da confrontare");
            return out;
        }
        for (Piano p : piani) {
            String motivo = pianoCompatibile(p, contoBancarioId, importo, descrizione);
            if (motivo != null) {
                out.add("piano «" + p.descrizione() + "» → AGGANCIA: " + motivo);
            } else if (p.contoBancarioId() != null && !p.contoBancarioId().equals(contoBancarioId)) {
                out.add("piano «" + p.descrizione() + "» → no: è sul conto " + p.contoBancarioId()
                        + ", la riga sul conto " + contoBancarioId);
            } else if (importo != null && importo.compareTo(IMPORTO_MINIMO) < 0) {
                out.add("piano «" + p.descrizione() + "» → no: importo " + importo
                        + " sotto la soglia di " + IMPORTO_MINIMO + " (commissioni SDD)");
            } else {
                out.add("piano «" + p.descrizione() + "» → no: né il riferimento «"
                        + (p.riferimentoEstrattoConto() == null ? "—" : p.riferimentoEstrattoConto())
                        + "» né i token del nome " + tokens(p.descrizione())
                        + " compaiono nella descrizione");
            }
        }
        return out;
    }

    /** Motivo del match piano↔riga, o null se il piano non c'entra. */
    private static String pianoCompatibile(Piano p, Short conto, BigDecimal importo, String descrizione) {
        if (conto == null || p.contoBancarioId() == null || !p.contoBancarioId().equals(conto)) return null;
        if (importo == null || importo.compareTo(IMPORTO_MINIMO) < 0) return null;
        String desc = descrizione == null ? "" : descrizione.toUpperCase(Locale.ROOT);
        if (desc.isBlank()) return null;

        String rif = p.riferimentoEstrattoConto();
        if (rif != null && !rif.isBlank() && desc.contains(rif.trim().toUpperCase(Locale.ROOT))) {
            return "Riferimento in estratto conto: " + rif.trim();
        }
        for (String t : tokens(p.descrizione())) {
            if (contieneParola(desc, t)) return "Nome del piano riconosciuto: " + t;
        }
        return null;
    }

    /** Token identificativi del nome del piano: ≥4 caratteri, generici esclusi. */
    static List<String> tokens(String nome) {
        List<String> out = new ArrayList<>();
        if (nome == null) return out;
        for (String t : nome.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (t.length() >= LEN_TOKEN_MIN && !STOP.contains(t) && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Match su parola intera: "ENEL" non deve accendersi dentro "CARBURANTE ENELGAS…".
     * ponytail: scansione lineare, la descrizione è una riga di estratto conto.
     */
    private static boolean contieneParola(String desc, String token) {
        int i = desc.indexOf(token);
        while (i >= 0) {
            boolean primaOk = i == 0 || !Character.isLetterOrDigit(desc.charAt(i - 1));
            int fine = i + token.length();
            boolean dopoOk = fine >= desc.length() || !Character.isLetterOrDigit(desc.charAt(fine));
            if (primaOk && dopoOk) return true;
            i = desc.indexOf(token, i + 1);
        }
        return false;
    }
}
