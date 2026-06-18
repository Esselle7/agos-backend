package com.agostinelli.gestionale.movimenti.importlayer.keyword;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Motore di classificazione a KEYWORD in import (PROMPT-KEYWORD-LEARNING.md §4.3). Stesso
 * pattern di {@link com.agostinelli.gestionale.movimenti.importlayer.RegoleClassificazioneEngine}:
 * carica tutto in memoria con {@link #refresh()} e usa un <b>indice invertito</b> token→firme,
 * così il match è O(k) sui token della riga e mai O(n²) sulle firme.
 *
 * <p>Tiene due ruoli, alimentati dalla stessa tabella {@code keyword_firma}:
 * <ul>
 *   <li>firme <b>BOOK</b> (azione=BOOK): match in AND → target {@code (coge, bu, fornitore)} per
 *       contabilizzare la riga (consultato in {@code classifyEntrata/Uscita});</li>
 *   <li>firme <b>PARK_EVENTO</b> (azione=PARK_EVENTO): forniscono le liste keyword evento
 *       (forte/debole), prima hardcoded nel mapping engine, ora editabili da DB. NON
 *       contabilizzano: la riga viene parcheggiata dal Gate B (nessun movimento).</li>
 * </ul>
 *
 * <p>Precedenza (§3.2): tra le firme BOOK che matchano, vincono le IDENTITÀ sulle DOMINIO; nello
 * stesso gruppo, target unico → auto-catalogazione (vince la più specifica = più token), target
 * divergenti → <b>conflitto di MATCH</b> (nessuna catalogazione cieca; la riga resta sul
 * transitorio e l'orchestratore registra il conflitto).
 */
@ApplicationScoped
public class KeywordClassificazioneEngine {

    @Inject EntityManager em;

    private volatile boolean loaded = false;

    /** Firma BOOK in memoria (package-private per i test del risolutore puro). */
    record Firma(UUID id, KeywordExtractor.Natura natura, String tipoMovimento, String sorgente,
                 Short bu, String cogeCodice, UUID fornitoreId, String signatureHash, Set<String> token) {}

    /** Vista del target appreso per la riga (o conflitto da risolvere). */
    public record KeywordMatch(boolean conflitto, String cogeCodice, Short bu, UUID fornitoreId,
                               UUID firmaId, KeywordExtractor.Natura natura, String signatureHash) {
        static KeywordMatch target(Firma f) {
            return new KeywordMatch(false, f.cogeCodice(), f.bu(), f.fornitoreId(), f.id(), f.natura(), f.signatureHash());
        }
        static KeywordMatch inConflitto(String signatureHash) {
            return new KeywordMatch(true, null, null, null, null, null, signatureHash);
        }
    }

    private final List<Firma> bookFirme = new ArrayList<>();
    private final Map<String, List<Firma>> indiceInvertito = new HashMap<>(); // token → firme BOOK
    private final Set<String> stopwords = new HashSet<>();
    private final Set<String> domainTokens = new HashSet<>();
    private final Set<String> eventiForti = new HashSet<>();
    private final Set<String> eventiDeboli = new HashSet<>();

    public synchronized void refresh() {
        bookFirme.clear();
        indiceInvertito.clear();
        stopwords.clear();
        domainTokens.clear();
        eventiForti.clear();
        eventiDeboli.clear();
        load();
        loaded = true;
    }

    private synchronized void ensureLoaded() {
        if (!loaded) { load(); loaded = true; }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        for (Object r : em.createNativeQuery("SELECT token FROM keyword_stopword").getResultList()) {
            stopwords.add(((String) r).toUpperCase());
        }
        // Firme BOOK ATTIVE → indice invertito.
        Map<UUID, Firma> byId = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT id, natura, tipo_movimento, sorgente, bu_id, coge_codice, fornitore_id, signature_hash " +
                "FROM keyword_firma WHERE stato = 'ATTIVA' AND azione = 'BOOK'").getResultList()) {
            UUID id = toUuid(r[0]);
            byId.put(id, new Firma(id,
                    KeywordExtractor.Natura.valueOf((String) r[1]),
                    (String) r[2], (String) r[3],
                    r[4] == null ? null : ((Number) r[4]).shortValue(),
                    (String) r[5],
                    r[6] == null ? null : toUuid(r[6]),
                    (String) r[7], new HashSet<>()));
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT t.firma_id, t.token FROM keyword_token t " +
                "JOIN keyword_firma f ON f.id = t.firma_id WHERE f.stato = 'ATTIVA' AND f.azione = 'BOOK'")
                .getResultList()) {
            Firma f = byId.get(toUuid(r[0]));
            if (f != null) f.token().add(((String) r[1]).toUpperCase());
        }
        for (Firma f : byId.values()) {
            if (f.token().isEmpty()) continue; // firma senza token: ignorata (difensivo)
            bookFirme.add(f);
            for (String tk : f.token()) indiceInvertito.computeIfAbsent(tk, k -> new ArrayList<>()).add(f);
        }
        // Liste evento (PARK_EVENTO) per il Gate B + dizionario di dominio per l'estrattore.
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT t.token, f.evento_forza FROM keyword_token t " +
                "JOIN keyword_firma f ON f.id = t.firma_id " +
                "WHERE f.stato = 'ATTIVA' AND f.azione = 'PARK_EVENTO'").getResultList()) {
            String tk = ((String) r[0]).toUpperCase();
            if ("DEBOLE".equals(r[1])) eventiDeboli.add(tk); else eventiForti.add(tk);
        }
        for (Object r : em.createNativeQuery(
                "SELECT t.token FROM keyword_token t JOIN keyword_firma f ON f.id = t.firma_id " +
                "WHERE f.stato = 'ATTIVA' AND f.natura = 'DOMINIO'").getResultList()) {
            domainTokens.add(((String) r).toUpperCase());
        }
    }

    /**
     * Cerca una firma BOOK che catalogi la riga. {@code Optional.empty()} se nessuna firma matcha;
     * {@link KeywordMatch#conflitto()} true se più firme della stessa natura puntano a target
     * diversi (la riga va lasciata sul transitorio e va aperto un conflitto di MATCH).
     */
    public java.util.Optional<KeywordMatch> classifica(RawMovimento n, String sorgente) {
        ensureLoaded();
        if (n.descrizione() == null) return java.util.Optional.empty();
        Set<String> tokenRiga = KeywordExtractor.tokenizza(n.descrizione(), stopwords);
        if (tokenRiga.isEmpty()) return java.util.Optional.empty();

        // Candidate via indice invertito, deduplicate per identità.
        Set<Firma> candidate = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (String tk : tokenRiga) {
            List<Firma> l = indiceInvertito.get(tk);
            if (l != null) candidate.addAll(l);
        }
        return risolvi(tokenRiga, candidate, n.tipo(), sorgente);
    }

    /**
     * Risolutore puro (testabile senza DB): dato l'insieme dei token della riga e le firme
     * candidate, applica scope + match in AND + precedenza IDENTITÀ&gt;DOMINIO e ritorna il
     * target unico, un conflitto, oppure vuoto.
     */
    static java.util.Optional<KeywordMatch> risolvi(Set<String> tokenRiga, Iterable<Firma> candidate,
                                                    String tipo, String sorgente) {
        List<Firma> match = new ArrayList<>();
        for (Firma f : candidate) {
            if (!scopeOk(f.tipoMovimento(), tipo) || !scopeOk(f.sorgente(), sorgente)) continue;
            if (tokenRiga.containsAll(f.token())) match.add(f);
        }
        if (match.isEmpty()) return java.util.Optional.empty();

        // Precedenza: IDENTITÀ > DOMINIO.
        boolean hasIdentita = match.stream().anyMatch(f -> f.natura() == KeywordExtractor.Natura.IDENTITA);
        KeywordExtractor.Natura naturaVincente = hasIdentita
                ? KeywordExtractor.Natura.IDENTITA : KeywordExtractor.Natura.DOMINIO;

        // Target unico? (vince la più specifica = più token); target divergenti → conflitto.
        Firma vincente = null;
        for (Firma f : match) {
            if (f.natura() != naturaVincente) continue;
            if (vincente == null) { vincente = f; continue; }
            if (!stessoTarget(vincente, f)) {
                return java.util.Optional.of(KeywordMatch.inConflitto(f.signatureHash()));
            }
            if (f.token().size() > vincente.token().size()) vincente = f; // più specifica
        }
        return java.util.Optional.of(KeywordMatch.target(vincente));
    }

    // ── liste per il Gate B + dizionario per l'estrattore ───────────────────────────────
    public Set<String> eventiForti()  { ensureLoaded(); return eventiForti; }
    public Set<String> eventiDeboli() { ensureLoaded(); return eventiDeboli; }
    public Set<String> stopwords()    { ensureLoaded(); return stopwords; }
    public Set<String> domainTokens() { ensureLoaded(); return domainTokens; }

    private static boolean scopeOk(String scope, String valore) {
        return "*".equals(scope) || scope == null || scope.equals(valore);
    }

    private static boolean stessoTarget(Firma a, Firma b) {
        return Objects.equals(a.cogeCodice(), b.cogeCodice())
                && Objects.equals(a.bu(), b.bu())
                && Objects.equals(a.fornitoreId(), b.fornitoreId());
    }

    private UUID toUuid(Object o) {
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }
}
