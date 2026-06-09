package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.EventoParcheggiatoDTO;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest;
import com.agostinelli.gestionale.movimenti.dto.SuggerimentoControparteDTO;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backend del triage assistito (ETL v2 §8/§13): KPI di qualità dell'import,
 * suggerimenti di controparte (fuzzy pg_trgm), CRUD regole data-driven, e il
 * "centro di smistamento" dei movimenti su conti transitori e degli eventi
 * parcheggiati. La UI vive nel frontend; qui si espongono solo i servizi.
 */
@ApplicationScoped
public class ImportTriageService {

    private static final String COGE_RICAVI_DACLASS = "39.99.999";
    private static final String COGE_COSTI_DACLASS = "49.99.999";

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;
    @Inject MovimentiService movimentiService;
    @Inject MvRefreshService mvRefresh;
    @Inject MovimentoMappingEngineImpl mappingEngine;

    // ── KPI (§13) ────────────────────────────────────────────────────────────────
    // 4 aggregazioni full-table: cache (TTL 2m) invalidata dai mutator di import.
    @CacheResult(cacheName = "import-kpi")
    public ImportKpiDTO getKpi() {
        Object[] s = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(righe_totali),0), COALESCE(SUM(righe_importate),0), " +
                "COALESCE(SUM(righe_ambigue),0), COALESCE(SUM(righe_scartate),0), " +
                "COALESCE(SUM(righe_parcheggiate),0) FROM import_log").getSingleResult();
        long totali = num(s[0]), importate = num(s[1]), ambigue = num(s[2]),
                scartate = num(s[3]), parcheggiate = num(s[4]);

        Object[] t = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*), COALESCE(SUM(m.importo_lordo),0) FROM movimenti m " +
                "JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice IN ('39.99.999','49.99.999') AND m.stato <> 'ANNULLATO'")
                .getSingleResult();
        long transitoriCount = num(t[0]);
        BigDecimal saldoTransitori = t[1] == null ? BigDecimal.ZERO : (BigDecimal) t[1];

        Object[] f = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*) FILTER (WHERE fornitore_id IS NOT NULL), COUNT(*) FROM movimenti " +
                "WHERE tipo = 'USCITA' AND fonte_importazione_id IS NOT NULL AND stato <> 'ANNULLATO'")
                .getSingleResult();
        long usciteConFornitore = num(f[0]), usciteTot = num(f[1]);

        double tassoAmbiguita = totali == 0 ? 0 : (ambigue * 100.0) / totali;
        double copertura = usciteTot == 0 ? 0 : (usciteConFornitore * 100.0) / usciteTot;

        return new ImportKpiDTO(totali, importate, ambigue, scartate, parcheggiate,
                transitoriCount, saldoTransitori,
                round2(tassoAmbiguita), round2(copertura));
    }

    // ── Suggerimenti controparte per una riga ambigua (§8.2) ──────────────────────
    @SuppressWarnings("unchecked")
    public List<SuggerimentoControparteDTO> suggerimenti(UUID ambiguitaId) {
        List<Object[]> amb = em.createNativeQuery(
                "SELECT controparte_nome, controparte_iban FROM import_ambiguita WHERE id = :id")
                .setParameter("id", ambiguitaId).getResultList();
        if (amb.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "AMBIGUITA_NON_TROVATA",
                    "Ambiguità non trovata: " + ambiguitaId);
        }
        return suggerisciControparti((String) amb.get(0)[0], (String) amb.get(0)[1]);
    }

    /** Core dei suggerimenti: IBAN esatto (sim 1.0), poi fuzzy sul nome (trigrammi). */
    @SuppressWarnings("unchecked")
    public List<SuggerimentoControparteDTO> suggerisciControparti(String nome, String iban) {
        List<SuggerimentoControparteDTO> out = new ArrayList<>();
        if (iban != null && !iban.isBlank()) {
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    suggerimentoSelect() + " WHERE c.iban = :iban LIMIT 1")
                    .setParameter("iban", iban).getResultList()) {
                out.add(mapSuggerimento(r, 1.0));
            }
        }
        if (out.isEmpty() && nome != null && !nome.isBlank()) {
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    suggerimentoSelect() +
                    " WHERE similarity(c.nome_normalizzato, :nome) > 0.3 " +
                    " ORDER BY similarity(c.nome_normalizzato, :nome) DESC LIMIT 3")
                    .setParameter("nome", nome.toUpperCase()).getResultList()) {
                out.add(mapSuggerimento(r, similarita(nome, (String) r[1])));
            }
        }
        return out;
    }

    private String suggerimentoSelect() {
        return "SELECT c.id, c.nome_normalizzato, c.iban, c.fornitore_id, c.coge_default_id, " +
               "p.codice, c.bu_default_id FROM controparti c " +
               "LEFT JOIN piano_dei_conti_coge p ON p.id = c.coge_default_id";
    }

    private SuggerimentoControparteDTO mapSuggerimento(Object[] r, double sim) {
        return new SuggerimentoControparteDTO(
                toUuid(r[0]), (String) r[1], (String) r[2], r[3] == null ? null : toUuid(r[3]),
                r[4] == null ? null : ((Number) r[4]).intValue(), (String) r[5],
                r[6] == null ? null : ((Number) r[6]).shortValue(), round2(sim));
    }

    private double similarita(String a, String b) {
        Object v = em.createNativeQuery("SELECT similarity(:a, :b)")
                .setParameter("a", a.toUpperCase())
                .setParameter("b", b == null ? "" : b.toUpperCase())
                .getSingleResult();
        return v == null ? 0 : round2(((Number) v).doubleValue());
    }

    // ── CRUD regole_classificazione (§9, modificabili senza redeploy) ─────────────
    @SuppressWarnings("unchecked")
    public List<RegolaClassificazioneDTO> listRegole() {
        List<RegolaClassificazioneDTO> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, " +
                "coge_codice, bu_id, metodo_codice, confidence, attivo, note " +
                "FROM regole_classificazione ORDER BY priorita, id").getResultList()) {
            out.add(new RegolaClassificazioneDTO(
                    ((Number) r[0]).intValue(), ((Number) r[1]).intValue(), (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6], (String) r[7], (String) r[8],
                    r[9] == null ? null : ((Number) r[9]).shortValue(), (String) r[10],
                    (BigDecimal) r[11], (Boolean) r[12], (String) r[13]));
        }
        return out;
    }

    @Transactional
    public Integer createRegola(RegolaClassificazioneDTO d) {
        Object id = em.createNativeQuery(
                "INSERT INTO regole_classificazione (priorita, sorgente, tipo_movimento, campo, match_type, " +
                "pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note) " +
                "VALUES (:pri, :sorg, :tipo, :campo, :mt, :pat, :az, :coge, :bu, :met, :conf, :att, :note) RETURNING id")
                .setParameter("pri", d.priorita())
                .setParameter("sorg", coalesce(d.sorgente(), "*"))
                .setParameter("tipo", coalesce(d.tipoMovimento(), "*"))
                .setParameter("campo", d.campo())
                .setParameter("mt", d.matchType())
                .setParameter("pat", d.pattern())
                .setParameter("az", d.azione())
                .setParameter("coge", d.cogeCodice())
                .setParameter("bu", d.buId())
                .setParameter("met", d.metodoCodice())
                .setParameter("conf", d.confidence() == null ? BigDecimal.ONE : d.confidence())
                .setParameter("att", d.attivo())
                .setParameter("note", d.note())
                .getSingleResult();
        regoleEngine.refresh();
        return ((Number) id).intValue();
    }

    @Transactional
    public void setRegolaAttiva(int id, boolean attiva) {
        int upd = em.createNativeQuery("UPDATE regole_classificazione SET attivo = :a WHERE id = :id")
                .setParameter("a", attiva).setParameter("id", id).executeUpdate();
        if (upd == 0) throw new ApiException(Response.Status.NOT_FOUND, "REGOLA_NON_TROVATA", "Regola " + id);
        regoleEngine.refresh();
    }

    @Transactional
    public void deleteRegola(int id) {
        int del = em.createNativeQuery("DELETE FROM regole_classificazione WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        if (del == 0) throw new ApiException(Response.Status.NOT_FOUND, "REGOLA_NON_TROVATA", "Regola " + id);
        regoleEngine.refresh();
    }

    // ── Centro smistamento: movimenti su conti transitori (39/49.99.999) ──────────

    /** Lista paginata dei movimenti ancora su conto transitorio (da catalogare). */
    @SuppressWarnings("unchecked")
    public PagedResponse<TransitorioDTO> listTransitori(String tipo, int page, int size) {
        String where =
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice IN ('" + COGE_RICAVI_DACLASS + "','" + COGE_COSTI_DACLASS + "') " +
                "AND m.stato <> 'ANNULLATO' AND (CAST(:tipo AS VARCHAR) IS NULL OR m.tipo = :tipo)";

        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.id, m.tipo, m.importo_lordo, m.data_movimento, m.descrizione, p.codice, " +
                "m.fornitore_id, m.conto_bancario_id " + where +
                " ORDER BY m.data_movimento, m.id LIMIT :size OFFSET :offset")
                .setParameter("tipo", tipo)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<TransitorioDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String descr = (String) r[4];
            Short conto = r[7] == null ? null : ((Number) r[7]).shortValue();
            EntitaEstratte ent = estraiEntita(descr, conto);
            content.add(new TransitorioDTO(
                    toUuid(r[0]), (String) r[1], (BigDecimal) r[2],
                    ((java.sql.Date) r[3]).toLocalDate(), descr, (String) r[5],
                    r[6] == null ? null : toUuid(r[6]), conto,
                    ent.ibanControparte(),
                    ent.beneficiario() != null ? ent.beneficiario() : ent.ordinante()));
        }

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("tipo", tipo).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /** Suggerimenti controparte per un movimento transitorio (ri-estrae nome/IBAN dalla descrizione). */
    public List<SuggerimentoControparteDTO> suggerimentiTransitorio(UUID movimentoId) {
        Movimento m = em.find(Movimento.class, movimentoId);
        if (m == null) throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_TROVATO", "Movimento " + movimentoId);
        EntitaEstratte ent = estraiEntita(m.descrizione, m.contoBancarioId);
        String nome = ent.beneficiario() != null ? ent.beneficiario() : ent.ordinante();
        return suggerisciControparti(nome, ent.ibanControparte());
    }

    /**
     * Classifica un movimento transitorio: lo sposta sul conto COGE/BU corretto (e
     * fornitore), opzionalmente apprende la controparte per IBAN (auto-riconoscimento
     * ai prossimi import). Monitorabile: dopo la chiamata il movimento esce dai transitori.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void classificaTransitorio(UUID movimentoId, ClassificaTransitorioRequest req) {
        Movimento m = em.find(Movimento.class, movimentoId);
        if (m == null) throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_TROVATO", "Movimento " + movimentoId);

        String cogeCorrente = (String) em.createNativeQuery(
                "SELECT codice FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", m.contoCoge).getSingleResult();
        if (!COGE_RICAVI_DACLASS.equals(cogeCorrente) && !COGE_COSTI_DACLASS.equals(cogeCorrente)) {
            throw new ApiException(Response.Status.CONFLICT, "NON_TRANSITORIO",
                    "Il movimento non è su un conto transitorio (è su " + cogeCorrente + ")");
        }

        m.contoCoge = req.cogeId();
        m.businessUnitId = req.businessUnitId();
        m.fornitoreId = req.fornitoreId();
        if (req.nota() != null && !req.nota().isBlank()) {
            m.note = (m.note == null ? "" : m.note + " | ") + req.nota();
        }
        em.merge(m);

        if (req.apprendiControparte()) {
            EntitaEstratte ent = estraiEntita(m.descrizione, m.contoBancarioId);
            apprendiControparte(ent, m.tipo, req.fornitoreId(), req.cogeId(), req.businessUnitId());
        }

        mvRefresh.requestRefreshAfterCommit();
    }

    /** Upsert controparte per IBAN (apprendimento §7.3). No-op se manca l'IBAN. */
    private void apprendiControparte(EntitaEstratte ent, String tipoMov, UUID fornitoreId, Integer cogeId, Short buId) {
        String iban = ent == null ? null : ent.ibanControparte();
        if (iban == null || iban.isBlank()) return;
        String nome = ent.beneficiario() != null ? ent.beneficiario()
                : (ent.ordinante() != null ? ent.ordinante() : iban);
        if (nome.length() > 255) nome = nome.substring(0, 255);
        String tipo = "ENTRATA".equals(tipoMov) ? "CLIENTE" : "FORNITORE";
        em.createNativeQuery(
                "INSERT INTO controparti (tipo, nome_normalizzato, iban, fornitore_id, coge_default_id, bu_default_id) " +
                "VALUES (:tipo, :nome, :iban, :fid, :coge, :bu) " +
                "ON CONFLICT (iban) WHERE iban IS NOT NULL DO UPDATE SET " +
                "fornitore_id = EXCLUDED.fornitore_id, coge_default_id = EXCLUDED.coge_default_id, " +
                "bu_default_id = EXCLUDED.bu_default_id, updated_at = now()")
                .setParameter("tipo", tipo).setParameter("nome", nome).setParameter("iban", iban)
                .setParameter("fid", fornitoreId).setParameter("coge", cogeId).setParameter("bu", buId)
                .executeUpdate();
        mappingEngine.refreshLookups();
    }

    /** Ri-estrae IBAN/nome dalla descrizione del movimento (sorgente dedotta dal conto). */
    private EntitaEstratte estraiEntita(String descrizione, Short contoBancarioId) {
        String sorgente = contoBancarioId == null ? Sorgente.CA
                : (contoBancarioId == 1 ? Sorgente.BPM : (contoBancarioId == 2 ? Sorgente.CA : Sorgente.BILLY));
        return DescNormalizer.extract(descrizione, sorgente);
    }

    // ── Centro smistamento: eventi parcheggiati (eventi_da_riconciliare) ──────────

    @SuppressWarnings("unchecked")
    public PagedResponse<EventoParcheggiatoDTO> listEventi(String stato, int page, int size) {
        String where = "FROM eventi_da_riconciliare WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)";
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, fonte, chiave_aggancio, data_movimento, importo, tipo, conto_bancario_id, " +
                "descrizione_norm, tipo_evento_presunto, keyword_match, controparte_nome, controparte_iban, " +
                "data_evento_estratta, stato " + where +
                " ORDER BY data_movimento, id LIMIT :size OFFSET :offset")
                .setParameter("stato", stato)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<EventoParcheggiatoDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new EventoParcheggiatoDTO(
                    toUuid(r[0]), (String) r[1], (String) r[2],
                    r[3] == null ? null : ((java.sql.Date) r[3]).toLocalDate(),
                    (BigDecimal) r[4], (String) r[5],
                    r[6] == null ? null : ((Number) r[6]).shortValue(),
                    (String) r[7], (String) r[8], (String) r[9], (String) r[10], (String) r[11],
                    r[12] == null ? null : ((java.sql.Date) r[12]).toLocalDate(), (String) r[13]));
        }

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Risolve una voce-evento: SCARTA (non è un evento), CLASSIFICA (crea un movimento
     * sul COGE/BU scelto) o RICONCILIA (collega a un evento dell'anagrafica). In tutti i
     * casi la riga esce dalla coda DA_RICONCILIARE.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void risolviEvento(UUID eventoParkId, RisolviEventoRequest req, UUID userId) {
        List<Object[]> found = em.createNativeQuery(
                "SELECT import_log_id, fonte, data_movimento, importo, tipo, conto_bancario_id, " +
                "descrizione_norm, stato FROM eventi_da_riconciliare WHERE id = :id")
                .setParameter("id", eventoParkId).getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "EVENTO_NON_TROVATO", "Evento parcheggiato " + eventoParkId);
        }
        Object[] e = found.get(0);
        if (!"DA_RICONCILIARE".equals((String) e[7])) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_GIA_RISOLTO", "La voce è già stata risolta");
        }

        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            case "SCARTA" -> aggiornaStatoEvento(eventoParkId, "SCARTATO", null, req.nota());

            case "CLASSIFICA" -> {
                if (req.cogeId() == null || req.businessUnitId() == null) {
                    throw new ApiException(Response.Status.BAD_REQUEST, "CLASSIFICA_INCOMPLETA",
                            "cogeId e businessUnitId sono obbligatori per classificare l'evento come movimento");
                }
                UUID importLogId = toUuid(e[0]);
                LocalDate data = ((java.sql.Date) e[2]).toLocalDate();
                Short conto = e[5] == null ? null : ((Number) e[5]).shortValue();
                MovimentoCreateRequest createReq = new MovimentoCreateRequest(
                        (String) e[4], (BigDecimal) e[3], null, null, data, data, data, null,
                        conto, metodoBonificoId(), req.businessUnitId(), req.cogeId(), null,
                        null, null, null, (String) e[6], req.nota(), null, (String) e[1], null);
                movimentiService.createMovimentoImport(createReq, userId, importLogId);
                aggiornaStatoEvento(eventoParkId, "RICONCILIATO", null, req.nota());
                mvRefresh.requestRefreshAfterCommit();
            }

            case "RICONCILIA" -> aggiornaStatoEvento(eventoParkId, "RICONCILIATO", req.eventoId(), req.nota());

            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (SCARTA | CLASSIFICA | RICONCILIA)");
        }
    }

    private void aggiornaStatoEvento(UUID id, String stato, UUID eventoId, String nota) {
        em.createNativeQuery(
                "UPDATE eventi_da_riconciliare SET stato = :stato, evento_id = :ev, " +
                "raw_data = jsonb_set(raw_data, '{_nota_triage}', to_jsonb(CAST(:nota AS TEXT)), true) " +
                "WHERE id = :id")
                .setParameter("stato", stato)
                .setParameter("ev", eventoId)
                .setParameter("nota", nota == null ? "" : nota)
                .setParameter("id", id)
                .executeUpdate();
    }

    private Integer metodoBonificoId() {
        List<?> r = em.createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'").getResultList();
        return r.isEmpty() ? null : ((Number) r.get(0)).intValue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String coalesce(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private UUID toUuid(Object o) { return o instanceof UUID u ? u : UUID.fromString(o.toString()); }
}
