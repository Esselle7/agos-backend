-- Bonifica del dizionario keyword, parte 1 di 3: i token SBAGLIATI e i conflitti che nascondevano.
-- Base di fatti: docs/analisi/keyword-audit-2026-08-24.md §9.4 (bucket B10/B11), §4.2, §4.3.
--
-- Due famiglie di token rotti, misurate sul corpus reale (1.134 righe di giugno + agosto 2026):
--   B10 — artefatti di parsing: `RIF.` incollato al cognome dall'estratto conto CA
--         (BERNASCONIRIF, PISCHEDDURIF, LUIGIRIF, SRLRIF) e sigle mozzate (SOCIET, AGRICOLALA).
--         Una firma su BERNASCONIRIF non aggancera' mai una riga che scrive BERNASCONI RIF.
--   B11 — identita' che sono in realta' una LOCALITA': ALTA+VALLE cataloga 34 righe (2.595,43 €)
--         sul nome del COMUNE (Alta Valle Intelvi) invece che sull'esercente (CAVALLASCA), e
--         qualunque altro fornitore di quel comune finirebbe sullo stesso conto.
--
-- QUESTA MIGRATION VA INSIEME al fix di normalizzazione in KeywordExtractor.lex(): applicare
-- l'uno senza l'altro PERDE RIGHE, ed e' misurato in entrambe le direzioni.
--   * il fix `RIF.` da solo    → 19 esiti cambiati, 4 righe perdono la catalogazione
--     (le firme apprese CONTENGONO i token rotti, correggere il testo le rende cieche);
--   * i token corretti da soli → 4 righe perse (i conflitti sotto).
--   * insieme, con l'allineamento dei conflitti → 0 perse, 6 guadagnate (72,8 % → 74,7 %).
--
-- Perche' l'allineamento dei conflitti sta QUI e non nella migration successiva: correggere i
-- token fa emergere la seconda firma su righe dove prima ne matchava una sola, e due firme con
-- target divergenti sulla stessa riga = CONFLITTO = riga a smistamento manuale. Il conflitto
-- non e' nuovo: era MASCHERATO dal token rotto. Separarlo in due migration aprirebbe una
-- finestra in cui 4 righe reali perdono la catalogazione.

-- ── I due conti nuovi, decisi dal titolare il 24/08 ──────────────────────────────────────
-- Stesso pattern di V21/V35/V41: id = max+1 e setval finale (la sequence si e' gia' disallineata
-- in passato e un nextval collide), WHERE NOT EXISTS = idempotenza.

-- SMART WASH: 15 righe, 710,00 € a periodo. Le due firme in conflitto proponevano
-- `40.11.001 Sonvico – forniture varie` (conto intestato a un ALTRO fornitore) e
-- `40.06.002 Carburante benzina`: nessuno dei due descrive un autolavaggio. Il titolare ha
-- scelto un conto dedicato dentro la famiglia veicoli, cosi' la voce resta leggibile a bilancio.
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '40.06.004', 'Lavaggio automezzi', 'COSTO', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.06'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '40.06.004');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- AQUILINI RODOLFO: tinteggiatura e posa in opera vetri (detto dal titolare il 24/08), da
-- trattare come INVESTIMENTO. Nessun conto esistente lo descriveva: 50.01 e' "Attrezzature e
-- macchinari", qui si tratta di opere. Va quindi sotto `50.02 Opere edili e impianti`, che e'
-- gia' is_capex = true. Serve alla migration successiva (ri-target della firma AQUILINI+RODOLFO,
-- che oggi punta al mastro generico 91) e all'intervento sui dati di produzione.
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '50.02.005', 'Tinteggiature e posa vetri', 'COSTO', true,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '50.02'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '50.02.005');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- ── I token corretti ─────────────────────────────────────────────────────────────────────
-- signature_hash = sha256 hex dei token UPPERCASE ORDINATI e uniti da '|' — identico a
-- KeywordExtractor.signatureHash(). Validato sull'oracolo del seed V7
-- (signatureHash(['MATRIMONIO']) = 110bc395d0…5a22) e contro 3 righe reali di keyword_firma.
--
-- ⚠️ PORTABILITA' FRA AMBIENTI. Le firme corrette qui sotto sono APPRESE in produzione, quindi
-- il loro id (gen_random_uuid) esiste SOLO li': su test e dev quelle righe non ci sono. Per
-- questo ogni blocco e' scritto per essere un NO-OP quando la firma non esiste — INSERT ... SELECT
-- ... WHERE f.id = '<uuid>' invece di VALUES con firma_id cablato, che violerebbe la FK e
-- FAREBBE FALLIRE IL BOOT dell'applicazione (Flyway blocca l'avvio su migration fallita).
-- Misurato: con VALUES cablati, `mvn test` non parte affatto su agosdb_test.
-- ═══ B10 — token rotti da artefatti di parsing degli estratti conto ═══

