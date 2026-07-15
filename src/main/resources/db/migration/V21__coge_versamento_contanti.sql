-- Conto CoGe patrimoniale per il versamento contanti su banca (coppia banca↔cassa
-- generata dall'import per la causale BPM 78A). Famiglia 10.03 = giroconti interni.
-- Idempotente e senza id espliciti: prod ha righe create dall'app oltre il seed V4,
-- quindi id = max+1 e riallineo la sequence (V4 inserisce id fissi senza setval).

INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '10.03.003', 'Versamento contanti su banca', 'ATTIVITA', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '10.03.003');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));
