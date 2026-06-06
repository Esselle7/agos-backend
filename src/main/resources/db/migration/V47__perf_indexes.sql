-- ============================================================
-- V47 - Indici di performance (nessuna modifica di schema logico)
--
-- Solo indici: nessuna colonna/vincolo/dato cambia. Pensati per le due query
-- che oggi fanno sequential scan su movimenti.
-- ============================================================

-- 1. Rollback import + conteggio righe per import_log_id.
--    Usato da MovimentoImportService.rollbackImport (DELETE WHERE fonte_importazione_id = :id)
--    e dalle COUNT per import. Indice parziale: solo le righe importate (le manuali sono NULL).
CREATE INDEX IF NOT EXISTS idx_movimenti_fonte_import
    ON movimenti (fonte_importazione_id)
    WHERE fonte_importazione_id IS NOT NULL;

-- 2. Ricerca testo nella lista movimenti.
--    MovimentiRepository filtra con `LOWER(m.descrizione) LIKE '%term%'`: un LIKE con
--    wildcard iniziale non usa un btree. L'indice GIN trigram sull'ESPRESSIONE lower(descrizione)
--    combacia esattamente col predicato, quindi il planner lo sfrutta SENZA toccare la query JPQL.
CREATE EXTENSION IF NOT EXISTS pg_trgm; -- già presente da V43; IF NOT EXISTS è idempotente
CREATE INDEX IF NOT EXISTS idx_movimenti_descrizione_trgm
    ON movimenti USING gin (lower(descrizione) gin_trgm_ops);
