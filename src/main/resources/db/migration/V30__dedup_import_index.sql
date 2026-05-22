-- Impedisce doppia importazione dello stesso movimento da fonti esterne.
-- Il vincolo si applica solo quando riferimento_esterno è valorizzato (NULL non viola UNIQUE in PG).
CREATE UNIQUE INDEX idx_movimenti_dedup_import
    ON movimenti (fonte, riferimento_esterno, data_movimento)
    WHERE fonte IN ('IMPORT_BILLY', 'IMPORT_BANCA', 'IMPORT_ALVEARE', 'IMPORT_FATTURA')
      AND riferimento_esterno IS NOT NULL;
