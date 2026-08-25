-- Bonifica del dizionario keyword, parte 2 di 3: la POTATURA e i RI-TARGET.
-- Base di fatti: docs/analisi/keyword-audit-2026-08-24.md §9 (bucket), §9.5 (target transitori),
-- §4.3 (conflitti di sola BU), piu' le decisioni del titolare del 24/08/2026.
--
-- IL PROBLEMA, misurato: il dizionario e' cresciuto a 236 firme imparando da solo, e il 47 %
-- e' zavorra. Il grosso sono CODICI DI DISPOSIZIONE irripetibili (AGOS000000765675742): hanno
-- agganciato la singola riga da cui sono nati e non possono tornare. Non fanno danno diretto,
-- ma coprono firme migliori (una riga catalogata da AGOS000000784256313 invece che da SOGEGROSS),
-- gonfiano i conflitti e rendono illeggibile la schermata "In import".
--
-- IL GATE, eseguito sul corpus reale (1.134 righe, giugno + agosto 2026), replay CON e SENZA:
--     firme attive     215  ->  116
--     catalogate       246  ->  250      (77,8 % -> 79,1 %)
--     esiti cambiati    46: 42 "subentra una firma piu' corta", 4 "conflitto -> catalogata"
--     >>> righe che PERDONO la catalogazione: 0 <<<
--
-- ORDINE OBBLIGATO, e non e' un dettaglio di stile: i ri-target vengono PRIMA della potatura.
-- Allineare due firme in conflitto sullo stesso target rende la firma lunga RIDONDANTE, quindi
-- eliminabile: 5 delle 14 ridondanti qui sotto lo sono diventate solo per questo. Nell'ordine
-- inverso resterebbero in tabella. Stesso motivo per FATT: va spenta PRIMA, altrimenti
-- BRICCOLA+FATT risulta "coperta da FATT" e si eliminerebbero entrambe, perdendo le righe vere.
--
-- Cosa NON si elimina, e perche' (§ "Difetto latente != difetto da correggere" di CLAUDE.md):
--   * i 3 mandati SDD ricorrenti (Telepass, acqua): sono codici che TORNANO davvero, misurato;
--   * GIARDINAGGIO / POTATURA / SFALCIO: quei ricavi arrivano per bonifico CON causale;
--   * GPL / BENZINA / OLIO: nulla le blocca, semplicemente non sono ancora comparse;
--   * i 7 PARK_EVENTO di cerimonia e le 6 identita' a zero colpi (fra cui ALINA, alias legittimo
--     di Elvira Ciobanu): zero occorrenze in 6 mesi e' un DATO, non una condanna;
--   * le 35 one-shot con token leggibile: nomi di fornitori veri.
-- Si elimina solo cio' che e' strutturalmente incapace di tornare, o attivamente dannoso.

