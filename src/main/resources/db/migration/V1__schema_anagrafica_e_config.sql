-- ============================================================
-- V1 - Schema ANAGRAFICA e CONFIGURAZIONE (DDL)
--
-- Migration consolidata: rappresenta lo stato FINALE delle
-- tabelle di lookup, anagrafica e configurazione (ex V1..V47).
-- Le tabelle operative sono in V2, le viste materializzate in V3,
-- i dati di config (seed) in V4.
-- ============================================================

-- ---------- Estensioni ----------
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------- Funzione updated_at ----------
CREATE FUNCTION fn_set_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

-- ============================================================
-- TABELLE DI LOOKUP (codice PK)
-- ============================================================
CREATE TABLE lk_ruoli_utente (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_ruoli_utente_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_coge (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_coge_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_movimento (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_movimento_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_cassa_mov (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_cassa_mov_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_conto (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_conto_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_evento (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_evento_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_stati_evento (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_stati_evento_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_tipi_evento_mov (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_tipi_evento_mov_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_stati_movimento (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_stati_movimento_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_fonti_movimento (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_fonti_movimento_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_stati_import (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_stati_import_pkey PRIMARY KEY (codice)
);

CREATE TABLE lk_match_types (
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    CONSTRAINT lk_match_types_pkey PRIMARY KEY (codice)
);

-- ============================================================
-- BUSINESS UNITS
-- ============================================================
CREATE TABLE business_units (
    id          smallint NOT NULL,
    codice      character varying(10) NOT NULL,
    nome        character varying(100) NOT NULL,
    descrizione text,
    colore_hex  character varying(7),
    is_active   boolean DEFAULT true NOT NULL,
    CONSTRAINT business_units_pkey PRIMARY KEY (id),
    CONSTRAINT uq_bu_codice UNIQUE (codice)
);

-- ============================================================
-- ALIQUOTE IVA
-- ============================================================
CREATE TABLE aliquote_iva (
    id                  integer NOT NULL,
    aliquota            numeric(4,1) NOT NULL,
    descrizione         character varying(100),
    perc_indetraibilita numeric(5,2) DEFAULT 0.00 NOT NULL,
    is_active           boolean DEFAULT true NOT NULL,
    CONSTRAINT aliquote_iva_pkey PRIMARY KEY (id),
    CONSTRAINT uq_aliquota_iva UNIQUE (aliquota)
);
CREATE SEQUENCE aliquote_iva_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE aliquote_iva_id_seq OWNED BY aliquote_iva.id;
ALTER TABLE ONLY aliquote_iva ALTER COLUMN id SET DEFAULT nextval('aliquote_iva_id_seq'::regclass);

-- ============================================================
-- PIANO DEI CONTI COGE (gerarchico)
-- ============================================================
CREATE TABLE piano_dei_conti_coge (
    id          integer NOT NULL,
    codice      character varying(20) NOT NULL,
    descrizione character varying(255) NOT NULL,
    tipo        character varying(50) NOT NULL,
    is_capex    boolean DEFAULT false NOT NULL,
    parent_id   integer,
    is_active   boolean DEFAULT true NOT NULL,
    CONSTRAINT piano_dei_conti_coge_pkey PRIMARY KEY (id),
    CONSTRAINT uq_coge_codice UNIQUE (codice),
    CONSTRAINT piano_dei_conti_coge_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT piano_dei_conti_coge_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_coge(codice)
);
CREATE SEQUENCE piano_dei_conti_coge_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE piano_dei_conti_coge_id_seq OWNED BY piano_dei_conti_coge.id;
ALTER TABLE ONLY piano_dei_conti_coge ALTER COLUMN id SET DEFAULT nextval('piano_dei_conti_coge_id_seq'::regclass);

CREATE INDEX idx_coge_capex ON piano_dei_conti_coge USING btree (is_capex) WHERE (is_capex = true);
CREATE INDEX idx_coge_parent ON piano_dei_conti_coge USING btree (parent_id) WHERE (parent_id IS NOT NULL);
CREATE INDEX idx_coge_tipo ON piano_dei_conti_coge USING btree (tipo);

-- ============================================================
-- CENTRI DI COSTO COAN
-- ============================================================
CREATE TABLE centri_di_costo_coan (
    id               integer NOT NULL,
    codice           character varying(20) NOT NULL,
    descrizione      character varying(255) NOT NULL,
    business_unit_id smallint NOT NULL,
    is_active        boolean DEFAULT true NOT NULL,
    CONSTRAINT centri_di_costo_coan_pkey PRIMARY KEY (id),
    CONSTRAINT uq_cdc_codice UNIQUE (codice),
    CONSTRAINT centri_di_costo_coan_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id)
);
CREATE SEQUENCE centri_di_costo_coan_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE centri_di_costo_coan_id_seq OWNED BY centri_di_costo_coan.id;
ALTER TABLE ONLY centri_di_costo_coan ALTER COLUMN id SET DEFAULT nextval('centri_di_costo_coan_id_seq'::regclass);

CREATE INDEX idx_cdc_bu ON centri_di_costo_coan USING btree (business_unit_id);

-- ============================================================
-- METODI DI PAGAMENTO
-- ============================================================
CREATE TABLE metodi_pagamento (
    id          integer NOT NULL,
    codice      character varying(50) NOT NULL,
    descrizione character varying(100) NOT NULL,
    is_active   boolean DEFAULT true NOT NULL,
    CONSTRAINT metodi_pagamento_pkey PRIMARY KEY (id),
    CONSTRAINT uq_metodo_codice UNIQUE (codice)
);
CREATE SEQUENCE metodi_pagamento_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE metodi_pagamento_id_seq OWNED BY metodi_pagamento.id;
ALTER TABLE ONLY metodi_pagamento ALTER COLUMN id SET DEFAULT nextval('metodi_pagamento_id_seq'::regclass);

-- ============================================================
-- CONTI BANCARI
-- ============================================================
CREATE TABLE conti_bancari (
    id                  smallint NOT NULL,
    nome                character varying(100) NOT NULL,
    tipo                character varying(50) NOT NULL,
    iban                character varying(34),
    saldo_iniziale      numeric(12,2) DEFAULT 0 NOT NULL,
    data_saldo_iniziale date,
    is_active           boolean DEFAULT true NOT NULL,
    CONSTRAINT conti_bancari_pkey PRIMARY KEY (id),
    CONSTRAINT conti_bancari_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_conto(codice)
);

-- ============================================================
-- MANSIONI
-- ============================================================
CREATE TABLE mansioni (
    id         uuid DEFAULT gen_random_uuid() NOT NULL,
    nome       character varying(100) NOT NULL,
    is_active  boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT mansioni_pkey PRIMARY KEY (id),
    CONSTRAINT uq_mansioni_nome UNIQUE (nome)
);

-- ============================================================
-- PERSONALE
-- ============================================================
CREATE TABLE personale (
    id                      uuid DEFAULT gen_random_uuid() NOT NULL,
    nome                    character varying(100) NOT NULL,
    cognome                 character varying(100) NOT NULL,
    centro_di_costo_id      integer,
    business_unit_id        smallint,
    costo_aziendale_mensile numeric(10,2),
    is_active               boolean DEFAULT true NOT NULL,
    mansione_id             uuid,
    tipo_retribuzione       character varying(20) DEFAULT 'MENSILE'::character varying NOT NULL,
    paga_oraria             numeric(10,2),
    CONSTRAINT personale_pkey PRIMARY KEY (id),
    CONSTRAINT personale_tipo_retribuzione_check CHECK (((tipo_retribuzione)::text = ANY ((ARRAY['MENSILE'::character varying, 'ORARIA'::character varying])::text[]))),
    CONSTRAINT personale_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id),
    CONSTRAINT personale_centro_di_costo_id_fkey FOREIGN KEY (centro_di_costo_id) REFERENCES centri_di_costo_coan(id),
    CONSTRAINT personale_mansione_id_fkey FOREIGN KEY (mansione_id) REFERENCES mansioni(id)
);

-- ============================================================
-- USERS
-- ============================================================
CREATE TABLE users (
    id           uuid DEFAULT gen_random_uuid() NOT NULL,
    email        character varying(255) NOT NULL,
    google_sub   character varying(255) NOT NULL,
    full_name    character varying(255),
    role         character varying(50) NOT NULL,
    is_active    boolean DEFAULT true NOT NULL,
    last_login   timestamp with time zone,
    created_at   timestamp with time zone DEFAULT now() NOT NULL,
    updated_at   timestamp with time zone,
    personale_id uuid,
    CONSTRAINT users_pkey PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT uq_users_google_sub UNIQUE (google_sub),
    CONSTRAINT uq_users_personale_id UNIQUE (personale_id),
    CONSTRAINT users_role_fkey FOREIGN KEY (role) REFERENCES lk_ruoli_utente(codice),
    CONSTRAINT fk_users_personale FOREIGN KEY (personale_id) REFERENCES personale(id) ON DELETE SET NULL
);

CREATE INDEX idx_users_personale ON users USING btree (personale_id) WHERE (personale_id IS NOT NULL);

CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON users FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- ============================================================
-- FORNITORI
-- ============================================================
CREATE TABLE fornitori (
    id              uuid DEFAULT gen_random_uuid() NOT NULL,
    ragione_sociale character varying(255) NOT NULL,
    alias           character varying(100),
    piva            character varying(11),
    codice_sdi      character varying(7),
    coge_default_id integer,
    bu_default_id   smallint,
    note            text,
    created_at      timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT fornitori_pkey PRIMARY KEY (id),
    CONSTRAINT uq_fornitori_piva UNIQUE (piva),
    CONSTRAINT fornitori_bu_default_id_fkey FOREIGN KEY (bu_default_id) REFERENCES business_units(id),
    CONSTRAINT fornitori_coge_default_id_fkey FOREIGN KEY (coge_default_id) REFERENCES piano_dei_conti_coge(id)
);

CREATE INDEX idx_fornitori_bu ON fornitori USING btree (bu_default_id) WHERE (bu_default_id IS NOT NULL);
CREATE INDEX idx_fornitori_piva ON fornitori USING btree (piva) WHERE (piva IS NOT NULL);

-- ============================================================
-- FORNITORE ALIAS MATCHING
-- ============================================================
CREATE TABLE fornitore_alias_matching (
    id           integer NOT NULL,
    fornitore_id uuid NOT NULL,
    pattern      character varying(255) NOT NULL,
    match_type   character varying(50) NOT NULL,
    CONSTRAINT fornitore_alias_matching_pkey PRIMARY KEY (id),
    CONSTRAINT fornitore_alias_matching_fornitore_id_fkey FOREIGN KEY (fornitore_id) REFERENCES fornitori(id) ON DELETE CASCADE,
    CONSTRAINT fornitore_alias_matching_match_type_fkey FOREIGN KEY (match_type) REFERENCES lk_match_types(codice)
);
CREATE SEQUENCE fornitore_alias_matching_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE fornitore_alias_matching_id_seq OWNED BY fornitore_alias_matching.id;
ALTER TABLE ONLY fornitore_alias_matching ALTER COLUMN id SET DEFAULT nextval('fornitore_alias_matching_id_seq'::regclass);

CREATE INDEX idx_alias_fornitore ON fornitore_alias_matching USING btree (fornitore_id);
CREATE INDEX idx_alias_pattern ON fornitore_alias_matching USING btree (pattern);

-- ============================================================
-- CONTROPARTI (rubrica)
-- ============================================================
CREATE TABLE controparti (
    id                uuid DEFAULT gen_random_uuid() NOT NULL,
    tipo              character varying(20) DEFAULT 'FORNITORE'::character varying NOT NULL,
    nome_normalizzato character varying(255) NOT NULL,
    iban              character varying(34),
    fornitore_id      uuid,
    coge_default_id   integer,
    bu_default_id     smallint,
    created_at        timestamp with time zone DEFAULT now() NOT NULL,
    updated_at        timestamp with time zone,
    CONSTRAINT controparti_pkey PRIMARY KEY (id),
    CONSTRAINT controparti_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['FORNITORE'::character varying, 'CLIENTE'::character varying, 'SOCIO'::character varying, 'ENTE_PUBBLICO'::character varying, 'BANCA'::character varying, 'INTERNO'::character varying, 'PERSONALE'::character varying])::text[]))),
    CONSTRAINT controparti_bu_default_id_fkey FOREIGN KEY (bu_default_id) REFERENCES business_units(id),
    CONSTRAINT controparti_coge_default_id_fkey FOREIGN KEY (coge_default_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT controparti_fornitore_id_fkey FOREIGN KEY (fornitore_id) REFERENCES fornitori(id) ON DELETE SET NULL
);

CREATE INDEX idx_controparti_nome_trgm ON controparti USING gin (nome_normalizzato gin_trgm_ops);
CREATE UNIQUE INDEX uq_controparti_iban ON controparti USING btree (iban) WHERE (iban IS NOT NULL);

-- ============================================================
-- CATEGORIE
-- ============================================================
CREATE TABLE categorie (
    id          bigint NOT NULL,
    nome        character varying(100) NOT NULL,
    tipo        character varying(50) NOT NULL,
    parent_id   bigint,
    bu_id       smallint NOT NULL,
    ordinamento integer DEFAULT 0 NOT NULL,
    is_active   boolean DEFAULT true NOT NULL,
    CONSTRAINT categorie_pkey PRIMARY KEY (id),
    CONSTRAINT categorie_bu_id_fkey FOREIGN KEY (bu_id) REFERENCES business_units(id),
    CONSTRAINT categorie_parent_id_fkey FOREIGN KEY (parent_id) REFERENCES categorie(id),
    CONSTRAINT categorie_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_movimento(codice)
);
CREATE SEQUENCE categorie_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE categorie_id_seq OWNED BY categorie.id;
ALTER TABLE ONLY categorie ALTER COLUMN id SET DEFAULT nextval('categorie_id_seq'::regclass);

CREATE INDEX idx_categorie_parent ON categorie USING btree (parent_id) WHERE (parent_id IS NOT NULL);
CREATE INDEX idx_categorie_tipo_bu ON categorie USING btree (tipo, bu_id) WHERE (is_active = true);

-- ============================================================
-- CESPITI
-- ============================================================
CREATE TABLE cespiti (
    id                    uuid DEFAULT gen_random_uuid() NOT NULL,
    descrizione           character varying(255) NOT NULL,
    conto_coge_id         integer NOT NULL,
    costo_storico         numeric(12,2) NOT NULL,
    aliquota_ammortamento numeric(5,2) NOT NULL,
    fondo_ammortamento    numeric(12,2) DEFAULT 0 NOT NULL,
    data_acquisto         date NOT NULL,
    is_active             boolean DEFAULT true NOT NULL,
    CONSTRAINT cespiti_pkey PRIMARY KEY (id),
    CONSTRAINT cespiti_conto_coge_id_fkey FOREIGN KEY (conto_coge_id) REFERENCES piano_dei_conti_coge(id)
);

-- ============================================================
-- REGOLE DI CLASSIFICAZIONE (ETL)
-- ============================================================
CREATE TABLE regole_classificazione (
    id             integer NOT NULL,
    priorita       integer NOT NULL,
    sorgente       character varying(10) DEFAULT '*'::character varying NOT NULL,
    tipo_movimento character varying(10) DEFAULT '*'::character varying NOT NULL,
    campo          character varying(20) NOT NULL,
    match_type     character varying(20) NOT NULL,
    pattern        text NOT NULL,
    azione         character varying(20) NOT NULL,
    coge_codice    character varying(20),
    bu_id          smallint,
    metodo_codice  character varying(30),
    confidence     numeric(3,2) DEFAULT 1.00 NOT NULL,
    attivo         boolean DEFAULT true NOT NULL,
    note           text,
    created_at     timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT regole_classificazione_pkey PRIMARY KEY (id)
);
CREATE SEQUENCE regole_classificazione_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE regole_classificazione_id_seq OWNED BY regole_classificazione.id;
ALTER TABLE ONLY regole_classificazione ALTER COLUMN id SET DEFAULT nextval('regole_classificazione_id_seq'::regclass);

CREATE INDEX idx_regole_attivo_pri ON regole_classificazione USING btree (attivo, priorita);
