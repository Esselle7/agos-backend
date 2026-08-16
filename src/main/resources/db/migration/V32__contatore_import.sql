-- V32 — Il contatore dell'import (SPEC docs/specs/import-v2-motore-che-suggerisce.md §5, R7–R10).
--
-- L'invariante «Σ righe banca lette = Σ a libro + Σ da catalogare + Σ fuori dai conti +
-- Σ escluso di proposito + Σ duplicate + Σ partite di giro» ha bisogno di un termine SINISTRO
-- misurato in modo INDIPENDENTE dai bucket, altrimenti è una guardia vacua: derivare anche il
-- totale dalla somma dei destini lo rende vero per costruzione (stesso errore già visto in
-- RiconciliazioneService, audit §2 intervento §8 #9).
--
-- Qui il termine sinistro si misura UNA volta, all'import, direttamente sulle righe normalizzate
-- dei DUE FILE BANCA (BPM + CA) — prima di qualunque mapping. Billy resta fuori dall'universo
-- (contanti/agriturismo si dichiarano a parte, §5).
ALTER TABLE import_log
    ADD COLUMN IF NOT EXISTS righe_banca   integer,
    ADD COLUMN IF NOT EXISTS banca_entrate numeric(14,2),
    ADD COLUMN IF NOT EXISTS banca_uscite  numeric(14,2);

-- R9 — «escluso di proposito» richiede un MOTIVO SCRITTO: nessun secchio muto. Le altre code
-- hanno già dove scriverlo (ricorrenti.note, import_ambiguita.note_operatore,
-- eventi_da_riconciliare.raw_data._nota_triage); import_scartati no.
ALTER TABLE import_scartati
    ADD COLUMN IF NOT EXISTS note text;

-- R8 — le righe DUPLICATE lasciano una traccia. Fino a oggi incrementavano un contatore in
-- import_log e sparivano (MovimentoImportService:181-186): denaro bancario vero senza nessuna
-- riga da nessuna parte. Ora entrano in import_scartati con motivo 'DUPLICATA' e stato
-- 'DUPLICATA' — casa propria, fuori dalla coda DA_VEDERE che l'operatore deve lavorare.
COMMENT ON COLUMN import_scartati.stato IS
    'DA_VEDERE (coda da lavorare) | CONTABILIZZATA | IGNORATA (esclusa di proposito, con note) | DUPLICATA (R8: traccia, nessuna azione attesa)';
