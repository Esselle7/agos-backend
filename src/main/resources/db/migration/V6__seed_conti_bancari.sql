-- ============================================================
-- V6 - SEED: Conti bancari, Metodi di pagamento, Fornitori reali
-- ============================================================

-- ============================================================
-- CONTI BANCARI E CASSE
-- ID fissi e documentati per riferimento diretto nel codice
-- ============================================================

INSERT INTO conti_bancari (id, nome, tipo, iban, saldo_iniziale, data_saldo_iniziale) VALUES
    (1, 'Banco BPM – c/c operativo',           'BANCARIO', NULL, 0.00, '2024-01-01'),
    (2, 'Crédit Agricole – c/c operativo',     'BANCARIO', NULL, 0.00, '2024-01-01'),
    (3, 'Cassa contanti',                      'CASSA',    NULL, 0.00, '2024-01-01'),
    (4, 'Satispay – portafoglio digitale',     'DIGITALE', NULL, 0.00, '2024-01-01'),
    (5, 'Stripe / Alveare – portafoglio',      'DIGITALE', NULL, 0.00, '2024-01-01');

-- Nota: aggiornare saldo_iniziale e data_saldo_iniziale con i valori reali
-- prima del go-live per permettere la riconciliazione bancaria.

-- ============================================================
-- METODI DI PAGAMENTO
-- Codici usati dall'import automatico per classificare le righe
-- ============================================================

INSERT INTO metodi_pagamento (codice, descrizione) VALUES
    ('CONTANTI',       'Contanti (cassa fisica)'),
    ('POS_BPM',        'POS Banco BPM – Numia (fisico)'),
    ('POS_CA_NEXI',    'POS Crédit Agricole – Nexi NFC (smartphone)'),
    ('SATISPAY',       'Satispay (accredito netto commissioni)'),
    ('BONIFICO',       'Bonifico bancario'),
    ('ALVEARE_STRIPE', 'L''Alveare / Stripe (bonifico mensile netto)'),
    ('SHOPIFY_STRIPE', 'Shopify / Stripe (accrediti e-commerce)'),
    ('F24',            'F24 – tributi e contributi'),
    ('ASSEGNO',        'Assegno bancario'),
    ('RID_SDDMANDAT',  'Addebito diretto SEPA (RID / SDD)');

-- ============================================================
-- FORNITORI REALI AGOSTINELLI
-- Collegati al conto COGE default per suggerimento automatico
-- nell'inserimento manuale e nell'import da estratto conto
-- ============================================================

INSERT INTO fornitori (ragione_sociale, alias, coge_default_id, bu_default_id, note) VALUES
    -- Materie prime ristorazione
    ('Pasticceria RM',          'Pasticceria RM',  (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.001'), 1, 'Dolci e dessert per ristorazione'),
    ('Pasini Verdure',          'Pasini',          (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.002'), 1, 'Fornitore verdure e ortaggi freschi'),
    ('Orma Birra',              'Orma',            (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.003'), 1, 'Fornitore birra artigianale'),
    ('Gruppo Italiano Vini',    'GIV',             (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.004'), 1, 'Vini e bevande'),
    ('Nicellini Vini',          'Nicellini',       (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.005'), 1, 'Vino locale / regionale'),
    ('Zeus Acque Minerali',     'Zeus',            (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.006'), 1, 'Acque minerali per ristorazione'),
    ('Ciocca Formaggi',         'Ciocca',          (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.007'), 1, 'Formaggi e latticini'),
    ('Sogegross',               'Sogegross',       (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.008'), 1, 'Ingrosso generi alimentari'),
    ('Val Mulini',              'Val Mulini',      (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04.009'), 1, 'Farine, cereali, pasta'),
    -- Investimenti / CAPEX
    ('Gerosa Macchine Agricole','Gerosa',          (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01.004'), NULL, 'Macchinari e attrezzature agricole'),
    ('Aiani Impianti',          'Aiani',           (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01.005'), NULL, 'Lavori idraulici e impianti'),
    ('Eurosistem',              'Eurosistem',      (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01.006'), NULL, 'Impianti tecnologici'),
    ('Mallamace Edilizia',      'Mallamace',       (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01.007'), NULL, 'Forniture edili e materiali'),
    -- Utenze
    ('Erogatore Acqua Locale',  'Acqua',           (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.03.001'), 5, 'Utenza idrica'),
    ('Fornitore GPL',           'GPL',             (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.03.002'), 5, 'Gas propano liquido'),
    -- Pedaggi e carburanti
    ('Telepass Italia',         'Telepass',        (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.06.001'), 5, 'Pedaggi autostradali'),
    -- Costi bancari
    ('Nexi Payments',           'Nexi',            (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.02.001'), 5, 'Commissioni POS Nexi / Crédit Agricole');

-- ============================================================
-- PATTERN DI ALIAS MATCHING per import automatico da banca
-- Permette di abbinare le causali dell'estratto conto ai fornitori
-- ============================================================

INSERT INTO fornitore_alias_matching (fornitore_id, pattern, match_type) VALUES
    ((SELECT id FROM fornitori WHERE alias = 'Pasticceria RM'),  'PASTICCERIA RM',     'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Pasini'),          'PASINI',             'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Orma'),            'ORMA BIRRA',         'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'GIV'),             'GRUPPO ITALIANO VI', 'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Nicellini'),       'NICELLINI',          'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Zeus'),            'ZEUS',               'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Ciocca'),          'CIOCCA',             'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Sogegross'),       'SOGEGROSS',          'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Val Mulini'),      'VAL MULINI',         'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Telepass'),        'TELEPASS',           'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'Nexi'),            'NEXI',               'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'GPL'),             'GPL',                'CONTAINS'),
    ((SELECT id FROM fornitori WHERE alias = 'GPL'),             'GAS PROPANO',        'CONTAINS');
