-- V47 — un conto CoGe per piano, con lo stesso nome: la mappa 20.01.* ↔ piani ricorrenti
--       deve leggersi senza doverla ricostruire.
--
-- Stato misurato sul dump di prod del 2026-09-09 08:30 (copia agosdb_an0909):
--
--   20.01.001 Rata mutuo ipotecario          → Mutuo N.1273 – Banco BPM            1:1 ok
--   20.01.002 Rata finanz. Regione Lombardia → piano «leasing furgone»             NOME SBAGLIATO
--   20.01.003 Rata ISMEA (dal 2027)          → nessun piano
--   20.01.004 Rata Fidicomptur               → DUE piani (32k e 8k)                condiviso, resta
--   20.01.005 Rata Merlo                     → Leasing Merlo                        1:1 ok
--   20.01.006 Rata Asconfidi (40k)           → Finanziamento 40k                    1:1 ok
--   20.01.007 Leasing furgone (COSTO)        → nessun piano; ci sta il canone del 01/07 (515,73).
--                                               PROPOSTO lo spostamento a 40.06.005 «Carburanti,
--                                               pedaggi e veicoli»: un canone di leasing operativo
--                                               è un costo d'esercizio e sta nel ramo dei debiti
--                                               finanziari. Va fatto da /piano-conti, il cui PUT
--                                               propaga il codice su keyword_firma. NON eseguito.
--
-- Qui si tocca SOLO l'anagrafica del piano dei conti. Il piano del furgone è stato rifatto FLAT
-- il 09/09/2026 dagli endpoint dell'app (decisione del committente: è un leasing OPERATIVO, il
-- canone intero è un costo — V38 aveva ragione): rigenerare 54 rate non si fa in una migration.
-- Piano nuovo `ef287a1a`, vecchio `e4461f8a` cancellato. Per questo qui non compare.
--
-- 1. SEQUENCE — igiene, NON un difetto: `piano_dei_conti_coge_id_seq` è a 178 con max(id) = 187.
--    Un `INSERT` che si affidasse a nextval() collide («duplicate key (id)=(180)», verificato sulla
--    copia) ma NESSUNO lo fa: `PianoContiCogeRepository:57-63` inserisce con `MAX(id)+1` in una
--    native query — commento `ponytail:` in loco — e non esiste nessun `persist()` di
--    PianoContiCoge. È così che i conti 179-187 sono nati dalla UI mentre la sequence restava
--    ferma. La riallineo solo perché lo fanno già V21/V35/V41/V42/V43/V44, e perché il prossimo
--    che legge lo stato del DB non deve ri-derivare la conclusione sbagliata che ho fatto io.
--
-- 2. Rinomina: stesso nome a sinistra, «quota capitale» sul 20.01.* e «interessi» sul 60.01.*.
--    Il conto del furgone compare DUE volte, con entrambi i codici possibili: la UPDATE è
--    `WHERE codice = v.codice`, quindi quello dei due che non esiste è un no-op. Così questa
--    migration è corretta sia che lo spostamento a 40.06.005 sia già stato fatto sia che no.
--    Si toccano solo le DESCRIZIONI, mai i codici: keyword_firma e regole_classificazione
--    referenziano il conto per `coge_codice`, un rename di codice le staccherebbe.
--    «Fidicomptur» con la p è la grafia dell'estratto conto («FAVORE FIDICOMPTUR SOC. COOP.»).
--
-- 3. I tre piani senza `riferimento_estratto_conto` lo ricevono. È l'unico segnale che RataMatcher
--    sa leggere su questi addebiti (il nome del piano non compare nella causale). Ogni riferimento
--    è verificato sulle righe reali: compare in una sola riga di `ricorrenti_da_riconciliare`.
--    Il furgone non è in lista: lo riceve alla ricreazione del piano.
--    ⚠️ Non sblocca le 3 righe di agosto in coda: i piani partono a settembre e il matcher propone
--    una rata solo entro ±7 giorni dalla scadenza. Serve dal prossimo import.
--
-- Tutto idempotente e data-driven: in dev e test i piani non esistono e le UPDATE sono no-op.

-- ── 1. sequence ────────────────────────────────────────────────────────────────
SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- ── 2. i nomi dicono a quale piano appartengono ────────────────────────────────
UPDATE piano_dei_conti_coge SET descrizione = v.nuovo
  FROM (VALUES
    ('20.01.001', 'Mutuo N.1273 Banco BPM – quota capitale'),
    ('20.01.002', 'Finanziamento Regione Lombardia – quota capitale'),
    ('20.01.003', 'Finanziamento ISMEA (dal 2027) – quota capitale'),
    ('20.01.004', 'Finanziamenti Fidicomptur 32k e 8k – quota capitale'),
    ('20.01.005', 'Leasing Merlo – quota capitale'),
    ('20.01.006', 'Finanziamento Asconfidi 40k – quota capitale'),
    ('20.01.007', 'Leasing furgone Crédit Agricole – canone (leasing operativo)'),
    ('40.06.005', 'Leasing furgone Crédit Agricole – canone'),
    ('60.01.001', 'Mutuo N.1273 Banco BPM – interessi'),
    ('60.01.002', 'Finanziamento Regione Lombardia – interessi'),
    ('60.01.003', 'Finanziamento ISMEA (dal 2027) – interessi'),
    ('60.01.004', 'Finanziamenti Fidicomptur 32k e 8k – interessi'),
    ('60.01.005', 'Leasing Merlo – interessi'),
    ('60.01.006', 'Finanziamento Asconfidi 40k – interessi')
  ) AS v(codice, nuovo)
 WHERE piano_dei_conti_coge.codice = v.codice
   AND piano_dei_conti_coge.descrizione <> v.nuovo;

-- ── 3. il riferimento in estratto conto, l'unico segnale che riconosce questi piani ──
UPDATE recurring_expense_plan SET riferimento_estratto_conto = v.rif, updated_at = now()
  FROM (VALUES
    ('Finanziamento 32k Fidicomtur',           '981811800294902'),   -- BPM, mandato SDD B2B
    ('Finanziamento 8k Fidicomtur',            '981811800294901'),   -- BPM, mandato SDD B2B
    ('Finanziamento 40k fidicomtur/asconfidi', '030910000075300')    -- CA, RIF. MUTUO N.
  ) AS v(descrizione, rif)
 WHERE recurring_expense_plan.descrizione = v.descrizione
   AND coalesce(recurring_expense_plan.riferimento_estratto_conto, '') = '';

-- Nessun movimento e nessun `tipo` di conto cambia: le viste materializzate restano valide,
-- il refresh non serve (a differenza di V38, che spostava 515,73 dentro il P&L).
