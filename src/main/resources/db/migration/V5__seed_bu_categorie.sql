-- ============================================================
-- V5 - SEED: Lookup tables, Business Units, Piano dei Conti,
--            Centri di Costo, Aliquote IVA
-- Dati reali dell'agriturismo Agostinelli
-- ============================================================

-- ============================================================
-- LOOKUP TABLES
-- ============================================================

INSERT INTO lk_ruoli_utente (codice, descrizione) VALUES
    ('ADMIN',      'Amministratore – accesso completo'),
    ('DIPENDENTE', 'Dipendente – accesso limitato (solo eventi)');

INSERT INTO lk_tipi_coge (codice, descrizione) VALUES
    ('ATTIVITA', 'Attività patrimoniale'),
    ('PASSIVITA', 'Passività patrimoniale / debito finanziario'),
    ('COSTO',    'Costo operativo (Opex)'),
    ('RICAVO',   'Ricavo');

INSERT INTO lk_tipi_movimento (codice, descrizione) VALUES
    ('ENTRATA', 'Entrata di cassa / incasso'),
    ('USCITA',  'Uscita di cassa / pagamento');

INSERT INTO lk_tipi_cassa_mov (codice, descrizione) VALUES
    ('ENTRATA',             'Entrata in cassa contanti'),
    ('USCITA',              'Uscita dalla cassa contanti'),
    ('PRELIEVO_DA_BANCA',   'Prelievo bancomat / sportello portato in cassa'),
    ('VERSAMENTO_IN_BANCA', 'Versamento contanti in banca');

INSERT INTO lk_tipi_conto (codice, descrizione) VALUES
    ('BANCARIO', 'Conto corrente bancario'),
    ('CASSA',    'Cassa contanti fisica'),
    ('DIGITALE', 'Portafoglio digitale (Satispay, Stripe, Alveare, ecc.)');

INSERT INTO lk_tipi_evento (codice, descrizione) VALUES
    ('MATRIMONIO',          'Matrimonio e ricevimento nuziale'),
    ('BANCHETTO_PRIVATO',   'Banchetto privato / compleanno / anniversario'),
    ('AZIENDALE',           'Evento aziendale / team building'),
    ('RISTORAZIONE_GRUPPO', 'Ristorazione per gruppi organizzati'),
    ('ALTRO',               'Altro tipo di evento');

INSERT INTO lk_stati_evento (codice, descrizione) VALUES
    ('PREVENTIVO',  'Preventivo in attesa di conferma'),
    ('CONFERMATO',  'Evento confermato con caparra'),
    ('COMPLETATO',  'Evento completato e saldo incassato'),
    ('ANNULLATO',   'Evento annullato');

INSERT INTO lk_tipi_evento_mov (codice, descrizione) VALUES
    ('CAPARRA',  'Caparra confirmatoria / deposito iniziale'),
    ('ACCONTO',  'Acconto intermedio'),
    ('SALDO',    'Saldo finale a evento completato'),
    ('RIMBORSO', 'Rimborso al cliente');

INSERT INTO lk_stati_movimento (codice, descrizione) VALUES
    ('REGISTRATO',   'Registrato (manuale o importato, da verificare)'),
    ('RICONCILIATO', 'Riconciliato con estratto conto bancario'),
    ('ANNULLATO',    'Annullato / stornato');

INSERT INTO lk_fonti_movimento (codice, descrizione) VALUES
    ('MANUALE',         'Inserimento manuale da interfaccia'),
    ('IMPORT_BILLY',    'Importazione CSV da Billy (registratore cassa)'),
    ('IMPORT_BANCA',    'Importazione estratto conto bancario (BPM / CA)'),
    ('IMPORT_ALVEARE',  'Importazione da L''Alveare / Stripe (bonifico mensile)'),
    ('IMPORT_FATTURA',  'Importazione fattura da Sportello Cloud (SDI)');

INSERT INTO lk_stati_import (codice, descrizione) VALUES
    ('IN_CORSO',   'Importazione in elaborazione'),
    ('COMPLETATO', 'Importazione completata con successo'),
    ('ERRORE',     'Importazione terminata con errori critici');

INSERT INTO lk_match_types (codice, descrizione) VALUES
    ('CONTAINS',    'Il pattern è contenuto nella causale'),
    ('STARTS_WITH', 'La causale inizia con il pattern'),
    ('REGEX',       'Espressione regolare su causale');

-- ============================================================
-- BUSINESS UNITS
-- ============================================================

