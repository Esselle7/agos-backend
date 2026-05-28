-- ============================================================
-- V27 - SEED dati di esempio per maggio 2026
--
-- Contiene:
--   - 5 eventi con stati eterogenei (PREVENTIVATO/CONFERMATO/SALDATO/ANNULLATO)
--   - 30 movimenti con mix:
--       * REGISTRATO (liquidità immediata)        → conto/metodo valorizzati, data_finanziaria = data_movimento
--       * DA_LIQUIDARE (fattura a 30/60gg)        → conto/metodo NULL, data_liquidita futura, data_finanziaria NULL
--       * movimenti legati ad eventi (CAPARRA/ACCONTO/SALDO + USCITE imputate al cdc evento)
--   - 5 piani di spesa ricorrenti + relative rate pre-generate (PENDING)
--
-- Tutti i movimenti hanno data_movimento (= competenza economica) nel mese
-- corrente di maggio 2026. data_finanziaria varia (immediata vs. DA_LIQUIDARE).
--
-- L'utente "creato_da" è risolto in fallback:
--   simone.leone300900@gmail.com → se esiste, altrimenti utente tecnico test.
-- ============================================================

DO $$
DECLARE
    -- Utente creatore (fallback test user con UUID fisso)
    v_user_id UUID;

    -- UUID degli eventi (pre-generati per FK movimenti.evento_id)
    v_ev_matrimonio UUID := gen_random_uuid();
    v_ev_bianchi    UUID := gen_random_uuid();
    v_ev_aziendale  UUID := gen_random_uuid();
    v_ev_verdi      UUID := gen_random_uuid();
    v_ev_gruppo     UUID := gen_random_uuid();

    -- UUID dei piani ricorrenti
    v_plan_mutuo     UUID := gen_random_uuid();
    v_plan_regione   UUID := gen_random_uuid();
    v_plan_assic     UUID := gen_random_uuid();
    v_plan_leasing   UUID := gen_random_uuid();
    v_plan_asconfidi UUID := gen_random_uuid();

    -- Cache ID dei metodi di pagamento (resolved by codice)
    v_mp_contanti INT;
    v_mp_pos_bpm  INT;
    v_mp_satispay INT;
    v_mp_bonifico INT;
    v_mp_alveare  INT;
    v_mp_rid      INT;

    -- Cache ID aliquote IVA (resolved by aliquota)
    v_iva_0  INT;
    v_iva_4  INT;
    v_iva_10 INT;
    v_iva_22 INT;

    -- Cache ID conti del piano dei conti COGE (resolved by codice)
    v_coge_mutuo            INT;
    v_coge_finanziamento    INT;
    v_coge_leasing          INT;
    v_coge_asconfidi        INT;
    v_coge_ricavi_billy     INT;
    v_coge_caparre          INT;
    v_coge_saldi            INT;
    v_coge_carni            INT;
    v_coge_alveare          INT;
    v_coge_verde            INT;
    v_coge_personale_altri  INT;
    v_coge_nexi             INT;
    v_coge_acqua            INT;
    v_coge_gpl              INT;
    v_coge_pasini           INT;
    v_coge_orma             INT;
    v_coge_ciocca           INT;
    v_coge_sogegross        INT;
    v_coge_assic_dacia      INT;
    v_coge_carburante       INT;

    -- Cache ID fornitori (resolved by alias)
    v_forn_pasini    UUID;
    v_forn_sogegross UUID;
    v_forn_ciocca    UUID;
    v_forn_orma      UUID;
    v_forn_gpl       UUID;
    v_forn_acqua     UUID;
    v_forn_nexi      UUID;
