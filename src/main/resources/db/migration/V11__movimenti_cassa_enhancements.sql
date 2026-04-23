-- ============================================================
-- V11 - Aggiunta categoria_id a movimenti e stato a cassa_movimenti.
-- Aggiunto utente test con UUID fisso per integration test.
-- ============================================================

-- Categoria operativa (albero categorie UI) su movimenti.
-- Nullable per retrocompatibilità con i dati storici (V9).
ALTER TABLE movimenti
    ADD COLUMN IF NOT EXISTS categoria_id BIGINT REFERENCES categorie(id);

CREATE INDEX IF NOT EXISTS idx_movimenti_categoria
    ON movimenti (categoria_id)
    WHERE categoria_id IS NOT NULL;

-- Stato del movimento di cassa (REGISTRATO / ANNULLATO).
ALTER TABLE cassa_movimenti
    ADD COLUMN IF NOT EXISTS stato VARCHAR(50)
        NOT NULL DEFAULT 'REGISTRATO' REFERENCES lk_stati_movimento(codice);

CREATE INDEX IF NOT EXISTS idx_cassa_stato
    ON cassa_movimenti (stato)
    WHERE stato != 'ANNULLATO';

-- ============================================================
-- Utente tecnico con UUID fisso – usato esclusivamente
-- dagli integration test come created_by nei movimenti.
-- NON rimuovere: usato da MovimentiIntegrationTest e CassaIntegrationTest.
-- ============================================================
INSERT INTO users (id, email, google_sub, full_name, role)
VALUES (
    '00000000-0000-0000-0000-000000000099'::UUID,
    'test@agostinelli.internal',
    'test-sub-integration-99',
    'Test Integration User',
    'ADMIN'
)
ON CONFLICT (email) DO NOTHING;
