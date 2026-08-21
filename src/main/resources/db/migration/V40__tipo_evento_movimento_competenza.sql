-- V40 — Fase 4: il tipo di movimento evento «COMPETENZA»
-- SPEC: docs/specs/competenza-ricavo-evento.md
--
-- La riga di ricavo maturato e non ancora incassato NON è un pagamento: non ha banca né data
-- finanziaria, quindi il conto economico la conta e la vista finanziaria la ignora.
-- Serve un codice proprio perché `movimenti.tipo_evento_movimento` ha una FK verso questa
-- lookup (`movimenti_tipo_evento_movimento_fkey`, V2:290) e perché i filtri esistenti su
-- CAPARRA/ACCONTO/SALDO (dataConferma, dataSaldo, caparreIncassate) non devono intercettarla.
--
-- Idempotente: ON CONFLICT DO NOTHING. Nessuna riga esistente viene toccata, nessuna vista
-- materializzata cambia — questa migration non ha bisogno di fn_refresh_all_mv().

INSERT INTO lk_tipi_evento_mov (codice, descrizione)
VALUES ('COMPETENZA', 'Ricavo maturato alla data evento, non ancora incassato')
ON CONFLICT (codice) DO NOTHING;