BEGIN
    -- ── Risolvi created_by (Simone o fallback test user) ──
    SELECT COALESCE(
        (SELECT id FROM users WHERE email = 'simone.leone300900@gmail.com'),
        '00000000-0000-0000-0000-000000000099'::UUID
    ) INTO v_user_id;

    -- ── Risolvi metodi di pagamento ──
    SELECT id INTO v_mp_contanti FROM metodi_pagamento WHERE codice = 'CONTANTI';
    SELECT id INTO v_mp_pos_bpm  FROM metodi_pagamento WHERE codice = 'POS_BPM';
    SELECT id INTO v_mp_satispay FROM metodi_pagamento WHERE codice = 'SATISPAY';
    SELECT id INTO v_mp_bonifico FROM metodi_pagamento WHERE codice = 'BONIFICO';
    SELECT id INTO v_mp_alveare  FROM metodi_pagamento WHERE codice = 'ALVEARE_STRIPE';
    SELECT id INTO v_mp_rid      FROM metodi_pagamento WHERE codice = 'RID_SDDMANDAT';

    -- ── Risolvi aliquote IVA ──
    SELECT id INTO v_iva_0  FROM aliquote_iva WHERE aliquota = 0;
    SELECT id INTO v_iva_4  FROM aliquote_iva WHERE aliquota = 4;
    SELECT id INTO v_iva_10 FROM aliquote_iva WHERE aliquota = 10;
    SELECT id INTO v_iva_22 FROM aliquote_iva WHERE aliquota = 22;

    -- ── Risolvi conti COGE ──
    SELECT id INTO v_coge_mutuo           FROM piano_dei_conti_coge WHERE codice = '20.01.001';
    SELECT id INTO v_coge_finanziamento   FROM piano_dei_conti_coge WHERE codice = '20.01.002';
    SELECT id INTO v_coge_leasing         FROM piano_dei_conti_coge WHERE codice = '20.01.005';
    SELECT id INTO v_coge_asconfidi       FROM piano_dei_conti_coge WHERE codice = '20.01.006';
    SELECT id INTO v_coge_ricavi_billy    FROM piano_dei_conti_coge WHERE codice = '30.01.001';
    SELECT id INTO v_coge_caparre         FROM piano_dei_conti_coge WHERE codice = '30.02.001';
    SELECT id INTO v_coge_saldi           FROM piano_dei_conti_coge WHERE codice = '30.02.002';
    SELECT id INTO v_coge_carni           FROM piano_dei_conti_coge WHERE codice = '30.03.001';
    SELECT id INTO v_coge_alveare         FROM piano_dei_conti_coge WHERE codice = '30.03.003';
    SELECT id INTO v_coge_verde           FROM piano_dei_conti_coge WHERE codice = '30.04.001';
    SELECT id INTO v_coge_personale_altri FROM piano_dei_conti_coge WHERE codice = '40.01.005';
    SELECT id INTO v_coge_nexi            FROM piano_dei_conti_coge WHERE codice = '40.02.001';
    SELECT id INTO v_coge_acqua           FROM piano_dei_conti_coge WHERE codice = '40.03.001';
    SELECT id INTO v_coge_gpl             FROM piano_dei_conti_coge WHERE codice = '40.03.002';
    SELECT id INTO v_coge_pasini          FROM piano_dei_conti_coge WHERE codice = '40.04.002';
    SELECT id INTO v_coge_orma            FROM piano_dei_conti_coge WHERE codice = '40.04.003';
    SELECT id INTO v_coge_ciocca          FROM piano_dei_conti_coge WHERE codice = '40.04.007';
    SELECT id INTO v_coge_sogegross       FROM piano_dei_conti_coge WHERE codice = '40.04.008';
    SELECT id INTO v_coge_assic_dacia     FROM piano_dei_conti_coge WHERE codice = '40.05.004';
    SELECT id INTO v_coge_carburante      FROM piano_dei_conti_coge WHERE codice = '40.06.002';

    -- ── Risolvi fornitori (per popolare fornitore_id sui movimenti che lo richiedono) ──
    SELECT id INTO v_forn_pasini    FROM fornitori WHERE alias = 'Pasini';
    SELECT id INTO v_forn_sogegross FROM fornitori WHERE alias = 'Sogegross';
    SELECT id INTO v_forn_ciocca    FROM fornitori WHERE alias = 'Ciocca';
    SELECT id INTO v_forn_orma      FROM fornitori WHERE alias = 'Orma';
    SELECT id INTO v_forn_gpl       FROM fornitori WHERE alias = 'GPL';
    SELECT id INTO v_forn_acqua     FROM fornitori WHERE alias = 'Acqua';
    SELECT id INTO v_forn_nexi      FROM fornitori WHERE alias = 'Nexi';

    -- ============================================================
    -- EVENTI (5)
    -- ============================================================
    INSERT INTO eventi (
        id, nome, tipo, data_evento, data_preventivo, importo_totale_preventivato,
        stato, business_unit_id,
        contatto_nome, contatto_telefono, contatto_email,
        n_ospiti, numero_totale_partecipanti, numero_bambini, note
    ) VALUES
        (v_ev_matrimonio, 'Matrimonio Rossi & Bianchi',     'MATRIMONIO',          '2026-05-30', '2026-01-15', 12000.00,
            'CONFERMATO',   2, 'Marco Rossi',     '+39 333 1234567', 'marco.rossi@example.com',     80, 80, 6,
            'Cerimonia in giardino. Menu degustazione 5 portate.'),

        (v_ev_bianchi,    'Banchetto 50° Bianchi',          'BANCHETTO_PRIVATO',   '2026-05-10', '2026-02-20', 3200.00,
            'SALDATO',      2, 'Luigi Bianchi',   '+39 347 9876543', 'l.bianchi@example.com',       35, 35, 2,
            'Festa anniversario di matrimonio.'),

        (v_ev_aziendale,  'Team Building Innovaplus SRL',   'AZIENDALE',           '2026-06-15', '2026-03-10', 4500.00,
            'PREVENTIVATO', 2, 'Laura Verdi',     '+39 320 5556677', 'laura.verdi@innovaplus.it',   50, 50, 0,
            'Workshop mattina + pranzo aziendale. In attesa di conferma cliente.'),

        (v_ev_verdi,      'Banchetto 80° Sig.ra Verdi',     'BANCHETTO_PRIVATO',   '2026-05-23', '2026-02-28', 3700.00,
            'SALDATO',      2, 'Anna Verdi',      '+39 339 1112233', NULL,                          40, 40, 1,
            'Pranzo per 80° compleanno.'),

        (v_ev_gruppo,     'Pranzo gruppo Tour Lombardia',   'RISTORAZIONE_GRUPPO', '2026-05-15', '2026-03-05', 1400.00,
            'ANNULLATO',    2, 'Tour Operator G', '+39 02 5556677',  'tour@lombardia.example.it',   25, 25, 0,
            'Annullato a meno di 7 giorni dall''evento. Caparra trattenuta come penale.');

    -- Allergie di esempio (solo Matrimonio – tabella popolata via app normalmente)
    INSERT INTO evento_allergie (evento_id, descrizione) VALUES
        (v_ev_matrimonio, 'Glutine (2 ospiti)'),
        (v_ev_matrimonio, 'Lattosio (1 ospite)'),
        (v_ev_bianchi,    'Frutta a guscio (1 ospite)');

    -- ============================================================
    -- MOVIMENTI (30) — maggio 2026
    --
    -- Schema colonne (in ordine):
    --   id, data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva, importo_commissione,
    --   data_competenza, data_finanziaria, data_liquidita,
    --   conto_bancario_id, metodo_pagamento_id, aliquota_iva_id, conto_coge_id, business_unit_id,
    --   fornitore_id, evento_id, tipo_evento_movimento, cespite_id,
    --   descrizione, stato, fonte, riferimento_esterno, created_by
    -- ============================================================
    INSERT INTO movimenti (
        id, data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva, importo_commissione,
        data_competenza, data_finanziaria, data_liquidita,
        conto_bancario_id, metodo_pagamento_id, aliquota_iva_id, conto_coge_id, business_unit_id,
        fornitore_id, evento_id, tipo_evento_movimento, cespite_id,
        descrizione, stato, fonte, riferimento_esterno, created_by
    ) VALUES
        -- 1. Rata mutuo BPM (RID, esente IVA, BU5 Overhead)
        (gen_random_uuid(), '2026-05-01', 'USCITA',  1850.00, NULL, NULL, 0,
            '2026-05-01', '2026-05-01', '2026-05-01',
            1, v_mp_rid, v_iva_0, v_coge_mutuo, 5,
            NULL, NULL, NULL, NULL,
            'Rata mensile mutuo ipotecario BPM',                       'REGISTRATO',   'MANUALE', 'MUTUO-BPM-2026-05', v_user_id),

        -- 2. Incassi ristorazione Billy POS 02/05
        (gen_random_uuid(), '2026-05-02', 'ENTRATA',  850.00, NULL, NULL, 0,
            '2026-05-02', '2026-05-02', '2026-05-02',
            1, v_mp_pos_bpm, v_iva_10, v_coge_ricavi_billy, 1,
            NULL, NULL, NULL, NULL,
            'Incasso giornaliero ristorazione (Billy) 02/05',          'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 3. Caparra Pranzo gruppo (Satispay)
        (gen_random_uuid(), '2026-05-02', 'ENTRATA',  350.00, NULL, NULL, 0,
            '2026-05-02', '2026-05-02', '2026-05-02',
            4, v_mp_satispay, v_iva_10, v_coge_caparre, 2,
            NULL, v_ev_gruppo, 'CAPARRA', NULL,
            'Caparra Pranzo gruppo Tour Lombardia',                    'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 4. Caparra Matrimonio Rossi (Bonifico)
        (gen_random_uuid(), '2026-05-03', 'ENTRATA', 1500.00, NULL, NULL, 0,
            '2026-05-03', '2026-05-03', '2026-05-03',
            1, v_mp_bonifico, v_iva_10, v_coge_caparre, 2,
            NULL, v_ev_matrimonio, 'CAPARRA', NULL,
            'Caparra Matrimonio Rossi & Bianchi',                       'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 5. Caparra Banchetto 50° Bianchi (Satispay)
        (gen_random_uuid(), '2026-05-04', 'ENTRATA',  800.00, NULL, NULL, 0,
            '2026-05-04', '2026-05-04', '2026-05-04',
            4, v_mp_satispay, v_iva_10, v_coge_caparre, 2,
            NULL, v_ev_bianchi, 'CAPARRA', NULL,
            'Caparra Banchetto 50° Bianchi',                           'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 6. Fattura verdure Pasini — DA_LIQUIDARE 30gg
        (gen_random_uuid(), '2026-05-05', 'USCITA',   280.00, NULL, NULL, 0,
            '2026-05-05', NULL, '2026-06-04',
            NULL, NULL, v_iva_4, v_coge_pasini, 1,
            v_forn_pasini, NULL, NULL, NULL,
            'Fattura verdure Pasini – termini 30gg',                   'DA_LIQUIDARE', 'MANUALE', 'PASINI-2026-05-05', v_user_id),

        -- 7. Vendita spaccio carni (cassa contanti)
        (gen_random_uuid(), '2026-05-06', 'ENTRATA',  220.00, NULL, NULL, 0,
            '2026-05-06', '2026-05-06', '2026-05-06',
            3, v_mp_contanti, v_iva_10, v_coge_carni, 3,
            NULL, NULL, NULL, NULL,
            'Vendita spaccio carni e salumi',                          'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 8. Caparra Banchetto 80° Verdi
        (gen_random_uuid(), '2026-05-06', 'ENTRATA',  700.00, NULL, NULL, 0,
            '2026-05-06', '2026-05-06', '2026-05-06',
            4, v_mp_satispay, v_iva_10, v_coge_caparre, 2,
            NULL, v_ev_verdi, 'CAPARRA', NULL,
            'Caparra Banchetto 80° Sig.ra Verdi',                      'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 9. Manutenzione verde privato
        (gen_random_uuid(), '2026-05-07', 'ENTRATA',  320.00, NULL, NULL, 0,
            '2026-05-07', '2026-05-07', '2026-05-07',
            2, v_mp_bonifico, v_iva_22, v_coge_verde, 4,
            NULL, NULL, NULL, NULL,
            'Manutenzione giardino Sig. Magri (privato)',              'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 10. Incassi ristorazione Billy POS 09/05
        (gen_random_uuid(), '2026-05-09', 'ENTRATA', 1100.00, NULL, NULL, 0,
            '2026-05-09', '2026-05-09', '2026-05-09',
            1, v_mp_pos_bpm, v_iva_10, v_coge_ricavi_billy, 1,
            NULL, NULL, NULL, NULL,
            'Incasso giornaliero ristorazione (Billy) 09/05',          'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 11. Satispay ristorazione 09/05
        (gen_random_uuid(), '2026-05-09', 'ENTRATA',  320.00, NULL, NULL, 0,
            '2026-05-09', '2026-05-09', '2026-05-09',
            4, v_mp_satispay, v_iva_10, v_coge_ricavi_billy, 1,
            NULL, NULL, NULL, NULL,
            'Pagamenti Satispay ristorazione 09/05',                   'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 12. F&B Sogegross per Matrimonio — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-10', 'USCITA',  1800.00, NULL, NULL, 0,
            '2026-05-10', NULL, '2026-06-09',
            NULL, NULL, v_iva_10, v_coge_sogegross, 2,
            v_forn_sogegross, v_ev_matrimonio, NULL, NULL,
            'F&B Sogegross per Matrimonio Rossi & Bianchi',            'DA_LIQUIDARE', 'MANUALE', 'SOGEGROSS-2026-05-10', v_user_id),

        -- 13. Utenza acqua — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-10', 'USCITA',   180.00, NULL, NULL, 0,
            '2026-05-10', NULL, '2026-06-09',
            NULL, NULL, v_iva_22, v_coge_acqua, 5,
            v_forn_acqua, NULL, NULL, NULL,
            'Utenza acqua bimestre marzo-aprile',                      'DA_LIQUIDARE', 'MANUALE', 'ACQUA-2026-Q2', v_user_id),

        -- 14. Saldo Banchetto Bianchi
        (gen_random_uuid(), '2026-05-11', 'ENTRATA', 2400.00, NULL, NULL, 0,
            '2026-05-11', '2026-05-11', '2026-05-11',
            1, v_mp_bonifico, v_iva_10, v_coge_saldi, 2,
            NULL, v_ev_bianchi, 'SALDO', NULL,
            'Saldo Banchetto 50° Bianchi',                             'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 15. Allestimenti tavoli e fiori per Matrimonio (uscita imputata all'evento)
        (gen_random_uuid(), '2026-05-11', 'USCITA',   600.00, NULL, NULL, 0,
            '2026-05-11', '2026-05-11', '2026-05-11',
            1, v_mp_bonifico, v_iva_22, v_coge_sogegross, 2,
            NULL, v_ev_matrimonio, NULL, NULL,
            'Allestimenti tavoli, mise en place e fiori Matrimonio',   'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 16. Sogegross ingrosso ristorazione (immediato)
        (gen_random_uuid(), '2026-05-12', 'USCITA',  1200.00, NULL, NULL, 0,
            '2026-05-12', '2026-05-12', '2026-05-12',
            1, v_mp_bonifico, v_iva_10, v_coge_sogegross, 1,
            v_forn_sogegross, NULL, NULL, NULL,
            'Acquisto generi alimentari ingrosso Sogegross',           'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 17. Imballaggi spaccio (contanti)
        (gen_random_uuid(), '2026-05-12', 'USCITA',    95.00, NULL, NULL, 0,
            '2026-05-12', '2026-05-12', '2026-05-12',
            3, v_mp_contanti, v_iva_22, v_coge_sogegross, 3,
            NULL, NULL, NULL, NULL,
            'Imballaggi e shopper spaccio',                            'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 18. Carburante attrezzatura verde
        (gen_random_uuid(), '2026-05-13', 'USCITA',    75.00, NULL, NULL, 0,
            '2026-05-13', '2026-05-13', '2026-05-13',
            3, v_mp_contanti, v_iva_22, v_coge_carburante, 4,
            NULL, NULL, NULL, NULL,
            'Carburante tosaerba e decespugliatori',                   'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 19. Bonifico mensile Alveare (netto commissioni)
        (gen_random_uuid(), '2026-05-13', 'ENTRATA',  780.00, NULL, NULL, 0,
            '2026-05-13', '2026-05-13', '2026-05-13',
            5, v_mp_alveare, v_iva_0, v_coge_alveare, 3,
            NULL, NULL, NULL, NULL,
            'Bonifico mensile L''Alveare – incassi aprile (netto)',    'REGISTRATO',   'MANUALE', 'ALVEARE-2026-04', v_user_id),

        -- 20. Acconto Banchetto Verdi
        (gen_random_uuid(), '2026-05-14', 'ENTRATA', 1200.00, NULL, NULL, 0,
            '2026-05-14', '2026-05-14', '2026-05-14',
            1, v_mp_bonifico, v_iva_10, v_coge_caparre, 2,
            NULL, v_ev_verdi, 'ACCONTO', NULL,
            'Acconto Banchetto 80° Sig.ra Verdi',                      'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 21. Fattura formaggi Ciocca — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-15', 'USCITA',   450.00, NULL, NULL, 0,
            '2026-05-15', NULL, '2026-06-14',
            NULL, NULL, v_iva_10, v_coge_ciocca, 1,
            v_forn_ciocca, NULL, NULL, NULL,
            'Fattura formaggi Ciocca – termini 30gg',                  'DA_LIQUIDARE', 'MANUALE', 'CIOCCA-2026-05-15', v_user_id),

        -- 22. Utenza GPL — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-15', 'USCITA',   420.00, NULL, NULL, 0,
            '2026-05-15', NULL, '2026-06-14',
            NULL, NULL, v_iva_22, v_coge_gpl, 5,
            v_forn_gpl, NULL, NULL, NULL,
            'Utenza GPL bimestre marzo-aprile',                        'DA_LIQUIDARE', 'MANUALE', 'GPL-2026-Q2', v_user_id),

        -- 23. Incassi ristorazione Billy POS 16/05
        (gen_random_uuid(), '2026-05-16', 'ENTRATA', 1450.00, NULL, NULL, 0,
            '2026-05-16', '2026-05-16', '2026-05-16',
            1, v_mp_pos_bpm, v_iva_10, v_coge_ricavi_billy, 1,
            NULL, NULL, NULL, NULL,
            'Incasso giornaliero ristorazione (Billy) 16/05',          'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 24. Carni da produttore locale — DA_LIQUIDARE 60gg
        (gen_random_uuid(), '2026-05-19', 'USCITA',  1200.00, NULL, NULL, 0,
            '2026-05-19', NULL, '2026-07-18',
            NULL, NULL, v_iva_10, v_coge_sogegross, 3,
            NULL, NULL, NULL, NULL,
            'Acquisto carni da produttore locale – termini 60gg',      'DA_LIQUIDARE', 'MANUALE', NULL, v_user_id),

        -- 25. Assicurazione Dacia (semestrale)
        (gen_random_uuid(), '2026-05-19', 'USCITA',   380.00, NULL, NULL, 0,
            '2026-05-19', '2026-05-19', '2026-05-19',
            2, v_mp_bonifico, v_iva_0, v_coge_assic_dacia, 5,
            NULL, NULL, NULL, NULL,
            'Premio semestrale assicurazione RC Dacia',                'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 26. Personale extra Banchetto Verdi — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-20', 'USCITA',   480.00, NULL, NULL, 0,
            '2026-05-20', NULL, '2026-06-19',
            NULL, NULL, v_iva_0, v_coge_personale_altri, 2,
            NULL, v_ev_verdi, NULL, NULL,
            'Personale extra (cuoco + 2 camerieri) Banchetto Verdi',   'DA_LIQUIDARE', 'MANUALE', NULL, v_user_id),

        -- 27. Manutenzione condominio (fattura attiva) — DA_LIQUIDARE
        (gen_random_uuid(), '2026-05-21', 'ENTRATA',  850.00, NULL, NULL, 0,
            '2026-05-21', NULL, '2026-06-20',
            NULL, NULL, v_iva_22, v_coge_verde, 4,
            NULL, NULL, NULL, NULL,
            'Fattura manutenzione condominio Vialba – termini 30gg',   'DA_LIQUIDARE', 'MANUALE', 'COND-VIALBA-2026-05', v_user_id),

        -- 28. Acquisto birra Orma
        (gen_random_uuid(), '2026-05-22', 'USCITA',   380.00, NULL, NULL, 0,
            '2026-05-22', '2026-05-22', '2026-05-22',
            1, v_mp_bonifico, v_iva_22, v_coge_orma, 1,
            v_forn_orma, NULL, NULL, NULL,
            'Acquisto birra artigianale Orma',                         'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 29. Saldo Banchetto Verdi
        (gen_random_uuid(), '2026-05-23', 'ENTRATA', 1800.00, NULL, NULL, 0,
            '2026-05-23', '2026-05-23', '2026-05-23',
            1, v_mp_bonifico, v_iva_10, v_coge_saldi, 2,
            NULL, v_ev_verdi, 'SALDO', NULL,
            'Saldo Banchetto 80° Sig.ra Verdi',                        'REGISTRATO',   'MANUALE', NULL, v_user_id),

        -- 30. Commissioni POS Nexi (RID mensile)
        (gen_random_uuid(), '2026-05-25', 'USCITA',    65.00, NULL, NULL, 0,
            '2026-05-25', '2026-05-25', '2026-05-25',
            2, v_mp_rid, v_iva_0, v_coge_nexi, 5,
            v_forn_nexi, NULL, NULL, NULL,
            'Commissioni POS Nexi aprile (addebito mensile)',          'REGISTRATO',   'MANUALE', 'NEXI-2026-04', v_user_id);

    -- ============================================================
    -- TOTALI EVENTI (set manuale; il trigger fn_aggiorna_totali_evento
    -- è stato disattivato in V20 → la logica di ricalcolo vive in Java)
    -- ============================================================
    UPDATE eventi SET caparre_incassate = 1500.00, importo_incassato = 1500.00, costi_diretti_imputati = 2400.00
        WHERE id = v_ev_matrimonio;       -- caparra incassata; F&B + allestimenti già imputati
    UPDATE eventi SET caparre_incassate =  800.00, importo_incassato = 3200.00, costi_diretti_imputati =    0.00
        WHERE id = v_ev_bianchi;          -- caparra + saldo (full)
    UPDATE eventi SET caparre_incassate =  700.00, importo_incassato = 3700.00, costi_diretti_imputati =  480.00
        WHERE id = v_ev_verdi;            -- caparra + acconto + saldo + personale extra costo
    UPDATE eventi SET caparre_incassate =  350.00, importo_incassato =  350.00, costi_diretti_imputati =    0.00
        WHERE id = v_ev_gruppo;           -- annullato, caparra trattenuta come penale
    -- aziendale: tutti i totali restano a 0 (PREVENTIVATO, nessun incasso)

    -- ============================================================
    -- PIANI DI SPESA RICORRENTI (5) + RATE PRE-GENERATE
    -- numero_rate ridotto a 12 (o 8 per trimestrale) per non gonfiare il seed.
    -- I piani sono ATTIVO con prima rata in maggio 2026.
    -- ============================================================
    INSERT INTO recurring_expense_plan (
        id, descrizione, business_unit_id, conto_bancario_id, conto_coge_id,
        importo_rata, giorno_del_mese, frequenza, numero_rate, data_prima_rata,
        stato, created_by, note
    ) VALUES
        (v_plan_mutuo,     'Mutuo ipotecario BPM',                 5, 1, v_coge_mutuo,         1850.00,  1, 'MENSILE',     12, '2026-05-01', 'ATTIVO', v_user_id, 'Esempio piano: rappresenta una porzione del mutuo a 20 anni'),
        (v_plan_regione,   'Finanziamento Regione Lombardia',      5, 1, v_coge_finanziamento,  480.00, 15, 'MENSILE',     12, '2026-05-15', 'ATTIVO', v_user_id, 'Finanziamento agevolato Regione – piano demo 12 mesi'),
        (v_plan_assic,     'Assicurazione fabbricato (trimestre)', 5, 2, v_coge_assic_dacia,    320.00, 10, 'TRIMESTRALE',  8, '2026-05-10', 'ATTIVO', v_user_id, NULL),
        (v_plan_leasing,   'Leasing Merlo telescopico',            5, 1, v_coge_leasing,        720.00, 20, 'MENSILE',     12, '2026-05-20', 'ATTIVO', v_user_id, NULL),
        (v_plan_asconfidi, 'Rata Asconfidi 40k',                   5, 2, v_coge_asconfidi,      950.00,  5, 'MENSILE',     12, '2026-05-05', 'ATTIVO', v_user_id, NULL);

    -- Rate mensili (12 ciascuna)
    INSERT INTO recurring_expense_installment (piano_id, numero_rata, data_scadenza, importo)
    SELECT v_plan_mutuo,     n, ('2026-05-01'::DATE + (n - 1) * INTERVAL '1 month')::DATE, 1850.00 FROM generate_series(1, 12) AS n;

    INSERT INTO recurring_expense_installment (piano_id, numero_rata, data_scadenza, importo)
    SELECT v_plan_regione,   n, ('2026-05-15'::DATE + (n - 1) * INTERVAL '1 month')::DATE,  480.00 FROM generate_series(1, 12) AS n;

    INSERT INTO recurring_expense_installment (piano_id, numero_rata, data_scadenza, importo)
    SELECT v_plan_assic,     n, ('2026-05-10'::DATE + (n - 1) * INTERVAL '3 months')::DATE, 320.00 FROM generate_series(1, 8)  AS n;

    INSERT INTO recurring_expense_installment (piano_id, numero_rata, data_scadenza, importo)
    SELECT v_plan_leasing,   n, ('2026-05-20'::DATE + (n - 1) * INTERVAL '1 month')::DATE,  720.00 FROM generate_series(1, 12) AS n;

    INSERT INTO recurring_expense_installment (piano_id, numero_rata, data_scadenza, importo)
    SELECT v_plan_asconfidi, n, ('2026-05-05'::DATE + (n - 1) * INTERVAL '1 month')::DATE,  950.00 FROM generate_series(1, 12) AS n;

    -- ============================================================
    -- REFRESH viste materializzate (non CONCURRENTLY: dopo cleanup
    -- alcune potrebbero essere vuote, e CONCURRENTLY richiede dati esistenti)
    -- ============================================================
    REFRESH MATERIALIZED VIEW mv_kpi_mensili;
    REFRESH MATERIALIZED VIEW mv_saldi_conti;
    REFRESH MATERIALIZED VIEW mv_conto_economico_mensile;
    REFRESH MATERIALIZED VIEW mv_cash_flow_statement;
    REFRESH MATERIALIZED VIEW mv_redditivita_eventi;
    REFRESH MATERIALIZED VIEW mv_riconciliazione_bancaria;
END $$;
