-- Modulo «Contanti» (docs/specs/modulo-contanti.md, R7 + R11).
--
-- 1) Due CoGe patrimoniali nuovi sotto 10.03 (giroconti interni): il prelievo da banca e la
--    rettifica di cassa. Sono ATTIVITA ⇒ fuori dal P&L (invariante I3): spostare denaro fra
--    propri contenitori non è né ricavo né costo.
--    Stesso pattern di V21: id = max+1 e setval finale, perché la sequence è disallineata
--    (misurato: max(id)=166, last_value=164) e un nextval collide. WHERE NOT EXISTS = idempotenza.
--
-- 2) Il vecchio modulo Cassa se ne va: 0 righe in cassa_movimenti, 0 movimenti sul conto 3,
--    nulla da migrare. Il saldo cassa ha una fonte sola — le righe `movimenti` sul conto
--    tipo='CASSA' (invariante I5, niente secondo libro mastro).
--    Verificato su pg_constraint: cassa_movimenti ha solo FK uscenti, nessuna entrante;
--    lk_tipi_cassa_mov è referenziata solo da cassa_movimenti; nessuna vista dipende da loro.

INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '10.03.004', 'Prelievo contanti da banca', 'ATTIVITA', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '10.03.004');

INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '10.03.005', 'Rettifica di cassa (ammanco/eccedenza)', 'ATTIVITA', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '10.03.005');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

DROP TABLE IF EXISTS cassa_movimenti;
DROP TABLE IF EXISTS lk_tipi_cassa_mov;
