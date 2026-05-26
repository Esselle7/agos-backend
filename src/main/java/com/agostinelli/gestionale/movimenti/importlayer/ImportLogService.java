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

    // ── Storico import ────────────────────────────────────────────────────────
    public PagedResponse<ImportLogDTO> findHistory(String fonte, int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, fonte, filename, data_import, righe_totali, righe_importate, " +
                        "righe_errore, righe_duplicate, righe_ambigue, righe_ambigue_classificate, " +
                        "stato, imported_by FROM import_log " +
                        "WHERE (CAST(:fonte AS VARCHAR) IS NULL OR fonte = :fonte) " +
                        "ORDER BY data_import DESC LIMIT :size OFFSET :offset")
                .setParameter("fonte", fonte)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<ImportLogDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new ImportLogDTO(
                    toUuid(r[0]), (String) r[1], (String) r[2], toInstant(r[3]),
                    toInt(r[4]), toInt(r[5]), toInt(r[6]), toInt(r[7]),
                    toInt(r[8]), toInt(r[9]), (String) r[10], toUuid(r[11])));
        }

        long total = ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM import_log WHERE (CAST(:fonte AS VARCHAR) IS NULL OR fonte = :fonte)")
                .setParameter("fonte", fonte)
                .getSingleResult()).longValue();

        return PagedResponse.of(content, page, size, total);
    }

    // ── Ambiguità di un import ──────────────────────────────────────────────────
    public PagedResponse<AmbiguitaDTO> getAmbiguita(UUID importLogId, String stato, int page, int size) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, import_log_id, riga_numero, fonte, raw_data, motivo, stato, " +
                        "movimento_id, classificato_at, note_operatore FROM import_ambiguita " +
                        "WHERE import_log_id = :logId AND (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato) " +
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
                        "WHERE import_log_id = :logId AND (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)")
                .setParameter("logId", importLogId)
                .setParameter("stato", stato)
                .getSingleResult()).longValue();

        return PagedResponse.of(content, page, size, total);
    }

    // ── Classificazione manuale ──────────────────────────────────────────────────
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
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

        // Scarto: nessun movimento creato
        if (req.scarta()) {
            em.createNativeQuery(
                            "UPDATE import_ambiguita SET stato = 'SCARTATO', classificato_da = :uid, " +
                            "classificato_at = now(), note_operatore = :nota WHERE id = :id")
                    .setParameter("uid", userId)
                    .setParameter("nota", req.nota())
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

        if (req.aggiungiRegola() && req.fornitoreId() != null && norm.descrizione() != null) {
            String pattern = norm.descrizione().length() > 255
                    ? norm.descrizione().substring(0, 255) : norm.descrizione();
            em.createNativeQuery(
                            "INSERT INTO fornitore_alias_matching (fornitore_id, pattern, match_type) " +
                            "VALUES (:fid, :pattern, 'CONTAINS')")
                    .setParameter("fid", req.fornitoreId())
                    .setParameter("pattern", pattern)
                    .executeUpdate();
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

    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant i) return i;
        if (o instanceof Timestamp t) return t.toInstant();
        if (o instanceof OffsetDateTime odt) return odt.toInstant();
        return null;
    }
}
