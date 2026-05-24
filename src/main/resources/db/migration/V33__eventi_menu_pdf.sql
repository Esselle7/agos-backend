-- ============================================================
-- V33 — Menu PDF eventi
--   Aggiunge la colonna che memorizza l'URL pubblica del menu PDF
--   caricato su Cloudflare R2 (chiave eventi/{id}/menu.pdf).
--   NULL = nessun menu caricato.
-- ============================================================

ALTER TABLE eventi ADD COLUMN menu_pdf_url VARCHAR(500);
