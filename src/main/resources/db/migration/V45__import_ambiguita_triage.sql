-- ============================================================
-- V45 - Estensione import_ambiguita per il triage assistito (ETL v2 §8/§9.5)
--
-- Aggiunge confidence, suggerimenti (top-3 candidati), e le entità estratte
-- (controparte/IBAN) + i suggerimenti COGE/BU, così la coda di revisione può
-- proporre una classificazione con un click.
-- ============================================================

ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS confidence       NUMERIC(3,2);
ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS suggerimenti      JSONB;
ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS controparte_nome  TEXT;
ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS controparte_iban  TEXT;
ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS coge_suggerito_id INT      REFERENCES piano_dei_conti_coge(id);
ALTER TABLE import_ambiguita ADD COLUMN IF NOT EXISTS bu_suggerita      SMALLINT REFERENCES business_units(id);