-- GIULIA+PISCHEDDURIF  →  GIULIA+PISCHEDDU   (RIF. incollato al cognome)
DELETE FROM keyword_token WHERE firma_id = '3871ca9e-b814-42cc-a8ae-bb94dcbb4d05';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('GIULIA'), ('PISCHEDDU')) AS t(token)
 WHERE f.id = '3871ca9e-b814-42cc-a8ae-bb94dcbb4d05'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '3b79944a3121e9701f8bf99f46f2530a30f43b1bd2fb329f939bda14db4d5f58', updated_at = now() WHERE id = '3871ca9e-b814-42cc-a8ae-bb94dcbb4d05';

-- AGRICOLALA+AZIENDA+BONGIOLATTI+NICOLA+TAIADA  →  BONGIOLATTI+TAIADA   (AGRICOLALA = 'AGRICOLA LA' incollati)
DELETE FROM keyword_token WHERE firma_id = '785f787b-d7e8-488d-944b-648bdc75321d';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('BONGIOLATTI'), ('TAIADA')) AS t(token)
 WHERE f.id = '785f787b-d7e8-488d-944b-648bdc75321d'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '88b85994e0ac872dfe4b184a3416857e4bb245b74943c30c6d32ff8146f5acff', updated_at = now() WHERE id = '785f787b-d7e8-488d-944b-648bdc75321d';

-- BERNASCONIRIF+MARCO  →  BERNASCONI+MARCO   (RIF. incollato)
DELETE FROM keyword_token WHERE firma_id = '7ed792f3-762d-40ed-b97a-065c0caea79b';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('BERNASCONI'), ('MARCO')) AS t(token)
 WHERE f.id = '7ed792f3-762d-40ed-b97a-065c0caea79b'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '52f5faf98eb143bbceddbc8a6fe21f7afd51b670fdc3ec67455c0454d3486590', updated_at = now() WHERE id = '7ed792f3-762d-40ed-b97a-065c0caea79b';

-- BRIZZOLARA+LUIGIRIF: la firma corretta ESISTE GIA'. Vedi il blocco deduplica in fondo.

-- GVM+SERVICE+SRLRIF  →  GVM+SERVICE   (SRL+RIF. incollati)
DELETE FROM keyword_token WHERE firma_id = 'bf8fb97d-9d92-4c43-892a-0890fd5f024c';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('GVM'), ('SERVICE')) AS t(token)
 WHERE f.id = 'bf8fb97d-9d92-4c43-892a-0890fd5f024c'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = 'df6f513bf5611d07077fc528b10bc7ccc65b354c92cc8da8ba0972b8eee0f64f', updated_at = now() WHERE id = 'bf8fb97d-9d92-4c43-892a-0890fd5f024c';

