-- ============================================================
-- V44 - Motore regole data-driven (ETL_CLASSIFICAZIONE_v2 §9.1/§10)
--
-- Il set di regole esce dal codice e vive in tabella: l'utente può aggiungere
-- o modificare regole senza redeploy (CRUD in F6). L'interprete le valuta per
-- priorità PRIMA dei gate hardcoded; se nessuna regola matcha, si applicano i
-- gate esistenti (che restano la rete di sicurezza per la logica speciale:
-- tag Alveare, carve-out Billy, giroconto simmetrico).
-- ============================================================

CREATE TABLE regole_classificazione (
    id             SERIAL       PRIMARY KEY,
    priorita       INT          NOT NULL,
    sorgente       VARCHAR(10)  NOT NULL DEFAULT '*',   -- BILLY | CA | BPM | *
    tipo_movimento VARCHAR(10)  NOT NULL DEFAULT '*',   -- ENTRATA | USCITA | *
    campo          VARCHAR(20)  NOT NULL,               -- CAUSALE | DESC_SPACED | DESC_COMPACT | IBAN
    match_type     VARCHAR(20)  NOT NULL,               -- EQUALS | CONTAINS | STARTS_WITH | REGEX | IN_LIST
    pattern        TEXT         NOT NULL,
    azione         VARCHAR(20)  NOT NULL,               -- SKIP_POS | SKIP_GIROCONTO | SKIP_RICORRENTE | PARK_EVENTO | MAP
    coge_codice    VARCHAR(20),                         -- per azione MAP
    bu_id          SMALLINT,                            -- per azione MAP
    metodo_codice  VARCHAR(30),                         -- override opzionale
    confidence     NUMERIC(3,2) NOT NULL DEFAULT 1.00,
    attivo         BOOLEAN      NOT NULL DEFAULT true,
    note           TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_regole_attivo_pri ON regole_classificazione (attivo, priorita);

-- Seed iniziale: sottoinsieme del §10 esprimibile a regola e OUTCOME-EQUIVALENTE ai gate
-- (sposta la "manopola" in tabella senza cambiare il comportamento validato).
INSERT INTO regole_classificazione (priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, coge_codice, bu_id, note) VALUES
    -- Ricorrenti solo sulle banche (Billy è la fonte originale, mai ricorrenti): coerente coi gate.
    (30,  'CA',  '*',       'DESC_SPACED', 'IN_LIST',  'CANONE,ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,BOLLO,ASCONFIDI', 'SKIP_RICORRENTE', NULL, NULL, 'Spese ricorrenti/finanziamenti (§4 A3)'),
    (30,  'BPM', '*',       'DESC_SPACED', 'IN_LIST',  'CANONE,ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,BOLLO,ASCONFIDI', 'SKIP_RICORRENTE', NULL, NULL, 'Spese ricorrenti/finanziamenti (§4 A3)'),
    (400, 'CA',  'ENTRATA', 'DESC_SPACED', 'CONTAINS', 'ORGANISMO PAGATORE', 'MAP', '30.05.001', 1, 'Contributo pubblico / PAC (§10.2 pri.400)'),
    (400, 'BPM', 'ENTRATA', 'DESC_SPACED', 'CONTAINS', 'VERSAMENTO SOCIO',  'MAP', '90.02.001', 5, 'Apporto soci (§10.3 pri.400)');
