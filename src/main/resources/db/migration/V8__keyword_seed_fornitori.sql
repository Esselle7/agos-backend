-- ============================================================
-- V8 — Keyword curate dai fornitori reali + arricchimento stopword
-- ============================================================
-- Incrocio del vocabolario costi storico (Excel pre-gestionale) con il piano dei conti:
-- i nomi combaciano 1:1 col piano conti (40.04.* materie prime ristorazione, 40.12.* spaccio,
-- 40.06/07/09/11 ecc.), quindi seminiamo keyword IDENTITA/DOMINIO che fanno ricadere i
-- fornitori/concetti ricorrenti sul COGE+BU corretti, riducendo i transitori al prossimo import.
--
-- NOTE:
--  * COGE per CODICE; signature_hash = sha256(hex) dei token UPPERCASE ordinati e uniti da '|'
--    (stesso algoritmo del KeywordExtractor) → idempotente con ON CONFLICT.
--  * Scope tipo_movimento='USCITA' (sono tutti costi) → niente falsi positivi sugli incassi.
--  * fornitore_id agganciato dove il fornitore esiste in anagrafica, altrimenti NULL.
--  * ESCLUSI volutamente i FINANZIAMENTI / RiBa / cambiali (mutuo, asconfidi, fidicomptur,
--    merlo, ismea, effetti/riba): da decidere come categorizzarli (overhead?) → fase separata.
--  * Alcuni COGE sono best-effort e vanno verificati in UI (vedi commenti "FLAG").

