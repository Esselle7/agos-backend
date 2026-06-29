-- ============================================================================
-- V17 — Fix date "Preventivato" degli eventi storici (seed V15)
-- ----------------------------------------------------------------------------
-- Il seed V15 inserisce gli eventi senza valorizzare `created_at`, che resta al
-- default now() = giorno di caricamento del seed (es. 26/06/2026). Il timeline
-- dell'evento mostra `created_at` come data "Preventivato": per gli eventi
-- passati risultava POSTERIORE alla data evento e al saldo (incoerente).
--
-- Qui la riportiamo a ~10 giorni prima della data evento — coerente con un
-- preventivo fatto prima della cerimonia — e valorizziamo `data_preventivo`
-- se mancante. Tocca SOLO gli eventi del seed (note dedicata) e solo le righe
-- ancora "rotte" (created_at successivo all'evento): idempotente e ri-eseguibile.
--
-- Nota: dataSaldo/dataConferma sono calcolate dai movimenti, non da created_at,
-- quindi questa correzione non altera le date di saldo/conferma già corrette.
-- ============================================================================

UPDATE eventi
SET created_at      = (data_evento - INTERVAL '10 days')::timestamptz,
    data_preventivo = COALESCE(data_preventivo, (data_evento - INTERVAL '10 days')::date)
WHERE note = 'Seed storico eventi 2026'
  AND created_at::date > data_evento;
