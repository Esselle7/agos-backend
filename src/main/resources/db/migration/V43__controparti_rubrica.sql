-- ============================================================
-- V43 - Rubrica controparti + fuzzy matching (ETL_CLASSIFICAZIONE_v2 §7)
--
-- Mappa (IBAN | nome) → fornitore/tipo/COGE/BU di default. È il "cervello" del
-- motore fornitori: si popola dal seed dei fornitori esistenti e si arricchisce
-- automaticamente ad ogni classificazione manuale (auto-apprendimento §7.3).
-- ============================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE controparti (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo              VARCHAR(20)  NOT NULL DEFAULT 'FORNITORE'
                      CHECK (tipo IN ('FORNITORE','CLIENTE','SOCIO','ENTE_PUBBLICO','BANCA','INTERNO','PERSONALE')),
    nome_normalizzato VARCHAR(255) NOT NULL,
    iban              VARCHAR(34),
    fornitore_id      UUID         REFERENCES fornitori(id) ON DELETE SET NULL,
    coge_default_id   INT          REFERENCES piano_dei_conti_coge(id),
    bu_default_id     SMALLINT     REFERENCES business_units(id),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ
);

-- Un IBAN identifica una sola controparte (chiave forte).
CREATE UNIQUE INDEX uq_controparti_iban ON controparti (iban) WHERE iban IS NOT NULL;
-- Fuzzy matching dei nomi (similarità trigrammi, §7.1 livello 3).
CREATE INDEX idx_controparti_nome_trgm ON controparti USING gin (nome_normalizzato gin_trgm_ops);

-- Seed iniziale dai fornitori esistenti (V6/V8): nome upper + COGE/BU di default.
INSERT INTO controparti (tipo, nome_normalizzato, fornitore_id, coge_default_id, bu_default_id)
SELECT 'FORNITORE', upper(ragione_sociale), id, coge_default_id, bu_default_id
FROM fornitori;
