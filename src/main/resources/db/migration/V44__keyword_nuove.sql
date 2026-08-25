-- Bonifica del dizionario keyword, parte 3 di 3: le KEYWORD NUOVE.
-- Base di fatti: docs/analisi/keyword-audit-2026-08-24.md §10.3.
--
-- Le prime due migration hanno tolto rumore e chiuso conflitti, ma NON alzano da sole la
-- copertura di molto: la potatura serve a rendere leggibile il dizionario, non ad aggiungere
-- capacita'. La capacita' la aggiungono queste righe. Misurato sul corpus reale:
--     dopo V42+V43   250/316 catalogate (79,1 %)
--     dopo V44       284/316 catalogate (89,9 %)   -> 34 righe in meno a mano, a periodo
--
-- METODO (e i suoi vincoli). Ogni firma raggruppa sull'ENTITA'/esercente estratto, mai su
-- parole della causale: accorpare per causale applicherebbe una voce sola a fornitori e
-- giornate diverse. Ogni token e' stato verificato NON presente in keyword_stopword. Dove un
-- token solo sarebbe ambiguo si usano piu' token in AND (IMAT+FELCO, CIP+GARDEN, BERGNA+FERRO,
-- BONETTI+ANGELO): «FELCO» da solo e' anche un marchio di forbici da potatura, «CIP» e «FERRO»
-- e «ANGELO» sono troppo comuni per stare in piedi da soli.
--
-- FALSI POSITIVI: ogni token-set e' stato passato su tutte e 1.134 le righe del corpus, non
-- solo su quelle attese. Misurati: 0. Il replay conferma che ognuna aggancia ESATTAMENTE le
-- righe dichiarate, e che nessuna riga oggi catalogata cambia esito.
--
-- SEI PROPOSTE DELL'AUDIT NON SONO QUI, e il motivo va scritto:
--   * REBECCA+MEANI e GUESS — MISURATO su agosdb_kwaudit: l'evento «Compleanno Rebecca» (700,00)
--     e l'evento «Guess» (3.501,04) hanno GIA' un movimento a libro su 30.02.002. Il criterio
--     dell'audit §8.3 era «se ne ha 0 l'incassato e' storico; se ne ha, e' un doppio conteggio
--     da non creare». Ne hanno. Quelle righe vanno trattate dal modulo Eventi, non da una firma.
--   * KAIROS — copre 3 righe ma e' una parola della CAUSALE (nome di un progetto), con tre
--     paganti diversi: viola la regola di dominio. Deciso dal titolare il 24/08.
--   * MEDIAWORLD — le 2 spese (218,00 e 528,98) possono essere di natura diversa fra loro
--     (elettrodomestico da cucina = capex, materiale di consumo = costo): una keyword unica ne
--     sbaglierebbe sempre una. Deciso dal titolare.
--   * MESAK — 2.732,80 € senza causale, fornitore non riconosciuto dal titolare.
--   * CAVADINI+RIVOLTA — il rimborso «fatture riparazione muro» (490,00 €) e' una ENTRATA, e
--     l'unico conto sensato disponibile (40.09.002 Manutenzione) e' di tipo COSTO non-capex.
--     MISURATO su mv_conto_economico_mensile: un'ENTRATA su conto COSTO entra fra i costi solo se
--     l'importo e' NEGATIVO, quindi quella riga sparirebbe da ricavi E da costi — oggi, sul mastro
--     91 (capex), vale almeno -490 fra gli investimenti. Nel piano dei conti non esiste un conto
--     «rimborsi / recuperi spese» e non se ne inventa uno per una riga: resta a smistamento
--     manuale. Segnalato dal verifier a contesto fresco.
--   * IRMA+VECCHIO — CORREZIONE ALL'AUDIT: §10.3 la propone ENTRATA su «30.03.001 Vendita
--     carni», ma l'unica riga del corpus e' una USCITA di 144,14 €. Come ENTRATA sarebbe inerte,
--     come USCITA metterebbe un pagamento su un conto di RICAVO. Non si inserisce.
--
-- Nota su GUARISCO: l'esempio citato in audit §10.3 (i 20.000 € di «DANIELA GUARISCORIF») e'
-- una USCITA verso una persona fisica; la riga che questa firma aggancia davvero e' la fattura
-- 31/001 da 2.100,20 € in ENTRATA. Lo scope ENTRATA non e' un dettaglio: senza, la firma
-- imputerebbe 20.000 € di uscita a un conto di ricavo.