INSERT INTO business_units (id, codice, nome, descrizione, colore_hex) VALUES
    (1, 'BU1', 'Ristorazione e Agriturismo',    'Pranzi, cene, ospitalità, B&B agriturismo',                         '#4CAF50'),
    (2, 'BU2', 'Cerimonie ed Eventi',            'Matrimoni, banchetti privati e aziendali – logica caparra/saldo',   '#2196F3'),
    (3, 'BU3', 'Vendita Prodotti e Spaccio',     'Carne, salumi, ortofrutta, trasformati – Spaccio, Alveare, Shopify','#FF9800'),
    (4, 'BU4', 'Manutenzione Verde',             'Manutenzione verde per privati, aziende e condomini',               '#795548'),
    (5, 'BU5', 'Overhead',                       'Costi generali: mutui, assicurazioni, ammortamenti',                '#9E9E9E');

-- ============================================================
-- ALIQUOTE IVA
-- ============================================================

INSERT INTO aliquote_iva (aliquota, descrizione, perc_indetraibilita) VALUES
    ( 0.0, 'Esente / Fuori campo IVA (es. Satispay netto, Alveare netto)', 0.00),
    ( 4.0, 'IVA 4% – Prodotti agricoli di base, ortofrutta',               0.00),
    (10.0, 'IVA 10% – Carni, salumi, alimenti trasformati, ristorazione',   0.00),
    (22.0, 'IVA 22% – Aliquota ordinaria (servizi, beni non food)',         0.00);

-- ============================================================
-- PIANO DEI CONTI – COGE
-- Struttura: XX = mastro | XX.XX = conto | XX.XX.XXX = sottoconto
-- ============================================================

-- --- MASTRI RADICE ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex) VALUES
    ('10', 'ATTIVITÀ PATRIMONIALI',       'ATTIVITA', false),
    ('20', 'PASSIVITÀ E DEBITI',          'PASSIVITA', false),
    ('30', 'RICAVI',                      'RICAVO',   false),
    ('40', 'COSTI OPERATIVI',             'COSTO',    false),
    ('50', 'INVESTIMENTI (CAPEX)',        'COSTO',    true);

-- --- ATTIVITÀ: Liquidità (10.01) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.01', 'Liquidità e disponibilità', 'ATTIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '10'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.01.001', 'Banca BPM – c/c operativo',              'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.01')),
    ('10.01.002', 'Crédit Agricole – c/c operativo',        'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.01')),
    ('10.01.003', 'Cassa contanti',                         'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.01')),
    ('10.01.004', 'Satispay – portafoglio digitale',        'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.01')),
    ('10.01.005', 'Stripe / Alveare – portafoglio digitale','ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.01'));

-- --- ATTIVITÀ: Crediti (10.02) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.02', 'Crediti', 'ATTIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '10'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('10.02.001', 'Crediti verso clienti (fatture attive)',  'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.02')),
    ('10.02.002', 'Crediti IVA a credito',                  'ATTIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.02'));

-- --- PASSIVITÀ: Debiti finanziari / Mutui (20.01) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('20.01', 'Debiti finanziari – Mutui e finanziamenti', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '20'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('20.01.001', 'Rata mutuo ipotecario',                          'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01')),
    ('20.01.002', 'Rata finanziamento Regione Lombardia',           'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01')),
    ('20.01.003', 'Rata ISMEA (dal 2027)',                          'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01')),
    ('20.01.004', 'Rata Fidicomptur',                               'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01')),
    ('20.01.005', 'Rata Merlo (leasing / finanziamento)',           'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01')),
    ('20.01.006', 'Rata Asconfidi (40k)',                           'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.01'));

-- --- PASSIVITÀ: Debiti operativi (20.02) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('20.02', 'Debiti verso fornitori', 'PASSIVITA',
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '20'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('20.02.001', 'Debiti verso fornitori materie prime',  'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.02')),
    ('20.02.002', 'IVA a debito',                          'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.02')),
    ('20.02.003', 'F24 – imposte e contributi',            'PASSIVITA', (SELECT id FROM piano_dei_conti_coge WHERE codice = '20.02'));

-- --- RICAVI per BU (30.XX) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('30.01', 'Ricavi Ristorazione e Agriturismo', 'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30')),
    ('30.02', 'Ricavi Cerimonie ed Eventi',        'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30')),
    ('30.03', 'Ricavi Vendita Prodotti Spaccio',   'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30')),
    ('30.04', 'Ricavi Manutenzione Verde',         'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('30.01.001', 'Incassi ristorazione (Billy – cassa)',          'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.01')),
    ('30.01.002', 'Incassi B&B / ospitalità',                      'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.01')),
    ('30.02.001', 'Caparre eventi',                                'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02')),
    ('30.02.002', 'Saldi eventi',                                  'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02')),
    ('30.03.001', 'Vendita carni e salumi (IVA 10%)',              'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.03')),
    ('30.03.002', 'Vendita ortofrutta e trasformati (IVA 4%)',     'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.03')),
    ('30.03.003', 'Vendita Alveare / Shopify (netto commissioni)', 'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.03')),
    ('30.04.001', 'Ricavi manutenzione verde',                     'RICAVO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.04'));

