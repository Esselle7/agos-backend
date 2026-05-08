-- ============================================================
-- V17 - Separazione data_finanziaria (liquidazione effettiva)
--
-- MODELLO TO-BE:
--   data_movimento    = dataCompetenzaEconomica (sempre valorizzata, partition key)
--   data_finanziaria  = data di liquidazione effettiva (null = DA_LIQUIDARE)
--   data_liquidita    = scadenzaFinanziaria (attesa, obbligatoria se data_finanziaria è null)
--   data_competenza   = alias economica per mv_conto_economico_mensile (= data_movimento)
--
-- STATO:
--   REGISTRATO   = data_finanziaria IS NOT NULL (liquidato)
--   DA_LIQUIDARE = data_finanziaria IS NULL     (in attesa)
--
-- KPI ECONOMICI  → mv_kpi_mensili (usa data_movimento = competenza economica)
-- KPI FINANZIARI → mv_cash_flow_statement (usa data_finanziaria, filtra IS NOT NULL)
-- SALDI          → mv_saldi_conti (usa JOIN su conto_bancario_id, invariato)
-- ============================================================

-- 1. Aggiunge colonna data_finanziaria (nullable)
ALTER TABLE movimenti ADD COLUMN IF NOT EXISTS data_finanziaria DATE;

-- 2. Backfill: movimenti già liquidati (conto_bancario_id valorizzato)
--    data_finanziaria = data_liquidita (se impostata) oppure data_movimento
UPDATE movimenti
SET data_finanziaria = COALESCE(data_liquidita, data_movimento)
WHERE conto_bancario_id IS NOT NULL
  AND stato IN ('REGISTRATO', 'RICONCILIATO')
  AND data_finanziaria IS NULL;

-- 3. Backfill: data_competenza deve essere valorizzata per mv_conto_economico_mensile
--    Per i movimenti nuovi sarà impostata via @PrePersist nel backend.
UPDATE movimenti
SET data_competenza = data_movimento
WHERE data_competenza IS NULL;

-- 4. Ricrea mv_cash_flow_statement usando data_finanziaria
--    (solo movimenti effettivamente liquidati, raggruppati per data finanziaria)
DROP MATERIALIZED VIEW IF EXISTS mv_cash_flow_statement;

CREATE MATERIALIZED VIEW mv_cash_flow_statement AS
SELECT
    EXTRACT(YEAR  FROM m.data_finanziaria)::INT AS anno,
    EXTRACT(MONTH FROM m.data_finanziaria)::INT AS mese,
    m.conto_bancario_id,
    cb.nome                                     AS conto_nome,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE 0 END)   AS entrate_operative,
    SUM(CASE WHEN m.tipo = 'USCITA'  AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE 0 END)   AS uscite_operative,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex AND pc.tipo != 'PASSIVITA'
             THEN m.importo_lordo ELSE -m.importo_lordo END) AS flusso_operativo_netto,
    SUM(CASE WHEN pc.is_capex AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END)   AS uscite_investimento,
    SUM(CASE WHEN pc.tipo = 'PASSIVITA' AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END)   AS uscite_finanziarie,
    SUM(CASE WHEN pc.tipo = 'PASSIVITA' AND m.tipo = 'ENTRATA'
             THEN m.importo_lordo ELSE 0 END)   AS entrate_finanziarie
FROM movimenti m
JOIN conti_bancari        cb ON cb.id = m.conto_bancario_id
JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id
WHERE m.stato != 'ANNULLATO'
  AND m.data_finanziaria IS NOT NULL
GROUP BY 1, 2, 3, 4
WITH DATA;

CREATE UNIQUE INDEX idx_mv_cash_flow
    ON mv_cash_flow_statement (anno, mese, conto_bancario_id);

-- 5. Ricrea mv_riconciliazione_bancaria usando data_finanziaria
DROP MATERIALIZED VIEW IF EXISTS mv_riconciliazione_bancaria;

CREATE MATERIALIZED VIEW mv_riconciliazione_bancaria AS
SELECT
    cb.id                                                            AS conto_id,
    cb.nome                                                          AS conto_nome,
    sb.data_riferimento,
    sb.saldo                                                         AS saldo_reale,
    cb.saldo_iniziale + COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' AND m.data_finanziaria <= sb.data_riferimento
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  AND m.data_finanziaria <= sb.data_riferimento
             THEN -m.importo_lordo
             ELSE 0 END
    ), 0)                                                            AS saldo_calcolato,
    sb.saldo - (cb.saldo_iniziale + COALESCE(SUM(
        CASE WHEN m.tipo = 'ENTRATA' AND m.data_finanziaria <= sb.data_riferimento
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA'  AND m.data_finanziaria <= sb.data_riferimento
             THEN -m.importo_lordo
             ELSE 0 END
    ), 0))                                                           AS scarto
FROM saldi_banca sb
JOIN conti_bancari cb ON cb.id = sb.conto_id
LEFT JOIN movimenti m
       ON m.conto_bancario_id = cb.id
      AND m.stato != 'ANNULLATO'
      AND m.data_finanziaria IS NOT NULL
GROUP BY cb.id, cb.nome, sb.data_riferimento, sb.saldo, cb.saldo_iniziale
WITH DATA;

CREATE UNIQUE INDEX idx_mv_riconciliazione
    ON mv_riconciliazione_bancaria (conto_id, data_riferimento);
