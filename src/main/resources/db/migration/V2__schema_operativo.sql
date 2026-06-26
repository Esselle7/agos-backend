-- ============================================================
-- V2 - Schema OPERATIVO (DDL)
--
-- Tabelle operative (nascono VUOTE): movimenti (partizionato),
-- cassa, eventi e correlate, piani ricorrenti, import/ETL, audit.
-- Dipende da V1 (anagrafica/config).
-- ============================================================

-- ---------- Funzione audit generica ----------
CREATE FUNCTION fn_audit_generic() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_nuovi)
        VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'INSERT', to_jsonb(NEW));

    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_precedenti, dati_nuovi)
        VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'UPDATE', to_jsonb(OLD), to_jsonb(NEW));

    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_precedenti)
        VALUES (TG_TABLE_NAME, OLD.id::TEXT, 'DELETE', to_jsonb(OLD));
    END IF;

    RETURN NULL; -- AFTER trigger: valore di ritorno ignorato per DML
END;
$$;

-- ============================================================
-- AUDIT LOG
-- ============================================================
CREATE TABLE audit_log (
    id              bigint NOT NULL,
    tabella         character varying(100) NOT NULL,
    record_id       character varying(255) NOT NULL,
    operazione      character varying(10) NOT NULL,
    dati_precedenti jsonb,
    dati_nuovi      jsonb,
    user_id         uuid,
    ip_address      inet,
    created_at      timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT audit_log_pkey PRIMARY KEY (id),
    CONSTRAINT audit_log_operazione_check CHECK (((operazione)::text = ANY ((ARRAY['INSERT'::character varying, 'UPDATE'::character varying, 'DELETE'::character varying])::text[]))),
    CONSTRAINT audit_log_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id)
);
CREATE SEQUENCE audit_log_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE audit_log_id_seq OWNED BY audit_log.id;
ALTER TABLE ONLY audit_log ALTER COLUMN id SET DEFAULT nextval('audit_log_id_seq'::regclass);

CREATE INDEX idx_audit_created_at ON audit_log USING btree (created_at DESC);
CREATE INDEX idx_audit_tabella_record ON audit_log USING btree (tabella, record_id);
CREATE INDEX idx_audit_user ON audit_log USING btree (user_id) WHERE (user_id IS NOT NULL);

-- ============================================================
-- IMPORT LOG
-- ============================================================
CREATE TABLE import_log (
    id                         uuid DEFAULT gen_random_uuid() NOT NULL,
    fonte                      character varying(50) NOT NULL,
    filename                   character varying(255),
    data_import                timestamp with time zone DEFAULT now() NOT NULL,
    righe_totali               integer,
    righe_importate            integer,
    righe_errore               integer,
    righe_duplicate            integer,
    stato                      character varying(50) DEFAULT 'IN_CORSO'::character varying NOT NULL,
    errori_dettaglio           jsonb,
    imported_by                uuid,
    righe_ambigue              integer DEFAULT 0 NOT NULL,
    righe_ambigue_classificate integer DEFAULT 0 NOT NULL,
    righe_scartate             integer DEFAULT 0 NOT NULL,
    righe_parcheggiate         integer DEFAULT 0 NOT NULL,
    CONSTRAINT import_log_pkey PRIMARY KEY (id),
    CONSTRAINT import_log_fonte_fkey FOREIGN KEY (fonte) REFERENCES lk_fonti_movimento(codice),
    CONSTRAINT import_log_imported_by_fkey FOREIGN KEY (imported_by) REFERENCES users(id),
    CONSTRAINT import_log_stato_fkey FOREIGN KEY (stato) REFERENCES lk_stati_import(codice)
);

CREATE INDEX idx_import_fonte_data ON import_log USING btree (fonte, data_import DESC);
CREATE INDEX idx_import_in_corso ON import_log USING btree (stato) WHERE ((stato)::text = 'IN_CORSO'::text);