-- NICELLI+SRLRIF+VINI  →  NICELLI+VINI   (SRL+RIF. incollati)
DELETE FROM keyword_token WHERE firma_id = 'e4a1b00d-379c-4175-86c2-89e37fb613be';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('NICELLI'), ('VINI')) AS t(token)
 WHERE f.id = 'e4a1b00d-379c-4175-86c2-89e37fb613be'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '677c2540000747e1bdcfa9a50ce318fa2c30a5c3dd0abf88af73f0a6b6c4ad41', updated_at = now() WHERE id = 'e4a1b00d-379c-4175-86c2-89e37fb613be';

-- AGRICOLAAGOSTINELLI+BPM+SOCIET  →  AGRICOLAAGOSTINELLI+BPM   (SOCIET = SOCIETA' mozzato)
DELETE FROM keyword_token WHERE firma_id = '40bb98b0-b121-4b16-8f82-9269a28fd4c1';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('AGRICOLAAGOSTINELLI'), ('BPM')) AS t(token)
 WHERE f.id = '40bb98b0-b121-4b16-8f82-9269a28fd4c1'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '73a08161bf6b4c1e5b7100f5f7042aa9c1b4ed1f95f7aed5403e09905d6562da', updated_at = now() WHERE id = '40bb98b0-b121-4b16-8f82-9269a28fd4c1';

-- ═══ B11 — identità che erano in realtà una LOCALITÀ ═══

-- ALTA+VALLE  →  CAVALLASCA   (il token era il COMUNE (Alta Valle Intelvi), non l'esercente)
DELETE FROM keyword_token WHERE firma_id = '70162ba3-ad92-4180-bf88-13efd2c58cc2';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('CAVALLASCA')) AS t(token)
 WHERE f.id = '70162ba3-ad92-4180-bf88-13efd2c58cc2'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '6204e4a58a0030a946daab4fbd4705c71bf7bf0f84a8dd3f672b578033c607c3', updated_at = now() WHERE id = '70162ba3-ad92-4180-bf88-13efd2c58cc2';

-- ALBAIRA+AULAKH+SMART+WASH  →  AULAKH+SMART+WASH   (ALBAIRA = Albairate (MI))
DELETE FROM keyword_token WHERE firma_id = 'f08e73ac-5969-4cf0-95cd-2aadb9e987c9';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('AULAKH'), ('SMART'), ('WASH')) AS t(token)
 WHERE f.id = 'f08e73ac-5969-4cf0-95cd-2aadb9e987c9'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '81abe149082b60187e097b31a76a5cf743b7b1faad6330315e7c9db696367f3d', updated_at = now() WHERE id = 'f08e73ac-5969-4cf0-95cd-2aadb9e987c9';

-- CARRY+CASH+LUCIN+MONTANO  →  CARRY+CASH   (Montano Lucino (CO))
DELETE FROM keyword_token WHERE firma_id = '43077982-9e85-4197-bee4-b5782ccd5b22';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('CARRY'), ('CASH')) AS t(token)
 WHERE f.id = '43077982-9e85-4197-bee4-b5782ccd5b22'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = '8a486b617ae40233b5d40993e2a7b69938165215cfa89593dcd127f840a05215', updated_at = now() WHERE id = '43077982-9e85-4197-bee4-b5782ccd5b22';

-- COMAS+COMEDILMANGINO+OLGIAT  →  COMEDILMANGINO   (Olgiate Comasco; il token singolo prende tutte le grafie)
DELETE FROM keyword_token WHERE firma_id = '9c5b9d98-f099-4965-bff6-4c6c6eea031b';
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT f.id, t.token, 'IDENTITA'
  FROM keyword_firma f CROSS JOIN (VALUES ('COMEDILMANGINO')) AS t(token)
 WHERE f.id = '9c5b9d98-f099-4965-bff6-4c6c6eea031b'
ON CONFLICT (firma_id, token) DO NOTHING;
UPDATE keyword_firma SET signature_hash = 'c191b365517f6d1616ac141b0d9822a4c13aceeb004e3737625a1c29e7db3dee', updated_at = now() WHERE id = '9c5b9d98-f099-4965-bff6-4c6c6eea031b';