-- ── Arricchimento stopword (rumore bancario emerso dall'analisi dei transitori) ──────────
INSERT INTO keyword_stopword (token, categoria) VALUES
    ('CARTA','RUMORE_BANCARIO'),('CARTE','RUMORE_BANCARIO'),('DEBIT','RUMORE_BANCARIO'),
    ('DEBITO','RUMORE_BANCARIO'),('ORDINE','RUMORE_BANCARIO'),('CONTO','RUMORE_BANCARIO'),
    ('RIFERIMENTO','RUMORE_BANCARIO'),('AGOS','RUMORE_BANCARIO'),('NROSUPCBI','RUMORE_BANCARIO'),
    ('MBVT','RUMORE_BANCARIO'),('TRAMITE','RUMORE_BANCARIO'),('CONTAB','RUMORE_BANCARIO'),
    ('CONTABILE','RUMORE_BANCARIO'),('IDENTIFICATIVO','RUMORE_BANCARIO'),('DISPONIBILE','RUMORE_BANCARIO')
ON CONFLICT (token) DO NOTHING;

-- ── Keyword curate (IDENTITA fornitori / DOMINIO concetti) ───────────────────────────────
WITH seed(tokens, natura, coge, bu, forn) AS (
    VALUES
    -- Materie prime ristorazione (BU1) — fornitori del piano conti 40.04.*
    (ARRAY['PASTICCERIA'],            'IDENTITA','40.04.001', 1::smallint, 'PASTICCERIA'),
    (ARRAY['PASINI'],                 'IDENTITA','40.04.002', 1, 'PASINI'),
    (ARRAY['ORMA'],                   'IDENTITA','40.04.003', 1, 'ORMA'),
    (ARRAY['GRUPPO','ITALIANO','VINI'],'IDENTITA','40.04.004', 1, 'ITALIANO VINI'),
    (ARRAY['NICELLINI'],              'IDENTITA','40.04.005', 1, 'NICELLINI'),
    (ARRAY['ZEUS'],                   'IDENTITA','40.04.006', 1, 'ZEUS'),
    (ARRAY['CIOCCA'],                 'IDENTITA','40.04.007', 1, 'CIOCCA'),
    (ARRAY['SOGEGROSS'],              'IDENTITA','40.04.008', 1, 'SOGEGROSS'),
    (ARRAY['VAL','MULINI'],           'IDENTITA','40.04.009', 1, 'VAL MULINI'),
    (ARRAY['OLIO'],                   'DOMINIO', '40.04.010', 1, NULL),
    -- Materie prime spaccio (BU3)
    (ARRAY['NOSTRAN','CARNI'],        'IDENTITA','40.12.002', 3, NULL),            -- fornitore principale carni
    (ARRAY['FATTORIA','GINESTRA'],    'IDENTITA','40.12.003', 3, 'GINESTRA'),
    -- Contabilità / consulenze (BU5)
    (ARRAY['COLDIRETTI'],             'IDENTITA','40.07.001', 5, NULL),
    (ARRAY['TORRES'],                 'IDENTITA','40.07.002', 5, NULL),
    -- Carburanti / veicoli (BU5)
    (ARRAY['TELEPASS'],               'IDENTITA','40.06.001', 5, 'TELEPASS'),
    (ARRAY['BENZINA'],                'DOMINIO', '40.06.002', 5, NULL),
    -- Utenze (BU5)
    (ARRAY['GPL'],                    'DOMINIO', '40.03.002', 5, NULL),
    -- Manutenzioni / pulizie (BU5)
    (ARRAY['COMEDIL'],                'IDENTITA','40.09.003', 5, 'COMEDIL'),
    (ARRAY['ZEP'],                    'IDENTITA','40.09.004', 5, 'ZEP'),
    (ARRAY['CLEANING'],               'IDENTITA','40.09.004', 5, NULL),            -- New Cleaning
    -- Altri costi operativi (BU5)
    (ARRAY['SONVICO'],                'IDENTITA','40.11.001', 5, NULL),
    -- Controparti emerse dall'analisi transitori (COGE best-effort → FLAG: verifica in UI)
    (ARRAY['EUROSISTEM'],             'IDENTITA','40.09.002', 5, 'EUROSISTEM'),    -- FLAG rifacimento guaina (manutenzione/investimento)
    (ARRAY['MALLAMACE'],              'IDENTITA','40.07.002', 5, 'MALLAMACE'),     -- FLAG geometra/consulente
    (ARRAY['ALTA','VALLE'],           'IDENTITA','40.11.001', 5, NULL),           -- FLAG merchant carta, categoria da confermare
    (ARRAY['SMART','WASH'],           'IDENTITA','40.11.001', 5, NULL),           -- FLAG autolavaggio
    (ARRAY['CONFIDI'],                'IDENTITA','40.02.002', 5, NULL),           -- FLAG fee confidi (overhead) — rivedere con i finanziamenti
    -- Manodopera (BU5, USCITA) — FLAG: nomi propri, verifica per evitare omonimie
    (ARRAY['CARLO'],                  'IDENTITA','40.01.001', 5, NULL),
    (ARRAY['ALINA'],                  'IDENTITA','40.01.003', 5, NULL),
    (ARRAY['NOEMI'],                  'IDENTITA','40.01.004', 5, NULL)
),
ins AS (
    INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                               fornitore_id, origine, stato, signature_hash, note)
    SELECT s.natura, 'BOOK', 'USCITA', '*', s.bu, s.coge,
           CASE WHEN s.forn IS NULL THEN NULL
                ELSE (SELECT id FROM fornitori WHERE ragione_sociale ILIKE '%'||s.forn||'%'
                      ORDER BY ragione_sociale LIMIT 1) END,
           'SEED', 'ATTIVA',
           encode(digest((SELECT string_agg(u, '|' ORDER BY u) FROM unnest(s.tokens) AS u), 'sha256'), 'hex'),
           'Seed V8 (Excel costi → piano conti)'
    FROM seed s
    ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING
    RETURNING id, signature_hash, tipo_movimento
)
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT i.id, u, CASE WHEN s.natura = 'IDENTITA' THEN 'IDENTITA' ELSE 'DOMINIO' END
FROM seed s
JOIN ins i ON i.signature_hash = encode(digest((SELECT string_agg(u2, '|' ORDER BY u2) FROM unnest(s.tokens) AS u2), 'sha256'), 'hex')
          AND i.tipo_movimento = 'USCITA'
CROSS JOIN LATERAL unnest(s.tokens) AS u
ON CONFLICT (firma_id, token) DO NOTHING;
