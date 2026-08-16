-- V30 — via la colonna residuo_core dalla quadratura: era una guardia che non poteva fallire.
--
-- Dopo il fix A2 del 09/08 («le righe POS bancarie SONO i movimenti»), residuo_core valeva
--   posBancaCore − posBancaContabilizzato
-- ma i due addendi nascevano dallo stesso loop, con lo stesso identico filtro: ≡ 0 per costruzione.
-- Verificato anche sul dato: quadratura_periodo.residuo_core = 0,00 sull'import gen–giu 2026.
-- Un controllo che non può fallire è falsa sicurezza (audit-catena-import-2026-08-11.md §2 e §8 #9).
--
-- Il delta che può davvero divergere (Σ POS banca − Σ Billy spaccio del periodo) resta, ed è già
-- scritto come nota testuale «Scarto informativo POS banca − Billy spaccio» in quadratura.note.
--
-- Idempotente: IF EXISTS.

ALTER TABLE quadratura_periodo DROP COLUMN IF EXISTS residuo_core;
