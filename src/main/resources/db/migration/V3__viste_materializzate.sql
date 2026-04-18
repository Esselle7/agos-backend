-- ============================================================
-- V3 - VISTE MATERIALIZZATE (Analytics & KPI Layer)
--
-- Tutte le MV hanno un UNIQUE index per supportare
-- REFRESH CONCURRENTLY (non blocca le letture durante il refresh).
--
-- REFRESH: chiamare fn_refresh_all_mv() da un job Quarkus
-- @Scheduled o da pg_cron se disponibile.
-- Su Neon: usare un Quarkus @Scheduled task ogni 30 minuti.
-- ============================================================

-- ============================================================
-- mv_kpi_mensili
-- KPI rapidi per dashboard: entrate/uscite/margine per BU e mese
-- Usa data_movimento (finanziaria) per il cash reporting
-- ============================================================

CREATE MATERIALIZED VIEW mv_kpi_mensili AS
SELECT
    EXTRACT(YEAR  FROM m.data_movimento)::INT AS anno,
    EXTRACT(MONTH FROM m.data_movimento)::INT AS mese,
    m.business_unit_id,
    bu.nome                                   AS business_unit_nome,
    SUM(CASE WHEN m.tipo = 'ENTRATA' THEN m.importo_lordo ELSE 0 END)                        AS totale_entrate,
    SUM(CASE WHEN m.tipo = 'USCITA'  THEN m.importo_lordo ELSE 0 END)                        AS totale_uscite,
    SUM(CASE WHEN m.tipo = 'ENTRATA' THEN m.importo_lordo ELSE -m.importo_lordo END)         AS margine,
    COUNT(*)                                                                                   AS n_movimenti
FROM movimenti m
JOIN business_units bu ON bu.id = m.business_unit_id
WHERE m.stato != 'ANNULLATO'
GROUP BY 1, 2, 3, 4
WITH DATA;

CREATE UNIQUE INDEX idx_mv_kpi_mensili
    ON mv_kpi_mensili (anno, mese, business_unit_id);

-- ============================================================
-- mv_saldi_conti
-- Saldo corrente calcolato dai movimenti per ogni conto
-- ============================================================

CREATE MATERIALIZED VIEW mv_saldi_conti AS
SELECT
    cb.id               AS conto_id,
    cb.nome,
    cb.tipo,
    cb.saldo_iniziale,
    COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  THEN -m.importo_lordo
             ELSE 0 END
    ), 0)               AS movimenti_netti,
    cb.saldo_iniziale + COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  THEN -m.importo_lordo
             ELSE 0 END
    ), 0)               AS saldo_calcolato
FROM conti_bancari cb
LEFT JOIN movimenti m
       ON m.conto_bancario_id = cb.id
      AND m.stato != 'ANNULLATO'
WHERE cb.is_active = true
GROUP BY cb.id, cb.nome, cb.tipo, cb.saldo_iniziale
WITH DATA;

CREATE UNIQUE INDEX idx_mv_saldi_conti ON mv_saldi_conti (conto_id);

-- ============================================================
-- mv_conto_economico_mensile
-- P&L per Anno/Mese/BU/Mastro contabile
-- Usa data_competenza (economica) – NULL esclusi
-- Calcola Ricavi, Costi, Margine, EBITDA approssimato
-- (EBITDA = Margine prima di ammortamenti e oneri finanziari)
-- ============================================================

CREATE MATERIALIZED VIEW mv_conto_economico_mensile AS
SELECT
    EXTRACT(YEAR  FROM m.data_competenza)::INT AS anno,
    EXTRACT(MONTH FROM m.data_competenza)::INT AS mese,
    m.business_unit_id,
    bu.nome                                    AS business_unit_nome,
    pc.codice                                  AS codice_coge,
    pc.descrizione                             AS descrizione_coge,
    pc.tipo                                    AS tipo_coge,
    pc.is_capex,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND pc.tipo = 'RICAVO'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS ricavi,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.tipo = 'COSTO' AND NOT pc.is_capex
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS costi_operativi,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.is_capex
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS investimenti_capex,
    -- Margine operativo lordo (proxy EBITDA escludendo CAPEX e oneri finanziari)
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND pc.tipo = 'RICAVO'
             THEN  COALESCE(m.importo_imponibile, m.importo_lordo)
             WHEN  m.tipo = 'USCITA' AND pc.tipo = 'COSTO' AND NOT pc.is_capex
             THEN -COALESCE(m.importo_imponibile, m.importo_lordo)
             ELSE 0 END)                                                         AS ebitda_proxy,
    COUNT(*)                                                                     AS n_movimenti
FROM movimenti m
JOIN business_units bu         ON bu.id = m.business_unit_id
JOIN piano_dei_conti_coge pc   ON pc.id = m.conto_coge_id
WHERE m.stato             != 'ANNULLATO'
  AND m.data_competenza   IS NOT NULL
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
WITH DATA;

CREATE UNIQUE INDEX idx_mv_conto_eco
    ON mv_conto_economico_mensile (anno, mese, business_unit_id, codice_coge);

-- ============================================================
-- mv_cash_flow_statement
-- Flussi reali per conto bancario separati in:
--   - Operativo (ricavi/costi correnti)
--   - Investimento (CAPEX)
--   - Finanziario (mutui, finanziamenti = tipo PASSIVITA)
-- Usa data_movimento (liquidità effettiva)
-- ============================================================

