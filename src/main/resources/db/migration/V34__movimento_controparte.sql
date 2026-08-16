-- V34 — La controparte del bonifico sul movimento (SPEC bonifica-doppioni-e-guardie-eventi, A4).
--
-- Perché serve: la guardia PAGAMENTO_DUPLICATO (ADR 009) identifica il gemello con 5 campi
-- (evento, tipo, importo, data finanziaria, conto). Due bonifici di PERSONE DIVERSE con lo stesso
-- importo, lo stesso giorno e lo stesso evento sono quindi indistinguibili da un doppione — e
-- succede davvero: 07/07/2026, due caparre da 20,00 € sullo stesso evento del 25/09, ORD:INDUNI
-- RENATA e ORD:DE AGOSTINI M RCO (estratto Crédit Agricole di luglio, righe 55-56).
--
-- Perché una colonna nuova e NON il riuso di riferimento_esterno: su quest'ultimo esiste
-- UNIQUE (fonte, riferimento_esterno, data_movimento). Oggi il vincolo è indisvalid=f sul padre,
-- ma ha figli validi su movimenti_2028/2029/2030: scriverci la controparte romperebbe dal 2028 le
-- due tranche legittime dello stesso cliente sullo stesso evento (stessa data_movimento = data
-- evento), cioè il caso CELLA ERIKA di ADR 003.
ALTER TABLE movimenti ADD COLUMN IF NOT EXISTS controparte text;

COMMENT ON COLUMN movimenti.controparte IS
    'A4: ordinante/beneficiario come letto dall''estratto conto, valorizzato solo dai pagamenti-evento '
    'nati dall''import. Discrimina due incassi veri identici nello stesso giorno. NULL = ignoto '
    '(registrazione manuale): il confronto anti-doppione resta fail-closed.';
