-- Spezza `30.03.002 Vendita ortofrutta e trasformati (IVA 4%)`, che mescolava due aliquote.
--
-- Misurato sui 235 scontrini Billy (dati_luglio/ + esempi_dati_storici/): ortofrutta 4,00 %
-- su 29 scontrini, prodotti trasformati 10,00 % su 20 scontrini per 6.270,24 €. Un conto solo
-- non può avere un default corretto per entrambe: finché stanno insieme, l'aliquota del conto
-- è una bugia per meta' delle righe.
--
-- Controesempio in produzione (copia agosdb_postdeploy del 21/08): 5 righe vive su 30.03.002
-- sono trasformati — 28,00 + 7,30 + 15,50 + 36,00 (31/01) e 172,00 (07/06) = 258,80 € — e
-- portano 9,96 € di IVA al 4 % invece di 23,53 € al 10 %.
--
-- Stesso pattern di V21/V35: id = max+1 e setval finale, perche' la sequence e' disallineata
-- (misurato il 21/08: max(id)=172, last_value=166) e un nextval collide.
-- WHERE NOT EXISTS = idempotenza.

INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '30.03.004', 'Vendita prodotti trasformati (IVA 10%)', 'RICAVO', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.03'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '30.03.004');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- Il nome del conto dichiara la sua aliquota (SPEC iva-aliquote-e-scorporo §3.3): ora che i
-- trasformati hanno il loro conto, 30.03.002 e' solo ortofrutta e il nome smette di mentire.
UPDATE piano_dei_conti_coge
   SET descrizione = 'Vendita ortofrutta (IVA 4%)'
 WHERE codice = '30.03.002';

-- Cascata verificata sulla copia di produzione del 21/08, nulla da propagare:
--   keyword_firma      → 1 riga su 30.03.002, natura DOMINIO 'ORTOFRUTTA' → resta dov'e';
--   regole_classificazione → 0 righe su 30.03.*;
--   allowlist forecasting (ForecastBaselineService.CONTI_RICAVO_CASH) → e' codice Java,
--     aggiornata a mano nello stesso commit.
-- Le 5 righe gia' a libro NON sono spostate qui: correggere dati si fa dagli endpoint
-- dell'app, non in migration (CLAUDE.md, «Interventi sui DATI di produzione»).
