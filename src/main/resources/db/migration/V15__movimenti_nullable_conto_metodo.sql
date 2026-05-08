-- Rende nullable conto_bancario_id e metodo_pagamento_id sulla tabella movimenti.
-- Motivazione: i movimenti economici puri (es. fatture a 90 giorni) non hanno ancora
-- un conto o metodo di pagamento associato al momento della registrazione.
-- La validazione cross-field nel service garantisce la coerenza:
--   - liquidità immediata (dataLiquidita null o <= dataMovimento): entrambi obbligatori lato applicazione
--   - liquidità futura (dataLiquidita > dataMovimento): entrambi devono essere null
--
-- La tabella movimenti è partizionata per anno su data_movimento; l'ALTER si applica
-- alla tabella padre e viene ereditato da tutte le partizioni figlie.

ALTER TABLE movimenti ALTER COLUMN conto_bancario_id DROP NOT NULL;
ALTER TABLE movimenti ALTER COLUMN metodo_pagamento_id DROP NOT NULL;
