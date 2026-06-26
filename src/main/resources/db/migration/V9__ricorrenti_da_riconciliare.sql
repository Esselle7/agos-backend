-- ============================================================
-- V9 — Parcheggio delle spese ricorrenti / finanziamenti
-- ============================================================
-- Mutui, rate di finanziamento, leasing, canoni, cambiali NON devono diventare movimenti
-- contabili dall'import: sono gestiti a mano nel modulo "Spese Ricorrenti" (recurring_expense_plan).
-- Finora il Gate A li marcava SKIP_RICORRENTE → import_scartati (spariti silenziosamente).
-- Ora, nel flusso CONGIUNTO, vengono PARCHEGGIATI in una coda visibile (gemella di
-- eventi_da_riconciliare): l'utente li riconcilia collegandoli al piano ricorrente, oppure li
-- ignora. Nessun effetto contabile: il modulo Spese Ricorrenti resta l'unica fonte di verità.

CREATE TABLE ricorrenti_da_riconciliare (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    import_log_id      uuid NOT NULL REFERENCES import_log(id) ON DELETE CASCADE,
    fonte              varchar(50) NOT NULL,
    data_movimento     date,
    importo            numeric(14,2) NOT NULL,
    tipo               varchar(10) NOT NULL DEFAULT 'USCITA',
    conto_bancario_id  smallint,
    descrizione_norm   text,
    tipo_presunto      varchar(20),   -- MUTUO|FINANZIAMENTO|LEASING|CANONE|CAMBIALE|ASSICURAZIONE|BOLLO|RATA|ALTRO
    keyword_match      varchar(40),
    recurring_plan_id  uuid REFERENCES recurring_expense_plan(id) ON DELETE SET NULL,
    stato              varchar(20) NOT NULL DEFAULT 'DA_RICONCILIARE', -- DA_RICONCILIARE|RICONCILIATA|IGNORATA
    raw_data           jsonb NOT NULL,
    note               text,
    risolto_at         timestamptz,
    risolto_by         uuid,
    created_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_ricorrenti_stato ON ricorrenti_da_riconciliare(stato);
CREATE INDEX idx_ricorrenti_import ON ricorrenti_da_riconciliare(import_log_id);

-- Contatore dedicato nell'import_log (le ricorrenti non sono né scartate né parcheggiate-eventi).
ALTER TABLE import_log ADD COLUMN IF NOT EXISTS righe_ricorrenti integer NOT NULL DEFAULT 0;
