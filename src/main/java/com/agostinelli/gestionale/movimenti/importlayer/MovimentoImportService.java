package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.dto.EtlRowError;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrazione dell'import ETL (Billy / BPM / CA):
 *  parse → normalize → map → (dedup) → persist diretto.
 *
 * Le righe ambigue NON vengono salvate in movimenti: finiscono in import_ambiguita
 * con stato DA_CLASSIFICARE per revisione manuale. Il refresh delle materialized view
 * e l'invalidazione cache avvengono UNA sola volta al termine del loop.
 */
@ApplicationScoped
public class MovimentoImportService {

    private static final Logger log = Logger.getLogger(MovimentoImportService.class);

    @Inject EntityManager em;
    @Inject MovimentiService movimentiService;
    @Inject MovimentiRepository repo;
    @Inject MvRefreshService mvRefresh;
    @Inject MovimentoMappingEngineImpl mappingEngine;
    @Inject ImportStrategyFactory strategyFactory;
    @Inject ObjectMapper objectMapper;

    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public EtlImportResponse importFile(InputStream file, String filename, String fonteStr, UUID userId) {
        ImportStrategy strategy = strategyFactory.get(fonteStr);
        String dbFonte = strategy.fonte();

        // Recepisce eventuali alias fornitore aggiunti da classificazioni precedenti.
        mappingEngine.refreshLookups();

        UUID importLogId = creaImportLog(dbFonte, filename, userId);

        List<RawRow> rows = strategy.parserFor(fonteStr).parse(file);

        int importati = 0, duplicati = 0, ambigui = 0, scartati = 0, parcheggiati = 0;
        List<EtlRowError> errori = new ArrayList<>();
        Set<String> rifEsistenti = repo.findRifimentiEsterniByFonte(dbFonte);

        for (RawRow raw : rows) {
            try {
                RawMovimento norm = strategy.getNormalizer().normalize(raw);

                MappingResult mapped = mappingEngine.map(norm);

                // Gate A: esclusioni deterministiche tracciate in import_scartati (non sono ambiguità)
                if (mapped.outcome().isSkip()) {
                    salvaScartato(importLogId, raw, norm, mapped.motivoAmbiguita(), dbFonte);
                    scartati++;
                    continue;
                }

                // Gate B: voci evento parcheggiate (dedup cross-sorgente su chiave_aggancio)
                if (mapped.outcome() == MappingResult.MappingOutcome.PARK_EVENTO) {
                    if (salvaEventoParcheggiato(importLogId, raw, norm, mapped.park(), dbFonte)) {
                        parcheggiati++;
                    } else {
                        duplicati++; // già parcheggiato da un'altra sorgente (stessa chiave aggancio)
                    }
                    continue;
                }

                if (mapped.outcome() == MappingResult.MappingOutcome.AMBIGUOUS
                        || mapped.outcome() == MappingResult.MappingOutcome.ERROR) {
                    salvaAmbiguita(importLogId, raw, norm, mapped.motivoAmbiguita(), dbFonte);
                    ambigui++;
                    continue;
                }

                MovimentoCreateRequest req = mapped.request();
                String rif = req.riferimentoEsterno();
                if (rif != null && !rif.isBlank() && rifEsistenti.contains(rif)) {
                    duplicati++;
                    continue;
                }

                movimentiService.createMovimentoImport(req, userId, importLogId);
                if (rif != null && !rif.isBlank()) rifEsistenti.add(rif);
                importati++;

            } catch (Exception e) {
                log.warnf("Import riga %d fallita: %s", raw.riga(), e.getMessage());
                errori.add(new EtlRowError(raw.riga(), e.getMessage(), raw.campi()));
            }
        }

        String statoFinale = statoFinale(errori.size(), ambigui, rows.size());
        chiudiImportLog(importLogId, rows.size(), importati, errori.size(), duplicati, ambigui,
                scartati, parcheggiati, statoFinale, errori);

        if (importati > 0) mvRefresh.requestRefreshAfterCommit();

        return new EtlImportResponse(importLogId, importati, duplicati, ambigui, scartati, parcheggiati, errori);
    }

    private String statoFinale(int errori, int ambigui, int totali) {
        if (totali > 0 && errori > totali * 0.5) return "ERRORE";
        if (ambigui > 0 || errori > 0) return "COMPLETATO_CON_AMBIGUITA";
        return "COMPLETATO";
    }

    // ── import_log ──────────────────────────────────────────────────────────────

