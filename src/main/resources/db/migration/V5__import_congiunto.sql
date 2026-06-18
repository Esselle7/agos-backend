-- ============================================================
-- V5 — Import ETL congiunto (Billy + BPM + CA)
-- ============================================================
-- Il flusso congiunto carica i 3 file insieme e li riconcilia in un'unica fonte
-- di verità prima della persistenza (vedi REFACTOR-IMPORT-CONGIUNTO.md). Per avere
-- UNA sola riga import_log per l'operazione (rollback atomico dei 3 file) serve un
-- codice fonte dedicato, referenziato da import_log.fonte (FK lk_fonti_movimento).
--
-- NB: i singoli movimenti continuano a nascere con la loro fonte reale per-riga
-- (IMPORT_BANCA / IMPORT_BILLY), così la dedup idx_movimenti_dedup_import resta
-- valida; IMPORT_CONGIUNTO vive solo a livello di import_log (grouping + rollback).
-- Nessuna colonna nuova su movimenti, nessuno stato nuovo: i residui senza match
-- riusano il transitorio 39.99.999 già esistente.

INSERT INTO lk_fonti_movimento (codice, descrizione)
VALUES ('IMPORT_CONGIUNTO', 'Importazione congiunta riconciliata (Billy + BPM + CA)')
ON CONFLICT (codice) DO NOTHING;
