-- Aggiunge il campo mansione/ruolo professionale al personale
ALTER TABLE personale ADD COLUMN IF NOT EXISTS mansione VARCHAR(100);
