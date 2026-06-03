-- ============================================================
-- V40 - Nuovi conti COGE per il motore ETL v2 (ETL_CLASSIFICAZIONE_v2 §11/§17.1)
--
-- Tutti gli INSERT sono idempotenti (ON CONFLICT (codice) DO NOTHING):
-- la migrazione può girare a vuoto senza errori su DB già allineati.
--
-- NOTE DECISIONI (rispetto al doc §11):
--  * "Contributi pubblici e PAC" usa 30.05 / 30.05.001 e NON 30.04.*:
--    il codice 30.04 è già occupato in V5 da "Ricavi Manutenzione Verde" (BU4).
--  * I conti 90.* usano tipo PASSIVITA: lk_tipi_coge non prevede "PATRIMONIALE"
--    (valori ammessi: ATTIVITA / PASSIVITA / COSTO / RICAVO). Restano comunque
--    fuori dal Conto Economico (gestito a livello di reporting in F3).
-- ============================================================

-- ── Contributi pubblici e PAC (RICAVO, BU1) ────────────────────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('30.05', 'Contributi pubblici e PAC', 'RICAVO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '30'))
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('30.05.001', 'Contributi pubblici / PAC / AGEA', 'RICAVO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.05'))
ON CONFLICT (codice) DO NOTHING;

-- ── Ricavi da classificare (transitorio, deve tendere a 0) ─────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('39', 'Ricavi da classificare (transitori)', 'RICAVO', NULL)
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('39.99', 'Ricavi da classificare', 'RICAVO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '39'))
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('39.99.999', 'Ricavi da classificare (transitorio)', 'RICAVO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '39.99'))
ON CONFLICT (codice) DO NOTHING;

-- ── Costi da classificare (transitorio, deve tendere a 0) ──────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('49', 'Costi da classificare (transitori)', 'COSTO', NULL)
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('49.99', 'Costi da classificare', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '49'))
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('49.99.999', 'Costi da classificare (transitorio)', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '49.99'))
ON CONFLICT (codice) DO NOTHING;

-- ── Partite patrimoniali / finanziarie (fuori P&L) ─────────────────────────
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('90', 'Partite patrimoniali e finanziarie', 'PASSIVITA', NULL)
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('90.01', 'Finanziamenti ricevuti', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '90')),
    ('90.02', 'Versamenti soci', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '90'))
ON CONFLICT (codice) DO NOTHING;

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('90.01.001', 'Finanziamenti ricevuti (erogazione)', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '90.01')),
    ('90.02.001', 'Versamenti soci', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '90.02'))
ON CONFLICT (codice) DO NOTHING;
