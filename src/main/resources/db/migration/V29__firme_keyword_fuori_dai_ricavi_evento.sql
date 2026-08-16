-- V29 — nessuna firma keyword può catalogare su un conto ricavi-evento (30.02.*).
--
-- Invariante DACLASS: un movimento su 30.02.* senza evento_id è un ricavo che non compare nel
-- bilancio di nessun evento. Catalogando a mano un incasso su quel mastro con «Apprendi keyword»
-- attivo (default ON), l'operatore insegnava al motore a rifarlo da solo per sempre.
--
-- Misurato sull'import storico gen–giu 2026 (audit-catena-import-2026-08-11.md §6.2/2):
--   13 firme BOOK apprese puntano a 30.02.*
--    6 movimenti nati su 30.02.* con evento_id NULL, per 3.425,60 €
--
-- Conseguenza dichiarata e accettata (decisione del titolare, 11/08/2026): quei ~6 incassi in sei
-- mesi non vengono più catalogati da soli — tornano nella coda «Incassi evento», dove l'attribuzione
-- crea anche il collegamento all'evento. +6 decisioni umane / 6 mesi.
--
-- Il blocco alla scrittura di NUOVE firme sta in KeywordLearningService.vietaCogeRiservato().
-- Qui si chiude solo il pregresso. DISATTIVATA (non DELETE): la firma resta leggibile nella pagina
-- Keyword, così si vede cosa il motore aveva imparato e perché non lo applica più.
--
-- Idempotente: rieseguirla non tocca nulla (le firme sono già DISATTIVATA).

UPDATE keyword_firma
SET stato = 'DISATTIVATA',
    note = COALESCE(note || ' | ', '') || 'Disattivata da V29: conto riservato al modulo Eventi (invariante DACLASS)',
    updated_at = now()
WHERE azione = 'BOOK'
  AND coge_codice LIKE '30.02.%'
  AND stato <> 'DISATTIVATA';
