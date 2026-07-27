-- ============================================================
-- V23 — Tracciabilità del movimento di PENALE generato all'annullamento di un piano ricorrente
-- ============================================================
-- All'annullamento (cancelPlan) con penale > 0 nasce un Movimento USCITA (fonte='RICORRENTE',
-- descrizione '<descr> – Penale cancellazione') il cui id finora NON era referenziato da nulla.
-- Serve tracciarlo perché la nuova azione "cestina" (purga fisica totale di un piano ANNULLATO)
-- deve cancellare anche quel movimento in modo affidabile per id.
--
-- Colonna uuid "debole": nessuna FK verso movimenti (tabella PARTIZIONATA, FK cross-partizione
-- non supportata — cfr. V22). La cestina cancella i movimenti esplicitamente per id.
ALTER TABLE recurring_expense_plan ADD COLUMN IF NOT EXISTS movimento_penale_id uuid;

-- Backfill una-tantum per i piani GIÀ ANNULLATO con penale: aggancia il movimento di penale
-- solo quando il match è UNIVOCO (fonte + descrizione esatta + conto + importo). Se i candidati
-- sono 0 o >1 la colonna resta NULL: degrado sicuro (meglio lasciare la penale che cancellare
-- il movimento sbagliato).
UPDATE recurring_expense_plan p
SET movimento_penale_id = m.id
FROM movimenti m
WHERE p.stato = 'ANNULLATO'
  AND p.importo_penale > 0
  AND m.fonte = 'RICORRENTE'
  AND m.tipo = 'USCITA'
  AND m.conto_bancario_id = p.conto_bancario_id
  AND m.importo_lordo = p.importo_penale
  AND m.descrizione = p.descrizione || ' – Penale cancellazione'
  AND (
      SELECT COUNT(*) FROM movimenti m2
      WHERE m2.fonte = 'RICORRENTE'
        AND m2.tipo = 'USCITA'
        AND m2.conto_bancario_id = p.conto_bancario_id
        AND m2.importo_lordo = p.importo_penale
        AND m2.descrizione = p.descrizione || ' – Penale cancellazione'
  ) = 1;
