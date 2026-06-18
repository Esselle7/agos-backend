-- ============================================================
-- V6 — Metodi di pagamento per le causali banca BPM (riduzione ambigui)
-- ============================================================
-- Le righe banca diventano movimenti solo se hanno un metodo di pagamento, assegnato dal
-- normalizzatore in base alla causale. BPM usa codici causale numerici interni: finora ne
-- erano mappati pochi, quindi pagamenti carta e addebiti automatici (commissioni, competenze,
-- imposte di bollo c/c) restavano CAUSALE_NON_MAPPATA → ambigui. Vedi AMBIGUI-OVERVIEW.md.
--
-- Si introducono due metodi mancanti, distinti con criterio dallo strumento di pagamento:
--   * CARTA_DEBITO  : pagamenti POS/online con carta di debito aziendale (causale BPM 118).
--   * ADDEBITO_CONTO: addebiti automatici sul conto non SEPA (commissioni, spese, competenze,
--                     interessi, imposta di bollo c/c) — distinti dal RID/SDD (mandato SEPA).

INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES
    (11, 'CARTA_DEBITO',   'Carta di debito / bancomat (pagamenti in uscita)', true),
    (12, 'ADDEBITO_CONTO', 'Addebito automatico sul conto (commissioni, spese, competenze, bolli)', true)
ON CONFLICT (id) DO NOTHING;

-- Allinea la sequence al nuovo massimo.
SELECT setval(pg_get_serial_sequence('metodi_pagamento','id'), COALESCE((SELECT MAX(id) FROM metodi_pagamento), 1));
