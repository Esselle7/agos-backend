-- ============================================================
-- V37 - Personale a ore, costo orario su evento, monitoring preventivo
--
-- 1. Tipologia retribuzione personale (MENSILE vs ORARIA).
-- 2. Allocazione ore + movimento collegato su evento_partecipanti.
-- 3. Conto CoGe per il costo del personale a ore sugli eventi.
-- 4. Tabella di tracciamento (monitoring) della composizione del
--    totale preventivato (affitto + catering), SENZA impatto contabile.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Personale: due tipologie di retribuzione
-- ------------------------------------------------------------
ALTER TABLE personale
    ADD COLUMN IF NOT EXISTS tipo_retribuzione VARCHAR(20) NOT NULL DEFAULT 'MENSILE'
        CHECK (tipo_retribuzione IN ('MENSILE','ORARIA')),
    ADD COLUMN IF NOT EXISTS paga_oraria NUMERIC(10,2);

-- ------------------------------------------------------------
-- 2. Allocazione ore su evento_partecipanti
--    (movimento_id, movimento_data) = riferimento al movimento USCITA
--    generato per il costo del personale a ore.
-- ------------------------------------------------------------
ALTER TABLE evento_partecipanti
    ADD COLUMN IF NOT EXISTS ore            NUMERIC(8,2),
    ADD COLUMN IF NOT EXISTS movimento_id   UUID,
    ADD COLUMN IF NOT EXISTS movimento_data DATE;

-- ------------------------------------------------------------
-- 3. Conto CoGe per il personale a ore sugli eventi
-- ------------------------------------------------------------
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.13.006', 'Personale a ore eventi', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13'));

-- ------------------------------------------------------------
-- 4. Monitoring composizione del preventivato (no contabilità)
--    Un solo record AFFITTO e uno CATERING per evento.
-- ------------------------------------------------------------
CREATE TABLE evento_preventivo_tracking (
    id                  BIGSERIAL PRIMARY KEY,
    evento_id           UUID          NOT NULL REFERENCES eventi(id) ON DELETE CASCADE,
    tipo                VARCHAR(20)   NOT NULL CHECK (tipo IN ('AFFITTO','CATERING')),
    -- AFFITTO: quota del preventivato attribuita all'affitto (incasso)
    importo_incasso     NUMERIC(15,2),
    -- CATERING: prezzo/persona esposto, costo/persona interno, n. persone
    costo_per_persona   NUMERIC(15,2),
    prezzo_per_persona  NUMERIC(15,2),
    num_persone         INT,
    note                VARCHAR(500),
    created_by          UUID          NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ,
    CONSTRAINT uq_evento_preventivo_tracking UNIQUE (evento_id, tipo)
);

CREATE INDEX ix_evento_preventivo_tracking_evento ON evento_preventivo_tracking(evento_id);
