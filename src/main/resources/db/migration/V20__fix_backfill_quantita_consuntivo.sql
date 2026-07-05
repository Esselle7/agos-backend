-- V20 — Corregge il backfill di V19.
--
-- V19 impostava quantita_consuntivo = 1 fissa per tutte le voci con un consuntivo:
-- corretto solo quando importo_consuntivo == importo_preventivo. Per le voci il cui
-- consuntivo (inserito prima di V19) differiva dal preventivo, la quantità restava
-- incoerente con importo_consuntivo = quantità × prezzo, e una modifica successiva in UI
-- avrebbe silenziosamente ricalcolato (e corrotto) l'importo consuntivato.
--
-- Ricalcola la quantità dall'importo AUTOREVOLE (quello inserito dall'utente),
-- preservando l'importo consuntivato. Idempotente: tocca solo le righe incoerenti.
UPDATE evento_voce
   SET quantita_consuntivo = ROUND(importo_consuntivo / prezzo_unitario, 2)
 WHERE importo_consuntivo IS NOT NULL
   AND prezzo_unitario > 0
   AND importo_consuntivo <> prezzo_unitario * quantita_consuntivo;
