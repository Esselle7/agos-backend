-- ============================================================
-- V18 – Spese Ricorrenti
--   recurring_expense_plan       → contenitore/piano
--   recurring_expense_installment → rate pre-generate
-- ============================================================

CREATE TABLE recurring_expense_plan (
    id                UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    descrizione       VARCHAR(255) NOT NULL,
    business_unit_id  SMALLINT     NOT NULL DEFAULT 5 REFERENCES business_units(id),
    conto_bancario_id SMALLINT     NOT NULL REFERENCES conti_bancari(id),
    conto_coge_id     INTEGER      NOT NULL REFERENCES piano_dei_conti_coge(id),
    importo_rata      NUMERIC(12,2) NOT NULL CHECK (importo_rata > 0),
    variazione_pct    NUMERIC(6,3)  NOT NULL DEFAULT 0,
    giorno_del_mese   SMALLINT      NOT NULL CHECK (giorno_del_mese BETWEEN 1 AND 28),
    frequenza         VARCHAR(20)   NOT NULL CHECK (frequenza IN ('MENSILE','BIMESTRALE','TRIMESTRALE')),
    numero_rate       INTEGER       NOT NULL CHECK (numero_rate > 0),
    data_prima_rata   DATE          NOT NULL,
    stato             VARCHAR(20)   NOT NULL DEFAULT 'ATTIVO' CHECK (stato IN ('ATTIVO','COMPLETATO','ANNULLATO')),
    importo_penale    NUMERIC(12,2) NOT NULL DEFAULT 0,
    note              TEXT,
    created_by        UUID          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ
);

CREATE TABLE recurring_expense_installment (
    id           UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    piano_id     UUID         NOT NULL REFERENCES recurring_expense_plan(id) ON DELETE CASCADE,
    numero_rata  INTEGER      NOT NULL,
    data_scadenza DATE        NOT NULL,
    importo      NUMERIC(12,2) NOT NULL CHECK (importo > 0),
    stato        VARCHAR(20)   NOT NULL DEFAULT 'PENDING' CHECK (stato IN ('PENDING','PAID','CANCELLED','SKIPPED')),
    movimento_id UUID,        -- nullable; può puntare allo stesso movimento (maxi-rata)
    note         VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ,
    UNIQUE (piano_id, numero_rata)
);

CREATE INDEX idx_rei_piano     ON recurring_expense_installment(piano_id);
CREATE INDEX idx_rei_scadenza  ON recurring_expense_installment(data_scadenza) WHERE stato = 'PENDING';
CREATE INDEX idx_rei_movimento ON recurring_expense_installment(movimento_id) WHERE movimento_id IS NOT NULL;
CREATE INDEX idx_rep_stato     ON recurring_expense_plan(stato);