-- ═══ Deduplica: dopo la correzione questi token-set collidono con una firma esistente ═══
-- (il vincolo uq_keyword_firma_sig(signature_hash, tipo_movimento, sorgente) lo impone comunque)

-- ANTONELLARIF+VIOLA → sarebbe identica a ANTONELLA+VIOLA (e46edc27…); 0 colpi efficaci sul corpus reale → si elimina
DELETE FROM keyword_firma WHERE id = '11688272-aaf4-40af-8e55-8da47fef12aa';   -- keyword_token va in CASCADE

-- NICELLINI → sarebbe identica a NICELLI (3554cf5e…); 0 colpi efficaci sul corpus reale → si elimina
DELETE FROM keyword_firma WHERE id = '1c16e5f0-0457-4dbd-9912-e10a72fd461a';   -- keyword_token va in CASCADE

-- BRIZZOLARA+LUIGIRIF → la gemella corretta ESISTE GIA' (16071514…, BRIZZOLARA+LUIGI) e punta
-- allo STESSO conto e alla STESSA BU (40.14.001 / bu5): correggere il token qui violerebbe
-- uq_keyword_firma_sig, e non serve — la gemella copre gia' la riga, con lo stesso esito.
-- Caso non elencato in audit §9.4, trovato applicando la migration su una copia del DB.
DELETE FROM keyword_firma WHERE id = '49397d45-b2a8-4a72-b246-19192430252f';   -- keyword_token va in CASCADE

-- ── I due conflitti che la correzione dei token fa emergere ──────────────────────────────
-- Non sono conflitti nuovi: erano gia' li', nascosti dietro un token rotto. Misurato: senza
-- questo blocco la migration PERDE 4 righe reali (2 Mallamace + 2 Smart Wash), che passano da
-- catalogate a CONFLITTO e quindi a smistamento manuale.

-- MALLAMACE (40.07.002 "consulenze") vs FABIO+MALLAMACE (mastro generico 91): con `FABIO`
-- finalmente estratto dalla riga, entrambe matchano e i target divergono.
-- Chiuso DAI DATI, non da un giudizio: in `fornitori` esiste `Mallamace Edilizia` con
-- coge_default → 50.01.007 "Mallamace – forniture edili", conto gia' intestato a lui.
UPDATE keyword_firma SET coge_codice = '50.01.007', bu_id = 5, updated_at = now()
 WHERE id IN ('1d4f0e7f-990e-4496-8dc5-e34c033119e8',   -- MALLAMACE          (era 40.07.002)
              'c8444354-1fe7-4577-a77d-3f2818604ada');  -- FABIO+MALLAMACE    (era 91)

-- SMART+WASH (40.11.001) vs AULAKH+SMART+WASH (40.06.002): tolto il token-localita' ALBAIRA,
-- la firma appresa aggancia anche le righe di Crédit Agricole e i due target divergono.
-- Entrambe sul conto nuovo deciso dal titolare. `usi_corretti = 2` sulla firma seed dice che
-- il titolare aveva gia' corretto a mano quel target: qui la correzione diventa strutturale.
UPDATE keyword_firma SET coge_codice = '40.06.004', bu_id = 5, updated_at = now()
 WHERE id IN ('e6f215f4-3ee4-4087-85f6-a6960f7bd669',   -- SMART+WASH         (era 40.11.001)
              'f08e73ac-5969-4cf0-95cd-2aadb9e987c9');  -- AULAKH+SMART+WASH  (era 40.06.002)

-- CAVALLASCA (ex ALTA+VALLE): 34 righe, 2.595,43 €. Il token e' corretto sopra (B11); il CONTO
-- e' deciso dal titolare il 24/08 → carburante. Era 40.11.001, conto di un altro fornitore.
UPDATE keyword_firma SET coge_codice = '40.06.002', bu_id = 5, updated_at = now()
 WHERE id = '70162ba3-ad92-4180-bf88-13efd2c58cc2';
