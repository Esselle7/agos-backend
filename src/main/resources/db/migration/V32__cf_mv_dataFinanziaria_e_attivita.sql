-- ============================================================
-- V32 — Cash Flow MV: ripristino raggruppamento per data_finanziaria
--       (regressione introdotta da V29) ed esclusione conti ATTIVITA
--       (giroconti, liquidità, crediti) dal flusso operativo.
--
-- REGR-1 (data_finanziaria):
--   V17__data_finanziaria.sql aveva impostato:
--     EXTRACT(... FROM m.data_finanziaria) + WHERE m.data_finanziaria IS NOT NULL
--   con la motivazione esplicita "KPI FINANZIARI usano data_finanziaria".
--   V29__pl_waterfall_e_split_rata.sql ha ricreato la MV per aggiungere il
--   branch ONERE_FINANZIARIO ma è tornata accidentalmente a data_movimento
--   senza il filtro IS NOT NULL — perdendo entrambi i fix V17.
--   Conseguenza: i movimenti DA_LIQUIDARE non venivano filtrati (anche se in
--   pratica venivano comunque eliminati dall'INNER JOIN su conti_bancari, dato
--   che conto_bancario_id è NULL su DA_LIQUIDARE) e — soprattutto — il CF era
--   raggruppato per mese di competenza economica anziché per mese di
--   liquidazione finanziaria, rendendo inutilizzabile la separazione P&L/CF.
--
-- REGR-2 (giroconti ATTIVITA):
--   I conti del mastro 10 (ATTIVITA: Liquidità 10.01, Crediti 10.02, Giroconti
--   interni 10.03) passavano il filtro `tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO')`
--   e venivano sommati in entrate/uscite_operative del CF. CassaService genera
--   movimenti su 10.03.001/10.03.002 per ogni PRELIEVO/VERSAMENTO cassa↔banca:
--   contarli come operativi gonfia artificialmente il flusso operativo netto.
--   Aggiungiamo 'ATTIVITA' all'esclusione su entrate_operative, uscite_operative
--   e flusso_operativo_netto.
--
-- INVARIANTI MANTENUTE da V29:
--   - branch ONERE_FINANZIARIO + PASSIVITA su uscite_finanziarie / entrate_finanziarie
--   - uscite_investimento = USCITA + is_capex
--
-- NOTA: nel ricalcolo di flusso_operativo_netto introduciamo un ramo esplicito
-- per le USCITA operative (era implicitamente in ELSE in V17/V29, che però
-- contava come uscita anche capex/passivita/attivita — bug subdolo). La nuova
-- formula a tre rami (ENTRATA op → +, USCITA op → −, ELSE → 0) è coerente con
-- entrate_operative e uscite_operative.
-- ============================================================

DROP MATERIALIZED VIEW IF EXISTS mv_cash_flow_statement CASCADE;

CREATE MATERIALIZED VIEW mv_cash_flow_statement AS
SELECT
    EXTRACT(YEAR  FROM m.data_finanziaria)::INT AS anno,
    EXTRACT(MONTH FROM m.data_finanziaria)::INT AS mese,
    m.conto_bancario_id,
    cb.nome                                     AS conto_nome,

    -- Entrate operative: ricavi e altre entrate non finanziarie / non capex.
    -- Esclude ATTIVITA (giroconti interni, liquidità, crediti — non sono cash flow).
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO','ATTIVITA')
             THEN m.importo_lordo ELSE 0 END) AS entrate_operative,

    -- Uscite operative: costi e imposte (non capex, non finanziarie, non giroconti).
    SUM(CASE WHEN m.tipo = 'USCITA'  AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO','ATTIVITA')
             THEN m.importo_lordo ELSE 0 END) AS uscite_operative,

    -- Flusso operativo netto = entrate_operative − uscite_operative.
    -- Tre rami espliciti per coerenza con le colonne sopra (V29 aveva un ELSE
    -- catch-all che includeva impropriamente capex/passività/attività).
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO','ATTIVITA')
             THEN  m.importo_lordo
             WHEN m.tipo = 'USCITA' AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO','ATTIVITA')
             THEN -m.importo_lordo
             ELSE 0 END) AS flusso_operativo_netto,

    -- Investimenti: solo uscite su conti is_capex.
    SUM(CASE WHEN pc.is_capex AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END)   AS uscite_investimento,

    -- Uscite finanziarie: rimborso capitale (PASSIVITA) + interessi (ONERE_FINANZIARIO).
    SUM(CASE WHEN pc.tipo IN ('PASSIVITA','ONERE_FINANZIARIO') AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END)   AS uscite_finanziarie,

    -- Entrate finanziarie: erogazione finanziamenti / rimborsi positivi su debito.
    SUM(CASE WHEN pc.tipo IN ('PASSIVITA','ONERE_FINANZIARIO') AND m.tipo = 'ENTRATA'
             THEN m.importo_lordo ELSE 0 END)   AS entrate_finanziarie
FROM movimenti m
JOIN conti_bancari          cb ON cb.id = m.conto_bancario_id
JOIN piano_dei_conti_coge   pc ON pc.id = m.conto_coge_id
WHERE m.stato != 'ANNULLATO'
  AND m.data_finanziaria IS NOT NULL
GROUP BY 1, 2, 3, 4
WITH DATA;

CREATE UNIQUE INDEX idx_mv_cash_flow
    ON mv_cash_flow_statement (anno, mese, conto_bancario_id);
