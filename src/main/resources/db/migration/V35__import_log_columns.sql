-- ============================================================
-- V35 - Contatori ambiguità sull'import_log per query veloci
-- ============================================================

ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_ambigue INT NOT NULL DEFAULT 0;
ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_ambigue_classificate INT NOT NULL DEFAULT 0;