-- ============================================================
-- EVENTI
-- ============================================================
CREATE TABLE eventi (
    id                          uuid DEFAULT gen_random_uuid() NOT NULL,
    nome                        character varying(255) NOT NULL,
    tipo                        character varying(50) NOT NULL,
    data_evento                 date NOT NULL,
    data_preventivo             date,
    importo_totale_preventivato numeric(12,2),
    importo_incassato           numeric(12,2) DEFAULT 0 NOT NULL,
    caparre_incassate           numeric(12,2) DEFAULT 0 NOT NULL,
    costi_diretti_imputati      numeric(12,2) DEFAULT 0 NOT NULL,
    stato                       character varying(50) DEFAULT 'PREVENTIVATO'::character varying NOT NULL,
    business_unit_id            smallint,
    contatto_nome               character varying(255),
    contatto_telefono           character varying(20),
    contatto_email              character varying(255),
    n_ospiti                    integer,
    note                        text,
    created_at                  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at                  timestamp with time zone,
    created_by                  uuid,
    note_annullamento           text,
    numero_totale_partecipanti  integer DEFAULT 0 NOT NULL,
    numero_bambini              integer,
    menu_pdf_url                character varying(500),
    CONSTRAINT eventi_pkey PRIMARY KEY (id),
    CONSTRAINT eventi_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id),
    CONSTRAINT eventi_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT eventi_stato_fkey FOREIGN KEY (stato) REFERENCES lk_stati_evento(codice),
    CONSTRAINT eventi_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_evento(codice)
);

CREATE INDEX idx_eventi_bu_data ON eventi USING btree (business_unit_id, data_evento DESC);
CREATE INDEX idx_eventi_data ON eventi USING btree (data_evento DESC);
CREATE INDEX idx_eventi_stato ON eventi USING btree (stato);

CREATE TRIGGER trg_eventi_updated_at BEFORE UPDATE ON eventi FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_audit_eventi AFTER INSERT OR DELETE OR UPDATE ON eventi FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- EVENTO_ALLERGIE
-- ============================================================
CREATE TABLE evento_allergie (
    id          bigint NOT NULL,
    evento_id   uuid NOT NULL,
    descrizione character varying(200) NOT NULL,
    CONSTRAINT evento_allergie_pkey PRIMARY KEY (id),
    CONSTRAINT evento_allergie_evento_id_fkey FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE
);
CREATE SEQUENCE evento_allergie_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_allergie_id_seq OWNED BY evento_allergie.id;
ALTER TABLE ONLY evento_allergie ALTER COLUMN id SET DEFAULT nextval('evento_allergie_id_seq'::regclass);

CREATE INDEX idx_allergie_evento ON evento_allergie USING btree (evento_id);

-- ============================================================
-- EVENTO_PARTECIPANTI
-- ============================================================
CREATE TABLE evento_partecipanti (
    id             bigint NOT NULL,
    evento_id      uuid NOT NULL,
    personale_id   uuid NOT NULL,
    ruolo          character varying(100),
    costo          numeric(15,2),
    note           character varying(500),
    ore            numeric(8,2),
    movimento_id   uuid,
    movimento_data date,
    CONSTRAINT evento_partecipanti_pkey PRIMARY KEY (id),
    CONSTRAINT uq_evento_personale UNIQUE (evento_id, personale_id),
    CONSTRAINT fk_ep_evento FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE,
    CONSTRAINT fk_ep_personale FOREIGN KEY (personale_id) REFERENCES personale(id)
);
CREATE SEQUENCE evento_partecipanti_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_partecipanti_id_seq OWNED BY evento_partecipanti.id;
ALTER TABLE ONLY evento_partecipanti ALTER COLUMN id SET DEFAULT nextval('evento_partecipanti_id_seq'::regclass);

CREATE INDEX idx_ep_evento ON evento_partecipanti USING btree (evento_id);
CREATE INDEX idx_ep_personale ON evento_partecipanti USING btree (personale_id);