-- ═══ 1. Il conto nuovo per Baitieri ═══════════════════════════════════════════════════════
-- ATTENZIONE, correzione all'audit: §9.5 chiama AGRI+ATT+BAITIERI+LLI+SEMPLICE un "fornitore
-- agricolo". NON lo e'. La riga e' `BONIF. VS. FAVORE - BON.DA BAITIERI F LLI SOCIETA SEMPLICE
-- AGRI F ATT.34/001`, cioe' un bonifico IN ENTRATA di 1.985,36 €, e la firma ha scope ENTRATA:
-- e' un CLIENTE che salda la fattura 34/001. Oggi punta a 49.99.999 «Costi da classificare»,
-- cioe' un RICAVO su un conto di COSTO che per giunta non cataloga nulla.
-- Il titolare ha scelto un conto intestato, come per gli altri clienti B2B di 30.04.
-- BU 3 (Vendita Prodotti e Spaccio) per analogia con 30.04.001 Agricola Guarisco, che e' l'altro
-- cliente-azienda agricola: e' un giudizio di coerenza, non un dato misurato.
INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active)
SELECT (SELECT max(id) + 1 FROM piano_dei_conti_coge),
       '30.04.003', 'Baitieri F.lli societa'' semplice agricola', 'RICAVO', false,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.04'), true
WHERE NOT EXISTS (SELECT 1 FROM piano_dei_conti_coge WHERE codice = '30.04.003');

SELECT setval('piano_dei_conti_coge_id_seq', (SELECT max(id) FROM piano_dei_conti_coge));

-- ═══ 2. RI-TARGET — le firme che puntavano al transitorio o al mastro generico ═════════════
-- applyKeyword (MovimentoMappingEngineImpl:775-778) RIFIUTA i target transitori: una firma su
-- 49.99.999 porta colpi che si traducono in ZERO catalogazioni. Occupano posto nella pagina
-- Keyword e nel ragionamento dell'operatore senza produrre nulla.

-- Deterministica: la controparte la calcola DescNormalizer.agenziaEntrate(), e il conto esiste.
UPDATE keyword_firma SET coge_codice = '20.02.003', bu_id = 5, updated_at = now()
 WHERE id = 'f3919ff2-9929-41ac-a3f9-53af6ba969dc';  -- AGENZIA+ENTRATE

-- Giudizio di lettura ("vivai"), non un dato: se si rivelasse sbagliato, e' una riga di UPDATE.
UPDATE keyword_firma SET coge_codice = '40.14.001', bu_id = 5, updated_at = now()
 WHERE id = 'd1c65372-de43-4ddf-aadd-88c401226615';  -- AZIENDA+PANZERI+VIVAI

UPDATE keyword_firma SET coge_codice = '30.04.003', bu_id = 3, updated_at = now()
 WHERE id = '8592135d-1108-4399-af8f-3de1cedf9514';  -- AGRI+ATT+BAITIERI+LLI+SEMPLICE

-- NON ri-targettata: AGRARIO+CONSORZIO (3e62cecf-4732-4589-8496-421e3815edd9), 1 riga da
-- 2.206,46 €. I due candidati (40.11.005 quote associative / 40.12.001 materie prime) sono
-- troppo diversi per sceglierli senza sapere cosa contiene quella fattura: il titolare l'ha
-- lasciata APERTA il 24/08. Resta su 49.99.999 e quindi inerte, come oggi: nessun peggioramento.

-- Le due firme sul mastro generico 91 «Investimenti». Conti decisi dal titolare il 24/08:
-- Aquilini fa tinteggiatura e posa vetri (investimento) -> il conto nuovo creato in V42;
-- Elle Emme conferma la scelta gia' fatta a mano sull'altra riga dello stesso fornitore.
UPDATE keyword_firma SET coge_codice = '50.02.005', bu_id = 5, updated_at = now()
 WHERE id = '1ce9dc54-60eb-4803-acb2-b253191f7d97';  -- AQUILINI+RODOLFO
UPDATE keyword_firma SET coge_codice = '50.01.008', bu_id = 5, updated_at = now()
 WHERE id = '9edb44d6-4ec1-4aff-a322-eb63390e9ef2';  -- ELLE+EMME+LUIGIMARINONI

-- ═══ 3. CONFLITTI DI SOLA BU (§4.3) ═══════════════════════════════════════════════════════
-- Stesso conto, business unit diversa: ogni volta che la riga compare, entrambe le firme
-- matchano, i target differiscono e `risolvi()` ritorna CONFLITTO -> la riga va a mano PUR
-- AVENDO due firme che sanno benissimo di chi si tratta. Misurato: 11 righe a periodo.
-- Le firme lunghe di ogni coppia NON sono aggiornate qui: diventano ridondanti e sono
-- eliminate nella sezione 4.

-- Chiusi DAI DATI: bu_default dell'anagrafica fornitori (verificato sul dump del 24/08).
UPDATE keyword_firma SET bu_id = 1, updated_at = now()
 WHERE id = '3554cf5e-7c89-4952-a188-6ae87a650a17';  -- NICELLI (era bu2) - Nicellini Vini -> bu 1
UPDATE keyword_firma SET bu_id = 1, updated_at = now()
 WHERE id = 'e46edc27-7f38-4eca-8321-03497d2a253f';  -- ANTONELLA+VIOLA (era bu5) - Ciocca Formaggi -> bu 1

-- Chiuso DAL TITOLARE: il personale a DB e' misto (Carlo bu5, Maggioni bu1, Elvira bu5, Giulia
-- bu1), quindi nessun criterio era deducibile dai dati. Scelta: bu1. 6 righe, 3.037,29 €.
UPDATE keyword_firma SET bu_id = 1, updated_at = now()
 WHERE id = '3be843ea-03b4-4f1b-ada6-1eb004450017';  -- NOEMI (era bu5)
-- ═══ 4. POTATURA — 117 firme ═══════════════════════════════════════════════════════════
-- keyword_token va in CASCADE (FK ON DELETE CASCADE), quindi basta cancellare la firma.

-- ── B0 — firme gia' DISATTIVATE + FATT: pulizia fisica (18 firme) ──
-- 17 erano gia' fuori dal motore (stato != ATTIVA) e per quelle l'eliminazione non puo' cambiare
-- nessun esito. La diciottesima e' FATT, che invece e' ATTIVA e sta catalogando: eliminarla CAMBIA
-- un esito, ed e' esattamente lo scopo (vedi sotto).
-- Fra queste FATT, spenta qui: token generico appreso da «BON.DA BRICCOLA GI SALDO FATT NR
-- 33/001», che pero' aggancia ANCHE «FATT. N. 41 ... LATTICINI CERNOBBIO S.R.L.» — falso
-- positivo MISURATO, 1 riga su 3 colpi. BRICCOLA da sola copre le righe vere di Briccola.
-- Ci sono anche MEANI+REBECCA e EUROPE+GUESS+SAGL: gia' disattivate da qualcun altro, e i
-- dati danno ragione a quella scelta — entrambi gli eventi hanno gia' un movimento a libro.
DELETE FROM keyword_firma WHERE id = '5b0f60c6-16bc-4704-86fb-1be9be08f170';  -- 02INTER20260117HSRT1524168782
DELETE FROM keyword_firma WHERE id = 'ad7f2bee-2ca1-4a53-a03a-72f5195d5ce2';  -- 260376066169859
DELETE FROM keyword_firma WHERE id = '6c5276df-e87f-4b8b-afbc-9b8afe4b534e';  -- AGO000000780794466
DELETE FROM keyword_firma WHERE id = 'dfb72be7-c62e-4efa-a4e0-c92b87bd800b';  -- AGOS000000754300586
DELETE FROM keyword_firma WHERE id = 'd9f073f3-5338-404a-ad58-5e254519924b';  -- ANDREEA+BRAYAN+IONESCU+KAIROS+MIHAI+NICOLETA+PROGETTO
DELETE FROM keyword_firma WHERE id = 'b4c315b7-5660-474e-aeb7-0c5376b54474';  -- BERNASCONI+CRISTINA+ISELLA+MASSIMO
DELETE FROM keyword_firma WHERE id = '3cadb0dc-8655-4148-964a-f92cc6cb3dce';  -- CH910023623616800941Y
DELETE FROM keyword_firma WHERE id = '49cc4517-3807-48f9-8c6b-72c21d2a80fa';  -- CIOBANU+ELVIRA
DELETE FROM keyword_firma WHERE id = 'c92cd23e-e501-40a9-bd36-d52ffb342562';  -- COMO+FELCO+IMAT
DELETE FROM keyword_firma WHERE id = '886c4d20-cb61-47e3-b878-1d8e0c451c25';  -- DE28502200853278750017
DELETE FROM keyword_firma WHERE id = '91110e48-f870-4689-ac7c-e2825f54242e';  -- EUROPE+GUESS+SAGL
DELETE FROM keyword_firma WHERE id = '065e19bf-a65d-42f6-a929-e5f1dbc62c76';  -- FATT
DELETE FROM keyword_firma WHERE id = '1661784c-257f-4c34-b8e3-32c5b042c88c';  -- FATTI+GAGIARDO+MARA+SETA
DELETE FROM keyword_firma WHERE id = '2e875812-c1e4-4086-bef9-801c37fc945e';  -- MEANI+REBECCA
DELETE FROM keyword_firma WHERE id = '6edac387-0495-44e8-a8db-84196a4dee73';  -- UBSWCHZH80A
DELETE FROM keyword_firma WHERE id = '72112e70-1b4e-4034-9d84-f1feb4a0ef10';  -- ZD19190ZD6336640
DELETE FROM keyword_firma WHERE id = '22b7cefa-c4c1-480a-add0-9bba567d3006';  -- ZD81089TO4270286
DELETE FROM keyword_firma WHERE id = '0b74a3d5-8fa3-497b-94f2-29dfd91391b1';  -- ZD81133TO5841929

-- ── B2 — codici di disposizione IRRIPETIBILI (69 firme) ──
-- Un AGOS000000765675742 e' il progressivo di UNA disposizione: non e' che non sia ancora
-- tornato, e' che NON PUO' tornare. Misurato sul corpus: le famiglie AGOS/AGO/PO contano 170
-- token distinti e NESSUNO ricorre in piu' di una riga. Sono il 40 % del dizionario appreso.
-- I mandati SDD (MU.../2C...), che invece ricorrono davvero, NON si toccano (bucket B1).
DELETE FROM keyword_firma WHERE id = '337f78c5-c3ee-4cfc-af85-981f366d93b3';  -- 109341178367545
DELETE FROM keyword_firma WHERE id = '7d61290e-a18d-45e4-a9c5-aff33505c7ea';  -- 1235071768459907703
DELETE FROM keyword_firma WHERE id = '5dd9d018-4f13-4d76-9cde-47faa38d1834';  -- 1251211782843542490
DELETE FROM keyword_firma WHERE id = 'e11989af-593b-495a-a063-6fe0bb625379';  -- 1410601773568
DELETE FROM keyword_firma WHERE id = '168bed51-6888-4571-a676-ac3c50191b07';  -- 1702331779
DELETE FROM keyword_firma WHERE id = '5cd5c51c-2094-4aa3-bc23-1e2381f5b472';  -- 19943817722279
DELETE FROM keyword_firma WHERE id = '0d439738-9bc2-4944-93a4-a30d77f423c6';  -- 2396731782919426141
DELETE FROM keyword_firma WHERE id = '82d30b2d-9b9a-434c-9956-e38ef2265707';  -- 2643701773569
DELETE FROM keyword_firma WHERE id = '60bdbd6b-f675-4375-8565-662eea70cfee';  -- 300000000183472019
DELETE FROM keyword_firma WHERE id = '9ca87b7e-17bd-4cd2-85fd-40ce1b8184e4';  -- 40361717735688
DELETE FROM keyword_firma WHERE id = 'c0408499-2af4-4234-85f6-52b6bea323a8';  -- 48763917830301895
DELETE FROM keyword_firma WHERE id = 'db69b1d5-28f6-4f18-8ea4-98cc00e16669';  -- 52839017821306
DELETE FROM keyword_firma WHERE id = '6670217e-67be-4cc3-9d85-dc7c8233765d';  -- 7003811784116
DELETE FROM keyword_firma WHERE id = '66177bdf-611d-4201-b5d3-e622920acb66';  -- 8155971783029
DELETE FROM keyword_firma WHERE id = '57f02f82-8ef5-46db-b25a-a7c81830253d';  -- 8174601783030030589
DELETE FROM keyword_firma WHERE id = '1315d2bf-a11a-49b5-8b57-024d792c3d5d';  -- 8515401771488185238
DELETE FROM keyword_firma WHERE id = '34e41d21-4021-4123-bb91-456394ae8323';  -- 8529001784116
DELETE FROM keyword_firma WHERE id = '9ebba86d-bfda-4e9d-b4b1-15bfdf59d73c';  -- 8637571784116
DELETE FROM keyword_firma WHERE id = 'b9520f61-31d6-4d34-b214-76c44b84eb57';  -- 8933301783675637167
DELETE FROM keyword_firma WHERE id = '56a3235d-55fe-4e9d-87c5-6e3f2e6d8259';  -- 9099141770905759324
DELETE FROM keyword_firma WHERE id = 'db70bc71-f85e-4c95-b32e-2832f59d5523';  -- 913401177333925
DELETE FROM keyword_firma WHERE id = 'b35ed83a-968a-41fc-bd97-b8bc796736eb';  -- 918598177789
DELETE FROM keyword_firma WHERE id = '923ced50-51f3-4de3-97c1-8d3e80cff16e';  -- 9387281783029073974
DELETE FROM keyword_firma WHERE id = '8217abdc-66bb-4ee1-855c-a6ac27cd8a58';  -- 9559111781704966753
DELETE FROM keyword_firma WHERE id = 'db7c9111-2784-4e05-8d76-7737cf61be6d';  -- 976270178411649
DELETE FROM keyword_firma WHERE id = 'b615787a-6d77-46b1-9137-7fe24af98a0c';  -- 9824431783029917384
DELETE FROM keyword_firma WHERE id = 'd9c8ee06-9fcc-4b99-bf43-18da5864883d';  -- AGO000000721247075
DELETE FROM keyword_firma WHERE id = 'dedeb1ce-a390-46ed-bbb3-fd1545aaf8fc';  -- AGO000000734292093
DELETE FROM keyword_firma WHERE id = '4af9362b-5c48-4918-bc1b-ee656e119028';  -- AGO000000783930947
DELETE FROM keyword_firma WHERE id = 'c70e3841-7277-4e16-a767-2f322fdbb34f';  -- AGO000000784256313
DELETE FROM keyword_firma WHERE id = '87a37d56-8275-41a7-9b72-b14fa57496fd';  -- AGO000000784577574
DELETE FROM keyword_firma WHERE id = 'b60505b0-158f-4e97-93ff-ab33c9bec07e';  -- AGO000000784992693
DELETE FROM keyword_firma WHERE id = '784a3018-6b6b-4f51-9be4-f2bbea7e417a';  -- AGO000000784992701
DELETE FROM keyword_firma WHERE id = '480144b0-8839-433d-b96e-3bc18c1b3ad6';  -- AGO000000784992703
DELETE FROM keyword_firma WHERE id = 'e30c87c7-7132-4851-90dc-50fcb611ebe7';  -- AGO000000784992705
DELETE FROM keyword_firma WHERE id = '674e4b5d-2dd1-4164-b218-5e26a8725084';  -- AGO000000788147547
DELETE FROM keyword_firma WHERE id = 'f5310acd-c440-4257-814a-f2a3c7ebea9f';  -- AGO000000791807322
DELETE FROM keyword_firma WHERE id = '59c0f06e-b0ff-4274-89f3-89050e06ddda';  -- AGO000000794657904
DELETE FROM keyword_firma WHERE id = '281c0c3c-3568-4b3a-84a8-212b4925a7bc';  -- AGOS000000731947654
DELETE FROM keyword_firma WHERE id = 'd22132b1-5b55-4c48-809a-742b56e0d2a0';  -- AGOS000000735739184
DELETE FROM keyword_firma WHERE id = '2ba8a49e-d7a7-4922-a11a-a2a4743a6f26';  -- AGOS000000737189517
DELETE FROM keyword_firma WHERE id = 'c2e3324e-9559-4d55-9e70-11a4b9808871';  -- AGOS000000737822571
DELETE FROM keyword_firma WHERE id = '6831fad7-ad90-40fa-bc7a-c0350d04e29c';  -- AGOS000000738429192
DELETE FROM keyword_firma WHERE id = '983f18a7-06e1-4425-a31c-138f03b13cb3';  -- AGOS000000738429194
DELETE FROM keyword_firma WHERE id = 'd9f61375-5680-4ec0-b26d-bcf26c30a1a5';  -- AGOS000000739397742
DELETE FROM keyword_firma WHERE id = '1902cca8-0fe7-4994-8004-21baea99b4e1';  -- AGOS000000740718539
DELETE FROM keyword_firma WHERE id = '09edb8c9-9c74-4383-8b45-7bc88efde46a';  -- AGOS000000741888806
DELETE FROM keyword_firma WHERE id = '9b942917-9300-4baf-89a1-55a69d288b27';  -- AGOS000000742683183
DELETE FROM keyword_firma WHERE id = 'ef4534aa-d14b-40d3-833c-025caa4b58e6';  -- AGOS000000743522814
DELETE FROM keyword_firma WHERE id = '9217fe7e-65c7-4bc3-95f4-b3d6e94cf2c4';  -- AGOS000000743522820
DELETE FROM keyword_firma WHERE id = '24924d37-3a52-4f9e-9ae4-bea0ae179a0d';  -- AGOS000000743522826
DELETE FROM keyword_firma WHERE id = '5ce7e812-24f9-4c52-97a6-4c7809f710bc';  -- AGOS000000744717218
DELETE FROM keyword_firma WHERE id = 'd38d8095-b124-4a3f-b4ac-146d31ffc29e';  -- AGOS000000748974325
DELETE FROM keyword_firma WHERE id = '123e8842-4bc6-43b1-94b7-df960573877f';  -- AGOS000000761438962
DELETE FROM keyword_firma WHERE id = 'f5217fc6-022f-4d4a-900c-48ea54a909f7';  -- AGOS000000761458353
DELETE FROM keyword_firma WHERE id = '9e266864-923d-4cc1-b54d-27d4b8d81227';  -- AGOS000000765675740
DELETE FROM keyword_firma WHERE id = '1222d1a8-3003-4054-a504-5f669b8d1266';  -- AGOS000000767168177
DELETE FROM keyword_firma WHERE id = 'd7a86dd9-c4d5-4642-aae1-7fadc1629881';  -- AGOS000000776672057
DELETE FROM keyword_firma WHERE id = 'c523ec09-3afa-43c4-baed-75baf268551c';  -- AGOS000000779094153
DELETE FROM keyword_firma WHERE id = 'b91bccff-0dbd-44fb-a53a-fb69fb150744';  -- AGOS000000779095048
DELETE FROM keyword_firma WHERE id = '85e21b06-24f4-4aef-92ec-30d6919a8289';  -- AGOS000000780450488
DELETE FROM keyword_firma WHERE id = '675ddda4-e9da-460c-be32-26ad9972c204';  -- AGOS000000784992695
DELETE FROM keyword_firma WHERE id = '84419c9b-aa7d-482f-be6d-bd2acdc77bdb';  -- AGOS000000788147006
DELETE FROM keyword_firma WHERE id = '6b82dd07-8fba-4603-afbf-a7604895d641';  -- AGOS000000789514321
DELETE FROM keyword_firma WHERE id = '0c1f91cf-e172-43ad-9cb5-20cfb4ea4e3a';  -- AGOS000000790126207
DELETE FROM keyword_firma WHERE id = '7feeaa10-f971-4c77-a105-aa990cb59972';  -- AGOS000000790126209
DELETE FROM keyword_firma WHERE id = '20ab20a2-49f1-4ee1-b304-f6ee66c4c267';  -- AGOS000000790126211
DELETE FROM keyword_firma WHERE id = '3f352595-b9b0-4b39-b971-f2acde9c44d3';  -- AGOS000000790126213
DELETE FROM keyword_firma WHERE id = 'c4ecf2c1-36d3-4fcf-8b79-1d0cf836e509';  -- AGOS000000790126215

-- ── B7 — codici che puntavano al conto transitorio (2 firme) ──
-- Doppiamente inerti: codice irripetibile E target che applyKeyword rifiuta.
DELETE FROM keyword_firma WHERE id = 'b7e7f255-13f5-4969-b736-bcf6577d0bca';  -- 180033101524477158
DELETE FROM keyword_firma WHERE id = '9f70156b-922f-4e32-975e-0d82c9a26325';  -- AGOS000000739397718

-- ── B3 — DOMINIO di ricavo di cassa: nessuna superficie su cui applicarsi (12 firme) ──
-- 0 colpi su 1.134 righe, e la causa e' strutturale, non casuale: i ricavi di cassa arrivano
-- da Billy, e le righe Billy NON HANNO descrizione (normalizeBilly -> clean(null) -> null).
-- Queste parole non compaiono in NESSUN testo che il motore legga. Billy e' gia' classificato
-- al 100 % dalle sue colonne merceologiche (BillyCategoria): 235 scontrini su 235.
DELETE FROM keyword_firma WHERE id = '59aa7a5e-e6ea-4e88-aec0-cc79291d3144';  -- AGRITURISMO
DELETE FROM keyword_firma WHERE id = '258d97cd-fb37-4fdd-a2d7-b7f3a70da27a';  -- CENA
DELETE FROM keyword_firma WHERE id = 'f0b51fd9-d65a-4e1b-b00d-69aa4cc7a30e';  -- COPERTI
DELETE FROM keyword_firma WHERE id = '664f1099-bf7c-4290-b0c7-ff42a7ec0ab5';  -- MACELLERIA
DELETE FROM keyword_firma WHERE id = '4a14e108-e3fd-487a-9269-5e413925ba74';  -- MENU
DELETE FROM keyword_firma WHERE id = '2715596f-74b0-4445-a29f-e71d29fbb28a';  -- ORTOFRUTTA
DELETE FROM keyword_firma WHERE id = 'f070900d-4ea5-4729-9af8-d790299a166c';  -- OSPITALITA
DELETE FROM keyword_firma WHERE id = '5e60ec36-fa8c-4a6e-9af4-db4e136fd7aa';  -- PERNOTTAMENTO
DELETE FROM keyword_firma WHERE id = '0bb4675e-966c-4a3c-84a0-515428a5b0aa';  -- PRANZO
DELETE FROM keyword_firma WHERE id = '6d13ebf6-f940-439e-ac70-e55433bd8b09';  -- RISTORANTE
DELETE FROM keyword_firma WHERE id = '2fa4fa1d-6c9b-4b43-bd8d-46c63e0bed6a';  -- SALUMI
DELETE FROM keyword_firma WHERE id = '5482515f-831d-47b4-9aa6-4a4b089ed02a';  -- SPACCIO

-- ── B4 — DOMINIO oscurate da una regola a priorita' piu' alta (2 firme) ──
-- Tutte le righe con POLIZZA/ASSICURAZ sono intercettate dalle regole 1/2 (SKIP_RICORRENTE,
-- priorita' 30) PRIMA che le keyword vengano consultate: irraggiungibili per costruzione.
DELETE FROM keyword_firma WHERE id = '2713be6a-9984-4921-9a4d-2604cdd7dd20';  -- ASSICURAZIONE
DELETE FROM keyword_firma WHERE id = 'c09884eb-5ec4-4e3c-87cd-f2df461c6e11';  -- POLIZZA

-- ── RIDONDANTI — token-set che CONTIENE quello di un'altra firma con lo STESSO (conto, BU) (14 firme) ──
-- La firma corta copre gia' la riga e produce lo stesso esito: la lunga non aggiunge nulla e
-- gonfia i conflitti. CINQUE di queste (ADD+ANTONELLA+TOT+VIOLA, AULAKH+SMART+WASH,
-- FABIO+MALLAMACE, GIORDANO+NOEMI, NICELLI+VINI) sono diventate ridondanti SOLO ORA, per
-- effetto dell'allineamento dei conflitti: e' il motivo per cui i ri-target vengono prima.
DELETE FROM keyword_firma WHERE id = 'a05d01e5-ab03-4baf-942f-ee4224b020e6';  -- ADD+ANTONELLA+TOT+VIOLA
DELETE FROM keyword_firma WHERE id = '037406da-f020-4a0a-a07a-a6e60281a43d';  -- ANTICIPO+BRICCOLA+FATT
DELETE FROM keyword_firma WHERE id = 'f08e73ac-5969-4cf0-95cd-2aadb9e987c9';  -- AULAKH+SMART+WASH
DELETE FROM keyword_firma WHERE id = '63940448-c627-4f3c-92c6-29d20dbc8db3';  -- AZIENDA+PASINI+RICCARDO
DELETE FROM keyword_firma WHERE id = '3646de7d-715f-49d2-91d3-99ac8757250a';  -- BERNASCONIRIF+CARLO
DELETE FROM keyword_firma WHERE id = 'e4b783cf-b8fd-4527-b436-31d424d2bbbc';  -- BRICCOLA+FATT
DELETE FROM keyword_firma WHERE id = '89faa158-68c5-4a62-a2b4-81ee8e563cf1';  -- CARNI+NOSTRAN+SRLV
DELETE FROM keyword_firma WHERE id = 'd84a267d-78c0-4a69-ba3d-73bd80ebaf77';  -- CAVADINI+EUROSISTEM+FELICE+NICOMEDE+ROBERTO
DELETE FROM keyword_firma WHERE id = '3c564f9b-ae74-42af-aca1-c40ff4057223';  -- COMMERCIALE+ZEUS
DELETE FROM keyword_firma WHERE id = 'c8444354-1fe7-4577-a77d-3f2818604ada';  -- FABIO+MALLAMACE
DELETE FROM keyword_firma WHERE id = 'dc67ffc0-4c4b-4073-8173-a011d6fe6650';  -- GIORDANO+NOEMI
DELETE FROM keyword_firma WHERE id = 'ecd40d79-9675-4c3c-8f1e-685b8cf01a00';  -- LERSA+LUISAGO+ROSSI
DELETE FROM keyword_firma WHERE id = 'e4a1b00d-379c-4175-86c2-89e37fb613be';  -- NICELLI+VINI
DELETE FROM keyword_firma WHERE id = '37b3dcb5-22e4-438e-a3ca-3ab353352b6a';  -- STUDIO+TORRES
