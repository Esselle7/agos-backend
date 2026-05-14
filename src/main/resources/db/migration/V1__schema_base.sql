-- ============================================================
-- V1 - SCHEMA BASE: estensioni, lookup tables, tutte le tabelle core
-- Agostinelli Gestionale Economico-Finanziario
-- PostgreSQL 16 / Neon
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- LOOKUP TABLES (sostituiscono gli ENUM per evolvibilità futura)
-- Aggiungere nuovi valori = semplice INSERT, zero downtime
-- ============================================================

CREATE TABLE lk_ruoli_utente (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_coge (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_movimento (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_cassa_mov (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_conto (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_evento (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_stati_evento (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_tipi_evento_mov (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_stati_movimento (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_fonti_movimento (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_stati_import (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

CREATE TABLE lk_match_types (
    codice      VARCHAR(50)  PRIMARY KEY,
    descrizione VARCHAR(100) NOT NULL
);

-- ============================================================
-- FUNZIONE GENERICA auto-aggiornamento updated_at
-- ============================================================

CREATE OR REPLACE FUNCTION fn_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- UTENTI (Google SSO – no password locali)
-- ============================================================

CREATE TABLE users (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(255) NOT NULL,
    google_sub  VARCHAR(255) NOT NULL,
    full_name   VARCHAR(255),
    role        VARCHAR(50)  NOT NULL REFERENCES lk_ruoli_utente(codice),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    last_login  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ,
    CONSTRAINT uq_users_email      UNIQUE (email),
    CONSTRAINT uq_users_google_sub UNIQUE (google_sub)
);

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- BUSINESS UNITS
-- ============================================================

CREATE TABLE business_units (
    id          SMALLINT     PRIMARY KEY,
    codice      VARCHAR(10)  NOT NULL,
    nome        VARCHAR(100) NOT NULL,
    descrizione TEXT,
    colore_hex  VARCHAR(7),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_bu_codice UNIQUE (codice)
);

-- ============================================================
-- ALIQUOTE IVA
-- ============================================================

CREATE TABLE aliquote_iva (
    id                  SERIAL       PRIMARY KEY,
    aliquota            NUMERIC(4,1) NOT NULL,
    descrizione         VARCHAR(100),
    perc_indetraibilita NUMERIC(5,2) NOT NULL DEFAULT 0.00,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_aliquota_iva UNIQUE (aliquota)
);

-- ============================================================
-- PIANO DEI CONTI – COGE (gerarchia self-referencing)
-- Struttura codice: XX = mastro, XX.XX = conto, XX.XX.XXX = sottoconto
-- ============================================================

CREATE TABLE piano_dei_conti_coge (
    id          SERIAL       PRIMARY KEY,
    codice      VARCHAR(20)  NOT NULL,
    descrizione VARCHAR(255) NOT NULL,
    tipo        VARCHAR(50)  NOT NULL REFERENCES lk_tipi_coge(codice),
    is_capex    BOOLEAN      NOT NULL DEFAULT false,
    parent_id   INT          REFERENCES piano_dei_conti_coge(id),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_coge_codice UNIQUE (codice)
);

-- ============================================================
-- CENTRI DI COSTO – COAN (contabilità analitica)
-- ============================================================

CREATE TABLE centri_di_costo_coan (
    id               SERIAL       PRIMARY KEY,
    codice           VARCHAR(20)  NOT NULL,
    descrizione      VARCHAR(255) NOT NULL,
    business_unit_id SMALLINT     NOT NULL REFERENCES business_units(id),
    is_active        BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_cdc_codice UNIQUE (codice)
);

-- ============================================================
-- METODI DI PAGAMENTO
-- ============================================================

CREATE TABLE metodi_pagamento (
    id          SERIAL       PRIMARY KEY,
    codice      VARCHAR(50)  NOT NULL,
    descrizione VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    CONSTRAINT uq_metodo_codice UNIQUE (codice)
);

-- ============================================================
-- CONTI BANCARI E CASSE
-- ============================================================

CREATE TABLE conti_bancari (
    id                  SMALLINT      PRIMARY KEY,
    nome                VARCHAR(100)  NOT NULL,
    tipo                VARCHAR(50)   NOT NULL REFERENCES lk_tipi_conto(codice),
    iban                VARCHAR(34),
    saldo_iniziale      NUMERIC(12,2) NOT NULL DEFAULT 0,
    data_saldo_iniziale DATE,
    is_active           BOOLEAN       NOT NULL DEFAULT true
);

-- ============================================================
-- FORNITORI
-- ============================================================

CREATE TABLE fornitori (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ragione_sociale VARCHAR(255) NOT NULL,
    alias           VARCHAR(100),
    piva            VARCHAR(11),
    codice_sdi      VARCHAR(7),
    coge_default_id INT          REFERENCES piano_dei_conti_coge(id),
    bu_default_id   SMALLINT     REFERENCES business_units(id),
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_fornitori_piva UNIQUE (piva)
);

-- Pattern per abbinamento automatico nelle importazioni (es. matching su causale bancaria)
CREATE TABLE fornitore_alias_matching (
    id           SERIAL       PRIMARY KEY,
    fornitore_id UUID         NOT NULL REFERENCES fornitori(id) ON DELETE CASCADE,
    pattern      VARCHAR(255) NOT NULL,
    match_type   VARCHAR(50)  NOT NULL REFERENCES lk_match_types(codice)
);

-- ============================================================
-- PERSONALE
-- ============================================================

CREATE TABLE personale (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    nome                    VARCHAR(100)  NOT NULL,
    cognome                 VARCHAR(100)  NOT NULL,
    centro_di_costo_id      INT           REFERENCES centri_di_costo_coan(id),
    business_unit_id        SMALLINT      REFERENCES business_units(id),
    costo_aziendale_mensile NUMERIC(10,2),
    is_active               BOOLEAN       NOT NULL DEFAULT true
);

-- ============================================================
-- CESPITI (immobilizzazioni materiali – CAPEX)
-- ============================================================

CREATE TABLE cespiti (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    descrizione           VARCHAR(255)  NOT NULL,
    conto_coge_id         INT           NOT NULL REFERENCES piano_dei_conti_coge(id),
    costo_storico         NUMERIC(12,2) NOT NULL,
    aliquota_ammortamento NUMERIC(5,2)  NOT NULL,
    fondo_ammortamento    NUMERIC(12,2) NOT NULL DEFAULT 0,
    data_acquisto         DATE          NOT NULL,
    is_active             BOOLEAN       NOT NULL DEFAULT true
);

-- ============================================================
-- EVENTI (BU2 – Cerimonie, matrimoni, banchetti)
-- Logica caparra/acconto/saldo: i totali vengono aggiornati
-- automaticamente dal trigger trg_aggiorna_totali_evento
-- ============================================================

CREATE TABLE eventi (
    id                          UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    nome                        VARCHAR(255)  NOT NULL,
    tipo                        VARCHAR(50)   NOT NULL REFERENCES lk_tipi_evento(codice),
    data_evento                 DATE          NOT NULL,
    data_preventivo             DATE,
    importo_totale_preventivato NUMERIC(12,2),
    importo_incassato           NUMERIC(12,2) NOT NULL DEFAULT 0,
    caparre_incassate           NUMERIC(12,2) NOT NULL DEFAULT 0,
    costi_diretti_imputati      NUMERIC(12,2) NOT NULL DEFAULT 0,
    stato                       VARCHAR(50)   NOT NULL DEFAULT 'PREVENTIVO' REFERENCES lk_stati_evento(codice),
    business_unit_id            SMALLINT      REFERENCES business_units(id),
    contatto_nome               VARCHAR(255),
    contatto_telefono           VARCHAR(20),
    contatto_email              VARCHAR(255),
    n_ospiti                    INT,
    note                        TEXT,
    created_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ
);

CREATE TRIGGER trg_eventi_updated_at
    BEFORE UPDATE ON eventi
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- IMPORT LOG (traccia ogni importazione CSV/file)
-- ============================================================

CREATE TABLE import_log (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    fonte            VARCHAR(50) NOT NULL REFERENCES lk_fonti_movimento(codice),
    filename         VARCHAR(255),
    data_import      TIMESTAMPTZ NOT NULL DEFAULT now(),
    righe_totali     INT,
    righe_importate  INT,
    righe_errore     INT,
    righe_duplicate  INT,
    stato            VARCHAR(50) NOT NULL DEFAULT 'IN_CORSO' REFERENCES lk_stati_import(codice),
    errori_dettaglio JSONB,
    imported_by      UUID        REFERENCES users(id)
);

-- ============================================================
-- MOVIMENTI – tabella core, partizionata per anno su data_movimento
--
-- PRINCIPIO: data_movimento = data finanziaria (liquidità reale)
--            data_competenza = data economica (ricavo/costo di competenza)
--
-- Idempotenza importazioni: UNIQUE su (fonte, riferimento_esterno, data_movimento)
-- garantisce che reimportare lo stesso file non crei duplicati.
-- ============================================================

CREATE TABLE movimenti (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid(),
    data_movimento        DATE          NOT NULL,

    -- Finanziario
    tipo                  VARCHAR(50)   NOT NULL REFERENCES lk_tipi_movimento(codice),
    importo_lordo         NUMERIC(12,2) NOT NULL CHECK (importo_lordo > 0),
    importo_imponibile    NUMERIC(12,2),
    importo_iva           NUMERIC(12,2),
    importo_commissione   NUMERIC(12,2) NOT NULL DEFAULT 0,

    -- Date analitiche
    data_competenza       DATE,
    data_liquidita        DATE,

    -- FK finanziarie
    conto_bancario_id     SMALLINT      NOT NULL REFERENCES conti_bancari(id),
    metodo_pagamento_id   INT           NOT NULL REFERENCES metodi_pagamento(id),
    aliquota_iva_id       INT           REFERENCES aliquote_iva(id),

    -- FK contabili / analitiche
    conto_coge_id         INT           NOT NULL REFERENCES piano_dei_conti_coge(id),
    centro_di_costo_id    INT           REFERENCES centri_di_costo_coan(id),
    business_unit_id      SMALLINT      NOT NULL REFERENCES business_units(id),

    -- FK operative (tutte opzionali)
    fornitore_id          UUID          REFERENCES fornitori(id),
    evento_id             UUID          REFERENCES eventi(id),
    tipo_evento_movimento VARCHAR(50)   REFERENCES lk_tipi_evento_mov(codice),
    cespite_id            UUID          REFERENCES cespiti(id),

    -- Metadati
    descrizione           VARCHAR(500),
    note                  TEXT,
    stato                 VARCHAR(50)   NOT NULL DEFAULT 'REGISTRATO' REFERENCES lk_stati_movimento(codice),
    fonte_importazione_id UUID          REFERENCES import_log(id),
    fonte                 VARCHAR(50)   NOT NULL REFERENCES lk_fonti_movimento(codice),
    riferimento_esterno   VARCHAR(255),
    allegato_path         VARCHAR(500),
    created_by            UUID          NOT NULL REFERENCES users(id),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ,

    -- La PK composita è obbligatoria per il partizionamento in PostgreSQL
    PRIMARY KEY (id, data_movimento),
    -- Idempotenza: previene duplicati su reimportazione dello stesso file
    UNIQUE (fonte, riferimento_esterno, data_movimento)

) PARTITION BY RANGE (data_movimento);

-- Partizioni annuali (aggiungere V8__add_partition_YYYY.sql ogni anno)
CREATE TABLE movimenti_2023 PARTITION OF movimenti FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE movimenti_2024 PARTITION OF movimenti FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE movimenti_2025 PARTITION OF movimenti FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE movimenti_2026 PARTITION OF movimenti FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE movimenti_2027 PARTITION OF movimenti FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');

-- Partizione di default: cattura date fuori range senza lanciare errori
-- Importante per robustezza import da fonti esterne con date impreviste
CREATE TABLE movimenti_default PARTITION OF movimenti DEFAULT;

-- ============================================================
-- CASSA MOVIMENTI (Excel cassa contanti: prelievi, versamenti, cash)
-- ============================================================

CREATE TABLE cassa_movimenti (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo             VARCHAR(50)   NOT NULL REFERENCES lk_tipi_cassa_mov(codice),
    importo          NUMERIC(12,2) NOT NULL CHECK (importo > 0),
    data_movimento   DATE          NOT NULL,
    descrizione      VARCHAR(500),
    conto_coge_id    INT           REFERENCES piano_dei_conti_coge(id),
    business_unit_id SMALLINT      REFERENCES business_units(id),
    conto_banca_id   SMALLINT      REFERENCES conti_bancari(id),
    created_by       UUID          REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ============================================================
-- SALDI BANCA (snapshot periodici per riconciliazione)
-- ============================================================

CREATE TABLE saldi_banca (
    id               SERIAL        PRIMARY KEY,
    conto_id         SMALLINT      NOT NULL REFERENCES conti_bancari(id),
    data_riferimento DATE          NOT NULL,
    saldo            NUMERIC(12,2) NOT NULL,
    CONSTRAINT uq_saldo_conto_data UNIQUE (conto_id, data_riferimento)
);

-- ============================================================
-- AUDIT LOG (immutabile – solo INSERT, mai UPDATE/DELETE)
-- ============================================================

CREATE TABLE audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    tabella         VARCHAR(100) NOT NULL,
    record_id       VARCHAR(255) NOT NULL,
    operazione      VARCHAR(10)  NOT NULL CHECK (operazione IN ('INSERT', 'UPDATE', 'DELETE')),
    dati_precedenti JSONB,
    dati_nuovi      JSONB,
    user_id         UUID         REFERENCES users(id),
    ip_address      INET,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
