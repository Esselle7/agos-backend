-- ============================================================
-- V26 - CLEANUP: svuotamento tabelle movimenti ed eventi
--
-- ATTENZIONE: questa migration cancella TUTTI i movimenti e TUTTI gli
-- eventi presenti in DB (in dev, test e prod). Serve per ripartire da
-- una base pulita prima del seed di esempio V27.
--
-- COSA VIENE CANCELLATO:
--   - movimenti          (DELETE su tabella partizionata → tutte le partizioni)
--   - eventi             (DELETE)
--   - evento_allergie    (ON DELETE CASCADE su eventi)
--
-- COSA VIENE PRESERVATO:
--   - users, business_units, piano_dei_conti_coge, categorie,
--     fornitori, conti_bancari, metodi_pagamento, aliquote_iva,
--     personale, cespiti, recurring_expense_plan,
--     recurring_expense_installment (movimento_id messo a NULL),
--     saldi_banca, import_log, lookup tables.
--
-- NOTE:
--   - recurring_expense_installment.movimento_id non ha FK formale ma è
--     un soft reference. Lo nullifichiamo prima del DELETE per evitare
--     riferimenti penzolanti.
--   - Le viste materializzate dipendenti da movimenti/eventi vengono
--     ricaricate da V27 dopo il seed.
-- ============================================================

-- 1. Rimuovi i soft-reference da rate ricorrenti verso movimenti
UPDATE recurring_expense_installment
SET    movimento_id = NULL
WHERE  movimento_id IS NOT NULL;

-- 2. Svuota movimenti (cancella anche da tutte le partizioni)
DELETE FROM movimenti;

-- 3. Svuota eventi (CASCADE clear evento_allergie via FK ON DELETE CASCADE)
DELETE FROM eventi;
