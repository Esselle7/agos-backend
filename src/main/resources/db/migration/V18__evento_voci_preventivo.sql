-- V18 — Voci di preventivo/consuntivo per evento + catalogo di label riutilizzabili.
--
-- Il preventivo dell'evento diventa scomponibile in voci a righe: ciascuna ha un
-- importo preventivato e (dalla data evento in poi) un importo consuntivato.
-- Le voci NON generano movimenti (pura pianificazione lato ricavo verso cliente).
-- I costi diretti restano movimenti; possono opzionalmente creare una voce di ricarico.
--
-- eventi.importo_totale_preventivato resta la colonna persistita letta da tutti i
-- consumatori (MV, dashboard, forecasting, scadenzario, scheduler, macchina a stati):
-- il service la ricalcola come Σ voci dopo ogni mutazione (pattern ricalcolaIncassi).
--
-- gira su tutti i profili (db/migration). Il backfill è no-op in `test` (nessun evento).

-- ── Catalogo label riutilizzabili ────────────────────────────────────────────
CREATE TABLE evento_voce_catalogo (
    id         bigint NOT NULL,
    label      character varying(120) NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    ordine     smallint NOT NULL DEFAULT 0,
    attivo     boolean NOT NULL DEFAULT true,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT evento_voce_catalogo_pkey PRIMARY KEY (id),
    CONSTRAINT uq_evento_voce_catalogo_label UNIQUE (label)
);
CREATE SEQUENCE evento_voce_catalogo_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_voce_catalogo_id_seq OWNED BY evento_voce_catalogo.id;
ALTER TABLE ONLY evento_voce_catalogo ALTER COLUMN id SET DEFAULT nextval('evento_voce_catalogo_id_seq'::regclass);

-- ── Voci di preventivo/consuntivo per evento ─────────────────────────────────
CREATE TABLE evento_voce (
    id                 bigint NOT NULL,
    evento_id          uuid NOT NULL,
    catalogo_id        bigint,
    label              character varying(120) NOT NULL,
    importo_preventivo numeric(15,2) NOT NULL DEFAULT 0,
    importo_consuntivo numeric(15,2),
    origine            character varying(20) NOT NULL DEFAULT 'MANUALE',
    costo_diretto_id   bigint,
    note               character varying(500),
    created_by         uuid,
    created_at         timestamp with time zone NOT NULL DEFAULT now(),
    updated_at         timestamp with time zone,
    CONSTRAINT evento_voce_pkey PRIMARY KEY (id),
    CONSTRAINT evento_voce_origine_check CHECK (((origine)::text = ANY ((ARRAY['MANUALE'::character varying, 'COSTO_DIRETTO'::character varying])::text[]))),
    CONSTRAINT evento_voce_prev_check CHECK (importo_preventivo >= (0)::numeric),
    CONSTRAINT evento_voce_cons_check CHECK (importo_consuntivo IS NULL OR importo_consuntivo >= (0)::numeric),
    CONSTRAINT evento_voce_costo_origine_check CHECK (costo_diretto_id IS NULL OR (origine)::text = 'COSTO_DIRETTO'::text),
    CONSTRAINT evento_voce_evento_fkey FOREIGN KEY (evento_id) REFERENCES eventi(id) ON DELETE CASCADE,
    CONSTRAINT evento_voce_catalogo_fkey FOREIGN KEY (catalogo_id) REFERENCES evento_voce_catalogo(id),
    CONSTRAINT evento_voce_costo_fkey FOREIGN KEY (costo_diretto_id) REFERENCES evento_costi_diretti(id) ON DELETE CASCADE,
    CONSTRAINT evento_voce_created_by_fkey FOREIGN KEY (created_by) REFERENCES users(id)
);
CREATE SEQUENCE evento_voce_id_seq START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;
ALTER SEQUENCE evento_voce_id_seq OWNED BY evento_voce.id;
ALTER TABLE ONLY evento_voce ALTER COLUMN id SET DEFAULT nextval('evento_voce_id_seq'::regclass);

CREATE INDEX ix_evento_voce_evento ON evento_voce USING btree (evento_id);
CREATE INDEX ix_evento_voce_costo ON evento_voce USING btree (costo_diretto_id) WHERE costo_diretto_id IS NOT NULL;
-- Una stessa voce di catalogo può stare al più una volta per evento (default guidati inclusi).
-- Le righe custom senza catalogo e quelle da costo diretto possono ripetersi.
CREATE UNIQUE INDEX uq_evento_voce_catalogo_per_evento ON evento_voce USING btree (evento_id, catalogo_id) WHERE catalogo_id IS NOT NULL;

-- ── Seed catalogo: default guidati (Affitto quota + Menu/catering quota) ──────
INSERT INTO evento_voce_catalogo (label, is_default, ordine) VALUES
    ('Affitto', true, 1),
    ('Menu',    true, 2);

-- ── Backfill retrocompatibilità ──────────────────────────────────────────────
-- Una voce "Preventivo iniziale" per ogni evento con preventivato valorizzato:
-- preventivo = consuntivo = totale attuale, così i totali storici restano identici
-- e nessun ricalcolo li porta a zero. No-op nel profilo `test`.
INSERT INTO evento_voce (evento_id, catalogo_id, label, importo_preventivo, importo_consuntivo, origine, created_by, created_at)
SELECT e.id, NULL, 'Preventivo iniziale', e.importo_totale_preventivato, e.importo_totale_preventivato, 'MANUALE', e.created_by, now()
FROM eventi e
WHERE e.importo_totale_preventivato IS NOT NULL;