-- --- COSTI: Manodopera (40.01) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.01', 'Manodopera e personale', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.01.001', 'Costo aziendale – Carlo',  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01')),
    ('40.01.002', 'Costo aziendale – Max',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01')),
    ('40.01.003', 'Costo aziendale – Alina',  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01')),
    ('40.01.004', 'Costo aziendale – Noemi',  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01')),
    ('40.01.005', 'Costo aziendale – Altri',  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.01'));

-- --- COSTI: Bancari e commissioni (40.02) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.02', 'Costi bancari e commissioni POS', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.02.001', 'Commissioni Nexi (POS Crédit Agricole)',   'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.02')),
    ('40.02.002', 'Spese tenuta conto bancario',             'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.02')),
    ('40.02.003', 'Commissioni POS Banco BPM (Numia)',       'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.02'));

-- --- COSTI: Utenze (40.03) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.03', 'Utenze', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.03.001', 'Utenza acqua',                    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.03')),
    ('40.03.002', 'Utenza GPL (gas propano liquido)', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.03'));

-- --- COSTI: Materie prime e fornitori ristorazione (40.04) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.04', 'Materie prime – Ristorazione e Spaccio', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.04.001', 'Acquisti Pasticceria RM (dolci, dessert)',    'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.002', 'Acquisti Pasini (verdure e ortaggi)',         'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.003', 'Acquisti Orma (birra)',                       'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.004', 'Acquisti Gruppo Italiano Vini',               'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.005', 'Acquisti Nicellini (vino)',                   'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.006', 'Acquisti Zeus (acqua minerale)',              'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.007', 'Acquisti Ciocca (formaggi)',                  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.008', 'Acquisti Sogegross (ingrosso generi alim.)',  'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04')),
    ('40.04.009', 'Acquisti Val Mulini (farine, cerali)',        'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.04'));

-- --- COSTI: Assicurazioni (40.05) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.05', 'Assicurazioni', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.05.001', 'Assicurazione immobile / fabbricato',      'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05')),
    ('40.05.002', 'Assicurazione vita',                       'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05')),
    ('40.05.003', 'Assicurazione incendio',                   'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05')),
    ('40.05.004', 'Assicurazione Dacia (autoveicolo)',         'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05')),
    ('40.05.005', 'Assicurazione sollevatore telescopico',     'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.05'));

-- --- COSTI: Carburanti e pedaggi (40.06) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.06', 'Carburanti, pedaggi e veicoli', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, parent_id) VALUES
    ('40.06.001', 'Telepass – pedaggi autostradali', 'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.06')),
    ('40.06.002', 'Carburante benzina',              'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.06')),
    ('40.06.003', 'Bollo auto – Dacia',              'COSTO', (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.06'));

-- --- INVESTIMENTI / CAPEX (50.01) ---

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('50.01', 'Attrezzature e macchinari', 'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50'));

INSERT INTO piano_dei_conti_coge (codice, descrizione, tipo, is_capex, parent_id) VALUES
    ('50.01.001', 'Lavastoviglie professionale',          'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.002', 'Lavapavimenti industriale',            'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.003', 'Sedie e tavoli (arredo sala)',          'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.004', 'Gerosa – macchine agricole',           'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.005', 'Aiani – lavori idraulici (impianti)',  'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.006', 'Eurosistem – impianti tecnologici',    'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.007', 'Mallamace – forniture edili',          'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.008', 'Scavi e movimento terra',              'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01')),
    ('50.01.009', 'Piastrelle cucina – rivestimenti',     'COSTO', true, (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01'));

-- ============================================================
-- CENTRI DI COSTO (COAN)
-- Un centro per ogni BU operativa
-- ============================================================

INSERT INTO centri_di_costo_coan (codice, descrizione, business_unit_id) VALUES
    ('CDC-BU1', 'Centro di costo – Ristorazione e Agriturismo', 1),
    ('CDC-BU2', 'Centro di costo – Cerimonie ed Eventi',        2),
    ('CDC-BU3', 'Centro di costo – Vendita Prodotti Spaccio',   3),
    ('CDC-BU4', 'Centro di costo – Manutenzione Verde',         4),
    ('CDC-BU5', 'Centro di costo – Overhead Generale',          5);
