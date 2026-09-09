-- ============================================================================
-- V46 — Gli eventi del file cliente che a gestionale non esistono
--       ("Eventi Agriturismo 2026.xlsx", foglio "Previsione 2026", letto 08/09/2026).
-- ----------------------------------------------------------------------------
-- PERCHE': il confronto riga-per-riga del 08/09 fra il foglio (105 righe) e
-- l'anagrafica (99 eventi) ha trovato 95 abbinamenti, 0 eventi a gestionale senza
-- riga nel foglio e 10 righe del foglio senza evento. Sono tutte posteriori a V26
-- (08/08): il cliente ha continuato a riempire il foglio. Qui entrano quelle 10.
--
-- REGOLE (stesse di V26, che questo file continua):
--  1. VALORE ZERO — nessun movimento, incassato = 0, caparre = 0. Gli acconti che
--     il foglio dichiara (4.500,00 EUR su 5 di questi eventi) NON sono seminati:
--     il ricavo evento entra da una porta sola, EventiService.registraPagamento,
--     dall'attribuzione degli incassi in import. sum(mv_saldi_conti.saldo_calcolato)
--     non cambia per effetto di questa migration.
--  2. CONFERMATO + incassato 0 = pagabile. SALDATO li bloccherebbe
--     (EventiService:319) e il preventivato pieno tiene il residuo capiente,
--     senza il quale l'attribuzione muore con IMPORTO_SUPERA_RESIDUO
--     (EventiService:292-299).
--  3. VOCI Affitto/Menu dalle colonne "Quota Affitto"/"Quota catering" del foglio:
--     `eventi.importo_totale_preventivato` e' ricalcolato dal service come somma
--     delle voci (V18), senza le voci il primo ricalcolo lo porterebbe a zero.
--     Consuntivo NULL: sono tutti eventi futuri, nascere "chiusi" falserebbe il
--     confronto preventivo/consuntivo.
--  4. 12/04/2027 "Complanno marek" e' l'unica riga senza NESSUN importo nel foglio:
--     entra PREVENTIVATO con preventivato NULL — una prenotazione a calendario,
--     come "Cena Coscritti" del 25/09. PREVENTIVATO e' pagabile: il primo incasso
--     lo promuove a CONFERMATO (EventiService:457).
--  5. 17/04/2027 "Matrimonio Munzone" non ha "Ricavo totale" compilato: il totale
--     e' la somma delle sue due quote (800 + 2.200), come per ogni altra riga del
--     foglio dove le tre colonne coesistono.
--
-- Idempotente: l'INSERT salta cio' che esiste gia' su stessa data + stesso nome.
-- ============================================================================

CREATE TEMPORARY TABLE _v46_file (
    data_evento date          NOT NULL,
    nome        text          NOT NULL,
    contatto    text,
    tipo        varchar(50)   NOT NULL,
    totale      numeric(12,2),          -- NULL = riga senza importi (marek 2027)
    affitto     numeric(12,2) NOT NULL,
    menu        numeric(12,2) NOT NULL,
    stato       varchar(20)   NOT NULL,
    nominativo  text                    -- colonna "nominativo" del foglio: e' il
) ON COMMIT DROP;                       -- nome che comparira' sull'estratto conto

INSERT INTO _v46_file (data_evento, nome, contatto, tipo, totale, affitto, menu, stato, nominativo) VALUES
    ('2026-09-08'::date, '18esimo Manuela',                   'Manuela',                          'BANCHETTO_PRIVATO',  900.00,  900.00,    0.00, 'CONFERMATO',   'Manzoni Manuela'),
    ('2026-09-09'::date, 'Laurea (affitto) Gioia Parravicini', 'Gioia Parravicini',               'BANCHETTO_PRIVATO',  700.00,  700.00,    0.00, 'CONFERMATO',   'Parravicini'),
    ('2026-09-10'::date, '18esimo Giada/Introzzi',            'Giada/Introzzi',                   'BANCHETTO_PRIVATO',  700.00,  700.00,    0.00, 'CONFERMATO',   'Introzzi Simona'),
    ('2026-09-11'::date, '18esimo Gorla/Bertuzzi',            'Gorla/Bertuzzi',                   'BANCHETTO_PRIVATO', 2550.00,  600.00, 1950.00, 'CONFERMATO',   'Bertuzzi Angela'),
    ('2026-10-03'::date, 'Cena Mamma marek',                  'Mamma marek',                      'BANCHETTO_PRIVATO', 1200.00,    0.00, 1200.00, 'CONFERMATO',   NULL),
    ('2026-10-11'::date, 'Affitto pranzo Kristine',           'Kristine',                         'BANCHETTO_PRIVATO', 1000.00, 1000.00,    0.00, 'CONFERMATO',   NULL),
    ('2026-10-31'::date, '18esimo Camilla Riente',            'Camilla Riente',                   'BANCHETTO_PRIVATO', 1600.00,  900.00,  700.00, 'CONFERMATO',   NULL),
    ('2026-12-13'::date, 'Reparto dialisi (Silvia negretti)', 'Reparto dialisi (Silvia negretti)','BANCHETTO_PRIVATO', 1680.00,    0.00, 1680.00, 'CONFERMATO',   NULL),
    ('2027-04-12'::date, 'Complanno marek',                   'marek',                            'BANCHETTO_PRIVATO',    NULL,    0.00,    0.00, 'PREVENTIVATO', NULL),
    ('2027-04-17'::date, 'Matrimonio Munzone',                'Munzone',                          'MATRIMONIO',        3000.00,  800.00, 2200.00, 'CONFERMATO',   'Munzone');

