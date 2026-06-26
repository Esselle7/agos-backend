package com.agostinelli.gestionale.movimenti.importlayer.keyword;

import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Estrattore di keyword dalla descrizione di un movimento (PROMPT-KEYWORD-LEARNING.md §4.2).
 * <b>Funzione pura</b>, senza DB e senza stato: stopword e dizionario di dominio sono passati
 * come strutture in memoria (caricate a monte dall'engine). Niente NLP: la classificazione è
 * stopword-based + euristiche posizionali sul "segmento nome controparte".
 *
 * <p>Perché qui e non nel mapping engine: l'estrazione è il cuore dello "spazio delle keyword"
 * (anti-collisione) e deve essere testabile in isolamento sui casi reali. È usata sia in
 * apprendimento (genera le firme IDENTITÀ da persistere) sia in match (tokenizza la riga).
 *
 * <p>Nature dei token:
 * <ul>
 *   <li><b>CODICE</b> (forte): len ≥ {@value #LEN_CODICE} con cifre (mandato SDD, IBAN, PO Stripe).
 *       I codici volatili preceduti da RIF/CRO ({@code rif. mbvt…}) sono scartati come rumore.</li>
 *   <li><b>DOMINIO</b>: token presente nel dizionario di dominio.</li>
 *   <li><b>IDENTITÀ</b> (forte): parola del segmento "nome controparte" (ordinante / beneficiario /
 *       testo dopo FAVORE / nome del creditore dopo il mandato SDD).</li>
 *   <li><b>NORMALE</b> (debole): altra parola alfabetica ≥ {@value #LEN_MIN}, non in un nome.</li>
 * </ul>
 *
 * <p>Regola di validità della firma (§3A): valida se ha ≥2 token significativi OPPURE ≥1 token
 * forte (IDENTITÀ/CODICE/DOMINIO). Una sola parola NORMALE non è una firma.
 */
public final class KeywordExtractor {

    private KeywordExtractor() {}

    /** Lunghezza minima di una parola significativa. */
    public static final int LEN_MIN = 3;
    /** Lunghezza minima perché un token alfanumerico con cifre sia un CODICE forte. */
    public static final int LEN_CODICE = 10;

    /** Token che, se precedono un codice, lo marcano come riferimento volatile (rumore). */
    private static final Set<String> RIF_CONTEXT = Set.of("RIF", "CRO", "RIFERIMENTO", "CRO:");

    public enum TipoToken { IDENTITA, CODICE, DOMINIO, NORMALE }

    /** Natura della firma: IDENTITÀ se contiene ≥1 token IDENTITÀ/CODICE, altrimenti DOMINIO. */
    public enum Natura { IDENTITA, DOMINIO }

    public record TokenTipizzato(String token, TipoToken tipo) {}

    /** Firma candidata: insieme di token (match in AND) + natura derivata dai token. */
    public record FirmaCandidata(Set<TokenTipizzato> token, Natura natura) {
        public Set<String> valori() {
            Set<String> s = new LinkedHashSet<>();
            for (TokenTipizzato t : token) s.add(t.token());
            return s;
        }
    }

    // ── Tokenizzazione per il MATCH (bag di token significativi presenti nella riga) ──────

    /**
     * Insieme dei token significativi della descrizione (parole ≥{@value #LEN_MIN} non-stopword
     * + codici non volatili), UPPERCASE. È la base su cui l'engine verifica se i token di una
     * firma sono tutti presenti (match in AND).
     */
    public static Set<String> tokenizza(String descrizione, Set<String> stopwords) {
        Set<String> out = new LinkedHashSet<>();
        for (Lessema l : lex(descrizione, stopwords)) out.add(l.token);
        return out;
    }

    // ── Estrazione delle FIRME per l'APPRENDIMENTO ────────────────────────────────────────

    /**
     * Estrae le firme candidate da imparare dalla descrizione catalogata a mano.
     * Emette: una firma per ogni CODICE; una firma IDENTITÀ per ogni run contiguo di parole-nome.
     * I token DOMINIO NON vengono appresi (li possiede il dizionario seed). I NORMALE da soli
     * non fanno firma. Il risultato è già filtrato per validità (§3A).
     */
    public static List<FirmaCandidata> estraiFirme(String descrizione, EntitaEstratte entita,
                                                   Set<String> stopwords, Set<String> domainTokens) {
        List<TokenTipizzato> tipizzati = classifica(descrizione, entita, stopwords, domainTokens);
        List<FirmaCandidata> firme = new ArrayList<>();

        // 1) ogni CODICE → firma a sé (identità forte).
        for (TokenTipizzato t : tipizzati) {
            if (t.tipo() == TipoToken.CODICE) {
                firme.add(new FirmaCandidata(new LinkedHashSet<>(List.of(t)), Natura.IDENTITA));
            }
        }
        // 2) run contigui di token IDENTITÀ → una firma-nome ciascuno.
        Set<TokenTipizzato> run = new LinkedHashSet<>();
        for (TokenTipizzato t : tipizzati) {
            if (t.tipo() == TipoToken.IDENTITA) {
                run.add(t);
            } else if (!run.isEmpty()) {
                firme.add(new FirmaCandidata(run, Natura.IDENTITA));
                run = new LinkedHashSet<>();
            }
        }
        if (!run.isEmpty()) firme.add(new FirmaCandidata(run, Natura.IDENTITA));

        firme.removeIf(f -> !valida(f));
        return firme;
    }

    /**
     * Classifica in ordine i token significativi della descrizione (per i test e l'apprendimento).
     * Determina prima il "segmento nome controparte" (euristiche §4.2) per distinguere IDENTITÀ
     * (parola in un nome) da NORMALE (parola generica).
     */
    public static List<TokenTipizzato> classifica(String descrizione, EntitaEstratte entita,
                                                  Set<String> stopwords, Set<String> domainTokens) {
        List<Lessema> lessemi = lex(descrizione, stopwords);
        Set<String> nomi = segmentoNomi(descrizione, entita, lessemi, stopwords);

        List<TokenTipizzato> out = new ArrayList<>(lessemi.size());
        for (Lessema l : lessemi) {
            TipoToken tipo;
            if (l.codice) {
                tipo = TipoToken.CODICE;
            } else if (domainTokens != null && domainTokens.contains(l.token)) {
                tipo = TipoToken.DOMINIO;
            } else if (nomi.contains(l.token)) {
                tipo = TipoToken.IDENTITA;
            } else {
                tipo = TipoToken.NORMALE;
            }
            out.add(new TokenTipizzato(l.token, tipo));
        }
        return out;
    }

    // ── Hash di firma (dedup) — DEVE coincidere col seed SQL: sha256 hex dei token ─────────
    //    UPPERCASE ordinati e uniti con '|'.

    /** signature_hash = sha256 hex dei token UPPERCASE ordinati e uniti da '|'. */
    public static String signatureHash(Collection<String> tokens) {
        TreeSet<String> ordinati = new TreeSet<>();
        for (String t : tokens) ordinati.add(t.toUpperCase());
        String joined = String.join("|", ordinati);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponibile", e);
        }
    }

    // ── interno ────────────────────────────────────────────────────────────────────────

    private record Lessema(String token, boolean codice) {}

    /**
     * Tokenizza UPPERCASE su non-alfanumerico, scarta stopword e parole corte/solo-cifre,
     * classifica i CODICE e scarta i codici volatili preceduti da RIF/CRO.
     */
    private static List<Lessema> lex(String descrizione, Set<String> stopwords) {
        List<Lessema> out = new ArrayList<>();
        if (descrizione == null || descrizione.isBlank()) return out;
        String[] parole = descrizione.toUpperCase().replaceAll("[^A-Z0-9]", " ").trim().split("\\s+");
        String prec = null;
        for (String w : parole) {
            if (w.isEmpty()) continue;
            boolean stop = stopwords != null && stopwords.contains(w);
            if (isCodice(w)) {
                // codice volatile (rif. mbvt…/cro …) → rumore, scartato.
                if (prec == null || !RIF_CONTEXT.contains(prec)) out.add(new Lessema(w, true));
                prec = w;
                continue;
            }
            if (stop) { prec = w; continue; }
            if (w.length() >= LEN_MIN && hasLetter(w)) out.add(new Lessema(w, false));
            prec = w;
        }
        return out;
    }

    private static boolean isCodice(String w) {
        return w.length() >= LEN_CODICE && hasDigit(w);
    }

    /**
     * Token-set del "segmento nome controparte", con fallback in cascata (§4.2): beneficiario →
     * ordinante → testo dopo FAVORE → nome del creditore dopo il mandato SDD (token dopo un CODICE).
     */
    private static Set<String> segmentoNomi(String descrizione, EntitaEstratte entita,
                                            List<Lessema> lessemi, Set<String> stopwords) {
        String segmento = null;
        if (entita != null) {
            segmento = entita.beneficiario() != null ? entita.beneficiario() : entita.ordinante();
        }
        Set<String> nomi = new LinkedHashSet<>();
        if (segmento != null) {
            for (Lessema l : lex(segmento, stopwords)) if (!l.codice) nomi.add(l.token);
            if (!nomi.isEmpty()) return nomi;
        }
        // fallback: testo dopo FAVORE
        String up = descrizione == null ? "" : descrizione.toUpperCase();
        int favore = up.indexOf("FAVORE");
        if (favore >= 0) {
            for (Lessema l : lex(up.substring(favore + 6), stopwords)) if (!l.codice) nomi.add(l.token);
            if (!nomi.isEmpty()) return nomi;
        }
        // fallback: il creditore SDD segue il mandato → parole dopo il primo CODICE
        boolean dopoCodice = false;
        for (Lessema l : lessemi) {
            if (l.codice) { dopoCodice = true; continue; }
            if (dopoCodice) nomi.add(l.token);
        }
        return nomi;
    }

    private static boolean valida(FirmaCandidata f) {
        int significativi = f.token().size();
        boolean forte = f.token().stream().anyMatch(t ->
                t.tipo() == TipoToken.IDENTITA || t.tipo() == TipoToken.CODICE || t.tipo() == TipoToken.DOMINIO);
        return significativi >= 2 || forte;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return true;
        return false;
    }
}
