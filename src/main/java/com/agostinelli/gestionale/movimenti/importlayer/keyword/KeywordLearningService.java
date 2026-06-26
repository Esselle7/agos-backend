package com.agostinelli.gestionale.movimenti.importlayer.keyword;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.ApprendimentoKeywordEsito;
import com.agostinelli.gestionale.movimenti.dto.KeywordAnteprimaDTO;
import com.agostinelli.gestionale.movimenti.dto.KeywordConflittoDTO;
import com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviConflittoKeywordRequest;
import com.agostinelli.gestionale.movimenti.importlayer.DescNormalizer;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.FirmaCandidata;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Apprendimento e gestione delle keyword (PROMPT-KEYWORD-LEARNING.md §4.4/§4.5 + CRUD §4.8).
 * Unico meccanismo di auto-apprendimento (l'IBAN/`controparti` è dismesso): quando l'utente
 * cataloga a mano una riga, estrae le firme IDENTITÀ dalla descrizione e le salva legate al
 * target {@code (fornitore, coge, bu)} scelto, così i prossimi import catalogano da soli.
 *
 * <p>Conflitti a STEP (§5A): mai sovrascrivere ciecamente. Se per lo stesso scope esiste già
 * una firma con target diverso, la firma esistente passa {@code IN_CONFLITTO}, si apre un
 * {@code keyword_conflitto} e si avvisa l'utente; la risoluzione è un'azione dedicata.
 */
@ApplicationScoped
public class KeywordLearningService {

    @Inject EntityManager em;
    @Inject KeywordClassificazioneEngine engine;

    // ── APPRENDIMENTO (hook del triage) ─────────────────────────────────────────────────

    /**
     * Apprende le firme IDENTITÀ dalla descrizione catalogata. {@code sorgente='*'} (la stessa
     * controparte ricorre su qualsiasi banca); scope per {@code tipoMovimento}. COGE per codice.
     * Ritorna l'esito (firme create/aggiornate + conflitti aperti). Chiama {@code engine.refresh()}.
     */
    @Transactional
    public ApprendimentoKeywordEsito apprendi(String descrizione, EntitaEstratte entita,
                                              String tipoMovimento, Short bu, Integer cogeId,
                                              UUID fornitoreId, UUID movimentoId, UUID userId) {
        String coge = cogeCodice(cogeId);
        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(
                descrizione, entita, engine.stopwords(), engine.domainTokens());

        int create = 0, aggiornate = 0;
        List<UUID> conflitti = new ArrayList<>();
        for (FirmaCandidata fc : firme) {
            List<String> tokens = new ArrayList<>(fc.valori());
            String sig = KeywordExtractor.signatureHash(tokens);

            Object[] esistente = trovaFirma(sig, tipoMovimento, "*");
            if (esistente == null) {
                UUID firmaId = inserisciFirma("IDENTITA", "BOOK", tipoMovimento, "*", bu, coge,
                        fornitoreId, "APPRESA", sig, descrizione, movimentoId, userId);
                inserisciToken(firmaId, tokens, "IDENTITA");
                create++;
            } else {
                UUID firmaId = (UUID) esistente[0];
                Short buEx = esistente[1] == null ? null : ((Number) esistente[1]).shortValue();
                String cogeEx = (String) esistente[2];
                UUID fornEx = esistente[3] == null ? null : (UUID) esistente[3];
                if (sameTarget(buEx, cogeEx, fornEx, bu, coge, fornitoreId)) {
                    em.createNativeQuery("UPDATE keyword_firma SET updated_at = now() WHERE id = :id")
                            .setParameter("id", firmaId).executeUpdate();
                    aggiornate++;
                } else {
                    em.createNativeQuery("UPDATE keyword_firma SET stato = 'IN_CONFLITTO', updated_at = now() WHERE id = :id")
                            .setParameter("id", firmaId).executeUpdate();
                    UUID confId = apriConflitto("APPRENDIMENTO", sig, firmaId, movimentoId,
                            targetJson(buEx, cogeEx, fornEx), targetJson(bu, coge, fornitoreId),
                            "Firma '" + String.join(" ", tokens) + "' già associata a un target diverso");
                    conflitti.add(confId);
                }
            }
        }
        // Divergenza di DOMINIO: la BU scelta diverge dal dizionario per un token DOMINIO seed (§4.4).
        conflitti.addAll(conflittiDominio(descrizione, tipoMovimento, bu, coge, movimentoId));

        engine.refresh();
        return new ApprendimentoKeywordEsito(create, aggiornate, conflitti);
    }

    /**
     * Anteprima (read-only) delle firme che verrebbero apprese da una descrizione: usata dal
     * triage/ambiguità per mostrare all'utente "queste keyword imparerò" PRIMA di salvare.
     * Riusa lo stesso estrattore puro dell'apprendimento (nessuna duplicazione di logica).
     */
    @Transactional
    public KeywordAnteprimaDTO anteprima(String descrizione, String sorgente) {
        if (descrizione == null || descrizione.isBlank()) return new KeywordAnteprimaDTO(List.of());
        String src = sorgente == null || sorgente.isBlank() ? Sorgente.CA : sorgente;
        EntitaEstratte ent = DescNormalizer.extract(descrizione, src);
        List<KeywordAnteprimaDTO.Firma> out = new ArrayList<>();
        for (FirmaCandidata f : KeywordExtractor.estraiFirme(descrizione, ent, engine.stopwords(), engine.domainTokens())) {
            out.add(new KeywordAnteprimaDTO.Firma(new ArrayList<>(f.valori()), f.natura().name()));
        }
        return new KeywordAnteprimaDTO(out);
    }

    /** Conflitto di MATCH in import: chiamato dall'orchestratore quando una riga matcha 2 firme. */
    @Transactional
    public UUID registraConflittoMatch(String signatureHash, UUID movimentoId, String descrizione) {
        return apriConflitto("MATCH", signatureHash, null, movimentoId, null, null, descrizione);
    }

    /** Apre i conflitti DOMINIO: token DOMINIO seed presente ma BU scelta diversa da quella del dizionario. */
    @SuppressWarnings("unchecked")
    private List<UUID> conflittiDominio(String descrizione, String tipoMovimento, Short bu, String coge, UUID movimentoId) {
        List<UUID> out = new ArrayList<>();
        var token = KeywordExtractor.tokenizza(descrizione, engine.stopwords());
        if (token.isEmpty() || bu == null) return out;
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT f.id, f.bu_id, f.coge_codice FROM keyword_firma f JOIN keyword_token t ON t.firma_id = f.id " +
                "WHERE f.natura = 'DOMINIO' AND f.azione = 'BOOK' AND f.stato = 'ATTIVA' " +
                "AND (f.tipo_movimento = '*' OR f.tipo_movimento = :tm) AND t.token IN (:tok)")
                .setParameter("tm", tipoMovimento)
                .setParameter("tok", new ArrayList<>(token))
                .getResultList()) {
            Short buDom = r[1] == null ? null : ((Number) r[1]).shortValue();
            if (buDom != null && !buDom.equals(bu)) {
                UUID confId = apriConflitto("APPRENDIMENTO", null, (UUID) r[0], movimentoId,
                        targetJson(buDom, (String) r[2], null), targetJson(bu, coge, null),
                        "BU scelta diversa dal dizionario di dominio");
                out.add(confId);
            }
        }
        return out;
    }

    // ── CRUD firme (pagina dedicata §4.8) ─────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<KeywordFirmaDTO> listFirme(String natura, String stato) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, natura, azione, tipo_movimento, sorgente, bu_id, coge_codice, fornitore_id, " +
                "evento_forza, tipo_evento, confidence, origine, stato, note, created_at " +
                "FROM keyword_firma " +
                "WHERE (CAST(:natura AS VARCHAR) IS NULL OR natura = :natura) " +
                "AND (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato) " +
                "ORDER BY natura, azione, coge_codice NULLS FIRST, created_at")
                .setParameter("natura", natura)
                .setParameter("stato", stato)
                .getResultList();
        if (rows.isEmpty()) return List.of();
        // Token in UNA query per tutte le firme (era N+1: una SELECT per ogni riga).
        Map<UUID, List<String>> tokenByFirma = tokenDelleFirme(
                rows.stream().map(r -> (UUID) r[0]).toList());
        List<KeywordFirmaDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UUID id = (UUID) r[0];
            out.add(new KeywordFirmaDTO(id, (String) r[1], (String) r[2], (String) r[3], (String) r[4],
                    r[5] == null ? null : ((Number) r[5]).shortValue(), (String) r[6],
                    r[7] == null ? null : (UUID) r[7], (String) r[8], (String) r[9],
                    (BigDecimal) r[10], (String) r[11], (String) r[12], (String) r[13],
                    tokenByFirma.getOrDefault(id, List.of()), r[14] == null ? null : r[14].toString()));
        }
        return out;
    }

    @Transactional
    public UUID createFirma(KeywordFirmaDTO d) {
        if (d.token() == null || d.token().isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "FIRMA_SENZA_TOKEN", "Una firma deve avere almeno un token");
        }
        boolean park = "PARK_EVENTO".equals(d.azione());
        if (!park && (d.cogeCodice() == null || d.buId() == null)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "FIRMA_BOOK_INCOMPLETA", "Una firma BOOK richiede COGE e BU");
        }
        if (d.fornitoreId() != null && !"IDENTITA".equals(d.natura())) {
            throw new ApiException(Response.Status.BAD_REQUEST, "FORNITORE_SOLO_IDENTITA", "Il fornitore è ammesso solo per firme IDENTITA");
        }
        List<String> tokens = upper(d.token());
        String sig = KeywordExtractor.signatureHash(tokens);
        String tm = d.tipoMovimento() == null ? "*" : d.tipoMovimento();
        String src = d.sorgente() == null ? "*" : d.sorgente();
        if (trovaFirma(sig, tm, src) != null) {
            throw new ApiException(Response.Status.CONFLICT, "FIRMA_DUPLICATA",
                    "Esiste già una firma con gli stessi token nello stesso scope");
        }
        UUID id = (UUID) em.createNativeQuery(
                "INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice, " +
                "fornitore_id, evento_forza, tipo_evento, origine, stato, signature_hash, note) " +
                "VALUES (:nat, :az, :tm, :src, CAST(:bu AS smallint), :coge, CAST(:forn AS uuid), :forza, :tipoEv, " +
                "'MANUALE', 'ATTIVA', :sig, :note) RETURNING id")
                .setParameter("nat", d.natura()).setParameter("az", d.azione())
                .setParameter("tm", tm).setParameter("src", src)
                .setParameter("bu", d.buId()).setParameter("coge", d.cogeCodice())
                .setParameter("forn", d.fornitoreId()).setParameter("forza", d.eventoForza())
                .setParameter("tipoEv", d.tipoEvento()).setParameter("sig", sig)
                .setParameter("note", d.note())
                .getSingleResult();
        inserisciToken(id, tokens, "IDENTITA".equals(d.natura()) ? "IDENTITA" : "DOMINIO");
        engine.refresh();
        return id;
    }

    @Transactional
    public void updateFirma(UUID id, KeywordFirmaDTO d) {
        int upd = em.createNativeQuery(
                "UPDATE keyword_firma SET bu_id = CAST(:bu AS smallint), coge_codice = :coge, " +
                "fornitore_id = CAST(:forn AS uuid), tipo_movimento = :tm, sorgente = :src, " +
                "evento_forza = :forza, tipo_evento = :tipoEv, stato = :stato, note = :note, updated_at = now() " +
                "WHERE id = :id")
                .setParameter("bu", d.buId()).setParameter("coge", d.cogeCodice())
                .setParameter("forn", d.fornitoreId())
                .setParameter("tm", d.tipoMovimento() == null ? "*" : d.tipoMovimento())
                .setParameter("src", d.sorgente() == null ? "*" : d.sorgente())
                .setParameter("forza", d.eventoForza()).setParameter("tipoEv", d.tipoEvento())
                .setParameter("stato", d.stato() == null ? "ATTIVA" : d.stato())
                .setParameter("note", d.note()).setParameter("id", id)
                .executeUpdate();
        if (upd == 0) throw new ApiException(Response.Status.NOT_FOUND, "FIRMA_NON_TROVATA", "Firma " + id);
        engine.refresh();
    }

    @Transactional
    public void deleteFirma(UUID id) {
        int del = em.createNativeQuery("DELETE FROM keyword_firma WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        if (del == 0) throw new ApiException(Response.Status.NOT_FOUND, "FIRMA_NON_TROVATA", "Firma " + id);
        engine.refresh();
    }

    // ── Conflitti (pagina dedicata, tab Conflitti) ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public List<KeywordConflittoDTO> listConflitti(String stato) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, tipo, stato, signature_hash, firma_esistente_id, movimento_id, " +
                "target_esistente, target_nuovo, descrizione, created_at FROM keyword_conflitto " +
                "WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato) ORDER BY created_at DESC")
                .setParameter("stato", stato).getResultList();
        List<KeywordConflittoDTO> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            out.add(new KeywordConflittoDTO((UUID) r[0], (String) r[1], (String) r[2], (String) r[3],
                    r[4] == null ? null : (UUID) r[4], r[5] == null ? null : (UUID) r[5],
                    r[6] == null ? null : r[6].toString(), r[7] == null ? null : r[7].toString(),
                    (String) r[8], r[9] == null ? null : r[9].toString()));
        }
        return out;
    }

    @Transactional
    public void risolviConflitto(UUID conflittoId, RisolviConflittoKeywordRequest req, UUID userId) {
        Object[] c = trovaConflitto(conflittoId);
        if (c == null) throw new ApiException(Response.Status.NOT_FOUND, "CONFLITTO_NON_TROVATO", "Conflitto " + conflittoId);
        if (!"APERTO".equals((String) c[1])) {
            throw new ApiException(Response.Status.CONFLICT, "CONFLITTO_GIA_RISOLTO", "Il conflitto è già stato risolto");
        }
        UUID firmaId = c[2] == null ? null : (UUID) c[2];
        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            case "USA_NUOVO" -> {
                if (firmaId != null && c[3] != null) {
                    applicaTargetNuovo(firmaId, c[3].toString());
                    riattiva(firmaId);
                }
                chiudiConflitto(conflittoId, "RISOLTO", userId);
            }
            case "SCARTA" -> {
                if (firmaId != null) em.createNativeQuery(
                        "UPDATE keyword_firma SET stato = 'DISATTIVATA', updated_at = now() WHERE id = :id")
                        .setParameter("id", firmaId).executeUpdate();
                chiudiConflitto(conflittoId, "IGNORATO", userId);
            }
            // TIENI_ESISTENTE / UNISCI: la firma esistente torna attiva col suo target originale.
            default -> {
                if (firmaId != null) riattiva(firmaId);
                chiudiConflitto(conflittoId, "RISOLTO", userId);
            }
        }
        engine.refresh();
    }

    // ── helper persistenza ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Object[] trovaFirma(String sig, String tm, String src) {
        List<Object[]> r = em.createNativeQuery(
                "SELECT id, bu_id, coge_codice, fornitore_id, stato FROM keyword_firma " +
                "WHERE signature_hash = :sig AND tipo_movimento = :tm AND sorgente = :src")
                .setParameter("sig", sig).setParameter("tm", tm).setParameter("src", src)
                .getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    private UUID inserisciFirma(String natura, String azione, String tm, String src, Short bu, String coge,
                                UUID fornitoreId, String origine, String sig, String descr, UUID movId, UUID userId) {
        return (UUID) em.createNativeQuery(
                "INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice, " +
                "fornitore_id, origine, stato, signature_hash, descrizione_origine, movimento_origine_id, created_by) " +
                "VALUES (:nat, :az, :tm, :src, CAST(:bu AS smallint), :coge, CAST(:forn AS uuid), :orig, 'ATTIVA', " +
                ":sig, :descr, CAST(:mov AS uuid), CAST(:usr AS uuid)) RETURNING id")
                .setParameter("nat", natura).setParameter("az", azione)
                .setParameter("tm", tm).setParameter("src", src)
                .setParameter("bu", bu).setParameter("coge", coge).setParameter("forn", fornitoreId)
                .setParameter("orig", origine).setParameter("sig", sig).setParameter("descr", descr)
                .setParameter("mov", movId).setParameter("usr", userId)
                .getSingleResult();
    }

    private void inserisciToken(UUID firmaId, List<String> tokens, String tipo) {
        for (String t : tokens) {
            em.createNativeQuery(
                    "INSERT INTO keyword_token (firma_id, token, tipo) VALUES (:fid, :tok, :tipo) " +
                    "ON CONFLICT (firma_id, token) DO NOTHING")
                    .setParameter("fid", firmaId).setParameter("tok", t.toUpperCase()).setParameter("tipo", tipo)
                    .executeUpdate();
        }
    }

    private UUID apriConflitto(String tipo, String sig, UUID firmaId, UUID movId,
                               String targetEx, String targetNew, String descr) {
        return (UUID) em.createNativeQuery(
                "INSERT INTO keyword_conflitto (tipo, signature_hash, firma_esistente_id, movimento_id, " +
                "target_esistente, target_nuovo, descrizione, stato) " +
                "VALUES (:tipo, :sig, CAST(:firma AS uuid), CAST(:mov AS uuid), " +
                "CAST(:te AS jsonb), CAST(:tn AS jsonb), :descr, 'APERTO') RETURNING id")
                .setParameter("tipo", tipo).setParameter("sig", sig)
                .setParameter("firma", firmaId).setParameter("mov", movId)
                .setParameter("te", targetEx).setParameter("tn", targetNew).setParameter("descr", descr)
                .getSingleResult();
    }

    @SuppressWarnings("unchecked")
    private Object[] trovaConflitto(UUID id) {
        List<Object[]> r = em.createNativeQuery(
                "SELECT id, stato, firma_esistente_id, target_nuovo FROM keyword_conflitto WHERE id = :id")
                .setParameter("id", id).getResultList();
        return r.isEmpty() ? null : r.get(0);
    }

    private void applicaTargetNuovo(UUID firmaId, String targetJson) {
        // target_nuovo: {"bu":N,"coge":"..","fornitore":"uuid"|null}
        em.createNativeQuery(
                "UPDATE keyword_firma SET " +
                "bu_id = CAST((:t::jsonb ->> 'bu') AS smallint), " +
                "coge_codice = (:t::jsonb ->> 'coge'), " +
                "fornitore_id = CAST(NULLIF(:t::jsonb ->> 'fornitore','') AS uuid), updated_at = now() " +
                "WHERE id = :id")
                .setParameter("t", targetJson).setParameter("id", firmaId).executeUpdate();
    }

    private void riattiva(UUID firmaId) {
        em.createNativeQuery("UPDATE keyword_firma SET stato = 'ATTIVA', updated_at = now() WHERE id = :id")
                .setParameter("id", firmaId).executeUpdate();
    }

    private void chiudiConflitto(UUID id, String stato, UUID userId) {
        em.createNativeQuery(
                "UPDATE keyword_conflitto SET stato = :stato, risolto_at = now(), risolto_by = CAST(:usr AS uuid) WHERE id = :id")
                .setParameter("stato", stato).setParameter("usr", userId).setParameter("id", id)
                .executeUpdate();
    }

    /** Token di piu' firme in una sola query (firma_id, token) ORDER BY token, raggruppati in mappa. */
    @SuppressWarnings("unchecked")
    private Map<UUID, List<String>> tokenDelleFirme(List<UUID> firmaIds) {
        Map<UUID, List<String>> out = new HashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT firma_id, token FROM keyword_token WHERE firma_id IN (:ids) ORDER BY token")
                .setParameter("ids", firmaIds).getResultList()) {
            out.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add((String) r[1]);
        }
        return out;
    }

    private String cogeCodice(Integer cogeId) {
        if (cogeId == null) return null;
        List<?> r = em.createNativeQuery("SELECT codice FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId).getResultList();
        return r.isEmpty() ? null : (String) r.get(0);
    }

    private boolean sameTarget(Short bu1, String coge1, UUID f1, Short bu2, String coge2, UUID f2) {
        return java.util.Objects.equals(bu1, bu2)
                && java.util.Objects.equals(coge1, coge2)
                && java.util.Objects.equals(f1, f2);
    }

    private String targetJson(Short bu, String coge, UUID fornitore) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"bu\":").append(bu == null ? "null" : bu);
        sb.append(",\"coge\":").append(coge == null ? "null" : "\"" + coge + "\"");
        sb.append(",\"fornitore\":").append(fornitore == null ? "null" : "\"" + fornitore + "\"");
        sb.append("}");
        return sb.toString();
    }

    private List<String> upper(List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim().toUpperCase());
        return out;
    }
}
