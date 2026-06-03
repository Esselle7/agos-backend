# Import Bulk — Regole di riconoscimento e catalogazione movimenti

> Guida di riferimento alle regole con cui una **riga di file** (Billy / Crédit Agricole / Banco BPM)
> diventa un **movimento mappato** in DB. Pensata per essere modificata: ogni regola è isolata e
> rimanda al punto esatto del codice.

## Indice
- [Pipeline ETL (comune a tutte le fonti)](#pipeline-etl-comune-a-tutte-le-fonti)
- [Tabelle di lookup e ID fissi](#tabelle-di-lookup-e-id-fissi)
- [Fonte 1 — Billy (registratore di cassa)](#fonte-1--billy-registratore-di-cassa)
- [Fonte 2 — Crédit Agricole (estratto conto)](#fonte-2--crédit-agricole-estratto-conto)
- [Fonte 3 — Banco BPM (estratto conto)](#fonte-3--banco-bpm-estratto-conto)
- [Validazione invarianti (comune)](#validazione-invarianti-comune)
- [Deduplica e persistenza](#deduplica-e-persistenza)
- [Motivi di ambiguità (revisione manuale)](#motivi-di-ambiguità-revisione-manuale)

---

## Pipeline ETL (comune a tutte le fonti)

Orchestrazione: `MovimentoImportService.importFile()`.

```
file → [PARSE] → RawRow → [NORMALIZE] → RawMovimento → [MAP] → MappingResult → [DEDUP] → movimenti
```

| Stadio | Classe | Responsabilità |
|--------|--------|----------------|
| **Parse** | `parser/BillyParser`, `BancaCaParser`, `BancaBpmParser` | Legge il file grezzo → `RawRow` (mappa colonna→stringa). Inietta il discriminatore `_SORGENTE`. Nessuna logica di dominio. |
| **Normalize** | `MovimentoNormalizerImpl` | Solo formato: date, importi, descrizione, **conto bancario** e **metodo pagamento** deterministici dalla fonte. **Non accede al DB.** |
| **Map** | `MovimentoMappingEngineImpl` | Risolve i campi di dominio dal DB: **COGE, BU, fornitore, evento, aliquota IVA**. In caso di dubbio → `AMBIGUOUS`. |
| **Persist/Dedup** | `MovimentoImportService` | Salva i `SUCCESS`; salta i duplicati per `riferimento_esterno`; manda `AMBIGUOUS`/`GIROCONTO` in `import_ambiguita`. |

**Endpoint REST** (`MovimentiResource`, multipart, `@RolesAllowed("ADMIN")`):

| Fonte | Endpoint | chiave fonte (`fonteStr`) | fonte DB |
|-------|----------|---------------------------|----------|
| Billy | `POST /api/movimenti/import/billy` | `IMPORT_BILLY` | `IMPORT_BILLY` |
| BPM | `POST /api/movimenti/import/bpm` | `IMPORT_BANCA_BPM` | `IMPORT_BANCA` |
| CA | `POST /api/movimenti/import/ca` | `IMPORT_BANCA_CA` | `IMPORT_BANCA` |

> BPM e CA condividono la stessa `BancaImportStrategy` e la stessa fonte DB `IMPORT_BANCA`; cambia solo il parser.

**Dispatch per fonte:** `MovimentoImportService` non conosce i parser direttamente. Risolve la
strategia con `ImportStrategyFactory.get(fonteStr)` (cerca tra le `ImportStrategy` iniettate quella il
cui `supports(fonteStr)` è true) e poi usa `strategy.fonte()` (fonte DB), `strategy.parserFor(fonteStr)`
(parser corretto) e `strategy.getNormalizer()` (normalizzatore condiviso). Strategie disponibili:
`BillyImportStrategy` (`IMPORT_BILLY`) e `BancaImportStrategy` (`IMPORT_BANCA_BPM` / `IMPORT_BANCA_CA`).
Il discriminatore `Sorgente._SORGENTE` iniettato nel `RawRow` (`BILLY` / `BPM` / `CA`) consente al
normalizzatore unico di scegliere il ramo di formato corretto senza un parametro fonte esplicito.

**Principio chiave:** il mapping engine non hardcoda mai gli ID interi. Carica le lookup dal DB
all'inizio di ogni import (`refreshLookups()`) e referenzia i COGE per **codice** (es. `30.02.001`).

---

## Tabelle di lookup e ID fissi

### Conti bancari (`conti_bancari`, ID fissi) — `V6`
| ID | Conto |
|----|-------|
| 1 | Banco BPM – c/c operativo |
| 2 | Crédit Agricole – c/c operativo |
| 3 | Cassa contanti |
| 4 | Satispay – portafoglio digitale |
| 5 | Stripe / Alveare – portafoglio |

### Business Unit (`business_units`, ID fissi) — `V5`
| ID | BU | Nome |
|----|-----|------|
| 1 | BU1 | Ristorazione e Agriturismo |
| 2 | BU2 | Cerimonie ed Eventi |
| 3 | BU3 | Vendita Prodotti e Spaccio |
| 4 | BU4 | Manutenzione Verde |
| 5 | BU5 | Overhead (costi generali) |

### COGE usati dal mapping (per codice) — `V5`/`V8`
| Costante codice | Codice COGE | Descrizione |
|-----------------|-------------|-------------|
| `COGE_CAPARRA` | `30.02.001` | Caparre eventi |
| `COGE_SALDO` | `30.02.002` | Saldi eventi |
| `COGE_CARNE_10` | `30.03.001` | Vendita carni e salumi (IVA 10%) |
| `COGE_ORTOFRUTTA_4` | `30.03.002` | Vendita ortofrutta e trasformati (IVA 4%) |
| `COGE_ALVEARE_STRIPE` | `30.03.003` | Vendita Alveare / Shopify (netto commissioni) |
| `COGE_AGRITURISMO` | `30.01.001` | Incassi ristorazione (Billy – cassa) |
| `COGE_COMMISSIONI_POS` | `40.02.001` | Commissioni Nexi (POS Crédit Agricole) |
| `COGE_SPESE_BANCA` | `40.02.002` | Spese tenuta conto bancario |

### Metodi di pagamento (`metodi_pagamento`, per codice) — `V6`
`CONTANTI`, `POS_BPM`, `POS_CA_NEXI`, `SATISPAY`, `BONIFICO`, `ALVEARE_STRIPE`, `SHOPIFY_STRIPE`, `F24`, `ASSEGNO`, `RID_SDDMANDAT`.

### Alias fornitori (`fornitore_alias_matching` + `fornitori`) — `V6`/`V8`
Usati **solo nelle uscite** (e per la sola attribuzione del fornitore nelle commissioni POS).
Ogni regola = `pattern` + `match_type` (`CONTAINS` | `STARTS_WITH` | `REGEX`) → `fornitore_id`,
con `coge_default_id` e `bu_default_id` opzionali dal fornitore.

Pattern attualmente seedati (tutti `CONTAINS`, confronto in UPPERCASE):
- **V6:** `PASTICCERIA RM`, `PASINI`, `ORMA BIRRA`, `GRUPPO ITALIANO VI`, `NICELLINI`, `ZEUS`, `CIOCCA`,
  `SOGEGROSS`, `VAL MULINI`, `TELEPASS`, `NEXI`, `GPL`, `GAS PROPANO`.
- **V8:** `LODETTI`, `COMEDIL MANGINO`, `ZEP ITALIA`, `FATTORIA GINESTRA`.

> Il match va a buon fine sull'uscita **solo se** il fornitore ha sia `coge_default_id` sia
> `bu_default_id` valorizzati (eccezione: il ramo commissioni POS Nexi, che imposta COGE/BU da sé).

---

## Fonte 1 — Billy (registratore di cassa)

**Formato file:** Excel `.xlsx` (Apache POI). Foglio index 0 (`Corrispettivi`).
**Parser:** `BillyParser`.

### Regole di parsing
- Salta riga 0 (header).
- **Filtro righe:** processa **solo** le righe con colonna `TIPO` (idx 4) = `"S"` (scontrini reali).
  Le righe con Tipo nullo sono aggregati/riepiloghi → escluse.
- Celle numeriche/data Excel → stringa canonica (ISO date / `toPlainString`), **senza** parsing euro.
- Colonne lette (per indice): `DATA`(1), `NOTE`(2), `CHIAVE`(3), `TIPO`(4), `NUMERO`(5), `BANCA`(6),
  `DESCRIZIONE`(7), `IMPORTO`(9), `PAGAMENTO`(10), `AGRITURISMO`(11), `ALTRO`(12), `CARNE_10`(13),
  `ORTOFRUTTA_4`(15) + colonne IVA/contanti/elettronico per riferimento.

### Regole di normalizzazione (`normalizeBilly`)
Tutti i movimenti Billy sono **ENTRATA**, importo `abs`, fonte `IMPORT_BILLY`.

**Conto bancario** (da colonna `BANCA`, uppercase) → `contoFromBanca()`:
| BANCA contiene/uguale | Conto |
|-----------------------|-------|
| contiene `CREDIT` | 2 (Crédit Agricole) |
| `= BPM` | 1 (Banco BPM) |
| `= CASH` | 3 (Cassa) |
| contiene `SATISPAY` | 2 (confluisce su CA) |
| altro / `#N/A` / `NA` | `null` → **BANCA_NON_IDENTIFICATA** |

**Metodo pagamento** (da `PAGAMENTO` + `BANCA`) → `metodoBilly()`:
| Condizione | Metodo |
|------------|--------|
| BANCA contiene `SATISPAY` | `SATISPAY` |
| PAGAMENTO `= C` | `CONTANTI` |
| PAGAMENTO `= E` e BANCA contiene `CREDIT` | `POS_CA_NEXI` |
| PAGAMENTO `= E` e BANCA `= BPM` | `POS_BPM` |
| PAGAMENTO `= E` e BANCA `= CASH` | `null` → **METODO_NON_IDENTIFICATO** (incoerente) |
| altro | `null` |

**Riferimento esterno (dedup):** `CHIAVE + "-" + NUMERO`.
**Descrizione:** `DESCRIZIONE` o, se vuota, `NOTE` (uppercase, trim).
Vengono passate al mapping le 4 colonne importo: `AGRITURISMO`, `ALTRO`, `CARNE_10`, `ORTOFRUTTA_4`.

### Regole di mapping (`classifyEntrata`, ramo Billy)
Variabili: `agri/carne/orto/altro` = la rispettiva colonna importo è > 0.
`eventoKw` = la descrizione contiene `CAPARRA` o `SALDO EVENTO` o `ACCONTO EVENTO`.

Ordine di valutazione:

1. **Riga mista evento + agriturismo** (`eventoKw && agri`) → **BU_AMBIGUA** (split non automatizzabile).
2. **Evento** (`eventoKw`): BU **2**, IVA **10%**, e in base alla keyword:
   - `CAPARRA` → COGE `30.02.001`, tipoEvento `CAPARRA`
   - `ACCONTO EVENTO` → COGE `30.02.001`, tipoEvento `ACCONTO`
   - altrimenti (saldo) → COGE `30.02.002`, tipoEvento `SALDO`
   - **Abbinamento evento obbligatorio** (`findEvento`): cerca nella descrizione un pattern
     `(EVENTO|MATRIMONIO|CERIMONIA) gg/mm/aaaa` e fa lookup `eventi` con `data_evento = X AND stato = 'CONFERMATO'`.
     Se i match sono ≠ 1 (zero o multipli) → **EVENTO_NON_TROVATO**.
3. **Satispay** (`metodo = SATISPAY`):
   - `agri` → COGE `30.01.001`, BU **1**, IVA 10%
   - `altro` → COGE `30.03.001`, BU **3**, IVA 10%
   - altrimenti → **BU_AMBIGUA**
4. **Agriturismo** (`agri`) → COGE `30.01.001`, BU **1**, IVA 10%.
5. **Carne** (`altro && carne`) → COGE `30.03.001`, BU **3**, IVA 10%.
6. **Ortofrutta** (`altro && orto`) → COGE `30.03.002`, BU **3**, IVA 4%.
7. Nessuna delle precedenti → **BU_AMBIGUA**.

---

## Fonte 2 — Crédit Agricole (estratto conto)

**Formato file:** CSV `;`, UTF-8, line endings `\r\r\n` normalizzati a `\n`. Prima riga = header (saltata).
**Parser:** `BancaCaParser`.
**Header reale:** `Data Operazione;Data valuta;CHECK;Chiave;Causale;Banca;Descrizione;Entrate;Uscite;Divisa`

### Regole di parsing
- Salta header e righe completamente vuote.
- **Skip righe-continuazione:** `CAUSALE` vuota **AND** (`CHIAVE` vuota o `= "/"`).
- Colonne per indice: `DATA_OPERAZIONE`(0), `DATA_VALUTA`(1), `CHECK`(2), `CHIAVE`(3), `CAUSALE`(4),
  `BANCA`(5), `DESCRIZIONE`(6), `ENTRATE`(7), `USCITE`(8), `DIVISA`(9).

### Regole di normalizzazione (`normalizeCa`)
- Conto bancario sempre **2** (Crédit Agricole). Fonte `IMPORT_BANCA`.
- Data: `DATA_OPERAZIONE`, formato `dd/MM/yyyy`.
- Importi formato italiano (`1.234,56` / `-321,76`) via `parseEuroAmount` (`#N/A`/`NA`/vuoto → null).
- **Tipo / importo:** se `ENTRATE` ≠ 0 → **ENTRATA** (importo = |entrate|), altrimenti **USCITA** (importo = |uscite|).
- **Metodo pagamento** da `CAUSALE` (uppercase) → `metodoCa()`:
  | CAUSALE | Metodo |
  |---------|--------|
  | `INCASSO TRAMITE POS` | `POS_CA_NEXI` |
  | `GIROCONTO/BONIFICO`, `DISPOSIZIONE DI PAGAMENTO` | `BONIFICO` |
  | `COMMISSIONI/SPESE`, `PAGAMENTO UTENZE`, `EFFETTI RITIRATI/RICHIAMATI` | `RID_SDDMANDAT` |
  | altro | `null` → **CAUSALE_NON_MAPPATA** |
- **Riferimento esterno (dedup):** `CHIAVE + "-" + primi 30 char descrizione`.

### Regole di mapping — ENTRATE (`classifyEntrata`, ramo banca)
1. **Stripe** (descrizione contiene `STRIPE`) → COGE `30.03.003`, BU **3**, IVA **0%**,
   metodo override `ALVEARE_STRIPE`.
2. Altrimenti gli incassi bancari **non** usano l'alias fornitori (eviterebbe di mappare un ricavo
   su un conto di costo). Quindi:
   - causale `ZI0` → **FORNITORE_NON_RICONOSCIUTO**
   - altrimenti → **COGE_NON_DETERMINABILE** (revisione manuale).

### Regole di mapping — USCITE (`classifyUscita`)
Variabili: `causale` (uppercase), `sdd` = causale contiene `SDD` o `= PAGAMENTO UTENZE`.

1. **Commissioni POS Nexi** (descrizione contiene `NEXI` e (`sdd` o causale `= COMMISSIONI/SPESE`)):
   COGE `40.02.001`, BU **5**; fornitore da alias se trovato.
2. **Spese bancarie** (descrizione contiene `BOLLO E/C` o `CANONE` o `COMMISSIONI`):
   COGE `40.02.002`, BU **5**.
3. **Causale `COMMISSIONI/SPESE`** (fallback): COGE `40.02.002`, BU **5**.
4. **Fallback alias fornitore** (RIBA `EFFETTI RITIRATI/RICHIAMATI` e generici): se l'alias matcha
   **e** il fornitore ha `bu_default_id` e `coge_default_id` → usa quelli + fornitore.
5. Nessun match → **FORNITORE_NON_RICONOSCIUTO**.

> Nota: oggi solo CA genera uscite (BPM è quasi sempre entrata/giroconto), ma `classifyUscita`
> è agnostico alla fonte.

---

## Fonte 3 — Banco BPM (estratto conto)

**Formato file:** CSV `;`, UTF-8, `\r\r\n` → `\n`. Prima riga = header (saltata).
**Parser:** `BancaBpmParser`.
**Header reale:** `Data contabile;Data valuta;CHECK;chiave;Importo;Divisa;Causale;Banca;Descrizione;Canale`

### Regole di parsing
- Salta header e righe vuote. Accesso per indice (robusto a `chiave` lowercase).
- Colonne: `DATA_CONTABILE`(0), `DATA_VALUTA`(1), `CHECK`(2), `CHIAVE`(3), `IMPORTO`(4), `DIVISA`(5),
  `CAUSALE`(6), `BANCA`(7), `DESCRIZIONE`(8), `CANALE`(9).

### Regole di normalizzazione (`normalizeBpm`)
- Conto bancario sempre **1** (Banco BPM). Fonte `IMPORT_BANCA`. Tipo sempre **ENTRATA**, importo `abs`.
- Data: `DATA_CONTABILE`, formato `dd/MM/yyyy`. Importo formato italiano via `parseEuroAmount`.
- **Metodo pagamento + giroconto** dalla `CAUSALE` (codice numerico):
  | CAUSALE | Metodo | Note |
  |---------|--------|------|
  | `480` | `BONIFICO` | se descrizione contiene `SOCIETA AGRICOLA AGOSTINELLI` (apostrofi rimossi) → **GIROCONTO_SKIP** (trasferimento interno CA→BPM) |
  | `090`, `092` | `POS_BPM` | |
  | `349` | `POS_BPM` | |
  | `ZI0` | `BONIFICO` | |
  | `78A` | — | **GIROCONTO_SKIP** (versamento contante ATM) |
  | altro | `null` | → **CAUSALE_NON_MAPPATA** |
- **Data competenza (Stripe):** se la descrizione contiene `POyyyymmdd` (regex `PO(\d{4})(\d{2})(\d{2})`),
  quella data diventa `dataCompetenza`.
- **Riferimento esterno (dedup):** `CHIAVE + "-" + primi 20 char descrizione`.

### Regole di mapping
BPM produce **solo ENTRATE** → segue lo stesso `classifyEntrata` ramo banca della fonte CA:
- **Stripe** → COGE `30.03.003`, BU **3**, IVA 0%, metodo `ALVEARE_STRIPE`;
- altrimenti → **COGE_NON_DETERMINABILE** (o **FORNITORE_NON_RICONOSCIUTO** se causale `ZI0`).

Le righe con `GIROCONTO_SKIP` (causali `480` interno / `78A`) **non** diventano movimenti: vanno in
`import_ambiguita` con motivo `GIROCONTO_SKIP` (tracciate, non sono errori).

---

## Validazione invarianti (comune)

Applicata dopo la classificazione, prima della persistenza (`MovimentoMappingEngineImpl.validate()`).
Il **primo** controllo che fallisce determina il motivo di ambiguità:

| Controllo | Motivo se fallisce |
|-----------|--------------------|
| importo presente e > 0 | `IMPORTO_NON_POSITIVO` |
| data presente | `DATA_MANCANTE` |
| data ≤ oggi | `DATA_FUTURA` |
| data ≥ 2023-01-01 | `DATA_TROPPO_VECCHIA` |
| conto bancario identificato | `BANCA_NON_IDENTIFICATA` |
| metodo identificato (codice→ID) | `CAUSALE_NON_MAPPATA` (banca, metodo nullo) / `METODO_NON_IDENTIFICATO` |
| COGE determinato | `COGE_NON_DETERMINABILE` |
| BU determinata | `BU_AMBIGUA` |

Movimento creato con: `dataFinanziaria = dataMovimento` (già liquidato), `dataLiquidita` auto,
`importoCommissione = 0`. Persistito via `MovimentiService.createMovimentoImport()`.

---

## Deduplica e persistenza

- Prima dell'import si caricano i `riferimento_esterno` già presenti per quella fonte
  (`repo.findRifimentiEsterniByFonte`).
- Una riga `SUCCESS` con `riferimento_esterno` già esistente → **duplicato**, saltata (non ri-salvata).
- I nuovi rif vengono aggiunti al set in memoria per deduplicare anche all'interno dello stesso file.
- Esiti possibili per riga: **importata** | **duplicata** | **ambigua** | **errore** (eccezione).
- Stato finale import_log:
  - `ERRORE` se errori > 50% delle righe;
  - `COMPLETATO_CON_AMBIGUITA` se ci sono ambigue o errori;
  - `COMPLETATO` altrimenti.
- Materialized view + cache dashboard invalidate **una sola volta** a fine import (se ≥ 1 importata).

---

## Motivi di ambiguità (revisione manuale)

Le righe non classificabili finiscono in `import_ambiguita` (stato `DA_CLASSIFICARE`) con `raw_data`
JSON della riga originale, per riclassificazione manuale dall'operatore.

| Motivo | Significato |
|--------|-------------|
| `BANCA_NON_IDENTIFICATA` | conto bancario non riconosciuto dalla colonna BANCA (Billy) |
| `METODO_NON_IDENTIFICATO` | metodo pagamento incoerente / non risolvibile a ID |
| `CAUSALE_NON_MAPPATA` | causale banca non presente nella tabella metodi (BPM/CA) |
| `COGE_NON_DETERMINABILE` | nessuna regola COGE deterministica (es. incasso banca non-Stripe) |
| `BU_AMBIGUA` | BU non determinabile o riga mista (es. evento + agriturismo) |
| `EVENTO_NON_TROVATO` | keyword evento ma nessun (o più d'un) evento CONFERMATO alla data |
| `FORNITORE_NON_RICONOSCIUTO` | uscita senza alias fornitore valido |
| `GIROCONTO_SKIP` | trasferimento interno: tracciato ma non è un movimento |
| `IMPORTO_NON_POSITIVO` / `DATA_MANCANTE` / `DATA_FUTURA` / `DATA_TROPPO_VECCHIA` | invarianti violate |

---

## Dove modificare le regole (mappa rapida)

| Vuoi cambiare… | File |
|----------------|------|
| Colonne lette / filtro righe | `importlayer/parser/{BillyParser,BancaCaParser,BancaBpmParser}.java` |
| Conto/metodo da banca o causale, parsing date/importi, giroconti | `importlayer/MovimentoNormalizerImpl.java` |
| Regole COGE/BU/IVA, eventi, alias fornitori, invarianti | `importlayer/MovimentoMappingEngineImpl.java` |
| Codici COGE / metodi / conti / BU | migration `V5`, `V6`, `V8` |
| Pattern alias fornitori | tabella `fornitore_alias_matching` (seed in `V6` + `V8`) |
| Schema tabella ambiguità / motivi | migration `V34` |
| Orchestrazione, dedup, stato import | `importlayer/MovimentoImportService.java` |
| Dispatch fonte→parser/normalizer | `importlayer/{ImportStrategy,ImportStrategyFactory,BillyImportStrategy,BancaImportStrategy}.java` |
| Endpoint REST import | `resource/MovimentiResource.java` (`/import/{billy,bpm,ca}`) |
