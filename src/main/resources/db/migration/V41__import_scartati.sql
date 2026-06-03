-- ============================================================
-- V41 - Tabella import_scartati (ETL_CLASSIFICAZIONE_v2 §4/§9.4)
--
-- Raccoglie le righe ESCLUSE dall'import dal Gate A (SKIP_*) in forma
-- tracciata e reversibile. NON sono errori e NON sono ambiguità:
--   * SKIP_POS        → incasso POS/Satispay duplicato di Billy
--   * SKIP_GIROCONTO  → trasferimento interno tra conti propri
--   * SKIP_RICORRENTE → spesa ricorrente / finanziamento (modulo dedicato);
--                       traccia leggera + conteggio in import_log (Q5)
--
-- chiave_aggancio = colonna CHIAVE grezza (numeroMovBanca/importo): utile per
-- audit e per agganciare lo scarto al movimento corrispondente cross-sorgente.
-- ============================================================

CREATE TABLE import_scartati (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id   UUID         NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    riga_numero     INT          NOT NULL,
    fonte           VARCHAR(50)  NOT NULL REFERENCES lk_fonti_movimento(codice),
    motivo          VARCHAR(50)  NOT NULL,
    -- Motivi: SKIP_POS | SKIP_GIROCONTO | SKIP_RICORRENTE
    chiave_aggancio VARCHAR(80),
    data_movimento  DATE,
    importo         NUMERIC(14,2),
    causale         VARCHAR(255),
    raw_data        JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_scartati_log    ON import_scartati (import_log_id, motivo);
CREATE INDEX idx_import_scartati_chiave ON import_scartati (chiave_aggancio);

-- Conteggio scartati sull'import_log (coerente con righe_ambigue di V35).
ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_scartate INT NOT NULL DEFAULT 0;
