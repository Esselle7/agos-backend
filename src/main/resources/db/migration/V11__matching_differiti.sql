-- ============================================================
-- V11 — Matching differiti (Feature: riconciliazione import banche ↔ movimenti Da Liquidare)
-- ============================================================
-- Quando l'import delle banche produce una riga che combacia (importo al centesimo + descrizione
-- uguale) con un movimento MANUALE già presente in stato DA_LIQUIDARE (non ancora liquidato), la
-- riga NON viene persistita come nuovo movimento (rischio doppia registrazione). Viene parcheggiata
-- in questa coda: l'utente decide poi dallo smistamento se:
--   COLLEGA → liquida il movimento Da Liquidare esistente con i dati della riga banca
--             (dataFinanziaria = data banca, conto bancario, metodo pagamento, stato = REGISTRATO);
--   IGNORA  → crea comunque un nuovo movimento dalla riga banca (falso positivo del match).
--
-- Le rate dei piani di spesa ricorrente NON finiscono qui: sono sempre REGISTRATE (lo scheduler
-- le liquida alla scadenza), quindi non sono MAI DA_LIQUIDARE al momento dell'import.
--
-- Geometria speculare a ricorrenti_da_riconciliare (V9): coda di lavorazione con stato, in
-- ON DELETE CASCADE su import_log (rollback atomico).

CREATE TABLE matching_differiti (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id       uuid NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    -- Movimento DA_LIQUIDARE già esistente in gestionale che combacia con la riga banca.
    -- UUID "weak link" (senza FK enforced): la tabella movimenti è partizionata per anno su
    -- data_movimento e le FK verso di essa sono problematiche; lo stesso pattern è usato da
    -- import_ambiguita.movimento_id ed eventi_da_riconciliare.evento_id.
    movimento_id        uuid NOT NULL,
    -- Campi della riga banca (per display + ricostruzione su IGNORA)
    fonte               varchar(50) NOT NULL,           -- IMPORT_BANCA | IMPORT_BILLY
    riga_numero         integer,
    data_banca          date,                           -- dataMovimento della riga banca
    importo             numeric(14,2) NOT NULL,
    descrizione         text,
    conto_bancario_id   smallint,
    -- Stato risoluzione: DA_RICONCILIARE | COLLEGATO | IGNORATO
    stato               varchar(20) NOT NULL DEFAULT 'DA_RICONCILIARE',
    -- MovimentoCreateRequest serializzato JSON: serve per ricostruire il movimento su IGNORA
    -- (la riga banca non è stata persistita come movimento in fase di import per evitare doppia
    -- registrazione). Su COLLEGA non è usato.
    raw_request         jsonb NOT NULL,
    note                text,
    risolto_at          timestamptz,
    risolto_by          uuid,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_matchdiff_stato   ON matching_differiti(stato);
CREATE INDEX idx_matchdiff_import  ON matching_differiti(import_log_id);
CREATE INDEX idx_matchdiff_mov     ON matching_differiti(movimento_id);

-- Contatore dedicato nell'import_log (come già fatto per righe_ricorrenti in V9).
ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_matching_differiti integer NOT NULL DEFAULT 0;
