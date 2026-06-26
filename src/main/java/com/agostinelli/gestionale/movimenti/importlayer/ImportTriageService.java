package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.EventoParcheggiatoDTO;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.QuadraturaPeriodoDTO;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
import com.agostinelli.gestionale.movimenti.dto.RicorrenteParcheggiataDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest;
import com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.movimenti.dto.AnalisiDuplicatiDTO;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Backend del triage assistito (ETL v2 §8/§13): KPI di qualità dell'import,
 * CRUD regole data-driven, e il "centro di smistamento" dei movimenti su conti
 * transitori e degli eventi parcheggiati. La catalogazione può apprendere KEYWORD
 * (PROMPT-KEYWORD-LEARNING.md §4.4). La UI vive nel frontend; qui solo i servizi.
 */
@ApplicationScoped
public class ImportTriageService {

    private static final String COGE_RICAVI_DACLASS = "39.99.999";
    private static final String COGE_COSTI_DACLASS = "49.99.999";

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;
    @Inject MovimentiService movimentiService;
    @Inject MvRefreshService mvRefresh;
    @Inject KeywordLearningService keywordLearning;
    @Inject com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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

        // Transitori divisi per natura: il "saldo" complessivo è una somma LORDA (entrate+uscite),
        // utile solo come volume; i numeri sensati sono ricavi-da-classificare e costi-da-classificare.
        Object[] tr = (Object[]) em.createNativeQuery(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE p.codice = '39.99.999'), " +
                "  COALESCE(SUM(m.importo_lordo) FILTER (WHERE p.codice = '39.99.999'),0), " +
                "  COUNT(*) FILTER (WHERE p.codice = '49.99.999'), " +
                "  COALESCE(SUM(m.importo_lordo) FILTER (WHERE p.codice = '49.99.999'),0) " +
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice IN ('39.99.999','49.99.999') AND m.stato <> 'ANNULLATO'")
                .getSingleResult();
        long ricaviTransitoriCount = num(tr[0]);
        BigDecimal ricaviDaClassificare = tr[1] == null ? BigDecimal.ZERO : (BigDecimal) tr[1];
        long costiTransitoriCount = num(tr[2]);
        BigDecimal costiDaClassificare = tr[3] == null ? BigDecimal.ZERO : (BigDecimal) tr[3];
        long transitoriCount = ricaviTransitoriCount + costiTransitoriCount;
        BigDecimal saldoTransitori = ricaviDaClassificare.add(costiDaClassificare);

