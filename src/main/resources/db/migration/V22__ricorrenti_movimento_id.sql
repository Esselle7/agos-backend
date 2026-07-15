-- ============================================================
-- V22 — Tracciabilità del movimento generato dalla CONFERMA di una ricorrente
-- ============================================================
-- La coda ricorrenti_da_riconciliare (V9) diventa AZIONABILE: l'azione CONFERMA crea un
-- movimento contabile reale (rata mutuo/finanziamento/assicurazione già addebitata in banca
-- ma non ancora a libro dopo la ricreazione dei piani sul debito residuo).
-- Serve tracciare QUALE movimento è nato dalla riga (audit + idempotenza a vista).
--
-- NB: nessuna FK verso movimenti: la tabella movimenti è PARTIZIONATA e una FK cross-partizione
-- non è supportata. Resta una colonna uuid "debole"; il rollback dell'import cancella comunque
-- il movimento in cascata via import_log_id (createMovimentoImport lega fonte_importazione_id).
ALTER TABLE ricorrenti_da_riconciliare ADD COLUMN IF NOT EXISTS movimento_id uuid;

-- Nuovo valore di stato 'CONFERMATA' (colonna varchar, nessun CHECK da estendere):
-- DA_RICONCILIARE | CONFERMATA | IGNORATA (RICONCILIATA resta per righe collegate in passato).
COMMENT ON COLUMN ricorrenti_da_riconciliare.stato IS
    'DA_RICONCILIARE | CONFERMATA | IGNORATA | RICONCILIATA(legacy COLLEGA)';
