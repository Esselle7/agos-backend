-- ============================================================
-- V36 - Costi diretti evento (cerimonie)
-- Permette di registrare dalla pagina evento i costi diretti
-- (fissi e variabili). Ogni costo genera un movimento USCITA
-- DA_LIQUIDARE collegato all'evento.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Piano dei conti CoGe – mastro 40.13 e sottoconti
-- ------------------------------------------------------------

-- 40.13 – Costi diretti eventi (cerimonie)
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.13', 'Costi diretti eventi', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.13.001', 'Affitto sala / location eventi', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13')),
    ('40.13.002', 'DJ e intrattenimento eventi',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13')),
    ('40.13.003', 'Catering esterno eventi',        'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13')),
    ('40.13.004', 'Torta eventi',                   'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13')),
    ('40.13.005', 'Altri costi diretti eventi',     'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.13'));

-- ------------------------------------------------------------
-- 2. Tabella evento_costi_diretti
-- ------------------------------------------------------------
-- (movimento_id, movimento_data) replica la PK composita di movimenti
-- (id, data_movimento) per il join lato Java. Niente FK composita verso
-- la tabella partizionata: Postgres non supporta FK verso tabelle partizionate.

CREATE TABLE evento_costi_diretti (
    id               BIGSERIAL PRIMARY KEY,
    evento_id        UUID          NOT NULL REFERENCES eventi(id) ON DELETE CASCADE,
    tipo_costo       VARCHAR(20)   NOT NULL CHECK (tipo_costo IN ('FISSO','VARIABILE')),
    voce             VARCHAR(30)   NOT NULL CHECK (voce IN ('AFFITTO_SALA','DJ','CATERING','TORTA','CUSTOM')),
    etichetta        VARCHAR(200)  NOT NULL,
    importo          NUMERIC(15,2) NOT NULL,
    -- Solo per CATERING
    costo_per_persona   NUMERIC(15,2),
    prezzo_per_persona  NUMERIC(15,2),
    num_persone         INT,
    -- Collegamento al movimento generato (PK composita di movimenti)
    movimento_id     UUID,
    movimento_data   DATE,
    conto_coge_id    INT           REFERENCES piano_dei_conti_coge(id),
    note             VARCHAR(500),
    created_by       UUID          NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX ix_evento_costi_diretti_evento ON evento_costi_diretti(evento_id);
