-- ============================================================
-- V46 - Rimozione dello stato RICONCILIATO dei movimenti
--
-- RICONCILIATO non aveva alcun impatto contabile: in OGNI vista/report il filtro
-- è `stato != 'ANNULLATO'`, quindi RICONCILIATO era trattato identico a REGISTRATO.
-- Era solo una spunta operativa della (ora rimossa) quadratura Billy↔estratto conto.
--
-- ATTENZIONE - NON tocca due feature omonime ma DISTINTE:
--   • eventi_da_riconciliare  → coda eventi ETL (ha un suo stato su altra tabella, CHECK)
--   • mv_riconciliazione_bancaria → quadratura saldo calcolato vs estratto conto (saldi_banca)
-- ============================================================

-- 1. Migra i movimenti esistenti PRIMA di rimuovere il valore di lookup (FK).
UPDATE movimenti SET stato = 'REGISTRATO' WHERE stato = 'RICONCILIATO';

-- 2. Rimuove il valore di lookup (ora non più referenziato da alcun movimento).
DELETE FROM lk_stati_movimento WHERE codice = 'RICONCILIATO';
