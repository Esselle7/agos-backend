-- ============================================================
-- V25 - Rimuove l'utente tecnico di test aggiunto in V11.
-- Nessun utente hardcodato deve esistere in produzione.
-- I profili test lo ri-creano via db/seed/test/V26.
-- ============================================================

DELETE FROM users
WHERE id = '00000000-0000-0000-0000-000000000099'::UUID;