-- Fail fast: il totale del foglio deve essere la somma delle sue quote. Se una
-- riga non quadra, il primo ricalcolo del service (V18) cambierebbe il preventivato
-- da solo e l'allineamento al foglio non sopravviverebbe alla prima modifica.
DO $$
DECLARE k int;
BEGIN
    SELECT count(*) INTO k FROM _v46_file
    WHERE totale IS NOT NULL AND totale IS DISTINCT FROM (affitto + menu);
    IF k > 0 THEN
        RAISE EXCEPTION 'V46: % righe con Ricavo totale != Quota Affitto + Quota catering', k;
    END IF;
END $$;

-- ── 1. Eventi mancanti: INSERT (nessun movimento) ───────────────────────────
CREATE TEMPORARY TABLE _v46_nuovi ON COMMIT DROP AS
WITH ins AS (
    INSERT INTO eventi (
        nome, tipo, data_evento, importo_totale_preventivato, importo_incassato,
        caparre_incassate, costi_diretti_imputati, stato, business_unit_id,
        contatto_nome, numero_totale_partecipanti, note, is_segnaposto, created_by)
    SELECT f.nome, f.tipo, f.data_evento, f.totale, 0, 0, 0, f.stato, 2,
           f.contatto, 0,
           'Anagrafica eventi 2026 (file cliente 08/09/2026)'
             || CASE WHEN f.nominativo IS NOT NULL
                     THEN E'\nNominativo sul foglio: ' || f.nominativo ELSE '' END,
           false, '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
    FROM _v46_file f
    WHERE NOT EXISTS (SELECT 1 FROM eventi e
                      WHERE e.data_evento = f.data_evento AND e.nome = f.nome)
    RETURNING id, nome, data_evento
)
SELECT i.id, f.data_evento, f.nome, f.affitto, f.menu
FROM ins i JOIN _v46_file f ON f.data_evento = i.data_evento AND f.nome = i.nome;

-- ── 2. Voci di preventivo (Affitto / Menu dal foglio) ───────────────────────
INSERT INTO evento_voce (evento_id, catalogo_id, label, importo_preventivo, importo_consuntivo, origine, created_by)
SELECT n.id, c.id, c.label, v.importo, NULL, 'MANUALE',
       '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
FROM _v46_nuovi n
CROSS JOIN LATERAL (VALUES ('Affitto', n.affitto), ('Menu', n.menu)) AS v(label, importo)
JOIN evento_voce_catalogo c ON c.label = v.label
WHERE v.importo > 0;

-- Fail fast di chiusura: preventivato == somma voci, e nessun evento nuovo nasce
-- con dei soldi addosso.
DO $$
DECLARE k int; j int;
BEGIN
    SELECT count(*) INTO k
    FROM eventi e JOIN _v46_nuovi n ON n.id = e.id
    WHERE coalesce(e.importo_totale_preventivato, 0) IS DISTINCT FROM
          (SELECT coalesce(sum(importo_preventivo), 0) FROM evento_voce WHERE evento_id = e.id);
    IF k > 0 THEN
        RAISE EXCEPTION 'V46: % eventi con preventivato != somma voci', k;
    END IF;

    SELECT count(*) INTO j
    FROM eventi e JOIN _v46_nuovi n ON n.id = e.id
    WHERE e.importo_incassato <> 0 OR e.caparre_incassate <> 0
       OR EXISTS (SELECT 1 FROM movimenti m WHERE m.evento_id = e.id);
    IF j > 0 THEN
        RAISE EXCEPTION 'V46: % eventi nuovi non sono a valore zero', j;
    END IF;
END $$;

-- Nessun movimento creato: le MV monetarie non cambiano. Il refresh serve solo a
-- mv_redditivita_eventi, che elenca anche gli eventi senza incassi.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_matviews WHERE matviewname = 'mv_redditivita_eventi') THEN
        REFRESH MATERIALIZED VIEW mv_redditivita_eventi;
    END IF;
END $$;
