-- ============================================================
-- V34 - Tabella import_ambiguita
-- Raccoglie le righe non classificabili automaticamente
-- dall'ETL per revisione manuale dell'operatore.
-- ============================================================

CREATE TABLE import_ambiguita (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id     UUID         NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    riga_numero       INT          NOT NULL,
    fonte             VARCHAR(50)  NOT NULL REFERENCES lk_fonti_movimento(codice),
    raw_data          JSONB        NOT NULL,
    motivo            VARCHAR(255) NOT NULL,
    -- Motivi: BANCA_NON_IDENTIFICATA | EVENTO_NON_TROVATO | FORNITORE_NON_RICONOSCIUTO
    --         | COGE_NON_DETERMINABILE | GIROCONTO_SKIP | BU_AMBIGUA
    --         | METODO_NON_IDENTIFICATO | CAUSALE_NON_MAPPATA | IMPORTO_NON_POSITIVO
    --         | DATA_MANCANTE | DATA_FUTURA | DATA_TROPPO_VECCHIA
    stato             VARCHAR(50)  NOT NULL DEFAULT 'DA_CLASSIFICARE',
    -- ENUM: DA_CLASSIFICARE | CLASSIFICATO | SCARTATO
    movimento_id      UUID,        -- valorizzato dopo classificazione manuale (non FK: partitioned table)
    classificato_da   UUID         REFERENCES users(id),
    classificato_at   TIMESTAMPTZ,
    note_operatore    TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_import_ambiguita_log
    ON import_ambiguita (import_log_id, stato);

INSERT INTO lk_stati_import (codice, descrizione)
    VALUES ('COMPLETATO_CON_AMBIGUITA', 'Importazione completata con movimenti ambigui da classificare')
    ON CONFLICT DO NOTHING;
