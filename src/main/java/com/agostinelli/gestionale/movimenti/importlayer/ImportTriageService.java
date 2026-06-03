package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
import com.agostinelli.gestionale.movimenti.dto.SuggerimentoControparteDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Backend del triage assistito (ETL v2 §8/§13): KPI di qualità dell'import,
 * suggerimenti di controparte (fuzzy pg_trgm) e CRUD delle regole data-driven.
 * La UI vive nel frontend; qui si espongono solo i servizi.
 */
@ApplicationScoped
public class ImportTriageService {

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;

    // ── KPI (§13) ────────────────────────────────────────────────────────────────
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
        String nome = (String) amb.get(0)[0];
        String iban = (String) amb.get(0)[1];
        List<SuggerimentoControparteDTO> out = new ArrayList<>();

        // Match forte per IBAN (similarità 1.0).
        if (iban != null && !iban.isBlank()) {
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    suggerimentoSelect() + " WHERE c.iban = :iban LIMIT 1")
                    .setParameter("iban", iban).getResultList()) {
                out.add(mapSuggerimento(r, 1.0));
            }
        }
        // Fuzzy per nome (trigrammi) se c'è un nome estratto.
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

    // ── helpers ──────────────────────────────────────────────────────────────────
    private long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String coalesce(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private UUID toUuid(Object o) { return o instanceof UUID u ? u : UUID.fromString(o.toString()); }
}