-- ============================================================
-- EVENTO_COSTI_DIRETTI
-- ============================================================
CREATE TABLE evento_costi_diretti (
    id                bigint NOT NULL,
    evento_id         uuid NOT NULL,
    tipo_costo        character varying(20) NOT NULL,
    voce              character varying(30) NOT NULL,
    etichetta         character varying(200) NOT NULL,
    importo           numeric(15,2) NOT NULL,
    costo_per_persona numeric(15,2),
    prezzo_per_persona numeric(15,2),
    num_persone       integer,
    movimento_id      uuid,
    movimento_data    date,
    conto_coge_id     integer,
    note              character varying(500),
    created_by        uuid NOT NULL,
    created_at        timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT evento_costi_diretti_pkey PRIMARY KEY (id),
    CONSTRAINT evento_costi_diretti_tipo_costo_check CHECK (((tipo_costo)::text = ANY ((ARRAY['FISSO'::character varying, 'VARIABILE'::character varying])::text[]))),
    CONSTRAINT evento_costi_diretti_voce_check CHECK (((voce)::text = ANY ((ARRAY['AFFITTO_SALA'::character varying, 'DJ'::character varying, 'CATERING'::character varying, 'TORTA'::character varying, 'CUSTOM'::character varying])::text[]))),
    CONSTRAINT evento_costi_diretti_conto_coge_id_fkey FOREIGN KEY (conto_coge_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT evento_costi_diretti_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT evento_costi_diretti_evento_id_fkey FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE
);
CREATE SEQUENCE evento_costi_diretti_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_costi_diretti_id_seq OWNED BY evento_costi_diretti.id;
ALTER TABLE ONLY evento_costi_diretti ALTER COLUMN id SET DEFAULT nextval('evento_costi_diretti_id_seq'::regclass);

CREATE INDEX ix_evento_costi_diretti_evento ON evento_costi_diretti USING btree (evento_id);

-- ============================================================
-- EVENTO_PREVENTIVO_TRACKING
-- ============================================================
CREATE TABLE evento_preventivo_tracking (
    id                bigint NOT NULL,
    evento_id         uuid NOT NULL,
    tipo              character varying(20) NOT NULL,
    importo_incasso   numeric(15,2),
    costo_per_persona numeric(15,2),
    prezzo_per_persona numeric(15,2),
    num_persone       integer,
    note              character varying(500),
    created_by        uuid NOT NULL,
    created_at        timestamp with time zone DEFAULT now() NOT NULL,
    updated_at        timestamp with time zone,
    CONSTRAINT evento_preventivo_tracking_pkey PRIMARY KEY (id),
    CONSTRAINT uq_evento_preventivo_tracking UNIQUE (evento_id, tipo),
    CONSTRAINT evento_preventivo_tracking_tipo_check CHECK (((tipo)::text = ANY ((ARRAY['AFFITTO'::character varying, 'CATERING'::character varying])::text[]))),
    CONSTRAINT evento_preventivo_tracking_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT evento_preventivo_tracking_evento_id_fkey FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE
);
CREATE SEQUENCE evento_preventivo_tracking_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_preventivo_tracking_id_seq OWNED BY evento_preventivo_tracking.id;
ALTER TABLE ONLY evento_preventivo_tracking ALTER COLUMN id SET DEFAULT nextval('evento_preventivo_tracking_id_seq'::regclass);

CREATE INDEX ix_evento_preventivo_tracking_evento ON evento_preventivo_tracking USING btree (evento_id);

-- ============================================================
-- MOVIMENTI (partizionato per RANGE su data_movimento)
-- ============================================================
CREATE TABLE movimenti (
    id                    uuid DEFAULT gen_random_uuid() NOT NULL,
    data_movimento        date NOT NULL,
    tipo                  character varying(50) NOT NULL,
    importo_lordo         numeric(12,2) NOT NULL,
    importo_imponibile    numeric(12,2),
    importo_iva           numeric(12,2),
    importo_commissione   numeric(12,2) DEFAULT 0 NOT NULL,
    data_competenza       date,
    data_liquidita        date,
    conto_bancario_id     smallint,
    metodo_pagamento_id   integer,
    aliquota_iva_id       integer,
    conto_coge_id         integer NOT NULL,
    centro_di_costo_id    integer,
    business_unit_id      smallint NOT NULL,
    fornitore_id          uuid,
    evento_id             uuid,
    tipo_evento_movimento character varying(50),
    cespite_id            uuid,
    descrizione           character varying(500),
    note                  text,
    stato                 character varying(50) DEFAULT 'REGISTRATO'::character varying NOT NULL,
    fonte_importazione_id uuid,
    fonte                 character varying(50) NOT NULL,
    riferimento_esterno   character varying(255),
    allegato_path         character varying(500),
    created_by            uuid NOT NULL,
    created_at            timestamp with time zone DEFAULT now() NOT NULL,
    updated_at            timestamp with time zone,
    categoria_id          bigint,
    data_finanziaria      date
)
PARTITION BY RANGE (data_movimento);

-- Partizioni annuali + default
CREATE TABLE movimenti_2023 PARTITION OF movimenti FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE movimenti_2024 PARTITION OF movimenti FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE movimenti_2025 PARTITION OF movimenti FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE movimenti_2026 PARTITION OF movimenti FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE movimenti_2027 PARTITION OF movimenti FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE movimenti_default PARTITION OF movimenti DEFAULT;

-- PK e UNIQUE (devono includere la partition key data_movimento)
ALTER TABLE ONLY movimenti ADD CONSTRAINT movimenti_pkey PRIMARY KEY (id, data_movimento);
ALTER TABLE ONLY movimenti ADD CONSTRAINT movimenti_fonte_riferimento_esterno_data_movimento_key UNIQUE (fonte, riferimento_esterno, data_movimento);

-- FK (definite sul parent, propagate alle partizioni)
ALTER TABLE movimenti ADD CONSTRAINT movimenti_aliquota_iva_id_fkey FOREIGN KEY (aliquota_iva_id) REFERENCES aliquote_iva(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_categoria_id_fkey FOREIGN KEY (categoria_id) REFERENCES categorie(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_centro_di_costo_id_fkey FOREIGN KEY (centro_di_costo_id) REFERENCES centri_di_costo_coan(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_cespite_id_fkey FOREIGN KEY (cespite_id) REFERENCES cespiti(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_conto_bancario_id_fkey FOREIGN KEY (conto_bancario_id) REFERENCES conti_bancari(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_conto_coge_id_fkey FOREIGN KEY (conto_coge_id) REFERENCES piano_dei_conti_coge(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_evento_id_fkey FOREIGN KEY (evento_id) REFERENCES eventi(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_fonte_fkey FOREIGN KEY (fonte) REFERENCES lk_fonti_movimento(codice);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_fonte_importazione_id_fkey FOREIGN KEY (fonte_importazione_id) REFERENCES import_log(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_fornitore_id_fkey FOREIGN KEY (fornitore_id) REFERENCES fornitori(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_metodo_pagamento_id_fkey FOREIGN KEY (metodo_pagamento_id) REFERENCES metodi_pagamento(id);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_stato_fkey FOREIGN KEY (stato) REFERENCES lk_stati_movimento(codice);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_tipo_evento_movimento_fkey FOREIGN KEY (tipo_evento_movimento) REFERENCES lk_tipi_evento_mov(codice);
ALTER TABLE movimenti ADD CONSTRAINT movimenti_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_movimento(codice);

-- Indici (definiti sul parent, propagati alle partizioni)
CREATE INDEX idx_movimenti_attivi ON movimenti USING btree (data_movimento DESC, conto_bancario_id) WHERE ((stato)::text <> 'ANNULLATO'::text);
CREATE INDEX idx_movimenti_bu_data ON movimenti USING btree (business_unit_id, data_movimento DESC);
CREATE INDEX idx_movimenti_categoria ON movimenti USING btree (categoria_id) WHERE (categoria_id IS NOT NULL);
CREATE INDEX idx_movimenti_cespite ON movimenti USING btree (cespite_id) WHERE (cespite_id IS NOT NULL);
CREATE INDEX idx_movimenti_coge_data ON movimenti USING btree (conto_coge_id, data_movimento DESC);
CREATE INDEX idx_movimenti_competenza ON movimenti USING btree (data_competenza DESC) WHERE (data_competenza IS NOT NULL);
CREATE INDEX idx_movimenti_conto_data ON movimenti USING btree (conto_bancario_id, data_movimento DESC);
CREATE INDEX idx_movimenti_created_at ON movimenti USING btree (created_at DESC);
CREATE UNIQUE INDEX idx_movimenti_dedup_import ON movimenti USING btree (fonte, riferimento_esterno, data_movimento) WHERE (((fonte)::text = ANY ((ARRAY['IMPORT_BILLY'::character varying, 'IMPORT_BANCA'::character varying, 'IMPORT_ALVEARE'::character varying, 'IMPORT_FATTURA'::character varying])::text[])) AND (riferimento_esterno IS NOT NULL));
CREATE INDEX idx_movimenti_descrizione_trgm ON movimenti USING gin (lower((descrizione)::text) gin_trgm_ops);
CREATE INDEX idx_movimenti_evento ON movimenti USING btree (evento_id) WHERE (evento_id IS NOT NULL);
CREATE INDEX idx_movimenti_fonte_import ON movimenti USING btree (fonte_importazione_id) WHERE (fonte_importazione_id IS NOT NULL);
CREATE INDEX idx_movimenti_fonte_rif ON movimenti USING btree (fonte, riferimento_esterno);
CREATE INDEX idx_movimenti_fornitore ON movimenti USING btree (fornitore_id) WHERE (fornitore_id IS NOT NULL);
CREATE INDEX idx_movimenti_tipo_data ON movimenti USING btree (tipo, data_movimento DESC);

CREATE TRIGGER trg_audit_movimenti AFTER INSERT OR DELETE OR UPDATE ON movimenti FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- CASSA_MOVIMENTI
-- ============================================================
CREATE TABLE cassa_movimenti (
    id               uuid DEFAULT gen_random_uuid() NOT NULL,
    tipo             character varying(50) NOT NULL,
    importo          numeric(12,2) NOT NULL,
    data_movimento   date NOT NULL,
    descrizione      character varying(500),
    conto_coge_id    integer,
    business_unit_id smallint,
    conto_banca_id   smallint,
    created_by       uuid,
    created_at       timestamp with time zone DEFAULT now() NOT NULL,
    stato            character varying(50) DEFAULT 'REGISTRATO'::character varying NOT NULL,
    CONSTRAINT cassa_movimenti_pkey PRIMARY KEY (id),
    CONSTRAINT cassa_movimenti_importo_check CHECK ((importo > (0)::numeric)),
    CONSTRAINT cassa_movimenti_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id),
    CONSTRAINT cassa_movimenti_conto_banca_id_fkey FOREIGN KEY (conto_banca_id) REFERENCES conti_bancari(id),
    CONSTRAINT cassa_movimenti_conto_coge_id_fkey FOREIGN KEY (conto_coge_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT cassa_movimenti_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT cassa_movimenti_stato_fkey FOREIGN KEY (stato) REFERENCES lk_stati_movimento(codice),
    CONSTRAINT cassa_movimenti_tipo_fkey FOREIGN KEY (tipo) REFERENCES lk_tipi_cassa_mov(codice)
);

CREATE INDEX idx_cassa_bu ON cassa_movimenti USING btree (business_unit_id, data_movimento DESC);
CREATE INDEX idx_cassa_data ON cassa_movimenti USING btree (data_movimento DESC);
CREATE INDEX idx_cassa_stato ON cassa_movimenti USING btree (stato) WHERE ((stato)::text <> 'ANNULLATO'::text);

CREATE TRIGGER trg_audit_cassa_movimenti AFTER INSERT OR DELETE OR UPDATE ON cassa_movimenti FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- RECURRING EXPENSE PLAN / INSTALLMENT
-- ============================================================
CREATE TABLE recurring_expense_plan (
    id                      uuid DEFAULT gen_random_uuid() NOT NULL,
    descrizione             character varying(255) NOT NULL,
    business_unit_id        smallint DEFAULT 5 NOT NULL,
    conto_bancario_id       smallint NOT NULL,
    conto_coge_id           integer NOT NULL,
    importo_rata            numeric(12,2) NOT NULL,
    variazione_pct          numeric(6,3) DEFAULT 0 NOT NULL,
    giorno_del_mese         smallint NOT NULL,
    frequenza               character varying(20) NOT NULL,
    numero_rate             integer NOT NULL,
    data_prima_rata         date NOT NULL,
    stato                   character varying(20) DEFAULT 'ATTIVO'::character varying NOT NULL,
    importo_penale          numeric(12,2) DEFAULT 0 NOT NULL,
    note                    text,
    created_by              uuid NOT NULL,
    created_at              timestamp with time zone DEFAULT now() NOT NULL,
    updated_at              timestamp with time zone,
    tipo_piano              character varying(20) DEFAULT 'FLAT'::character varying NOT NULL,
    importo_debito_iniziale numeric(12,2),
    tasso_interesse_annuo   numeric(8,5),
    conto_coge_interessi_id integer,
    CONSTRAINT recurring_expense_plan_pkey PRIMARY KEY (id),
    CONSTRAINT recurring_expense_plan_frequenza_check CHECK (((frequenza)::text = ANY ((ARRAY['MENSILE'::character varying, 'BIMESTRALE'::character varying, 'TRIMESTRALE'::character varying])::text[]))),
    CONSTRAINT recurring_expense_plan_giorno_del_mese_check CHECK (((giorno_del_mese >= 1) AND (giorno_del_mese <= 28))),
    CONSTRAINT recurring_expense_plan_importo_rata_check CHECK ((importo_rata > (0)::numeric)),
    CONSTRAINT recurring_expense_plan_numero_rate_check CHECK ((numero_rate > 0)),
    CONSTRAINT recurring_expense_plan_stato_check CHECK (((stato)::text = ANY ((ARRAY['ATTIVO'::character varying, 'COMPLETATO'::character varying, 'ANNULLATO'::character varying])::text[]))),
    CONSTRAINT recurring_expense_plan_tipo_piano_check CHECK (((tipo_piano)::text = ANY ((ARRAY['FLAT'::character varying, 'FINANZIAMENTO'::character varying])::text[]))),
    CONSTRAINT recurring_expense_plan_business_unit_id_fkey FOREIGN KEY (business_unit_id) REFERENCES business_units(id),
    CONSTRAINT recurring_expense_plan_conto_bancario_id_fkey FOREIGN KEY (conto_bancario_id) REFERENCES conti_bancari(id),
    CONSTRAINT recurring_expense_plan_conto_coge_id_fkey FOREIGN KEY (conto_coge_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT recurring_expense_plan_conto_coge_interessi_id_fkey FOREIGN KEY (conto_coge_interessi_id) REFERENCES piano_dei_conti_coge(id)
);

CREATE INDEX idx_rep_stato ON recurring_expense_plan USING btree (stato);

CREATE TABLE recurring_expense_installment (
    id                     uuid DEFAULT gen_random_uuid() NOT NULL,
    piano_id               uuid NOT NULL,
    numero_rata            integer NOT NULL,
    data_scadenza          date NOT NULL,
    importo                numeric(12,2) NOT NULL,
    stato                  character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    movimento_id           uuid,
    note                   character varying(500),
    created_at             timestamp with time zone DEFAULT now() NOT NULL,
    updated_at             timestamp with time zone,
    quota_capitale         numeric(12,2),
    quota_interessi        numeric(12,2),
    movimento_interessi_id uuid,
    CONSTRAINT recurring_expense_installment_pkey PRIMARY KEY (id),
    CONSTRAINT recurring_expense_installment_piano_id_numero_rata_key UNIQUE (piano_id, numero_rata),
    CONSTRAINT recurring_expense_installment_importo_check CHECK ((importo > (0)::numeric)),
    CONSTRAINT recurring_expense_installment_stato_check CHECK (((stato)::text = ANY ((ARRAY['PENDING'::character varying, 'PAID'::character varying, 'CANCELLED'::character varying, 'SKIPPED'::character varying])::text[]))),
    CONSTRAINT recurring_expense_installment_piano_id_fkey FOREIGN KEY (piano_id) REFERENCES recurring_expense_plan(id) ON DELETE CASCADE
);

CREATE INDEX idx_rei_movimento ON recurring_expense_installment USING btree (movimento_id) WHERE (movimento_id IS NOT NULL);
CREATE INDEX idx_rei_piano ON recurring_expense_installment USING btree (piano_id);
CREATE INDEX idx_rei_scadenza ON recurring_expense_installment USING btree (data_scadenza) WHERE ((stato)::text = 'PENDING'::text);

-- ============================================================
-- SALDI_BANCA
-- ============================================================
CREATE TABLE saldi_banca (
    id               integer NOT NULL,
    conto_id         smallint NOT NULL,
    data_riferimento date NOT NULL,
    saldo            numeric(12,2) NOT NULL,
    CONSTRAINT saldi_banca_pkey PRIMARY KEY (id),
    CONSTRAINT uq_saldo_conto_data UNIQUE (conto_id, data_riferimento),
    CONSTRAINT saldi_banca_conto_id_fkey FOREIGN KEY (conto_id) REFERENCES conti_bancari(id)
);
CREATE SEQUENCE saldi_banca_id_seq AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE saldi_banca_id_seq OWNED BY saldi_banca.id;
ALTER TABLE ONLY saldi_banca ALTER COLUMN id SET DEFAULT nextval('saldi_banca_id_seq'::regclass);

CREATE INDEX idx_saldi_banca_data ON saldi_banca USING btree (conto_id, data_riferimento DESC);

-- ============================================================
-- IMPORT_AMBIGUITA (triage ETL)
-- ============================================================
CREATE TABLE import_ambiguita (
    id                uuid DEFAULT gen_random_uuid() NOT NULL,
    import_log_id     uuid NOT NULL,
    riga_numero       integer NOT NULL,
    fonte             character varying(50) NOT NULL,
    raw_data          jsonb NOT NULL,
    motivo            character varying(255) NOT NULL,
    stato             character varying(50) DEFAULT 'DA_CLASSIFICARE'::character varying NOT NULL,
    movimento_id      uuid,
    classificato_da   uuid,
    classificato_at   timestamp with time zone,
    note_operatore    text,
    created_at        timestamp with time zone DEFAULT now() NOT NULL,
    confidence        numeric(3,2),
    suggerimenti      jsonb,
    controparte_nome  text,
    controparte_iban  text,
    coge_suggerito_id integer,
    bu_suggerita      smallint,
    CONSTRAINT import_ambiguita_pkey PRIMARY KEY (id),
    CONSTRAINT import_ambiguita_bu_suggerita_fkey FOREIGN KEY (bu_suggerita) REFERENCES business_units(id),
    CONSTRAINT import_ambiguita_classificato_da_fkey FOREIGN KEY (classificato_da) REFERENCES users(id),
    CONSTRAINT import_ambiguita_coge_suggerito_id_fkey FOREIGN KEY (coge_suggerito_id) REFERENCES piano_dei_conti_coge(id),
    CONSTRAINT import_ambiguita_fonte_fkey FOREIGN KEY (fonte) REFERENCES lk_fonti_movimento(codice),
    CONSTRAINT import_ambiguita_import_log_id_fkey FOREIGN KEY (import_log_id) REFERENCES import_log(id) ON DELETE CASCADE
);

CREATE INDEX idx_import_ambiguita_log ON import_ambiguita USING btree (import_log_id, stato);

-- ============================================================
-- IMPORT_SCARTATI
-- ============================================================
CREATE TABLE import_scartati (
    id              uuid DEFAULT gen_random_uuid() NOT NULL,
    import_log_id   uuid NOT NULL,
    riga_numero     integer NOT NULL,
    fonte           character varying(50) NOT NULL,
    motivo          character varying(50) NOT NULL,
    chiave_aggancio character varying(80),
    data_movimento  date,
    importo         numeric(14,2),
    causale         character varying(255),
    raw_data        jsonb NOT NULL,
    created_at      timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT import_scartati_pkey PRIMARY KEY (id),
    CONSTRAINT import_scartati_fonte_fkey FOREIGN KEY (fonte) REFERENCES lk_fonti_movimento(codice),
    CONSTRAINT import_scartati_import_log_id_fkey FOREIGN KEY (import_log_id) REFERENCES import_log(id) ON DELETE CASCADE
);

CREATE INDEX idx_import_scartati_chiave ON import_scartati USING btree (chiave_aggancio);
CREATE INDEX idx_import_scartati_log ON import_scartati USING btree (import_log_id, motivo);

-- ============================================================
-- EVENTI_DA_RICONCILIARE
-- ============================================================
CREATE TABLE eventi_da_riconciliare (
    id                   uuid DEFAULT gen_random_uuid() NOT NULL,
    import_log_id        uuid NOT NULL,
    fonte                character varying(50) NOT NULL,
    chiave_aggancio      character varying(80),
    data_movimento       date,
    importo              numeric(14,2) NOT NULL,
    tipo                 character varying(10) DEFAULT 'ENTRATA'::character varying NOT NULL,
    conto_bancario_id    smallint,
    descrizione_norm     text,
    tipo_evento_presunto character varying(20),
    keyword_match        character varying(40),
    controparte_nome     text,
    controparte_iban     text,
    data_evento_estratta date,
    evento_id            uuid,
    stato                character varying(20) DEFAULT 'DA_RICONCILIARE'::character varying NOT NULL,
    raw_data             jsonb NOT NULL,
    created_at           timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT eventi_da_riconciliare_pkey PRIMARY KEY (id),
    CONSTRAINT eventi_da_riconciliare_importo_check CHECK ((importo > (0)::numeric)),
    CONSTRAINT eventi_da_riconciliare_stato_check CHECK (((stato)::text = ANY ((ARRAY['DA_RICONCILIARE'::character varying, 'RICONCILIATO'::character varying, 'SCARTATO'::character varying])::text[]))),
    CONSTRAINT eventi_da_riconciliare_conto_bancario_id_fkey FOREIGN KEY (conto_bancario_id) REFERENCES conti_bancari(id),
    CONSTRAINT eventi_da_riconciliare_fonte_fkey FOREIGN KEY (fonte) REFERENCES lk_fonti_movimento(codice),
    CONSTRAINT eventi_da_riconciliare_import_log_id_fkey FOREIGN KEY (import_log_id) REFERENCES import_log(id) ON DELETE CASCADE
);

CREATE INDEX idx_eventi_riconc_data_ev ON eventi_da_riconciliare USING btree (data_evento_estratta);
CREATE INDEX idx_eventi_riconc_iban ON eventi_da_riconciliare USING btree (controparte_iban);
CREATE INDEX idx_eventi_riconc_stato ON eventi_da_riconciliare USING btree (stato);
CREATE UNIQUE INDEX uq_eventi_riconc_chiave ON eventi_da_riconciliare USING btree (chiave_aggancio) WHERE (chiave_aggancio IS NOT NULL);

-- ============================================================
-- FUNZIONI di ricalcolo totali evento
-- (definite ma NON agganciate a trigger: trg_z_aggiorna_totali_evento
--  è stato rimosso in V20; le funzioni restano disponibili al backend)
-- ============================================================
CREATE FUNCTION fn_ricalcola_evento(p_evento_id uuid) RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    UPDATE eventi SET
        importo_incassato = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id = p_evento_id
              AND  m.tipo      = 'ENTRATA'
              AND  m.stato    != 'ANNULLATO'
        ), 0),
        caparre_incassate = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id             = p_evento_id
              AND  m.tipo_evento_movimento = 'CAPARRA'
              AND  m.tipo                  = 'ENTRATA'
              AND  m.stato                != 'ANNULLATO'
        ), 0),
        costi_diretti_imputati = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id = p_evento_id
              AND  m.tipo      = 'USCITA'
              AND  m.stato    != 'ANNULLATO'
        ), 0)
    WHERE id = p_evento_id;
END;
$$;

CREATE FUNCTION fn_aggiorna_totali_evento() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    v_evento_id UUID;
BEGIN
    -- In caso di UPDATE con cambio evento, ricalcola il vecchio evento
    IF TG_OP = 'UPDATE'
       AND OLD.evento_id IS DISTINCT FROM NEW.evento_id
       AND OLD.evento_id IS NOT NULL
    THEN
        PERFORM fn_ricalcola_evento(OLD.evento_id);
    END IF;

    -- Determina l'evento da aggiornare
    IF TG_OP = 'DELETE' THEN
        v_evento_id := OLD.evento_id;
    ELSE
        v_evento_id := NEW.evento_id;
    END IF;

    IF v_evento_id IS NOT NULL THEN
        PERFORM fn_ricalcola_evento(v_evento_id);
    END IF;

    RETURN NULL;
END;
$$;
