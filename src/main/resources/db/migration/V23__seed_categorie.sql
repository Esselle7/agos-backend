-- ============================================================
-- V23 – SEED CATEGORIE OPERATIVE
-- Classificazione ENTRATA/USCITA per ogni BU.
-- Struttura piatta (no parent) per semplicità d'uso quotidiano.
-- ============================================================

-- ── BU1 – Ristorazione e Agriturismo ─────────────────────────

INSERT INTO categorie (nome, tipo, bu_id, ordinamento) VALUES
    -- ENTRATE
    ('Incassi cassa ristorazione (Billy)',    'ENTRATA', 1, 10),
    ('Pagamenti POS / carte',                'ENTRATA', 1, 20),
    ('Satispay ristorazione',                'ENTRATA', 1, 30),
    ('B&B / Ospitalità',                     'ENTRATA', 1, 40),
    ('Degustazioni ed enoturismo',           'ENTRATA', 1, 50),
    -- USCITE
    ('Materie prime alimentari',             'USCITA',  1, 10),
    ('Bevande – vini, birre, acqua',         'USCITA',  1, 20),
    ('Dolci e dessert',                      'USCITA',  1, 30),
    ('Forniture e materiali di consumo',     'USCITA',  1, 40),
    ('Manodopera cucina e sala',             'USCITA',  1, 50),
    ('Manutenzione e riparazioni',           'USCITA',  1, 60),
    ('Abbonamenti e servizi digitali',       'USCITA',  1, 70);

-- ── BU2 – Cerimonie ed Eventi ────────────────────────────────

INSERT INTO categorie (nome, tipo, bu_id, ordinamento) VALUES
    -- ENTRATE
    ('Caparre eventi',                       'ENTRATA', 2, 10),
    ('Acconti eventi',                       'ENTRATA', 2, 20),
    ('Saldi eventi',                         'ENTRATA', 2, 30),
    ('Rimborsi ricevuti',                    'ENTRATA', 2, 40),
    -- USCITE
    ('Food & Beverage per eventi',           'USCITA',  2, 10),
    ('Allestimenti e noleggi',               'USCITA',  2, 20),
    ('Personale eventi (extra)',             'USCITA',  2, 30),
    ('Servizi fornitori (DJ, foto, fiori)',  'USCITA',  2, 40),
    ('Costi organizzativi evento',           'USCITA',  2, 50);

-- ── BU3 – Vendita Prodotti e Spaccio ────────────────────────

INSERT INTO categorie (nome, tipo, bu_id, ordinamento) VALUES
    -- ENTRATE
    ('Vendita spaccio – contanti/POS',       'ENTRATA', 3, 10),
    ('Incassi Alveare',                      'ENTRATA', 3, 20),
    ('Incassi Shopify / online',             'ENTRATA', 3, 30),
    ('Satispay spaccio',                     'ENTRATA', 3, 40),
    -- USCITE
    ('Acquisti carni e salumi',              'USCITA',  3, 10),
    ('Acquisti ortofrutta e trasformati',    'USCITA',  3, 20),
    ('Imballaggi e packaging',               'USCITA',  3, 30),
    ('Commissioni marketplace',              'USCITA',  3, 40);

-- ── BU4 – Manutenzione Verde ─────────────────────────────────

INSERT INTO categorie (nome, tipo, bu_id, ordinamento) VALUES
    -- ENTRATE
    ('Ricavi manutenzione privati',          'ENTRATA', 4, 10),
    ('Ricavi manutenzione aziende',         'ENTRATA', 4, 20),
    ('Ricavi manutenzione condomini',       'ENTRATA', 4, 30),
    -- USCITE
    ('Materiali (piante, terriccio, concimi)', 'USCITA', 4, 10),
    ('Carburante attrezzature verde',        'USCITA',  4, 20),
    ('Manutenzione macchinari',             'USCITA',  4, 30),
    ('Smaltimento verde e rifiuti',         'USCITA',  4, 40);

-- ── BU5 – Overhead Generale ─────────────────────────────────

INSERT INTO categorie (nome, tipo, bu_id, ordinamento) VALUES
    -- (BU5 ha solo uscite generali; le entrate straordinarie vanno in BU specifica)
    ('Mutui e rate finanziamenti',           'USCITA',  5, 10),
    ('Assicurazioni',                        'USCITA',  5, 20),
    ('Utenze (acqua, gas, elettricità)',     'USCITA',  5, 30),
    ('Commercialista e consulenze',          'USCITA',  5, 40),
    ('Marketing e comunicazione',            'USCITA',  5, 50),
    ('Spese bancarie e commissioni POS',     'USCITA',  5, 60),
    ('Bollo auto e pedaggi',                 'USCITA',  5, 70),
    ('Cancelleria e ufficio',                'USCITA',  5, 80),
    ('Imposte e tasse (F24)',                'USCITA',  5, 90),
    ('Spese generali non classificate',      'USCITA',  5, 100);
