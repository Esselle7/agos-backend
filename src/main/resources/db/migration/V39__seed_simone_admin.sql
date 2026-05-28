-- ============================================================
-- V39 - SEED: Pre-registrazione utente Simone Leone come ADMIN
--
-- Motivazione:
--   Simone era inserito in V7 (db/seed/dev) insieme ai dati di esempio.
--   Essendo un utente reale deve esistere anche in produzione.
--   Stessa logica di V25 (Pietro): il google_sub viene aggiornato
--   automaticamente al primo login via AuthService.handleGoogleCallback.
--
--   ON CONFLICT DO NOTHING: sicuro da rieseguire, non sovrascrive
--   il google_sub reale se l'utente si è già loggato.
-- ============================================================

INSERT INTO users (email, google_sub, full_name, role, is_active)
VALUES (
    'simone.leone300900@gmail.com',
    'pending_google_sub_simone',
    'Simone Leone',
    'ADMIN',
    true
)
ON CONFLICT (email) DO NOTHING;
