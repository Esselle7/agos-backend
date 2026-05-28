-- ============================================================
-- V7 - SEED DATI DI TEST (solo ambiente dev)
-- NON eseguire in produzione.
-- Controllato dal profilo Quarkus: quarkus.flyway.locations
-- In produzione NON includere db/migration/dev/ nel path.
-- ============================================================

-- ============================================================
-- UTENTE ADMIN DI TEST
-- Sostituire google_sub con il valore reale dal Google OAuth
-- ============================================================

INSERT INTO users (email, google_sub, full_name, role)
VALUES ('simone.leone300900@gmail.com', 'google-sub-placeholder-admin', 'Simone Leone', 'ADMIN')
ON CONFLICT (email) DO UPDATE SET
    role      = EXCLUDED.role,
    is_active = true;

-- ============================================================
-- EVENTO DI ESEMPIO – Matrimonio
-- ============================================================

INSERT INTO eventi (nome, tipo, data_evento, data_preventivo, importo_totale_preventivato,
                    stato, business_unit_id, contatto_nome, contatto_telefono, n_ospiti, note)
VALUES (
    'Matrimonio Rossi – Bianchi',
    'MATRIMONIO',
    '2025-06-14',
    '2025-01-10',
    12500.00,
    'CONFERMATO',
    2,
    'Marco Rossi',
    '+39 333 1234567',
    80,
    'Menu degustazione 5 portate. Cerimonia in giardino.'
);

INSERT INTO eventi (nome, tipo, data_evento, data_preventivo, importo_totale_preventivato,
                    stato, business_unit_id, contatto_nome, n_ospiti)
VALUES (
    'Team Building Azienda XYZ',
    'AZIENDALE',
    '2025-09-20',
    '2025-03-15',
    3200.00,
    'PREVENTIVO',
    2,
    'Laura Verdi',
    35
);

-- ============================================================
-- SALDI BANCA INIZIALI (per testare riconciliazione)
-- ============================================================

INSERT INTO saldi_banca (conto_id, data_riferimento, saldo) VALUES
    (1, '2025-01-01',  8500.00),
    (2, '2025-01-01', 12300.00),
    (3, '2025-01-01',   450.00),
    (4, '2025-01-01',   120.00),
    (5, '2025-01-01',   280.00);

-- ============================================================
-- MOVIMENTO DI ESEMPIO – incasso POS ristorazione
-- (requires an existing user – the admin created above)
-- ============================================================

DO $$
DECLARE
    v_user_id          UUID;
    v_coge_rist        INT;
    v_metodo_pos_ca    INT;
    v_aliquota_10      INT;
    v_cdc_bu1          INT;
BEGIN
    SELECT id INTO v_user_id       FROM users                    WHERE email   = 'simone.leone300900@gmail.com';
    SELECT id INTO v_coge_rist     FROM piano_dei_conti_coge      WHERE codice  = '30.01.001';
    SELECT id INTO v_metodo_pos_ca FROM metodi_pagamento          WHERE codice  = 'POS_CA_NEXI';
    SELECT id INTO v_aliquota_10   FROM aliquote_iva              WHERE aliquota = 10.0;
    SELECT id INTO v_cdc_bu1       FROM centri_di_costo_coan      WHERE codice  = 'CDC-BU1';

    INSERT INTO movimenti (
        data_movimento, tipo, importo_lordo, importo_imponibile, importo_iva,
        importo_commissione, data_competenza, data_liquidita,
        conto_bancario_id, metodo_pagamento_id, aliquota_iva_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        descrizione, stato, fonte, riferimento_esterno, created_by
    ) VALUES (
        '2025-04-10',
        'ENTRATA',
        330.00, 300.00, 30.00,
        2.50,
        '2025-04-10', '2025-04-12',
        2, v_metodo_pos_ca, v_aliquota_10,
        v_coge_rist, v_cdc_bu1, 1,
        'Incasso pranzo domenicale – 12 coperti',
        'REGISTRATO',
        'MANUALE',
        'TEST-2025-04-10-001',
        v_user_id
    );

    -- Movimento caparra evento
    INSERT INTO movimenti (
        data_movimento, tipo, importo_lordo,
        data_competenza, data_liquidita,
        conto_bancario_id, metodo_pagamento_id,
        conto_coge_id, centro_di_costo_id, business_unit_id,
        evento_id, tipo_evento_movimento,
        descrizione, stato, fonte, riferimento_esterno, created_by
    )
    SELECT
        '2025-04-15',
        'ENTRATA', 1500.00,
        '2025-06-14', '2025-04-15',
        1, (SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'),
        (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02.001'),
        (SELECT id FROM centri_di_costo_coan WHERE codice = 'CDC-BU2'), 2,
        e.id, 'CAPARRA',
        'Caparra matrimonio Rossi – Bianchi',
        'REGISTRATO', 'MANUALE', 'TEST-CAPARRA-2025-04-15', v_user_id
    FROM eventi e WHERE e.nome = 'Matrimonio Rossi – Bianchi';

END $$;
