-- ============================================================
-- V12 - MODULO EVENTI: estensione schema per il ciclo economico cerimonie
-- ============================================================

-- Aggiunge il riferimento all'autore sulla tabella eventi
-- (non presente nel V1 originale)
ALTER TABLE eventi ADD COLUMN IF NOT EXISTS created_by UUID REFERENCES users(id);

-- Nota annullamento: obbligatoria in business logic quando stato=ANNULLATO
-- Non ha vincolo NOT NULL in DB perché la regola è gestita a livello applicativo
ALTER TABLE eventi ADD COLUMN IF NOT EXISTS note_annullamento TEXT;

-- ============================================================
-- LINK OPZIONALE tra users e personale
-- Un utente (accesso web app) può essere collegato a un record personale
-- Un record personale può NON avere user associato
-- Gli ADMIN possono non essere presenti in personale
-- ============================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS personale_id UUID REFERENCES personale(id);

CREATE INDEX IF NOT EXISTS idx_users_personale ON users(personale_id) WHERE personale_id IS NOT NULL;

-- ============================================================
-- TABELLA PARTECIPANTI EVENTO
-- Relazione molti-a-molti eventi ↔ personale.
-- personale_id è UUID perché personale.id è UUID (gen_random_uuid()).
-- ============================================================

CREATE TABLE IF NOT EXISTS evento_partecipanti (
    id            BIGSERIAL    PRIMARY KEY,
    evento_id     UUID         NOT NULL,
    personale_id  UUID         NOT NULL,
    ruolo         VARCHAR(100),
    costo         NUMERIC(15,2),
    note          VARCHAR(500),

    CONSTRAINT fk_ep_evento
        FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE,

    CONSTRAINT fk_ep_personale
        FOREIGN KEY (personale_id) REFERENCES personale(id),

    CONSTRAINT uq_evento_personale
        UNIQUE (evento_id, personale_id)
);

CREATE INDEX IF NOT EXISTS idx_ep_evento    ON evento_partecipanti(evento_id);
CREATE INDEX IF NOT EXISTS idx_ep_personale ON evento_partecipanti(personale_id);