-- ═══ Il conto nuovo per Latticini Cernobbio ═══════════════════════════════════════════════
-- 1 bonifico IN ENTRATA di 1.830,11 € che salda la fattura 41 del 31/12/2025. Oggi e'
-- catalogato su «30.06.001 Giardino Briccola Giovanni» dalla firma FATT, spenta in V43: e' il
-- falso positivo misurato di §4.4 — Latticini Cernobbio non e' il giardino di Briccola.
-- Conto intestato deciso dal titolare, come per Baitieri. BU 3 per coerenza con gli altri
-- clienti B2B di 30.04.
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '30.04.004', 'Latticini Cernobbio s.r.l.', 'RICAVO', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.04'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '30.04.004');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- Fatti di Seta di Gagiardo Mara: 1.190,00 € in entrata («SALDO VS. FATTURA N. 7»).
-- L'audit §10.3 proponeva 30.04.001, che pero' si chiama «Agricola Guarisco»: e' il conto di un
-- ALTRO cliente, cioe' esattamente il difetto che V42 corregge su ALTA+VALLE (catalogata su
-- «Sonvico – forniture varie», conto di un altro fornitore). Conto intestato, come per Baitieri
-- e Latticini Cernobbio. Segnalato dal verifier a contesto fresco, deciso dal titolare il 24/08.
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '30.04.005', 'Fatti di Seta di Gagiardo Mara', 'RICAVO', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.04'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '30.04.005');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- ═══ Le 25 firme ══════════════════════════════════════════════════════════════════════════
-- signature_hash = sha256 hex dei token UPPERCASE ORDINATI uniti da '|' (KeywordExtractor.
-- signatureHash), validato sull'oracolo del seed V7. ON CONFLICT DO NOTHING = idempotenza.

-- FELCO+IMAT  ->  50.01.001 / bu5   (FELCO da solo e' anche un marchio di forbici: AND obbligatorio)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.01.001', 1.00, 'SEED', 'ATTIVA', 'f42c3fb52abb5405636fb093f9b81f747f8a554fe59b03f0aeadbe8e860efec6')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('FELCO'), ('IMAT')) AS t(token)
 WHERE f.signature_hash = 'f42c3fb52abb5405636fb093f9b81f747f8a554fe59b03f0aeadbe8e860efec6' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- COMEDILMANGINO  ->  50.02.004 / bu5   (COGE verificato a DB: fornitori.Comedil Mangino s.r.l.)
-- NOTA: questa firma ESISTE GIA' dopo V42. La correzione del token B11 su
-- COMAS+COMEDILMANGINO+OLGIAT produce esattamente questo token-set, con lo stesso conto:
-- l'audit descrive la stessa firma in due punti (§9.4 come token da correggere, §10.3 come
-- proposta nuova) senza accorgersene. L'INSERT qui sotto e' quindi un no-op protetto da
-- ON CONFLICT, e resta per rendere la migration leggibile e indipendente dall'ordine.
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.02.004', 1.00, 'SEED', 'ATTIVA', 'c191b365517f6d1616ac141b0d9822a4c13aceeb004e3737625a1c29e7db3dee')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('COMEDILMANGINO')) AS t(token)
 WHERE f.signature_hash = 'c191b365517f6d1616ac141b0d9822a4c13aceeb004e3737625a1c29e7db3dee' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- FLORTECNICA  ->  40.14.001 / bu5   (token univoco)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.14.001', 1.00, 'SEED', 'ATTIVA', '0962506a5a6dfb1a3fee1d9c99ab2b1d7403f8fe5d3488bea2d361224d6c92aa')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('FLORTECNICA')) AS t(token)
 WHERE f.signature_hash = '0962506a5a6dfb1a3fee1d9c99ab2b1d7403f8fe5d3488bea2d361224d6c92aa' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- CIFA  ->  50.02.004 / bu5   (4 lettere: sorvegliare)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.02.004', 1.00, 'SEED', 'ATTIVA', '970eaa0b53a14c90c304c528d58c64c3831e482603b4d443776776000c9f1be9')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('CIFA')) AS t(token)
 WHERE f.signature_hash = '970eaa0b53a14c90c304c528d58c64c3831e482603b4d443776776000c9f1be9' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- FOC  ->  40.09.001 / bu5   (conto dedicato gia' esistente; richiede il fix sigle puntate)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.09.001', 1.00, 'SEED', 'ATTIVA', '6464e4e65c1c4a49d02ebf4eb3d4e63cf89154ab796c55f645f503c761507906')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('FOC')) AS t(token)
 WHERE f.signature_hash = '6464e4e65c1c4a49d02ebf4eb3d4e63cf89154ab796c55f645f503c761507906' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- CIP+GARDEN  ->  40.14.001 / bu5   (CIP da solo e' troppo corto: AND obbligatorio)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.14.001', 1.00, 'SEED', 'ATTIVA', 'b311a07345c2668b729a69dde2d2eb6d960c52e85ebeee005d452f6f1453e50a')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('CIP'), ('GARDEN')) AS t(token)
 WHERE f.signature_hash = 'b311a07345c2668b729a69dde2d2eb6d960c52e85ebeee005d452f6f1453e50a' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- OBI  ->  50.02.004 / bu5   (3 caratteri, il minimo del tokenizer: la piu' rischiosa)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.02.004', 1.00, 'SEED', 'ATTIVA', '103212fe23de4853abac26b2c2d1b9980025caba76af3a1bef81c1763b197a27')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('OBI')) AS t(token)
 WHERE f.signature_hash = '103212fe23de4853abac26b2c2d1b9980025caba76af3a1bef81c1763b197a27' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- GABAGLIO  ->  50.01.001 / bu1   (copre entrambe le grafie del fornitore)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 1, '50.01.001', 1.00, 'SEED', 'ATTIVA', '4ca9a5cacd7c6db09be146449b58557b71f5fea31e0762c49dfd3a2587518f11')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('GABAGLIO')) AS t(token)
 WHERE f.signature_hash = '4ca9a5cacd7c6db09be146449b58557b71f5fea31e0762c49dfd3a2587518f11' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- STRADI  ->  40.05.001 / bu5   (Stradi Assicurazioni)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.05.001', 1.00, 'SEED', 'ATTIVA', 'a79c0d19aa36d3c730d4f9436ba6b8178e4b5e985fded4fd9cce55cab66caf33')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('STRADI')) AS t(token)
 WHERE f.signature_hash = 'a79c0d19aa36d3c730d4f9436ba6b8178e4b5e985fded4fd9cce55cab66caf33' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;