CREATE MATERIALIZED VIEW mv_cash_flow_statement AS
SELECT
    EXTRACT(YEAR  FROM m.data_movimento)::INT AS anno,
    EXTRACT(MONTH FROM m.data_movimento)::INT AS mese,
    m.conto_bancario_id,
    cb.nome                                   AS conto_nome,
    -- Flussi operativi
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE 0 END) AS entrate_operative,
    SUM(CASE WHEN m.tipo = 'USCITA'  AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE 0 END) AS uscite_operative,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE -m.importo_lordo END) AS flusso_operativo_netto,
    -- Flussi di investimento (CAPEX)
    SUM(CASE WHEN pc.is_capex AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END) AS uscite_investimento,
    -- Flussi finanziari (mutui, rate, finanziamenti)
    SUM(CASE WHEN pc.tipo = 'PASSIVITA' AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END) AS uscite_finanziarie,
    SUM(CASE WHEN pc.tipo = 'PASSIVITA' AND m.tipo = 'ENTRATA'
             THEN m.importo_lordo ELSE 0 END) AS entrate_finanziarie
FROM movimenti m
JOIN conti_bancari          cb ON cb.id = m.conto_bancario_id
JOIN piano_dei_conti_coge   pc ON pc.id = m.conto_coge_id
WHERE m.stato != 'ANNULLATO'
GROUP BY 1, 2, 3, 4
WITH DATA;

CREATE UNIQUE INDEX idx_mv_cash_flow
    ON mv_cash_flow_statement (anno, mese, conto_bancario_id);

-- ============================================================
-- mv_redditivita_eventi
-- Per ogni evento: ricavi incassati, costi diretti, margine netto
-- e percentuale di incasso sul preventivato
-- ============================================================

CREATE MATERIALIZED VIEW mv_redditivita_eventi AS
SELECT
    e.id                            AS evento_id,
    e.nome                          AS evento_nome,
    e.tipo                          AS evento_tipo,
    e.data_evento,
    e.stato,
    e.importo_totale_preventivato,
    e.n_ospiti,
    COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA'
              AND m.tipo_evento_movimento IN ('CAPARRA', 'ACCONTO', 'SALDO')
             THEN m.importo_lordo ELSE 0 END
    ), 0)                           AS ricavi_incassati,
    COALESCE(SUM(
        CASE WHEN m.tipo = 'USCITA' THEN m.importo_lordo ELSE 0 END
    ), 0)                           AS costi_diretti,
    COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA'
              AND m.tipo_evento_movimento IN ('CAPARRA', 'ACCONTO', 'SALDO')
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'
             THEN -m.importo_lordo
             ELSE 0 END
    ), 0)                           AS margine_netto,
    CASE
        WHEN e.importo_totale_preventivato > 0
        THEN ROUND(
            COALESCE(SUM(
                CASE WHEN m.tipo = 'ENTRATA'
                      AND m.tipo_evento_movimento IN ('CAPARRA', 'ACCONTO', 'SALDO')
                     THEN m.importo_lordo ELSE 0 END
            ), 0) / e.importo_totale_preventivato * 100, 2)
        ELSE NULL
    END                             AS perc_incassato
FROM eventi e
LEFT JOIN movimenti m ON m.evento_id = e.id AND m.stato != 'ANNULLATO'
GROUP BY e.id, e.nome, e.tipo, e.data_evento, e.stato,
         e.importo_totale_preventivato, e.n_ospiti
WITH DATA;

CREATE UNIQUE INDEX idx_mv_redditivita_eventi ON mv_redditivita_eventi (evento_id);

-- ============================================================
-- mv_riconciliazione_bancaria
-- Confronto tra saldo calcolato dai movimenti e saldo reale
-- importato (tabella saldi_banca) – evidenzia scarti
-- ============================================================

CREATE MATERIALIZED VIEW mv_riconciliazione_bancaria AS
SELECT
    cb.id                                                            AS conto_id,
    cb.nome                                                          AS conto_nome,
    sb.data_riferimento,
    sb.saldo                                                         AS saldo_reale,
    cb.saldo_iniziale + COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' AND m.data_movimento <= sb.data_riferimento
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  AND m.data_movimento <= sb.data_riferimento
             THEN -m.importo_lordo
             ELSE 0 END
    ), 0)                                                            AS saldo_calcolato,
    sb.saldo - (cb.saldo_iniziale + COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' AND m.data_movimento <= sb.data_riferimento
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  AND m.data_movimento <= sb.data_riferimento
             THEN -m.importo_lordo
             ELSE 0 END
    ), 0))                                                           AS scarto
FROM saldi_banca sb
JOIN conti_bancari cb ON cb.id = sb.conto_id
LEFT JOIN movimenti m
       ON m.conto_bancario_id = cb.id
      AND m.stato != 'ANNULLATO'
GROUP BY cb.id, cb.nome, sb.data_riferimento, sb.saldo, cb.saldo_iniziale
WITH DATA;

CREATE UNIQUE INDEX idx_mv_riconciliazione
    ON mv_riconciliazione_bancaria (conto_id, data_riferimento);

-- ============================================================
-- FUNZIONE DI REFRESH
-- Chiamata da Quarkus @Scheduled ogni 30 minuti
-- (CONCURRENTLY non blocca le letture durante il refresh)
-- ============================================================

CREATE OR REPLACE FUNCTION fn_refresh_all_mv()
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_kpi_mensili;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_saldi_conti;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_conto_economico_mensile;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_cash_flow_statement;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_redditivita_eventi;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_riconciliazione_bancaria;
END;
$$;

-- ============================================================
-- NOTA PER pg_cron (se disponibile sull'istanza PostgreSQL):
--
-- SELECT cron.schedule(
--     'refresh-mv-agostinelli',
--     '*/30 * * * *',
--     'SELECT fn_refresh_all_mv()'
-- );
--
-- Su Neon (serverless) preferire Quarkus @Scheduled:
--
-- @Scheduled(every = "30m")
-- void refreshMv() {
--     entityManager.createNativeQuery("SELECT fn_refresh_all_mv()").getSingleResult();
-- }
-- ============================================================