    private UUID creaImportLog(String fonte, String filename, UUID userId) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO import_log (id, fonte, filename, stato, imported_by) " +
                        "VALUES (:id, :fonte, :filename, 'IN_CORSO', :uid)")
                .setParameter("id", id)
                .setParameter("fonte", fonte)
                .setParameter("filename", filename != null ? filename : "unknown")
                .setParameter("uid", userId)
                .executeUpdate();
        return id;
    }

    private void chiudiImportLog(UUID id, int totali, int importate, int errore, int duplicate,
                                 int ambigue, int scartate, int parcheggiate, String stato, List<EtlRowError> errori) {
        em.createNativeQuery(
                        "UPDATE import_log SET righe_totali = :tot, righe_importate = :imp, " +
                        "righe_errore = :err, righe_duplicate = :dup, righe_ambigue = :amb, " +
                        "righe_scartate = :sca, righe_parcheggiate = :par, " +
                        "stato = :stato, errori_dettaglio = CAST(:json AS jsonb) WHERE id = :id")
                .setParameter("tot", totali)
                .setParameter("imp", importate)
                .setParameter("err", errore)
                .setParameter("dup", duplicate)
                .setParameter("amb", ambigue)
                .setParameter("sca", scartate)
                .setParameter("par", parcheggiate)
                .setParameter("stato", stato)
                .setParameter("json", toJson(errori))
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * Persiste una voce-evento nella coda eventi_da_riconciliare (ETL v2 §5).
     * Dedup cross-sorgente: a parità di chiave_aggancio (Billy↔CA↔BPM) la seconda
     * occorrenza viene scartata (ON CONFLICT DO NOTHING). Ritorna true se inserita.
     */
    private boolean salvaEventoParcheggiato(UUID importLogId, RawRow raw, RawMovimento norm,
                                            com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento park,
                                            String fonte) {
        // chiave usabile per il dedup solo se valorizzata e con la parte importo (non "<num>/")
        String chiaveRaw = norm.chiaveAggancio();
        String chiaveDedup = (chiaveRaw == null || chiaveRaw.isBlank() || chiaveRaw.endsWith("/"))
                ? null : chiaveRaw;

        var entita = norm.entita();
        int inserted = em.createNativeQuery(
                        "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, chiave_aggancio, " +
                        "data_movimento, importo, tipo, conto_bancario_id, descrizione_norm, " +
                        "tipo_evento_presunto, keyword_match, controparte_nome, controparte_iban, " +
                        "data_evento_estratta, raw_data) " +
                        "VALUES (:id, :logId, :fonte, :chiave, :data, :importo, :tipo, :conto, :descr, " +
                        ":tipoEv, :kw, :nome, :iban, :dataEv, CAST(:raw AS jsonb)) " +
                        "ON CONFLICT (chiave_aggancio) WHERE chiave_aggancio IS NOT NULL DO NOTHING")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("fonte", fonte)
                .setParameter("chiave", chiaveDedup)
                .setParameter("data", norm.dataMovimento())
                .setParameter("importo", norm.importo())
                .setParameter("tipo", norm.tipo())
                .setParameter("conto", norm.contoBancarioId())
                .setParameter("descr", norm.descrizione())
                .setParameter("tipoEv", park != null ? park.tipoEventoPresunto() : null)
                .setParameter("kw", park != null ? park.keywordMatch() : null)
                .setParameter("nome", entita != null ? entita.ordinante() : null)
                .setParameter("iban", entita != null ? entita.ibanControparte() : null)
                .setParameter("dataEv", park != null ? park.dataEventoEstratta() : null)
                .setParameter("raw", toJson(raw.campi()))
                .executeUpdate();
        return inserted > 0;
    }

    /**
     * Persiste una riga esclusa dal Gate A in import_scartati (ETL v2 §4/§9.4):
     * tracciata e reversibile, conteggiata in import_log, mai un movimento.
     */
    private void salvaScartato(UUID importLogId, RawRow raw, RawMovimento norm, String motivo, String fonte) {
        em.createNativeQuery(
                        "INSERT INTO import_scartati (id, import_log_id, riga_numero, fonte, motivo, " +
                        "chiave_aggancio, data_movimento, importo, causale, raw_data) " +
                        "VALUES (:id, :logId, :riga, :fonte, :motivo, :chiave, :data, :importo, :causale, CAST(:raw AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("riga", raw.riga())
                .setParameter("fonte", fonte)
                .setParameter("motivo", motivo)
                .setParameter("chiave", norm.chiaveAggancio())
                .setParameter("data", norm.dataMovimento())
                .setParameter("importo", norm.importo())
                .setParameter("causale", raw.campi().get("CAUSALE"))
                .setParameter("raw", toJson(raw.campi()))
                .executeUpdate();
    }

    private void salvaAmbiguita(UUID importLogId, RawRow raw, RawMovimento norm, String motivo, String fonte) {
        var entita = norm.entita();
        String nome = entita == null ? null
                : (entita.beneficiario() != null ? entita.beneficiario() : entita.ordinante());
        em.createNativeQuery(
                        "INSERT INTO import_ambiguita (id, import_log_id, riga_numero, fonte, raw_data, motivo, stato, " +
                        "controparte_nome, controparte_iban) " +
                        "VALUES (:id, :logId, :riga, :fonte, CAST(:raw AS jsonb), :motivo, 'DA_CLASSIFICARE', :nome, :iban)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("riga", raw.riga())
                .setParameter("fonte", fonte)
                .setParameter("raw", toJson(raw.campi()))
                .setParameter("motivo", motivo != null ? motivo : "COGE_NON_DETERMINABILE")
                .setParameter("nome", nome)
                .setParameter("iban", entita == null ? null : entita.ibanControparte())
                .executeUpdate();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value instanceof Map ? "{}" : "[]";
        }
    }
}
