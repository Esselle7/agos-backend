-- V24: Tabella centralizzata mansioni + FK su personale
-- Sostituisce il campo mansione VARCHAR libero con una lookup table normalizzata.

-- ── 1. Tabella mansioni ────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mansioni (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    nome        VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_mansioni_nome UNIQUE (nome)
);

-- ── 2. Seed da valori distinti già presenti su personale ──────────────────
INSERT INTO mansioni (nome)
SELECT DISTINCT TRIM(mansione)
FROM   personale
WHERE  mansione IS NOT NULL AND TRIM(mansione) <> ''
ON CONFLICT (nome) DO NOTHING;

-- ── 3. Aggiungi FK mansione_id su personale ────────────────────────────────
ALTER TABLE personale
    ADD COLUMN IF NOT EXISTS mansione_id UUID REFERENCES mansioni(id);

-- ── 4. Popola mansione_id dai valori stringa esistenti ─────────────────────
UPDATE personale p
SET    mansione_id = m.id
FROM   mansioni m
WHERE  TRIM(p.mansione) = m.nome
  AND  p.mansione IS NOT NULL
  AND  TRIM(p.mansione) <> '';

-- ── 5. Rimuovi la colonna mansione VARCHAR ora ridondante ──────────────────
ALTER TABLE personale DROP COLUMN IF EXISTS mansione;
