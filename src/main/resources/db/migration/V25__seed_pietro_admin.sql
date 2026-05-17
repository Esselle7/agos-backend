-- ============================================================
-- V25 - SEED: Pre-registrazione utente Pietro Agostinelli come ADMIN
--
-- Motivazione:
--   createOrRejectUser() in AuthService crea sempre nuovi utenti con
--   ruolo DIPENDENTE. Pietro deve avere gli stessi privilegi di Simone
--   (ADMIN). Pre-inserendo la riga in users il flusso di login lo trova
--   per email (findByEmail) e popola google_sub automaticamente al primo
--   login senza passare per createOrRejectUser → mantiene il ruolo ADMIN.
--
--   Il ramo "trovato per email + aggiorna google_sub" è in
--   AuthService.handleGoogleCallback (.map(u -> { u.googleSub = ...; })).
-- ============================================================

INSERT INTO users (email, google_sub, full_name, role, is_active)
VALUES (
    'agostinelli.pietro1405@gmail.com',
    'pending_google_sub_pietro',
    'Pietro Agostinelli',
    'ADMIN',
    true
)
ON CONFLICT (email) DO NOTHING;
