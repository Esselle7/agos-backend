-- ============================================================
-- V29 - P&L Waterfall e Split Rata Finanziamento
-- Aggiunge: ONERE_FINANZIARIO, IMPOSTA (tipi COGE)
--           Mastri 60 (oneri finanziari) e 70 (imposte)
--           Campi piano di ammortamento su recurring_expense_plan
--           Quote capitale/interessi su recurring_expense_installment
--           MV aggiornate con nuove colonne oneri_finanziari e imposte
-- ============================================================

-- ── 1a. Nuovi tipi COGE ──────────────────────────────────────
INSERT INTO lk_tipi_coge (codice, descrizione) VALUES
    ('ONERE_FINANZIARIO', 'Onere finanziario – interessi passivi su finanziamenti'),
    ('IMPOSTA',           'Imposta / tributo (IRAP, IRPEF, IRES)');

-- ── 1b. Mastro 60 – ONERI FINANZIARI ─────────────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex) VALUES
    ('60', 'ONERI FINANZIARI', 'ONERE_FINANZIARIO', false);

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('60.01', 'Interessi passivi su finanziamenti', 'ONERE_FINANZIARIO', false,
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '60'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('60.01.001', 'Interessi – mutuo ipotecario',                'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01')),
    ('60.01.002', 'Interessi – finanziamento Regione Lombardia', 'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01')),
    ('60.01.003', 'Interessi – ISMEA',                           'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01')),
    ('60.01.004', 'Interessi – Fidicomptur',                     'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01')),
    ('60.01.005', 'Interessi – Merlo (leasing)',                 'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01')),
    ('60.01.006', 'Interessi – Asconfidi (40k)',                 'ONERE_FINANZIARIO', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '60.01'));

-- ── 1b. Mastro 70 – IMPOSTE ──────────────────────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex) VALUES
    ('70', 'IMPOSTE E TRIBUTI', 'IMPOSTA', false);

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('70.01', 'IRAP',        'IMPOSTA', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '70')),
    ('70.02', 'IRPEF / IRES','IMPOSTA', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '70'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('70.01.001', 'IRAP corrente',        'IMPOSTA', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '70.01')),
    ('70.02.001', 'IRPEF / IRES corrente','IMPOSTA', false, (SELECT id FROM piano_dei_conti_coge WHERE codice = '70.02'));

-- ── 1c. Alter recurring_expense_plan ─────────────────────────
ALTER TABLE recurring_expense_plan
    ADD COLUMN tipo_piano              VARCHAR(20)  NOT NULL DEFAULT 'FLAT'
                                       CHECK (tipo_piano IN ('FLAT','FINANZIAMENTO')),
    ADD COLUMN importo_debito_iniziale NUMERIC(12,2),
    ADD COLUMN tasso_interesse_annuo   NUMERIC(8,5),
    ADD COLUMN conto_coge_interessi_id INTEGER
                                       REFERENCES piano_dei_conti_coge(id);

-- ── 1d. Alter recurring_expense_installment ──────────────────
ALTER TABLE recurring_expense_installment
    ADD COLUMN quota_capitale         NUMERIC(12,2),
    ADD COLUMN quota_interessi        NUMERIC(12,2),
    ADD COLUMN movimento_interessi_id UUID;

-- ── 1e. Ricrea mv_conto_economico_mensile ─────────────────────
DROP MATERIALIZED VIEW IF EXISTS mv_conto_economico_mensile CASCADE;

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
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.tipo = 'ONERE_FINANZIARIO'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS oneri_finanziari,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.tipo = 'IMPOSTA'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS imposte,
    -- ebitda_proxy: ricavi - costi operativi (esclude capex, oneri finanziari, imposte, passività)
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

-- ── 1f. Ricrea mv_cash_flow_statement ────────────────────────
DROP MATERIALIZED VIEW IF EXISTS mv_cash_flow_statement CASCADE;

CREATE MATERIALIZED VIEW mv_cash_flow_statement AS
SELECT
    EXTRACT(YEAR  FROM m.data_movimento)::INT AS anno,
    EXTRACT(MONTH FROM m.data_movimento)::INT AS mese,
    m.conto_bancario_id,
    cb.nome                                   AS conto_nome,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO')
             THEN m.importo_lordo ELSE 0 END) AS entrate_operative,
    SUM(CASE WHEN m.tipo = 'USCITA'  AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO')
             THEN m.importo_lordo ELSE 0 END) AS uscite_operative,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND NOT pc.is_capex
                  AND pc.tipo NOT IN ('PASSIVITA','ONERE_FINANZIARIO')
             THEN m.importo_lordo ELSE -m.importo_lordo END) AS flusso_operativo_netto,
    SUM(CASE WHEN pc.is_capex AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END) AS uscite_investimento,
    -- uscite_finanziarie = rimborso capitale (PASSIVITA) + interessi (ONERE_FINANZIARIO)
    SUM(CASE WHEN pc.tipo IN ('PASSIVITA','ONERE_FINANZIARIO') AND m.tipo = 'USCITA'
             THEN m.importo_lordo ELSE 0 END) AS uscite_finanziarie,
    SUM(CASE WHEN pc.tipo IN ('PASSIVITA','ONERE_FINANZIARIO') AND m.tipo = 'ENTRATA'
             THEN m.importo_lordo ELSE 0 END) AS entrate_finanziarie
FROM movimenti m
JOIN conti_bancari          cb ON cb.id = m.conto_bancario_id
JOIN piano_dei_conti_coge   pc ON pc.id = m.conto_coge_id
WHERE m.stato != 'ANNULLATO'
GROUP BY 1, 2, 3, 4
WITH DATA;

CREATE UNIQUE INDEX idx_mv_cash_flow
    ON mv_cash_flow_statement (anno, mese, conto_bancario_id);
