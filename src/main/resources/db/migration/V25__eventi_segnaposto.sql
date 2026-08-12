-- V25 — eventi segnaposto ("da attribuire").
--
-- Un incasso-evento che arriva dall'estratto conto non ha sempre un evento in anagrafica: il
-- cliente incassa la caparra di un matrimonio di settembre che non ha ancora inserito. Senza un
-- posto dove metterlo, quel denaro resta parcheggiato e fuori dai saldi (misurato 2026-08-07:
-- 18.924,00 € su 26 righe).
--
-- Perché UN SEGNAPOSTO PER PAGAMENTO e non un contenitore unico condiviso: quattro regole vive in
-- EventiService.registraPagamento lo impedirebbero — max 1 CAPARRA/ACCONTO/SALDO per evento
-- (:281-289), importo ≤ residuo (:292-299), auto-chiusura a SALDATO (:345-351) e competenza
-- economica = data evento (:312). Un segnaposto per pagamento le rispetta tutte senza toccarne
-- nessuna. Dettaglio in docs/specs/import-eventi-attribuzione.md.

ALTER TABLE eventi ADD COLUMN is_segnaposto boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN eventi.is_segnaposto IS
  'true = contenitore temporaneo di un incasso non ancora attribuito a un evento reale. '
  'Escluso da calendario e lista eventi; incluso in mv_redditivita_eventi e nel bilancio eventi.';

-- La lista "Da attribuire" li cerca per flag; gli altri filtri li escludono. Indice parziale:
-- i segnaposto sono pochi rispetto agli eventi veri.
CREATE INDEX idx_eventi_segnaposto ON eventi (data_evento) WHERE is_segnaposto;
