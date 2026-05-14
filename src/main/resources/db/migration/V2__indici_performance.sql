-- ============================================================
-- V2 - INDICI DI PERFORMANCE
-- Pensati per volumi elevati (milioni di movimenti nel tempo)
-- Strategia: B-tree per query filtrate, indici parziali per
-- escludere stati non rilevanti (ANNULLATO), BRIN per date
-- ============================================================

-- ============================================================
-- USERS
-- ============================================================

-- Già garantiti da UNIQUE constraint (creano indici automaticamente),
-- ma li rendiamo espliciti per chiarezza nei piani di query
-- (gli UNIQUE constraint su users li creano già internamente)

-- ============================================================
-- MOVIMENTI (tabella più critica – query di dashboard)
-- ============================================================

-- Query principali: filtro per periodo + BU (dashboard KPI)
CREATE INDEX idx_movimenti_bu_data
    ON movimenti (business_unit_id, data_movimento DESC);

-- Filtro per tipo + periodo (entrate vs uscite nel tempo)
CREATE INDEX idx_movimenti_tipo_data
    ON movimenti (tipo, data_movimento DESC);

-- Movimenti non annullati per riconciliazione (indice parziale)
CREATE INDEX idx_movimenti_attivi
    ON movimenti (data_movimento DESC, conto_bancario_id)
    WHERE stato != 'ANNULLATO';

-- JOIN con eventi (nullable FK – indice parziale solo dove presente)
CREATE INDEX idx_movimenti_evento
    ON movimenti (evento_id)
    WHERE evento_id IS NOT NULL;

-- Idempotenza import: lookup rapido per deduplicazione
CREATE INDEX idx_movimenti_fonte_rif
    ON movimenti (fonte, riferimento_esterno);

-- Saldo per conto bancario (cash flow)
CREATE INDEX idx_movimenti_conto_data
    ON movimenti (conto_bancario_id, data_movimento DESC);

-- Query contabili per coge (P&L, conto economico)
CREATE INDEX idx_movimenti_coge_data
    ON movimenti (conto_coge_id, data_movimento DESC);

-- Query per competenza economica (separata dalla data finanziaria)
CREATE INDEX idx_movimenti_competenza
    ON movimenti (data_competenza DESC)
    WHERE data_competenza IS NOT NULL;

-- Lookup per fornitore (nullable FK – indice parziale)
CREATE INDEX idx_movimenti_fornitore
    ON movimenti (fornitore_id)
    WHERE fornitore_id IS NOT NULL;

-- Lookup per CAPEX / cespiti
CREATE INDEX idx_movimenti_cespite
    ON movimenti (cespite_id)
    WHERE cespite_id IS NOT NULL;

-- Ordine inserimento (audit, ultime operazioni)
CREATE INDEX idx_movimenti_created_at
    ON movimenti (created_at DESC);

-- ============================================================
-- EVENTI
-- ============================================================

CREATE INDEX idx_eventi_data        ON eventi (data_evento DESC);
CREATE INDEX idx_eventi_stato       ON eventi (stato);
CREATE INDEX idx_eventi_bu_data     ON eventi (business_unit_id, data_evento DESC);

-- ============================================================
-- CASSA MOVIMENTI
-- ============================================================

CREATE INDEX idx_cassa_data  ON cassa_movimenti (data_movimento DESC);
CREATE INDEX idx_cassa_bu    ON cassa_movimenti (business_unit_id, data_movimento DESC);

-- ============================================================
-- PIANO DEI CONTI
-- ============================================================

CREATE INDEX idx_coge_tipo    ON piano_dei_conti_coge (tipo);
CREATE INDEX idx_coge_parent  ON piano_dei_conti_coge (parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_coge_capex   ON piano_dei_conti_coge (is_capex)  WHERE is_capex = true;

-- ============================================================
-- CENTRI DI COSTO
-- ============================================================

CREATE INDEX idx_cdc_bu ON centri_di_costo_coan (business_unit_id);

-- ============================================================
-- FORNITORE ALIAS MATCHING
-- ============================================================

CREATE INDEX idx_alias_pattern    ON fornitore_alias_matching (pattern);
CREATE INDEX idx_alias_fornitore  ON fornitore_alias_matching (fornitore_id);

-- ============================================================
-- SALDI BANCA
-- ============================================================

CREATE INDEX idx_saldi_banca_data ON saldi_banca (conto_id, data_riferimento DESC);

-- ============================================================
-- IMPORT LOG
-- ============================================================

CREATE INDEX idx_import_fonte_data ON import_log (fonte, data_import DESC);
CREATE INDEX idx_import_in_corso   ON import_log (stato) WHERE stato = 'IN_CORSO';

-- ============================================================
-- AUDIT LOG (ricerche storiche per tabella/record)
-- ============================================================

CREATE INDEX idx_audit_tabella_record ON audit_log (tabella, record_id);
CREATE INDEX idx_audit_created_at     ON audit_log (created_at DESC);
CREATE INDEX idx_audit_user           ON audit_log (user_id) WHERE user_id IS NOT NULL;

-- ============================================================
-- FORNITORI
-- ============================================================

CREATE INDEX idx_fornitori_piva ON fornitori (piva) WHERE piva IS NOT NULL;
CREATE INDEX idx_fornitori_bu   ON fornitori (bu_default_id) WHERE bu_default_id IS NOT NULL;
