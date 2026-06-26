-- ============================================================
-- V13 — Indici per data-retention + partizioni movimenti future
-- ============================================================
-- Solo DDL (indici + partizioni). La CANCELLAZIONE periodica NON sta in Flyway:
-- gira nel job notturno RetentionScheduler (vedi infrastructure/scheduler).
--
-- Modello retention deciso (vincolo: NON rompere integrità referenziale, NON
-- toccare dati contabili):
--   audit_log         → DELETE > 6 mesi   (nessuno la referenzia: sicuro)
--   import_scartati   → DELETE > 3 mesi   (righe scartate all'import, jsonb pesante)
--   import_ambiguita  → DELETE > 3 mesi   SOLO se già classificate (triage pendente resta)
--   import_log        → DELETE > 3 mesi   SOLO se orfano (nessun movimento la cita
--                                          via fonte_importazione_id: provenance intatta)
--   eventi            → MAI cancellati    (referenziati da movimenti, tabella piccola)
--   movimenti         → MAI cancellati    (partizionati per anno)
--
-- Indici: audit_log.created_at e import_log.data_import esistono già. Mancano i
-- created_at delle due tabelle di staging più pesanti (raw_data jsonb).
-- ============================================================

CREATE INDEX IF NOT EXISTS idx_import_scartati_created
    ON import_scartati (created_at);

-- Partial: il job cancella solo le righe già classificate; l'indice copre la WHERE.
CREATE INDEX IF NOT EXISTS idx_import_ambiguita_created_classificate
    ON import_ambiguita (created_at)
    WHERE stato <> 'DA_CLASSIFICARE';

-- ------------------------------------------------------------
-- Partizioni movimenti future. Oggi l'ultima nominata è 2027 e il 2028+ finisce
-- in movimenti_default (storage non ottimizzato). Le pre-creo per qualche anno:
-- più semplice e robusto di un job DDL a runtime (1 partizione/anno).
-- IF NOT EXISTS → migration idempotente.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS movimenti_2028 PARTITION OF movimenti FOR VALUES FROM ('2028-01-01') TO ('2029-01-01');
CREATE TABLE IF NOT EXISTS movimenti_2029 PARTITION OF movimenti FOR VALUES FROM ('2029-01-01') TO ('2030-01-01');
CREATE TABLE IF NOT EXISTS movimenti_2030 PARTITION OF movimenti FOR VALUES FROM ('2030-01-01') TO ('2031-01-01');
