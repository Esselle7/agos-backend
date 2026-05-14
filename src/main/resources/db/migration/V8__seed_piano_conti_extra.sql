-- ============================================================
-- V8 - Completamento Piano dei Conti + Fornitori mancanti
-- Basato sulle voci reali del conto economico Agostinelli
-- e sui dati degli estratti conto (CA + BPM) e Billy
-- ============================================================

-- ============================================================
-- CATEGORIE MANCANTI NEL PIANO DEI CONTI
-- ============================================================

-- 40.07 – Contabilità, consulenze e paghe
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.07', 'Contabilità, consulenze e paghe', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.07.001', 'Coldiretti – assistenza tecnica e fiscale', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.07')),
    ('40.07.002', 'Torres – consulente esterno',              'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.07'));

-- 40.08 – Contributi previdenziali
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.08', 'Contributi previdenziali', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.08.001', 'Contributi previdenziali Amministratore', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.08')),
    ('40.08.002', 'Contributi previdenziali dipendenti',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.08'));

-- 40.09 – Manutenzioni ordinarie
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.09', 'Manutenzioni ordinarie', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.09.001', 'Manutenzione ordinaria – FOC (contratto)',        'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09')),
    ('40.09.002', 'Manutenzione ordinaria – interventi previsti',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09')),
    ('40.09.003', 'Manutenzione ordinaria – Comedil Mangino',        'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09')),
    ('40.09.004', 'Pulizie e sanificazione – Zep Italia / New Cleaning', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09'));

-- 40.10 – Compenso Amministratore
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.10', 'Compenso Amministratore', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.10.001', 'Compenso amministratore – quota mensile', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.10'));

-- 40.11 – Altri costi operativi
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.11', 'Altri costi operativi', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.11.001', 'Sonvico – forniture varie',              'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11')),
    ('40.11.002', 'Bio – prodotti biologici / certificazioni', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11')),
    ('40.11.003', 'New Cleaning – prodotti pulizia',        'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11')),
    ('40.11.004', 'Commissioni mercato / Alveare',          'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11')),
    ('40.11.005', 'Consorzio – quote associative',          'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.11'));

-- 40.12 – Materie prime vendita prodotti (Spaccio)
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.12', 'Materie prime – Vendita Prodotti e Spaccio', 'COSTO',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.12.001', 'Materie prime spaccio – stime preventive',          'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.12')),
    ('40.12.002', 'Materie prime spaccio – fatture fornitore principale', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.12')),
    ('40.12.003', 'Fattoria Ginestra – acquisti prodotti agricoli',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.12'));

-- 40.13 – Olio (materia prima ristorazione – aggiunto a 40.04)
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.04.010', 'Acquisti olio EVO', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04'));

-- 10.03 – Giroconto tra conti propri (trasferimenti interni)
INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.03', 'Giroconti e trasferimenti interni', 'ATTIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '10'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.03.001', 'Giroconto da Crédit Agricole a Banco BPM', 'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03')),
    ('10.03.002', 'Giroconto da Banco BPM a Crédit Agricole', 'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03'));

-- ============================================================
-- FORNITORI MANCANTI (dai dati estratto conto reali)
-- ============================================================

INSERT INTO fornitori (ragione_sociale, alias, coge_default_id, bu_default_id, note) VALUES
    -- Uscite CA identificate dall'estratto conto
    ('Lodetti Ivano',           'Lodetti',       (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01.005'), 5, 'Pagamento manodopera/prestazione occasionale – 07/01/2026'),
    ('Comedil Mangino s.r.l.', 'Comedil',        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09.003'), 5, 'Manutenzione edile – 07/01/2026'),
    ('Zep Italia Srl',          'Zep Italia',    (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.09.004'), 5, 'Prodotti per pulizia e manutenzione – 07/01/2026'),
    ('Fattoria Ginestra di Bettoni Adonis', 'Fattoria Ginestra', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.12.003'), 1, 'Acquisti prodotti agricoli per ristorazione/spaccio – 09/01/2026');

-- Pattern alias per matching automatico
INSERT INTO fornitore_alias_matching (fornitore_id, pattern, match_type) VALUES
    ((SELECT id FROM fornitori WHERE alias = 'Lodetti'),          'LODETTI',            'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Comedil'),          'COMEDIL MANGINO',    'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Zep Italia'),       'ZEP ITALIA',         'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Fattoria Ginestra'),'FATTORIA GINESTRA',  'CONTAINS');
