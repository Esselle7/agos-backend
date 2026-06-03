-- ============================================================
-- V42 - Coda eventi da riconciliare (ETL_CLASSIFICAZIONE_v2 §5/§9.3)
--
-- Bucket dei movimenti-evento (caparre/acconti/saldi cerimonie) messi da parte
-- dal Gate B. NON sono movimenti contabili finché non verranno riconciliati
-- dall'evolutiva futura con l'anagrafica `eventi`.
--
-- Dedup cross-sorgente: lo stesso incasso-evento compare su Billy E sull'estratto
-- conto (CA/BPM) con la STESSA "Chiave Aggancio" (numeroMovBanca/importo). L'indice
-- UNIQUE su chiave_aggancio (quando valorizzata) evita i doppioni.
-- ============================================================

CREATE TABLE eventi_da_riconciliare (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id         UUID         NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    fonte                 VARCHAR(50)  NOT NULL REFERENCES lk_fonti_movimento(codice),
    chiave_aggancio       VARCHAR(80),       -- dedup cross-sorgente (null se non agganciabile)
    data_movimento        DATE,
    importo               NUMERIC(14,2) NOT NULL CHECK (importo > 0),
    tipo                  VARCHAR(10)  NOT NULL DEFAULT 'ENTRATA',
    conto_bancario_id     SMALLINT     REFERENCES conti_bancari(id),
    descrizione_norm      TEXT,
    tipo_evento_presunto  VARCHAR(20),       -- CAPARRA | ACCONTO | SALDO | AFFITTO_SALA | null
    keyword_match         VARCHAR(40),       -- keyword che ha attivato il parcheggio
    controparte_nome      TEXT,              -- ordinante estratto
    controparte_iban      TEXT,              -- IBAN estratto
    data_evento_estratta  DATE,              -- data trovata in descrizione (se presente)
    evento_id             UUID,              -- null finché non riconciliato (FK futura → eventi)
    stato                 VARCHAR(20)  NOT NULL DEFAULT 'DA_RICONCILIARE'
                          CHECK (stato IN ('DA_RICONCILIARE','RICONCILIATO','SCARTATO')),
    raw_data              JSONB        NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Dedup cross-sorgente: una sola riga per Chiave Aggancio valorizzata.
CREATE UNIQUE INDEX uq_eventi_riconc_chiave
    ON eventi_da_riconciliare (chiave_aggancio)
    WHERE chiave_aggancio IS NOT NULL;

CREATE INDEX idx_eventi_riconc_stato   ON eventi_da_riconciliare (stato);
CREATE INDEX idx_eventi_riconc_data_ev ON eventi_da_riconciliare (data_evento_estratta);
CREATE INDEX idx_eventi_riconc_iban    ON eventi_da_riconciliare (controparte_iban);

-- Conteggio parcheggiati sull'import_log (coerente con righe_ambigue/righe_scartate).
ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_parcheggiate INT NOT NULL DEFAULT 0;
