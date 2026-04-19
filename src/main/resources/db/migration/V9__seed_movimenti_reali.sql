-- ============================================================
-- V9 - MOVIMENTI REALI: Billy (mar 2026) + Crédit Agricole (gen 2026)
--      + Banco BPM (mar 2026)
--
-- Fonti:
--   IMPORT_BILLY    → dati registratore cassa Billy (marzo 2026)
--   IMPORT_BANCA    → estratti conto CA (gennaio 2026) e BPM (marzo 2026)
--
-- Riferimento_esterno = Chiave Billy (es. "DCW2026/5637-3684") o
--                       chiave estratto conto (es. "46029/NEXI")
-- per garantire idempotenza: reimportare questo file non crea duplicati.
--
-- Nota giroconti: i bonifici "bon.da societa' agricola agostinelli"
-- visibili su BPM sono trasferimenti interni da CA. Non vengono
-- registrati in questa migration per evitare doppio conteggio
-- (andrebbero registrati come uscita CA + entrata BPM in doppia partita).
-- ============================================================

DO $$
DECLARE
    -- User admin (created in V7)
    v_user UUID;

    -- Conti bancari (ID fissi da V6)
    v_conto_bpm  CONSTANT SMALLINT := 1;
    v_conto_ca   CONSTANT SMALLINT := 2;
    v_conto_cash CONSTANT SMALLINT := 3;

    -- Metodi pagamento
    v_mp_contanti    INT;
    v_mp_pos_bpm     INT;
    v_mp_pos_ca      INT;
    v_mp_bonifico    INT;
    v_mp_rid         INT;
    v_mp_stripe      INT;

    -- Aliquote IVA
    v_iva_0   INT;
    v_iva_4   INT;
    v_iva_10  INT;
    v_iva_22  INT;

    -- Coge principali
    v_coge_rist_pos      INT;  -- 30.01.001 incassi ristorazione
    v_coge_eventi_cap    INT;  -- 30.02.001 caparre eventi
    v_coge_eventi_saldo  INT;  -- 30.02.002 saldi eventi
    v_coge_spaccio_carne INT;  -- 30.03.001 carne/salumi/trasformati 10%
    v_coge_spaccio_orto  INT;  -- 30.03.002 ortofrutta 4%
    v_coge_alveare       INT;  -- 30.03.003 Alveare/Shopify netto
    v_coge_banca_spese   INT;  -- 40.02.002 spese bancarie
    v_coge_nexi_comm     INT;  -- 40.02.001 commissioni Nexi
    v_coge_lodetti       INT;  -- 40.01.005 manodopera altri
    v_coge_comedil       INT;  -- 40.09.003 manutenzione edile
    v_coge_zep           INT;  -- 40.09.004 pulizie
    v_coge_ginestra      INT;  -- 40.12.003 acquisti Fattoria Ginestra
    v_coge_giroconto     INT;  -- 10.03.001 giroconto interno

    -- Centri di costo
    v_cdc_bu1 INT;  -- CDC-BU1 ristorazione
    v_cdc_bu2 INT;  -- CDC-BU2 cerimonie
    v_cdc_bu3 INT;  -- CDC-BU3 spaccio
    v_cdc_bu5 INT;  -- CDC-BU5 overhead

    -- Fornitori
    v_forn_lodetti   UUID;
    v_forn_comedil   UUID;
    v_forn_zep       UUID;
    v_forn_ginestra  UUID;

    -- IDs eventi (assegnati durante l'INSERT)
    v_evt_cavadini    UUID;
    v_evt_malacrida   UUID;
    v_evt_gallazzi    UUID;
    v_evt_gilardi     UUID;
    v_evt_gigliotti   UUID;
    v_evt_spinelli    UUID;
    v_evt_viola       UUID;
    v_evt_balzaretti  UUID;
    v_evt_cella       UUID;
    v_evt_seminara    UUID;
    v_evt_benini      UUID;
    v_evt_pelli       UUID;
    v_evt_davide      UUID;

BEGIN
    -- --------------------------------------------------------
    -- Lookup IDs
    -- --------------------------------------------------------
    SELECT id INTO v_user         FROM users WHERE email = 'simone.leone300900@gmail.com';
    SELECT id INTO v_mp_contanti  FROM metodi_pagamento WHERE codice = 'CONTANTI';
    SELECT id INTO v_mp_pos_bpm   FROM metodi_pagamento WHERE codice = 'POS_BPM';
    SELECT id INTO v_mp_pos_ca    FROM metodi_pagamento WHERE codice = 'POS_CA_NEXI';
    SELECT id INTO v_mp_bonifico  FROM metodi_pagamento WHERE codice = 'BONIFICO';
    SELECT id INTO v_mp_rid       FROM metodi_pagamento WHERE codice = 'RID_SDDMANDAT';
    SELECT id INTO v_mp_stripe    FROM metodi_pagamento WHERE codice = 'ALVEARE_STRIPE';

    SELECT id INTO v_iva_0  FROM aliquote_iva WHERE aliquota = 0.0;
    SELECT id INTO v_iva_4  FROM aliquote_iva WHERE aliquota = 4.0;
    SELECT id INTO v_iva_10 FROM aliquote_iva WHERE aliquota = 10.0;
    SELECT id INTO v_iva_22 FROM aliquote_iva WHERE aliquota = 22.0;

    SELECT id INTO v_coge_rist_pos      FROM piano_dei_conti_coge WHERE codice = '30.01.001';
    SELECT id INTO v_coge_eventi_cap    FROM piano_dei_conti_coge WHERE codice = '30.02.001';
    SELECT id INTO v_coge_eventi_saldo  FROM piano_dei_conti_coge WHERE codice = '30.02.002';
    SELECT id INTO v_coge_spaccio_carne FROM piano_dei_conti_coge WHERE codice = '30.03.001';
    SELECT id INTO v_coge_spaccio_orto  FROM piano_dei_conti_coge WHERE codice = '30.03.002';
    SELECT id INTO v_coge_alveare       FROM piano_dei_conti_coge WHERE codice = '30.03.003';
    SELECT id INTO v_coge_banca_spese   FROM piano_dei_conti_coge WHERE codice = '40.02.002';
    SELECT id INTO v_coge_nexi_comm     FROM piano_dei_conti_coge WHERE codice = '40.02.001';
    SELECT id INTO v_coge_lodetti       FROM piano_dei_conti_coge WHERE codice = '40.01.005';
    SELECT id INTO v_coge_comedil       FROM piano_dei_conti_coge WHERE codice = '40.09.003';
    SELECT id INTO v_coge_zep           FROM piano_dei_conti_coge WHERE codice = '40.09.004';
    SELECT id INTO v_coge_ginestra      FROM piano_dei_conti_coge WHERE codice = '40.12.003';
    SELECT id INTO v_coge_giroconto     FROM piano_dei_conti_coge WHERE codice = '10.03.001';

    SELECT id INTO v_cdc_bu1 FROM centri_di_costo_coan WHERE codice = 'CDC-BU1';
    SELECT id INTO v_cdc_bu2 FROM centri_di_costo_coan WHERE codice = 'CDC-BU2';
    SELECT id INTO v_cdc_bu3 FROM centri_di_costo_coan WHERE codice = 'CDC-BU3';
    SELECT id INTO v_cdc_bu5 FROM centri_di_costo_coan WHERE codice = 'CDC-BU5';

    SELECT id INTO v_forn_lodetti  FROM fornitori WHERE alias = 'Lodetti';
    SELECT id INTO v_forn_comedil  FROM fornitori WHERE alias = 'Comedil';
    SELECT id INTO v_forn_zep      FROM fornitori WHERE alias = 'Zep Italia';
    SELECT id INTO v_forn_ginestra FROM fornitori WHERE alias = 'Fattoria Ginestra';

    -- ========================================================
    -- SEZIONE 1: EVENTI (BU2 – Cerimonie ed eventi)
    -- Estratti da Billy e dall'estratto CA (gen/mar 2026)
    -- ========================================================

    -- 1. Cavadini Deborah / Rivolta Mirko – Evento 28/03/2026
    INSERT INTO eventi (nome, tipo, data_evento, importo_totale_preventivato,
                        stato, business_unit_id, contatto_nome)
    VALUES ('Evento Cavadini-Rivolta 28/03/2026', 'MATRIMONIO', '2026-03-28',
            NULL, 'CONFERMATO', 2, 'Cavadini Deborah / Rivolta Mirko')
    RETURNING id INTO v_evt_cavadini;

    -- 2. Malacrida Christian / Spano Eleonora – Evento 27/02/2026 (già completato con saldo)
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Malacrida-Spano 27/02/2026', 'MATRIMONIO', '2026-02-27',
            'COMPLETATO', 2, 'Malacrida Christian / Spano Eleonora')
    RETURNING id INTO v_evt_malacrida;

    -- 3. Gallazzi Valerio / Franceschetto Claudia – Evento 11/04/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Gallazzi-Franceschetto 11/04/2026', 'MATRIMONIO', '2026-04-11',
            'CONFERMATO', 2, 'Gallazzi Valerio / Franceschetto Claudia')
    RETURNING id INTO v_evt_gallazzi;

    -- 4. Gilardi Moreno / Mazzoni Michela – Evento 03/06/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Gilardi-Mazzoni 03/06/2026', 'MATRIMONIO', '2026-06-03',
            'CONFERMATO', 2, 'Gilardi Moreno / Mazzoni Michela')
    RETURNING id INTO v_evt_gilardi;

    -- 5. 18 Anni Marta Gigliotti – Evento 20/06/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('18 Anni Marta Gigliotti 20/06/2026', 'BANCHETTO_PRIVATO', '2026-06-20',
            'CONFERMATO', 2, 'De Bernardi Veronica (org.) / Gigliotti Marta')
    RETURNING id INTO v_evt_gigliotti;

    -- 6. Spinelli Alice – Evento 13/03/2026 (completato, saldo ricevuto 17/03)
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Spinelli Alice 13/03/2026', 'MATRIMONIO', '2026-03-13',
            'COMPLETATO', 2, 'Spinelli Dario / Parrotto Stefania')
    RETURNING id INTO v_evt_spinelli;

    -- 7. Viola Zottiche / Solero Francesca – Evento 22/05/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Viola Zottiche 22/05/2026', 'BANCHETTO_PRIVATO', '2026-05-22',
            'CONFERMATO', 2, 'Solero Francesca (org.) / Viola Zottiche')
    RETURNING id INTO v_evt_viola;

    -- 8. Balzaretti Erica 18esimo – Evento 06/07/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('18esimo Erica Balzaretti 06/07/2026', 'BANCHETTO_PRIVATO', '2026-07-06',
            'CONFERMATO', 2, 'Fontana Marco / Balzaretti Erica')
    RETURNING id INTO v_evt_balzaretti;

    -- 9. Cella Erika / Doardo Stefano – Evento 18/09/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Cella-Doardo 18/09/2026', 'MATRIMONIO', '2026-09-18',
            'CONFERMATO', 2, 'Cella Erika / Doardo Stefano')
    RETURNING id INTO v_evt_cella;

    -- 10. Seminara Letizia / Verta Gabriele – Gender Reveal ~22/03/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Gender Reveal Seminara-Verta 22/03/2026', 'BANCHETTO_PRIVATO', '2026-03-22',
            'COMPLETATO', 2, 'Seminara Letizia / Verta Gabriele')
    RETURNING id INTO v_evt_seminara;

    -- 11. Benini Bruna – Evento 18/04/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Benini Bruna 18/04/2026', 'BANCHETTO_PRIVATO', '2026-04-18',
            'CONFERMATO', 2, 'Benini Bruna')
    RETURNING id INTO v_evt_benini;

    -- 12. Pelli Elisabetta – Compleanno / affitto sala 19/04/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Compleanno Pelli Elisabetta 19/04/2026', 'BANCHETTO_PRIVATO', '2026-04-19',
            'CONFERMATO', 2, 'Pelli Elisabetta / Messina Elena')
    RETURNING id INTO v_evt_pelli;

    -- 13. Acconto evento Davide Meraviglia 10/01/2026
    INSERT INTO eventi (nome, tipo, data_evento, stato, business_unit_id, contatto_nome)
    VALUES ('Evento Davide Meraviglia 10/01/2026', 'BANCHETTO_PRIVATO', '2026-01-10',
            'COMPLETATO', 2, 'Cappiello Caterina / Davide Meraviglia')
    RETURNING id INTO v_evt_davide;

    -- ========================================================
    -- SEZIONE 2: MOVIMENTI BILLY – MARZO 2026
    -- fonte = IMPORT_BILLY, riferimento = Numero Billy (DCW...)
    -- ========================================================

    -- 02/03/2026 – Caparra Cavadini-Rivolta 500 EUR (CA, elettronico)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-02', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-03-28', '2026-03-02', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_cavadini, 'CAPARRA',
        'Caparra evento 28/03/2026 – Cavadini Deborah / Rivolta Mirko',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5637-3684', v_user);

    -- 02/03/2026 – Incasso contanti ristorazione 1800 EUR (Cash)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-02', 'ENTRATA', 1800.00, 1636.36, 163.64,
        '2026-03-02', v_conto_cash, v_mp_contanti, v_iva_10,
        v_coge_rist_pos, v_cdc_bu1, 1,
        'Incasso contanti ristorazione 02/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5625-4926', v_user);

    -- 03/03/2026 – Saldo evento Malacrida-Spano 830 EUR (CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-03', 'ENTRATA', 830.00, 754.55, 75.45,
        '2026-02-27', '2026-03-03', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_saldo, v_cdc_bu2, 2,
        v_evt_malacrida, 'SALDO',
        'Saldo evento 27/02/2026 – Malacrida Christian / Spano Eleonora',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5640-8283', v_user);

    -- 06/03/2026 – Spaccio carne BPM POS 66.90 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-06', 'ENTRATA', 66.90, 60.82, 6.08,
        '2026-03-06', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS BPM 06/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5743-2734', v_user);

    -- 07/03/2026 – Spaccio prodotti trasformati CA POS 720.80 EUR (carne 10%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-07', 'ENTRATA', 720.80, 655.27, 65.53,
        '2026-03-07', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio prodotti trasformati – POS CA 07/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-2984', v_user);

    -- 07/03/2026 – Spaccio ortofrutta CA POS 17.50 EUR (IVA 4%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-07', 'ENTRATA', 17.50, 16.83, 0.67,
        '2026-03-07', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS CA 07/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-2137', v_user);

    -- 07/03/2026 – Spaccio ortofrutta CA POS 8.90 EUR (IVA 4%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-07', 'ENTRATA', 8.90, 8.56, 0.34,
        '2026-03-07', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS CA 07/03/2026 #2',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-1948', v_user);

    -- 07/03/2026 – Spaccio ortofrutta CA POS 21.40 EUR (IVA 4%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-07', 'ENTRATA', 21.40, 20.58, 0.82,
        '2026-03-07', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS CA 07/03/2026 #3',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-1833', v_user);

    -- 07/03/2026 – Misto ortofrutta+carne CA POS 73.50 EUR
    -- Predominanza ortofrutta (70.67 imponibile 4% + 2.83 imponibile 10%)
    -- Registrato come 2 movimenti separati per IVA distinte
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    -- quota ortofrutta 4%: imponibile 70.67, IVA 2.83
    ('2026-03-07', 'ENTRATA', 73.50, 70.67, 2.83,
        '2026-03-07', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta (quota principale) – POS CA 07/03/2026 #4',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-1616-ORTO', v_user);

    -- 07/03/2026 – Spaccio carne BPM POS 40.60 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-07', 'ENTRATA', 40.60, 36.91, 3.69,
        '2026-03-07', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS BPM 07/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5773-0808', v_user);

    -- 08/03/2026 – Spaccio carne CA POS 13.70 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-08', 'ENTRATA', 13.70, 12.45, 1.25,
        '2026-03-08', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 08/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5789-9325', v_user);

    -- 11/03/2026 – Caparra Gallazzi 500 EUR (CA, elettronico)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-11', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-04-11', '2026-03-11', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_gallazzi, 'CAPARRA',
        'Caparra confirmatoria evento 11/04/2026 – Gallazzi',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5855-9270', v_user);

    -- 13/03/2026 – Spaccio carne BPM POS 48.37 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-13', 'ENTRATA', 48.37, 43.97, 4.40,
        '2026-03-13', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS BPM 13/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5933-9934', v_user);

    -- 14/03/2026 – Spaccio carne CA POS 71.00 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-14', 'ENTRATA', 71.00, 64.55, 6.45,
        '2026-03-14', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 14/03/2026 #1',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5958-3401', v_user);

    -- 14/03/2026 – Spaccio carne CA POS 50.00 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-14', 'ENTRATA', 50.00, 45.45, 4.55,
        '2026-03-14', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 14/03/2026 #2',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5948-1408', v_user);

    -- 14/03/2026 – Spaccio carne CA POS 95.00 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-14', 'ENTRATA', 95.00, 86.36, 8.64,
        '2026-03-14', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 14/03/2026 #3',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5946-4228', v_user);

    -- 15/03/2026 – Spaccio prodotti trasformati BPM POS 150 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-15', 'ENTRATA', 150.00, 136.36, 13.64,
        '2026-03-15', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio prodotti trasformati – POS BPM 15/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5981-5873', v_user);

    -- 15/03/2026 – Incasso contanti ristorazione 700 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-15', 'ENTRATA', 700.00, 636.36, 63.64,
        '2026-03-15', v_conto_cash, v_mp_contanti, v_iva_10,
        v_coge_rist_pos, v_cdc_bu1, 1,
        'Incasso contanti ristorazione 15/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/5981-5682', v_user);

    -- 16/03/2026 – Saldo evento Gilardi-Mazzoni 125 EUR (CA, bonifico)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-16', 'ENTRATA', 125.00, 113.64, 11.36,
        '2026-06-03', '2026-03-16', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_saldo, v_cdc_bu2, 2,
        v_evt_gilardi, 'SALDO',
        'Saldo evento 03/06/2026 – Gilardi Moreno / Mazzoni Michela',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6013-3917', v_user);

    -- 16/03/2026 – Acconto/caparra evento 500 EUR (CA, bonifico – banca non identificata da Billy)
    -- Nota: Billy segna #N/A per la banca; dal contesto è CA (circondata da movimenti CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, note, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-16', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-03-16', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        'Caparra evento (banca non identificata da Billy) 16/03/2026',
        'Banca segnata come #N/A in Billy – assegnato a CA per contesto. Verificare.',
        'REGISTRATO', 'IMPORT_BILLY', 'DCW2026/6013-4088', v_user);

    -- 17/03/2026 – Caparra De Bernardi (18anni Gigliotti) 500 EUR x3 → 1500 EUR totale
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    ('2026-03-17', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-06-20', '2026-03-17', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_gigliotti, 'CAPARRA',
        'Caparra #1 – 18 Anni Marta Gigliotti 20/06/2026 (De Bernardi)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6038-8449', v_user),
    ('2026-03-17', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-06-20', '2026-03-17', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_gigliotti, 'CAPARRA',
        'Caparra #2 – 18 Anni Marta Gigliotti 20/06/2026 (De Bernardi)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6038-8310', v_user),
    ('2026-03-17', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-06-20', '2026-03-17', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_gigliotti, 'CAPARRA',
        'Caparra #3 – 18 Anni Marta Gigliotti 20/06/2026 (De Bernardi)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6038-7996-C3', v_user);

    -- 17/03/2026 – Saldo evento Spinelli Alice 1600 EUR (CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-17', 'ENTRATA', 1600.00, 1454.55, 145.45,
        '2026-03-13', '2026-03-17', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_saldo, v_cdc_bu2, 2,
        v_evt_spinelli, 'SALDO',
        'Saldo evento 13/03/2026 – Alice Spinelli (Spinelli Dario / Parrotto Stefania)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6038-7996', v_user);

    -- 17/03/2026 – Caparra Viola Zottiche 200 EUR (CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-17', 'ENTRATA', 200.00, 181.82, 18.18,
        '2026-05-22', '2026-03-17', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_viola, 'CAPARRA',
        'Caparra confirmatoria – Viola Zottiche 22/05/2026 (Solero Francesca)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6038-7579', v_user);

    -- 17/03/2026 – Spaccio carne CA POS 60 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-17', 'ENTRATA', 60.00, 54.55, 5.45,
        '2026-03-17', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 17/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6037-9165', v_user);

    -- 18/03/2026 – Caparra Fontana-Balzaretti 500 EUR (CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-18', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-07-06', '2026-03-18', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_balzaretti, 'CAPARRA',
        'Caparra evento 06/07/2026 – Erica Balzaretti 18esimo (Fontana Marco)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6069-8601', v_user);

    -- 18/03/2026 – Spaccio ortofrutta 197.50 EUR (banca #N/A → CA per contesto)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, note, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-18', 'ENTRATA', 197.50, 189.90, 7.60,
        '2026-03-18', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta 18/03/2026',
        'Banca #N/A in Billy – assegnato CA per contesto. Verificare.',
        'REGISTRATO', 'IMPORT_BILLY', 'DCW2026/6069-5428', v_user);

    -- 18/03/2026 – Spaccio prodotti trasformati CA POS 560 EUR
    -- Nota: Billy include questo movimento insieme alla caparra Fontana-Balzaretti.
    -- Qui registrato come vendita spaccio autonoma (il movimento caparra è già sopra).
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-18', 'ENTRATA', 560.00, 509.09, 50.91,
        '2026-03-18', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio prodotti trasformati – POS CA 18/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6069-5184', v_user);

    -- 19/03/2026 – Spaccio carne CA POS 49.60 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-19', 'ENTRATA', 49.60, 45.09, 4.51,
        '2026-03-19', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 19/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6082-2558', v_user);

    -- 20/03/2026 – Spaccio ortofrutta CA POS 39 EUR (IVA 4%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-20', 'ENTRATA', 39.00, 37.50, 1.50,
        '2026-03-20', v_conto_ca, v_mp_pos_ca, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS CA 20/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6124-9639', v_user);

    -- 20/03/2026 – Spaccio ortofrutta BPM POS 38.60 EUR (IVA 4%)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-20', 'ENTRATA', 38.60, 37.12, 1.48,
        '2026-03-20', v_conto_bpm, v_mp_pos_bpm, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS BPM 20/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6123-0263', v_user);

    -- 20/03/2026 – Caparra Cella-Doardo 500 EUR (BPM, bonifico)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-20', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-09-18', '2026-03-20', v_conto_bpm, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_cella, 'CAPARRA',
        'Caparra evento 18/09/2026 – Cella Erika / Doardo Stefano',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6107-9743', v_user);

    -- 20/03/2026 – Spaccio ortofrutta BPM bancomat 11.70 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-20', 'ENTRATA', 11.70, 11.25, 0.45,
        '2026-03-20', v_conto_bpm, v_mp_pos_bpm, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS BPM bancomat 20/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6107-9616', v_user);

    -- 23/03/2026 – Gender Reveal Seminara-Verta 555 EUR (CA)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-23', 'ENTRATA', 555.00, 504.55, 50.45,
        '2026-03-22', '2026-03-23', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_saldo, v_cdc_bu2, 2,
        v_evt_seminara, 'SALDO',
        'Gender Reveal Seminara Letizia / Verta Gabriele 22/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6158-0226', v_user);

    -- 23/03/2026 – Incasso contanti ristorazione 1100 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-23', 'ENTRATA', 1100.00, 1000.00, 100.00,
        '2026-03-23', v_conto_cash, v_mp_contanti, v_iva_10,
        v_coge_rist_pos, v_cdc_bu1, 1,
        'Incasso contanti ristorazione 23/03/2026',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6157-6893', v_user);

    -- 23/03/2026 – Spaccio carne BPM 67.90 EUR (#N/A Billy → BPM da nota)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-23', 'ENTRATA', 67.90, 61.73, 6.17,
        '2026-03-23', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS BPM 23/03/2026 (nota Billy: Bpm 67,22 del 23/03)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6157-3627', v_user);

    -- 23/03/2026 – Spaccio carne CA POS 502.90 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-23', 'ENTRATA', 502.90, 457.18, 45.72,
        '2026-03-23', v_conto_ca, v_mp_pos_ca, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS CA 21/03/2026 (accreditato 23/03)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6156-9910', v_user);

    -- 23/03/2026 – Spaccio carne BPM 565.60 EUR (#N/A Billy → BPM da nota)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, note, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-23', 'ENTRATA', 565.60, 514.18, 51.42,
        '2026-03-23', v_conto_bpm, v_mp_pos_bpm, v_iva_10,
        v_coge_spaccio_carne, v_cdc_bu3, 3,
        'Vendita spaccio carne – POS BPM 23/03/2026',
        'Nota Billy: Bpm 426,9 + 138,7 del 23/03. Verificare se sono 2 movimenti separati.',
        'REGISTRATO', 'IMPORT_BILLY', 'DCW2026/6156-5754', v_user);

    -- 26/03/2026 – Spaccio ortofrutta BPM POS 102.80 EUR
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-26', 'ENTRATA', 102.80, 98.85, 3.95,
        '2026-03-26', v_conto_bpm, v_mp_pos_bpm, v_iva_4,
        v_coge_spaccio_orto, v_cdc_bu3, 3,
        'Vendita spaccio ortofrutta – POS BPM 25/03/2026 (accreditato 26/03)',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6260-8404', v_user);

    -- 27/03/2026 – Acconto/caparra Benini Bruna 3000 EUR (CA)
    -- Entità 3000 EUR per evento 18/04 → probabilmente caparra + acconto
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES ('2026-03-27', 'ENTRATA', 3000.00, 2727.27, 272.73,
        '2026-04-18', '2026-03-27', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_benini, 'CAPARRA',
        'Caparra/acconto evento 18/04/2026 – Benini Bruna',
        'RICONCILIATO', 'IMPORT_BILLY', 'DCW2026/6270-9260', v_user);

    -- ========================================================
    -- SEZIONE 3: ESTRATTO BANCO BPM – MARZO 2026 (Stripe/Alveare)
    -- Solo i bonifici Stripe non già coperti da Billy
    -- ========================================================

    INSERT INTO movimenti (data_movimento, tipo, importo_lordo,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    ('2026-03-02', 'ENTRATA', 57.50,  '2026-02-26', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 26/02/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46083/57.5-STRIPE',  v_user),
    ('2026-03-03', 'ENTRATA', 117.25, '2026-02-27', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 27/02/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46084/117.25-STRIPE', v_user),
    ('2026-03-03', 'ENTRATA', 27.10,  '2026-02-28', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 28/02/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46084/27.1-STRIPE',  v_user),
    ('2026-03-09', 'ENTRATA', 115.30, '2026-03-05', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 05/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46090/115.3-STRIPE', v_user),
    ('2026-03-10', 'ENTRATA', 85.29,  '2026-03-06', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 06/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46091/85.29-STRIPE', v_user),
    ('2026-03-13', 'ENTRATA', 138.88, '2026-03-11', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 11/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46094/138.88-STRIPE',v_user),
    ('2026-03-16', 'ENTRATA', 138.04, '2026-03-12', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 12/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46097/138.04-STRIPE',v_user),
    ('2026-03-17', 'ENTRATA', 21.21,  '2026-03-14', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 14/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46098/21.21-STRIPE', v_user),
    ('2026-03-18', 'ENTRATA', 173.39, '2026-03-16', v_conto_bpm, v_mp_stripe, v_iva_0, v_coge_alveare, v_cdc_bu3, 3, 'Stripe Alveare/Shopify – accredito PO 16/03/2026', 'RICONCILIATO', 'IMPORT_BANCA', '46099/173.39-STRIPE',v_user);

    -- ========================================================
    -- SEZIONE 4: ESTRATTO CRÉDIT AGRICOLE – GENNAIO 2026
    -- Uscite (commissioni, fornitori) e entrate (POS, bonifici)
    -- ========================================================

    -- USCITE BANCARIE (commissioni, spese)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    ('2026-01-02', 'USCITA', 25.21,  '2025-12-31', v_conto_ca, v_mp_rid, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Bollo E/C CA art.13 – competenza Q4 2025', 'RICONCILIATO', 'IMPORT_BANCA', '46022/BOLLO-EC-2025Q4',  v_user),
    ('2026-01-02', 'USCITA', 6.10,   '2026-01-02', v_conto_ca, v_mp_rid, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Canone NowBanking Corporate – CA gen 2026',    'RICONCILIATO', 'IMPORT_BANCA', '46022/CANONE-NOWBANK',   v_user),
    ('2026-01-07', 'USCITA', 35.51,  '2026-01-07', v_conto_ca, v_mp_rid, v_iva_22, v_coge_nexi_comm,  v_cdc_bu5, 5, 'SDD Nexi – commissioni POS dic 2025',         'RICONCILIATO', 'IMPORT_BANCA', '46029/NEXI-SDD-DIC2025', v_user),
    ('2026-01-07', 'USCITA', 1.00,   '2026-01-07', v_conto_ca, v_mp_rid, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Commissione SDD Nexi 5229966832',             'RICONCILIATO', 'IMPORT_BANCA', '46029/COMM-SDD-NEXI',    v_user),
    ('2026-01-07', 'USCITA', 1.50,   '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Comm. bonifico Lodetti 717592705',       'RICONCILIATO', 'IMPORT_BANCA', '46029/COMM-BON-LODETTI', v_user),
    ('2026-01-07', 'USCITA', 1.50,   '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Comm. bonifico Comedil 717643894',       'RICONCILIATO', 'IMPORT_BANCA', '46029/COMM-BON-COMEDIL', v_user),
    ('2026-01-07', 'USCITA', 1.50,   '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Comm. bonifico Zep Italia 717593042',    'RICONCILIATO', 'IMPORT_BANCA', '46029/COMM-BON-ZEP',     v_user),
    ('2026-01-09', 'USCITA', 1.50,   '2026-01-09', v_conto_ca, v_mp_bonifico, v_iva_22, v_coge_banca_spese, v_cdc_bu5, 5, 'Comm. bonifico Fattoria Ginestra 717898545','RICONCILIATO','IMPORT_BANCA','46031/COMM-BON-GINEST', v_user);

    -- USCITE FORNITORI
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        fornitore_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    ('2026-01-07', 'USCITA', 2000.00, 1639.34, 360.66,
        '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22,
        v_coge_lodetti, v_cdc_bu5, 5, v_forn_lodetti,
        'Bonifico Lodetti Ivano – prestazione manodopera gen 2026',
        'RICONCILIATO', 'IMPORT_BANCA', '46029/717592705-LODETTI', v_user),
    ('2026-01-07', 'USCITA', 321.76, 263.74, 58.02,
        '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22,
        v_coge_comedil, v_cdc_bu5, 5, v_forn_comedil,
        'Bonifico Comedil Mangino – manutenzione edile gen 2026',
        'RICONCILIATO', 'IMPORT_BANCA', '46029/717643894-COMEDIL', v_user),
    ('2026-01-07', 'USCITA', 747.25, 612.50, 134.75,
        '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_22,
        v_coge_zep, v_cdc_bu5, 5, v_forn_zep,
        'Bonifico Zep Italia – prodotti pulizia/manutenzione gen 2026',
        'RICONCILIATO', 'IMPORT_BANCA', '46029/717593042-ZEP', v_user),
    ('2026-01-09', 'USCITA', 550.00, 450.82, 99.18,
        '2026-01-09', v_conto_ca, v_mp_bonifico, v_iva_22,
        v_coge_ginestra, v_cdc_bu1, 1, v_forn_ginestra,
        'Bonifico Fattoria Ginestra – acquisti prodotti agricoli gen 2026',
        'RICONCILIATO', 'IMPORT_BANCA', '46031/717898545-GINEST', v_user);

    -- ENTRATE BANCARIE (POS CA + bonifici eventi gen 2026)
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo,
        data_competenza, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    ('2026-01-02', 'ENTRATA', 230.00,  '2025-12-31', v_conto_ca, v_mp_pos_ca, v_iva_10, v_coge_rist_pos, v_cdc_bu1, 1, 'POS Nexi CA – incasso 31/12/2025 (accreditato 02/01)', 'RICONCILIATO', 'IMPORT_BANCA', '46024/230-NEXI-CA',    v_user),
    ('2026-01-12', 'ENTRATA', 250.00,  '2026-01-11', v_conto_ca, v_mp_pos_ca, v_iva_10, v_coge_rist_pos, v_cdc_bu1, 1, 'POS Nexi CA – incasso 11/01/2026',                   'RICONCILIATO', 'IMPORT_BANCA', '46034/250-NEXI-CA',    v_user),
    ('2026-01-12', 'ENTRATA', 58.60,   '2026-01-09', v_conto_ca, v_mp_pos_ca, v_iva_10, v_coge_rist_pos, v_cdc_bu1, 1, 'POS Nexi CA – incasso 09/01/2026',                   'RICONCILIATO', 'IMPORT_BANCA', '46034/58.6-NEXI-CA',   v_user);

    -- Bonifici eventi ricevuti su CA gen 2026
    INSERT INTO movimenti (data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        data_competenza, data_liquidita, conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by)
    VALUES
    -- Guarisco FATTURA 31/001 → ricavo BU1 (manutenzione/ristorazione per Soc. Agricola)
    -- Nota: trattata come ricavo generico BU1 senza evento specifico
    ('2026-01-07', 'ENTRATA', 2100.20, 1909.27, 190.93,
        '2026-01-07', '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_rist_pos, v_cdc_bu1, 1,
        NULL, NULL,
        'Bonifico Soc. Agr. Guarisco – Fattura 31/001 – ricavo servizi',
        'RICONCILIATO', 'IMPORT_BANCA', '46028/2100.2-GUARISCO', v_user),
    -- Pelli Elisabetta – affitto sala compleanno
    ('2026-01-07', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-04-19', '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_pelli, 'CAPARRA',
        'Caparra affitto sala – Compleanno Pelli Elisabetta 19/04/2026',
        'RICONCILIATO', 'IMPORT_BANCA', '46028/500-PELLI', v_user),
    -- Cappiello Caterina – acconto evento Davide Meraviglia
    ('2026-01-07', 'ENTRATA', 500.00, 454.55, 45.45,
        '2026-01-10', '2026-01-07', v_conto_ca, v_mp_bonifico, v_iva_10,
        v_coge_eventi_cap, v_cdc_bu2, 2,
        v_evt_davide, 'ACCONTO',
        'Acconto evento Davide Meraviglia 10/01/2026 (Cappiello Caterina)',
        'RICONCILIATO', 'IMPORT_BANCA', '46028/500-CAPPIELLO', v_user);

END $$;
