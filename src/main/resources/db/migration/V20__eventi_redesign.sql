-- ============================================================
-- V20 - EVENTI REDESIGN: nuovi stati, tipi pagamento, campi,
--       tabella allergie, rimozione trigger (logica in Java)
-- ============================================================

-- ── 1. Nuovi stati evento ─────────────────────────────────────────────────────

INSERT INTO lk_stati_evento (codice, descrizione) VALUES
    ('PREVENTIVATO', 'Preventivo in attesa di conferma'),
    ('SALDATO',      'Evento completamente saldato')
ON CONFLICT (codice) DO NOTHING;

-- ── 2. Migrazione dati: vecchi stati → nuovi stati ────────────────────────────

UPDATE eventi SET stato = 'PREVENTIVATO' WHERE stato = 'PREVENTIVO';
UPDATE eventi SET stato = 'SALDATO'       WHERE stato = 'COMPLETATO';

-- ── 3. Rimuovi vecchi stati (nessun FK violato dopo la migrazione) ─────────────

DELETE FROM lk_stati_evento WHERE codice IN ('PREVENTIVO', 'COMPLETATO');

-- ── 4. Nuovo DEFAULT sulla colonna stato ──────────────────────────────────────

ALTER TABLE eventi ALTER COLUMN stato SET DEFAULT 'PREVENTIVATO';

-- ── 5. Nuovo tipo pagamento: PENALE ───────────────────────────────────────────

INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES
    ('PENALE', 'Penale per annullamento evento')
ON CONFLICT (codice) DO NOTHING;

-- ── 6. Rimuovi RIMBORSO (prima svincola i movimenti, poi cancella dal lookup) ──

UPDATE movimenti
SET tipo_evento_movimento = NULL
WHERE tipo_evento_movimento = 'RIMBORSO';

DELETE FROM lk_tipi_evento_mov WHERE codice = 'RIMBORSO';

-- ── 7. Stato ATTIVO per movimenti eventi (non più REGISTRATO) ─────────────────

INSERT INTO lk_stati_movimento (codice, descrizione) VALUES
    ('ATTIVO', 'Attivo — movimento valido e liquidato')
ON CONFLICT (codice) DO NOTHING;

-- ── 8. Nuove colonne su eventi ────────────────────────────────────────────────

ALTER TABLE eventi
    ADD COLUMN IF NOT EXISTS numero_totale_partecipanti INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS numero_bambini             INT;

-- ── 9. Tabella allergie (lista strutturata per evento) ────────────────────────

CREATE TABLE IF NOT EXISTS evento_allergie (
    id          BIGSERIAL    PRIMARY KEY,
    evento_id   UUID         NOT NULL REFERENCES eventi(id) ON DELETE CASCADE,
    descrizione VARCHAR(200) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_allergie_evento ON evento_allergie(evento_id);

-- ── 10. Rimuovi trigger (logica ricalcolo spostata in Java) ───────────────────

DROP TRIGGER IF EXISTS trg_z_aggiorna_totali_evento ON movimenti;

-- Nota: fn_aggiorna_totali_evento e fn_ricalcola_evento restano definiti per
-- compatibilità e possibili job di manutenzione manuali, ma non sono più
-- invocati automaticamente.
