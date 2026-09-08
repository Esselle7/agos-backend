package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Storico import, lettura ambiguità e classificazione manuale delle righe ambigue. */
@ApplicationScoped
public class ImportLogService {

    @Inject EntityManager em;
    @Inject MovimentiService movimentiService;
    @Inject MovimentoNormalizerImpl normalizer;
    @Inject MvRefreshService mvRefresh;
    @Inject ObjectMapper objectMapper;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService keywordLearning;
    /** Riusa l'euristica del Gate B quando l'operatore promuove una riga ambigua a incasso-evento. */
    @Inject MovimentoMappingEngineImpl mappingEngine;

    // ── Storico import ────────────────────────────────────────────────────────
    public PagedResponse<ImportLogDTO> findHistory(String fonte, int page, int size) {
        @SuppressWarnings("unchecked")
        // Il periodo di riferimento del file si AGGREGA dai movimenti che citano l'import: e' gia'
        // dato, e una colonna in import_log divergerebbe appena una riga ambigua viene classificata
        // dopo il caricamento. Costo: import_log ha 2 righe in produzione (08/09/2026) e la LATERAL
        // usa idx_movimenti_fonte_import — il gate §9.0 non chiede altro a questi volumi.
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT il.id, il.fonte, il.filename, il.data_import, il.righe_totali, " +
                        "il.righe_importate, il.righe_errore, il.righe_duplicate, il.righe_ambigue, " +
                        "il.righe_ambigue_classificate, il.stato, il.imported_by, p.dal, p.al " +
                        "FROM import_log il " +
                        "LEFT JOIN LATERAL (SELECT min(m.data_movimento) AS dal, " +
                        "                          max(m.data_movimento) AS al " +
                        "                   FROM movimenti m " +
                        "                   WHERE m.fonte_importazione_id = il.id) p ON TRUE " +
                        "WHERE (CAST(:fonte AS VARCHAR) IS NULL OR il.fonte = :fonte) " +
                        "ORDER BY il.data_import DESC LIMIT :size OFFSET :offset")
                .setParameter("fonte", fonte)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<ImportLogDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new ImportLogDTO(
                    toUuid(r[0]), (String) r[1], (String) r[2], toInstant(r[3]),
                    toInt(r[4]), toInt(r[5]), toInt(r[6]), toInt(r[7]),
                    toInt(r[8]), toInt(r[9]), (String) r[10], toUuid(r[11]),
                    toLocalDate(r[12]), toLocalDate(r[13])));
        }

        long total = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM import_log WHERE (CAST(:fonte AS VARCHAR) IS NULL OR fonte = :fonte)")
                .setParameter("fonte", fonte)
                .getSingleResult()).longValue();

        return PagedResponse.of(content, page, size, total);
    }

    // ── Ambiguità di un import ──────────────────────────────────────────────────

    /**
     * Le righe che l'import non ha saputo interpretare.
     *
     * <p>{@code importLogId} null = tutte, di ogni import. Serve perché il badge «Da rileggere»
     * conta {@code DA_CLASSIFICARE} su TUTTA la tabella: una pagina che leggesse solo l'ultimo
     * import mostrerebbe una lista vuota accanto a un contatore diverso da zero — il badge e la
     * schermata che lo apre devono contare la stessa cosa.
     */
    public PagedResponse<AmbiguitaDTO> getAmbiguita(UUID importLogId, String stato, int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, import_log_id, riga_numero, fonte, raw_data, motivo, stato, " +
                        "movimento_id, classificato_at, note_operatore FROM import_ambiguita " +
                        "WHERE (CAST(:logId AS uuid) IS NULL OR import_log_id = :logId) " +
                        "  AND (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato) " +
                        "ORDER BY riga_numero LIMIT :size OFFSET :offset")
                .setParameter("logId", importLogId)
                .setParameter("stato", stato)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<AmbiguitaDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new AmbiguitaDTO(
                    toUuid(r[0]), toUuid(r[1]), toInt(r[2]), (String) r[3],
                    parseMap(r[4]), (String) r[5], (String) r[6], toUuid(r[7]),
                    toInstant(r[8]), (String) r[9]));
        }

        long total = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM import_ambiguita " +
                        "WHERE (CAST(:logId AS uuid) IS NULL OR import_log_id = :logId) " +
                        "  AND (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)")
                .setParameter("logId", importLogId)
                .setParameter("stato", stato)
                .getSingleResult()).longValue();

        return PagedResponse.of(content, page, size, total);
    }

    /**
     * "Va che è un evento": sposta una riga da {@code import_ambiguita} alla coda
     * {@code eventi_da_riconciliare}, dove potrà essere attribuita a un evento.
     *
     * <p>Non crea movimenti — l'incasso-evento nasce solo dal modulo Eventi (invariante DACLASS).
     * I segnali (tipo presunto, data evento, controparte) sono ricavati con la stessa euristica
     * del Gate B, non con una copia divergente.
     */
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void promuoviAEvento(UUID id, UUID userId) {
        @SuppressWarnings("unchecked")
        List<Object[]> found = em.createNativeQuery(
                        "SELECT import_log_id, riga_numero, fonte, raw_data, stato " +
                        "FROM import_ambiguita WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "AMBIGUITA_NON_TROVATA",
                    "Ambiguità non trovata: " + id);
        }
        Object[] r = found.get(0);
        if (!"DA_CLASSIFICARE".equals((String) r[4])) {
            throw new ApiException(Response.Status.CONFLICT, "AMBIGUITA_GIA_CHIUSA",
                    "La riga ambigua è già stata classificata o scartata");
        }

        RawMovimento norm = normalizer.normalize(new RawRow(toInt(r[1]), parseMap(r[3])));
        if (!"ENTRATA".equals(norm.tipo())) {
            throw new ApiException(Response.Status.BAD_REQUEST, "NON_E_UN_INCASSO",
                    "Solo un'entrata può essere un incasso-evento: questa riga è " + norm.tipo());
        }
        var park = mappingEngine.estraiSegnaliEvento(
                norm.descrizione(), norm.descCompact(), norm.dataMovimento());
        String ordinante = norm.entita() == null ? null : norm.entita().ordinante();

        em.createNativeQuery(
                "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, chiave_aggancio, " +
                "data_movimento, importo, tipo, conto_bancario_id, descrizione_norm, " +
                "tipo_evento_presunto, keyword_match, controparte_nome, controparte_iban, " +
                "data_evento_estratta, stato, raw_data, created_at) " +
                "VALUES (gen_random_uuid(), CAST(:log AS uuid), :fonte, :chiave, :data, :imp, 'ENTRATA', " +
                ":conto, :descr, :tipoPres, :kw, :contro, :iban, :dataEv, 'DA_RICONCILIARE', " +
                "CAST(:raw AS jsonb), now())")
                .setParameter("log", r[0] == null ? null : r[0].toString())
                .setParameter("fonte", (String) r[2])
                .setParameter("chiave", norm.chiaveAggancio())
                .setParameter("data", norm.dataMovimento())
                .setParameter("imp", norm.importo())
                .setParameter("conto", norm.contoBancarioId())
                .setParameter("descr", norm.descrizione())
                .setParameter("tipoPres", park == null ? null : park.tipoEventoPresunto())
                .setParameter("kw", park == null ? null : park.keywordMatch())
                .setParameter("contro", ordinante)
                .setParameter("iban", norm.entita() == null ? null : norm.entita().ibanControparte())
                .setParameter("dataEv", park == null ? null : park.dataEventoEstratta())
                .setParameter("raw", r[3] == null ? "{}" : r[3].toString())
                .executeUpdate();

        em.createNativeQuery(
                "UPDATE import_ambiguita SET stato = 'CLASSIFICATA', classificato_da = :uid, " +
                "classificato_at = now(), note_operatore = 'Promossa a incasso-evento' WHERE id = :id")
                .setParameter("uid", userId).setParameter("id", id).executeUpdate();
    }

    // ── Classificazione manuale ──────────────────────────────────────────────────
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void classificaAmbiguita(UUID id, ClassificaAmbiguitaRequest req, UUID userId) {
        @SuppressWarnings("unchecked")
        List<Object[]> found = em.createNativeQuery(
                        "SELECT import_log_id, riga_numero, fonte, raw_data, stato " +
                        "FROM import_ambiguita WHERE id = :id")
                .setParameter("id", id)
                .getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "AMBIGUITA_NON_TROVATA",
                    "Ambiguità non trovata: " + id);
        }
        Object[] r = found.get(0);
        UUID importLogId = toUuid(r[0]);
        int riga = toInt(r[1]);
        String fonte = (String) r[2];
        Map<String, String> campi = parseMap(r[3]);
        String stato = (String) r[4];

        if (!"DA_CLASSIFICARE".equals(stato)) {
            throw new ApiException(Response.Status.CONFLICT, "AMBIGUITA_GIA_CHIUSA",
                    "La riga ambigua è già stata classificata o scartata");
        }

        // Scarto: nessun movimento creato. R9 — serve il motivo scritto: è una riga bancaria vera
        // che resta fuori dai conti.
        if (req.scarta()) {
            em.createNativeQuery(
                            "UPDATE import_ambiguita SET stato = 'SCARTATO', classificato_da = :uid, " +
                            "classificato_at = now(), note_operatore = :nota WHERE id = :id")
                    .setParameter("uid", userId)
                    .setParameter("nota", EsclusioneMotivata.obbligatorio(req.nota()))
                    .setParameter("id", id)
                    .executeUpdate();
            return;
        }

        if (req.cogeId() == null || req.businessUnitId() == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CLASSIFICAZIONE_INCOMPLETA",
                    "cogeId e businessUnitId sono obbligatori per classificare la riga");
        }

        // Ricostruisce i dati base ri-normalizzando la riga grezza salvata
        RawMovimento norm = normalizer.normalize(new RawRow(riga, campi));

        Short conto = req.contoBancarioId() != null ? req.contoBancarioId() : norm.contoBancarioId();
        Integer metodoId = req.metodoPagamentoId() != null ? req.metodoPagamentoId() : lookupMetodo(norm.metodoPagamentoCodice());

        MovimentoCreateRequest createReq = new MovimentoCreateRequest(
                norm.tipo(),
                norm.importo(),
                null,
                null,
                norm.dataMovimento(),
                norm.dataCompetenza(),
                norm.dataMovimento(),   // dataFinanziaria
                null,
                conto,
                metodoId,
                req.businessUnitId(),
                req.cogeId(),
                null,
                req.fornitoreId(),
                req.eventoId(),
                req.tipoEventoMovimento(),
                norm.descrizione(),
                req.nota(),
                norm.riferimentoEsterno(),
                fonte,
                null
        );

        UUID movimentoId = movimentiService.createMovimentoImport(createReq, userId, importLogId).id();

        em.createNativeQuery(
                        "UPDATE import_ambiguita SET stato = 'CLASSIFICATO', movimento_id = :mid, " +
                        "classificato_da = :uid, classificato_at = now(), note_operatore = :nota WHERE id = :id")
                .setParameter("mid", movimentoId)
                .setParameter("uid", userId)
                .setParameter("nota", req.nota())
                .setParameter("id", id)
                .executeUpdate();

        em.createNativeQuery(
                        "UPDATE import_log SET righe_ambigue_classificate = righe_ambigue_classificate + 1 " +
                        "WHERE id = :logId")
                .setParameter("logId", importLogId)
                .executeUpdate();

        // Auto-apprendimento a KEYWORD (PROMPT-KEYWORD-LEARNING.md §4.4): estrae le firme IDENTITA
        // dalla descrizione e le lega al target scelto, così il prossimo import cataloga da solo
        // una riga simile. Sostituisce l'auto-insert alias e l'apprendimento per IBAN (rimossi).
        if (req.apprendiKeyword()) {
            keywordLearning.apprendi(norm.descrizione(), norm.entita(), norm.tipo(),
                    req.businessUnitId(), req.cogeId(), req.fornitoreId(), movimentoId, userId);
        }

        mvRefresh.requestRefreshAfterCommit();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Integer lookupMetodo(String codice) {
        if (codice == null) return null;
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = :c")
                .setParameter("c", codice)
                .getResultList();
        return ids.isEmpty() ? null : ((Number) ids.get(0)).intValue();
    }

    private Map<String, String> parseMap(Object jsonb) {
        if (jsonb == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(jsonb.toString(), Map.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> out.put(k, v == null ? null : v.toString()));
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Integer toInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private UUID toUuid(Object o) {
        if (o == null) return null;
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