-- GAGIARDO+SETA  ->  30.04.005 / bu3   (Fatti di Seta di Gagiardo Mara)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'ENTRATA', '*', 3, '30.04.005', 1.00, 'SEED', 'ATTIVA', '9bab2b28544e9dfbb4c324e66b8315f71a8c740983a887b1ae75484aa649fb1f')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('GAGIARDO'), ('SETA')) AS t(token)
 WHERE f.signature_hash = '9bab2b28544e9dfbb4c324e66b8315f71a8c740983a887b1ae75484aa649fb1f' AND f.tipo_movimento = 'ENTRATA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- GUARISCO  ->  30.04.001 / bu3   (richiede il fix RIF.: oggi il token e' GUARISCORIF)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'ENTRATA', '*', 3, '30.04.001', 1.00, 'SEED', 'ATTIVA', '9ce264c8d8a78a17cfee8f4438b2a5389cb4161782fbf0a631ca9ea3f9e5345c')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('GUARISCO')) AS t(token)
 WHERE f.signature_hash = '9ce264c8d8a78a17cfee8f4438b2a5389cb4161782fbf0a631ca9ea3f9e5345c' AND f.tipo_movimento = 'ENTRATA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- F24CBI  ->  20.02.003 / bu5   (copre la variante CA che AGENZIA+ENTRATE non aggancia)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '20.02.003', 1.00, 'SEED', 'ATTIVA', 'ffaaddf5723e7a4bbdf81d09ff7724e8c0d5c05627fad30ebe5664be46769564')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('F24CBI')) AS t(token)
 WHERE f.signature_hash = 'ffaaddf5723e7a4bbdf81d09ff7724e8c0d5c05627fad30ebe5664be46769564' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- AIANI  ->  50.01.005 / bu5   (COGE verificato a DB: fornitori.Aiani Impianti)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.01.005', 1.00, 'SEED', 'ATTIVA', '32672bb0929b24a0c5a14a637f39b2a4aa5a1ee626dcf3c81edef6527c5d74ae')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('AIANI')) AS t(token)
 WHERE f.signature_hash = '32672bb0929b24a0c5a14a637f39b2a4aa5a1ee626dcf3c81edef6527c5d74ae' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- CERNOBBIO+LATTICINI  ->  30.04.004 / bu3   (la riga che FATT sbagliava; conto deciso dal titolare)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'ENTRATA', '*', 3, '30.04.004', 1.00, 'SEED', 'ATTIVA', 'b56f017043c0ad327681375d0eb6681ac176e9130037196a6c2e06450528141c')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('CERNOBBIO'), ('LATTICINI')) AS t(token)
 WHERE f.signature_hash = 'b56f017043c0ad327681375d0eb6681ac176e9130037196a6c2e06450528141c' AND f.tipo_movimento = 'ENTRATA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- BERGNA+FERRO  ->  50.02.004 / bu5   (FERRO e' parola comune: solo in AND)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.02.004', 1.00, 'SEED', 'ATTIVA', '62730b78b0d8942ad5a81a0c6a02ce9b6fdfda1aabe15640c456d754d77008b1')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('BERGNA'), ('FERRO')) AS t(token)
 WHERE f.signature_hash = '62730b78b0d8942ad5a81a0c6a02ce9b6fdfda1aabe15640c456d754d77008b1' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- ERBOSO+TAPPETO  ->  40.14.001 / bu5   (Il Tappeto Erboso societa' agricola)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.14.001', 1.00, 'SEED', 'ATTIVA', '347942673a24dd21642bdfd562ff767ecf5d42e5413d26234c45bde9ed48e11a')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('ERBOSO'), ('TAPPETO')) AS t(token)
 WHERE f.signature_hash = '347942673a24dd21642bdfd562ff767ecf5d42e5413d26234c45bde9ed48e11a' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- ANGELO+BONETTI  ->  50.01.008 / bu5   (ANGELO e' nome comune: solo in AND)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '50.01.008', 1.00, 'SEED', 'ATTIVA', '281ddde9886b211ab2e2efa22638fa9bcec2b435778b2611a211719aa316775c')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('ANGELO'), ('BONETTI')) AS t(token)
 WHERE f.signature_hash = '281ddde9886b211ab2e2efa22638fa9bcec2b435778b2611a211719aa316775c' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- DATEK22  ->  40.09.002 / bu5   (token univoco)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.09.002', 1.00, 'SEED', 'ATTIVA', 'b613dcfead5bdc028b571f95963c9c60a9ed44445307be80f5a612556f31fc9a')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('DATEK22')) AS t(token)
 WHERE f.signature_hash = 'b613dcfead5bdc028b571f95963c9c60a9ed44445307be80f5a612556f31fc9a' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- SERRITELLI  ->  40.12.005 / bu3   (prodotti del sud, coerente col conto)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 3, '40.12.005', 1.00, 'SEED', 'ATTIVA', 'a6b450e3b844eb9e7383e454e002b961757555bb3d8423744fca1b9db1d00493')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('SERRITELLI')) AS t(token)
 WHERE f.signature_hash = 'a6b450e3b844eb9e7383e454e002b961757555bb3d8423744fca1b9db1d00493' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- INSUBRIA  ->  40.11.005 / bu5   (ATS Insubria)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.11.005', 1.00, 'SEED', 'ATTIVA', '4144c8492192f0404531fe93e850039166a9479491061bb4847938085c75e9a4')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('INSUBRIA')) AS t(token)
 WHERE f.signature_hash = '4144c8492192f0404531fe93e850039166a9479491061bb4847938085c75e9a4' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- AUTORI  ->  40.11.005 / bu5   (SIAE: nella riga il nome e' per esteso e troncato)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.11.005', 1.00, 'SEED', 'ATTIVA', '3a39ea4030bf7467b5b640df179c0fe19af8d469182ffb25ddbe4a677eec3c9e')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('AUTORI')) AS t(token)
 WHERE f.signature_hash = '3a39ea4030bf7467b5b640df179c0fe19af8d469182ffb25ddbe4a677eec3c9e' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- ISOLA  ->  40.11.005 / bu5   (parola comune: 0 falsi positivi nel corpus, sorvegliare)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.11.005', 1.00, 'SEED', 'ATTIVA', '729601f5d3a01176fd8d0145beb3c1d38c202423628ad3f1830e5e4941c84b48')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('ISOLA')) AS t(token)
 WHERE f.signature_hash = '729601f5d3a01176fd8d0145beb3c1d38c202423628ad3f1830e5e4941c84b48' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- BIFFI  ->  40.09.002 / bu5   (Alessandro Biffi)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.09.002', 1.00, 'SEED', 'ATTIVA', '53420decb9d24538cb130b25f3e1062c470b408b82508deb9ae3b0840c548f65')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('BIFFI')) AS t(token)
 WHERE f.signature_hash = '53420decb9d24538cb130b25f3e1062c470b408b82508deb9ae3b0840c548f65' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;

-- LARIO  ->  40.11.001 / bu5   (MF Lario srl; MF e' sotto i 3 caratteri)
INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                           confidence, origine, stato, signature_hash)
VALUES ('IDENTITA', 'BOOK', 'USCITA', '*', 5, '40.11.001', 1.00, 'SEED', 'ATTIVA', 'b9d9ae9d0d0016fc9373c6ceb4029837738a8b82f022436e538a06162a52a486')
ON CONFLICT (signature_hash, tipo_movimento, sorgente) DO NOTHING;

INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('LARIO')) AS t(token)
 WHERE f.signature_hash = 'b9d9ae9d0d0016fc9373c6ceb4029837738a8b82f022436e538a06162a52a486' AND f.tipo_movimento = 'USCITA' AND f.sorgente = '*'
ON CONFLICT (firma_id, token) DO NOTHING;