        // Copertura fornitori: si escludono dal denominatore le uscite che per NATURA non hanno
        // un fornitore (spese/commissioni bancarie 40.02.*, tributi F24, metodo ADDEBITO_CONTO):
        // includerle abbasserebbe artificiosamente la percentuale (ANALISI-IMPORT Fix copertura).
        Object[] f = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*) FILTER (WHERE m.fornitore_id IS NOT NULL), COUNT(*) " +
                "FROM movimenti m " +
                "JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "LEFT JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id " +
                "WHERE m.tipo = 'USCITA' AND m.fonte_importazione_id IS NOT NULL AND m.stato <> 'ANNULLATO' " +
                "AND p.codice NOT LIKE '40.02.%' " +
                "AND COALESCE(mp.codice,'') NOT IN ('ADDEBITO_CONTO','F24')")
                .getSingleResult();
        long usciteConFornitore = num(f[0]), usciteTot = num(f[1]);

        double tassoAmbiguita = totali == 0 ? 0 : (ambigue * 100.0) / totali;
        double copertura = usciteTot == 0 ? 0 : (usciteConFornitore * 100.0) / usciteTot;

        return new ImportKpiDTO(totali, importate, ambigue, scartate, parcheggiate,
                transitoriCount, saldoTransitori,
                round2(tassoAmbiguita), round2(copertura),
                ricaviTransitoriCount, ricaviDaClassificare,
                costiTransitoriCount, costiDaClassificare);
    }

    // I suggerimenti basati sulla rubrica controparti (IBAN/fuzzy) sono rimossi con la dismissione
    // della tabella controparti (V7): l'auto-riconoscimento è ora a keyword (auto-catalogazione in
    // import) e la catalogazione manuale resta assistita dall'anagrafica fornitori/COGE.

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
        // "Da catalogare" mostra i transitori GENERICI: esclude solo ciò che ha una sezione dedicata
        // (effetti/RiBa → "Effetti/RiBa") così ogni riga compare una volta sola e i badge sono
        // disgiunti. Gli incassi POS non finiscono più sul transitorio (sono categorizzati da Billy):
        // la vecchia esclusione POS è rimossa, così un ricavo Billy con categoria non determinabile
        // (raro) resta visibile qui invece di sparire.
        String where =
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice IN ('" + COGE_RICAVI_DACLASS + "','" + COGE_COSTI_DACLASS + "') " +
                "AND m.stato <> 'ANNULLATO' AND (CAST(:tipo AS VARCHAR) IS NULL OR m.tipo = :tipo) " +
                "AND NOT (m.tipo = 'USCITA' AND (m.descrizione ILIKE '%EFFETTI%' OR m.descrizione ILIKE '%RIBA%'))";

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

        if (req.apprendiKeyword()) {
            EntitaEstratte ent = estraiEntita(m.descrizione, m.contoBancarioId);
            keywordLearning.apprendi(m.descrizione, ent, m.tipo, req.businessUnitId(), req.cogeId(),
                    req.fornitoreId(), movimentoId, null);
        }

        mvRefresh.requestRefreshAfterCommit();
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
     * Analizza la coda DA_RICONCILIARE alla ricerca di coppie di eventi che il matcher
     * giudica sospette duplicate (CERTA o PROBABILE), con punteggio e motivazioni
     * leggibili. Espone la stessa logica usata in import per l'auto-aggancio, qui in
     * sola lettura: utile per rivedere a mano i casi che il sistema NON ha unito da solo
     * (più candidati ambigui) o per audit.
     */
    public AnalisiDuplicatiDTO analisiDuplicati() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, fonte, data_movimento, importo, tipo, controparte_nome, " +
                "controparte_iban, data_evento_estratta, tipo_evento_presunto, descrizione_norm " +
                "FROM eventi_da_riconciliare WHERE stato = 'DA_RICONCILIARE' " +
                "ORDER BY importo, tipo, data_movimento, id").getResultList();

        int n = rows.size();
        List<AnalisiDuplicatiDTO.EventoBreveDTO> dto = new ArrayList<>(n);
        List<EventoMatcher.Segnali> seg = new ArrayList<>(n);
        for (Object[] r : rows) {
            LocalDate dm = r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate();
            BigDecimal imp = (BigDecimal) r[3];
            String tipo = (String) r[4], nome = (String) r[5], iban = (String) r[6];
            LocalDate ev = r[7] == null ? null : ((java.sql.Date) r[7]).toLocalDate();
            String tipoEv = (String) r[8], descr = (String) r[9];
            dto.add(new AnalisiDuplicatiDTO.EventoBreveDTO(
                    toUuid(r[0]), (String) r[1], dm, imp, tipo, nome, iban, ev, tipoEv, descr));
            seg.add(new EventoMatcher.Segnali(imp, tipo, dm, nome, iban, ev, tipoEv));
        }

        List<AnalisiDuplicatiDTO.CoppiaSospettaDTO> coppie = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            EventoMatcher.Segnali a = seg.get(i);
            if (a.importo() == null) continue;
            for (int j = i + 1; j < n; j++) {
                EventoMatcher.Segnali b = seg.get(j);
                if (b.importo() == null || a.importo().compareTo(b.importo()) != 0) continue;
                if (!Objects.equals(a.tipo(), b.tipo())) continue;
                if (a.dataMovimento() != null && b.dataMovimento() != null
                        && Math.abs(ChronoUnit.DAYS.between(a.dataMovimento(), b.dataMovimento())) > EventoMatcher.GIORNI_FINESTRA) {
                    continue;
                }
                EventoMatcher.Spiegazione sp = EventoMatcher.spiega(a, b);
                if (sp.esito() == EventoMatcher.Esito.NESSUNO) continue;
                List<AnalisiDuplicatiDTO.MotivoDTO> motivi = new ArrayList<>(sp.motivi().size());
                for (EventoMatcher.Motivo m : sp.motivi()) {
                    motivi.add(new AnalisiDuplicatiDTO.MotivoDTO(m.segnale(), m.dettaglio(), m.tono().name()));
                }
                coppie.add(new AnalisiDuplicatiDTO.CoppiaSospettaDTO(
                        sp.esito().name(), sp.punteggio(), dto.get(i), dto.get(j), motivi));
            }
        }
        coppie.sort((x, y) -> Integer.compare(y.punteggio(), x.punteggio()));
        return new AnalisiDuplicatiDTO(n, coppie.size(), coppie);
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

    // ── Parcheggio spese ricorrenti / finanziamenti (V9) ──────────────────────────

    /** Coda delle spese ricorrenti parcheggiate (non contabilizzate): da riconciliare a mano. */
    @SuppressWarnings("unchecked")
    public PagedResponse<RicorrenteParcheggiataDTO> listRicorrenti(String stato, int page, int size) {
        String where = "FROM ricorrenti_da_riconciliare WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)";
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, fonte, data_movimento, importo, tipo, conto_bancario_id, descrizione_norm, " +
                "tipo_presunto, recurring_plan_id, stato " + where +
                " ORDER BY data_movimento, id LIMIT :size OFFSET :offset")
                .setParameter("stato", stato).setParameter("size", size).setParameter("offset", (long) page * size)
                .getResultList();
        List<RicorrenteParcheggiataDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new RicorrenteParcheggiataDTO(
                    toUuid(r[0]), (String) r[1],
                    r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate(),
                    (BigDecimal) r[3], (String) r[4],
                    r[5] == null ? null : ((Number) r[5]).shortValue(),
                    (String) r[6], (String) r[7],
                    r[8] == null ? null : toUuid(r[8]), (String) r[9]));
        }
        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Riconcilia una ricorrente parcheggiata: COLLEGA a un piano ricorrente (nessun effetto
     * contabile, il modulo Spese Ricorrenti resta la fonte di verità) oppure IGNORA.
     */
    @Transactional
    public void risolviRicorrente(UUID id, RisolviRicorrenteRequest req, UUID userId) {
        List<?> found = em.createNativeQuery(
                "SELECT stato FROM ricorrenti_da_riconciliare WHERE id = :id").setParameter("id", id).getResultList();
        if (found.isEmpty()) throw new ApiException(Response.Status.NOT_FOUND, "RICORRENTE_NON_TROVATA", "Ricorrente " + id);
        if (!"DA_RICONCILIARE".equals(found.get(0))) {
            throw new ApiException(Response.Status.CONFLICT, "RICORRENTE_GIA_RISOLTA", "La voce è già stata risolta");
        }
        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            case "COLLEGA" -> {
                if (req.recurringPlanId() == null) {
                    throw new ApiException(Response.Status.BAD_REQUEST, "PIANO_OBBLIGATORIO",
                            "Seleziona il piano ricorrente a cui collegare la riga");
                }
                em.createNativeQuery(
                        "UPDATE ricorrenti_da_riconciliare SET stato = 'RICONCILIATA', recurring_plan_id = :pid, " +
                        "note = :nota, risolto_at = now(), risolto_by = :uid WHERE id = :id")
                        .setParameter("pid", req.recurringPlanId()).setParameter("nota", req.nota())
                        .setParameter("uid", userId).setParameter("id", id).executeUpdate();
            }
            case "IGNORA" -> em.createNativeQuery(
                    "UPDATE ricorrenti_da_riconciliare SET stato = 'IGNORATA', note = :nota, " +
                    "risolto_at = now(), risolto_by = :uid WHERE id = :id")
                    .setParameter("nota", req.nota()).setParameter("uid", userId).setParameter("id", id).executeUpdate();
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (COLLEGA | IGNORA)");
        }
    }

    /**
     * Vista dedicata Effetti/RiBa: i pagamenti a ricevuta bancaria finiscono sul transitorio costi
     * (49.99.999) perché la descrizione non nomina il fornitore. Qui si filtrano per catalogarli
     * rapidamente (riusa {@link #classificaTransitorio}). Sono movimenti USCITA su 49.99.999 la cui
     * descrizione contiene EFFETTI o RIBA.
     */
    @SuppressWarnings("unchecked")
    public PagedResponse<TransitorioDTO> listRibaTransitori(int page, int size) {
        String where =
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice = '" + COGE_COSTI_DACLASS + "' AND m.stato <> 'ANNULLATO' AND m.tipo = 'USCITA' " +
                "AND (m.descrizione ILIKE '%EFFETTI%' OR m.descrizione ILIKE '%RIBA%')";
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.id, m.tipo, m.importo_lordo, m.data_movimento, m.descrizione, p.codice, " +
                "m.fornitore_id, m.conto_bancario_id " + where +
                " ORDER BY m.data_movimento, m.id LIMIT :size OFFSET :offset")
                .setParameter("size", size).setParameter("offset", (long) page * size).getResultList();
        List<TransitorioDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String descr = (String) r[4];
            Short conto = r[7] == null ? null : ((Number) r[7]).shortValue();
            EntitaEstratte ent = estraiEntita(descr, conto);
            content.add(new TransitorioDTO(
                    toUuid(r[0]), (String) r[1], (BigDecimal) r[2],
                    ((java.sql.Date) r[3]).toLocalDate(), descr, (String) r[5],
                    r[6] == null ? null : toUuid(r[6]), conto,
                    ent.ibanControparte(), ent.beneficiario() != null ? ent.beneficiario() : ent.ordinante()));
        }
        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Pannello di quadratura di periodo (PROMPT-RICONCILIAZIONE-PERIODO §5): sostituisce la
     * vecchia vista "Incassi POS da ripartire" a scontrino. Restituisce la quadratura dell'ultimo
     * import congiunto (o di {@code importLogId} se valorizzato): Σ Billy ↔ Σ POS banca scomposto
     * per causa (coda testa esclusa, coda fondo in attesa, residuo core). Informativo: i ricavi
     * sono comunque contabilizzati da Billy. Null se non c'è ancora nessuna quadratura.
     */
    @SuppressWarnings("unchecked")
    public QuadraturaPeriodoDTO getQuadratura(UUID importLogId) {
        String where = importLogId == null ? "" : " WHERE import_log_id = :id";
        var qy = em.createNativeQuery(
                "SELECT import_log_id, created_at, anno, billy_elettronico_non_agri, billy_contabilizzato, " +
                "pos_banca_totale, pos_banca_core, sigma_bpm, sigma_ca, assegnato_bpm, assegnato_ca, " +
                // NB: CAST(... AS text), NON '::text' → in una query native Hibernate i ':' sono
                // marcatori di parametro e '::' rompe il parser (syntax error at or near ":").
                "coda_testa, coda_fondo, residuo_core, max_del_banca, CAST(note AS text), CAST(in_attesa AS text) " +
                "FROM quadratura_periodo" + where + " ORDER BY created_at DESC LIMIT 1");
        if (importLogId != null) qy.setParameter("id", importLogId);
        List<Object[]> rows = qy.getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);

        List<String> note = jsonToStringList((String) r[15]);
        List<QuadraturaPeriodoDTO.InAttesaDTO> attesa = jsonToInAttesa((String) r[16]);
        List<String> approssimazioni = buildApprossimazioni(
                (BigDecimal) r[7], (BigDecimal) r[8], (BigDecimal) r[9], (BigDecimal) r[10], (BigDecimal) r[13]);
        return new QuadraturaPeriodoDTO(
                toUuid(r[0]),
                toLocalDate(r[1]),   // created_at (timestamptz): il driver lo dà come Instant, non Timestamp
                ((Number) r[2]).intValue(),
                (BigDecimal) r[3], (BigDecimal) r[4], (BigDecimal) r[5], (BigDecimal) r[6],
                (BigDecimal) r[7], (BigDecimal) r[8], (BigDecimal) r[9], (BigDecimal) r[10],
                (BigDecimal) r[11], (BigDecimal) r[12], (BigDecimal) r[13],
                toLocalDate(r[14]),  // max_del_banca (date)
                note, approssimazioni, attesa);
    }

    /**
     * Approssimazioni dichiarate del metodo (PROMPT-RICONCILIAZIONE-PERIODO): vengono mostrate
     * ESPLICITAMENTE all'utente nel pannello, così sa esattamente cosa è una convenzione e cosa
     * uno scarto atteso (non un errore). Calcolate dai numeri persistiti della quadratura.
     */
    private List<String> buildApprossimazioni(BigDecimal sigmaBpm, BigDecimal sigmaCa,
                                              BigDecimal assBpm, BigDecimal assCa, BigDecimal residuo) {
        BigDecimal sigmaTot = sigmaBpm.add(sigmaCa);
        BigDecimal assTot = assBpm.add(assCa);
        BigDecimal deltaBpm = assBpm.subtract(sigmaBpm);
        BigDecimal deltaCa = assCa.subtract(sigmaCa);
        String pct = sigmaTot.signum() == 0 ? "0"
                : BigDecimal.ONE.subtract(assTot.divide(sigmaTot, 6, java.math.RoundingMode.HALF_UP))
                        .multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        List<String> a = new ArrayList<>();
        a.add("Attribuzione ricavo↔conto CONVENZIONALE: non si sa quale banca/carta abbia incassato "
                + "ogni scontrino (le righe banca sono aggregate per circuito). I ricavi sono distribuiti "
                + "per far quadrare i TOTALI di periodo, non la singola transazione.");
        a.add("Ripartizione PROPORZIONALE: BPM e CA risultano entrambe ~" + pct + "% sotto il rispettivo "
                + "POS lordo (BPM Δ " + deltaBpm.toPlainString() + " €, CA Δ " + deltaCa.toPlainString() + " €). "
                + "Quello scarto è il NON-spaccio (eventi a POS, Satispay, storni), NON un errore di import.");
        a.add("Granularità scontrino: uno scontrino non si spezza tra due conti, quindi i totali per "
                + "banca non centrano il target al centesimo.");
        a.add("Anche la CATEGORIA attribuita a ciascun conto è convenzionale: deriva dallo scontrino "
                + "Billy, non dalla riga banca.");
        a.add("Residuo core " + residuo.toPlainString() + " € INFORMATIVO: differenza Billy↔banca da "
                + "agriturismo incassato a POS, Satispay (netto banca vs lordo Billy ~1%) e storni. "
                + "Non è contabilizzato come spaccio.");
        return a;
    }

    /** Converte un valore temporale JDBC (Instant/OffsetDateTime/Timestamp/Date/Local*) in LocalDate. */
    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.time.Instant i) return i.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private List<String> jsonToStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<QuadraturaPeriodoDTO.InAttesaDTO> jsonToInAttesa(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<java.util.Map<String, String>> raw = objectMapper.readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {});
            List<QuadraturaPeriodoDTO.InAttesaDTO> out = new ArrayList<>(raw.size());
            for (var m : raw) {
                LocalDate d = m.get("data") == null || "null".equals(m.get("data")) ? null : LocalDate.parse(m.get("data"));
                BigDecimal imp = m.get("importo") == null ? null : new BigDecimal(m.get("importo"));
                out.add(new QuadraturaPeriodoDTO.InAttesaDTO(d, imp, m.get("rif"), m.get("descrizione")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String coalesce(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private UUID toUuid(Object o) { return o instanceof UUID u ? u : UUID.fromString(o.toString()); }
}
