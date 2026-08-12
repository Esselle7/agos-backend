-- V31 — import_scartati diventa una CODA: le righe escluse dalla pipeline si guardano e si chiudono.
--
-- Prima: la tabella era un registro morto (nessun endpoint di lettura, nessuna schermata). Sul
-- corpus storico ci stanno 9 righe SKIP_POS (959,55 €) + 1 SKIP_CODA_TESTA (230,00 €) di accrediti
-- bancari VERI mai contabilizzati e mai mostrati: 1.189,55 € muti in sei mesi
-- (docs/specs/audit-catena-import-2026-08-11.md §2, §3, §7.4 · SPEC docs/specs/righe-fuori-dai-conti.md).
--
-- stato: DA_VEDERE (default) → CONTABILIZZATA (movimento creato) | IGNORATA (denaro già contato).
-- La riga risolta NON si cancella (invariante I1): resta con movimento_id e chi/quando (I1, I2).
--
-- Idempotente: IF NOT EXISTS su colonne e indice.

ALTER TABLE import_scartati
    ADD COLUMN IF NOT EXISTS stato        character varying(20) DEFAULT 'DA_VEDERE' NOT NULL,
    ADD COLUMN IF NOT EXISTS movimento_id uuid,
    ADD COLUMN IF NOT EXISTS risolto_at   timestamp with time zone,
    ADD COLUMN IF NOT EXISTS risolto_by   uuid;

-- La coda si legge SEMPRE filtrata su DA_VEDERE: indice parziale, piccolo quanto la coda.
CREATE INDEX IF NOT EXISTS idx_import_scartati_da_vedere
    ON import_scartati (data_movimento) WHERE stato = 'DA_VEDERE';
