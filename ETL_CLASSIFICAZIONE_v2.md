# ETL Import — Regole di classificazione e catalogazione intelligente (v2)

> Documento infrastrutturale. Definisce il **nuovo motore di classificazione** dei movimenti
> importati da Billy / Crédit Agricole / Banco BPM, con l'obiettivo di **minimizzare le ambiguità**
> e di **catalogare in modo assistito e auto-apprendente** ciò che resta incerto (inclusi i fornitori).
>
> Sostituisce concettualmente la logica binaria `SUCCESS / AMBIGUOUS` descritta in
> `IMPORT_BULK_REGOLE.md` (che resta valido come fotografia dell'implementazione attuale, v1).
>
> **Principio guida:** *nessun dato finanziario va perso e nessuna riga viene "indovinata".*
> Ogni riga o viene classificata con alta confidenza, o viene **registrata in forma neutra** e messa
> in coda di raffinamento, o viene **scartata in modo tracciato e reversibile**.
> **Eccezione (v2.1):** le **spese ricorrenti/finanziamenti** sono gestite da un modulo dedicato e
> vengono **eliminate in import senza essere salvate** (vedi §4 A3) — unica categoria non persistita.

### Verifica requisiti operativi (v2.1)
| # | Requisito utente | Dove è applicato |
|---|------------------|------------------|
| R1 | Pagamenti POS sui conti banca → **filtrati** (sono già in Billy) | §4 A1, §10.2/10.3 pri.10-12 |
| R2 | **Commissioni** dai conti banca → **tenute** (Billy è al lordo) | §6 C2, §10.2 pri.300-310 |
| R3 | Movimenti **Stripe** = Alveare → **taggare la descrizione** del movimento | §6 C1, §10.2/10.3 pri.200 |
| R4 | Movimenti **eventi** (keyword evento/laurea/matrimonio/…; **+ Billy col `Agriturismo>0`**) → **bucket separato**, non eliminati, **deduplicati cross-sorgente** | §5, §9.3, §10.1 |
| R5 | **Spese ricorrenti** (mutui, affitti, canoni, canoni mensili, commissioni fisse mensili POS Nexi, bollo, assicurazioni, finanziamenti) → **filtrate ed eliminate** (altro modulo) | §4 A3 |

> ⚠️ R2 vs R5 hanno una **zona di sovrapposizione** sulle commissioni Nexi (variabili da tenere vs
> canone fisso da eliminare): risolta in §6 C2 / §4 A3, con i **punti da confermare** in §15.

---

## Indice
1. [Obiettivi e principi](#1-obiettivi-e-principi)
2. [Architettura: pipeline a gate sequenziali](#2-architettura-pipeline-a-gate-sequenziali)
3. [Normalizzazione del testo (anti-wordwrap)](#3-normalizzazione-del-testo-anti-wordwrap)
4. [Gate A — Esclusioni deterministiche (SKIP)](#4-gate-a--esclusioni-deterministiche-skip)
5. [Gate B — Parcheggio eventi (PARK_EVENTO, riusabile)](#5-gate-b--parcheggio-eventi-park_evento-riusabile)
6. [Gate C — Classificazione positiva con confidence](#6-gate-c--classificazione-positiva-con-confidence)
7. [Motore fornitori intelligente](#7-motore-fornitori-intelligente)
8. [Motore di catalogazione delle ambiguità (triage)](#8-motore-di-catalogazione-delle-ambiguità-triage)
9. [Regole data-driven: schema e tabelle](#9-regole-data-driven-schema-e-tabelle)
10. [Catalogo regole per sorgente](#10-catalogo-regole-per-sorgente)
11. [Nuovi conti COGE da introdurre](#11-nuovi-conti-coge-da-introdurre)
12. [Piano di rollout per fasi](#12-piano-di-rollout-per-fasi)
13. [KPI di qualità della classificazione](#13-kpi-di-qualità-della-classificazione)
14. [Glossario esiti / motivi](#14-glossario-esiti--motivi)
15. [Decisioni confermate (v2.2)](#15-decisioni-confermate-v22)
16. [Spese ricorrenti e finanziamenti da creare nel modulo dedicato](#16-spese-ricorrenti-e-finanziamenti-da-creare-nel-modulo-dedicato)
17. [Interventi necessari oltre alla logica di import (checklist)](#17-interventi-necessari-oltre-alla-logica-di-import-checklist)

---

## 1. Obiettivi e principi

| # | Principio | Conseguenza progettuale |
|---|-----------|-------------------------|
| P1 | **Niente duplicati** | POS e Satispay arrivano già da Billy → esclusi a monte dalle banche |
| P2 | **Niente perdita di dato** | Una riga mai "scartata silenziosamente": o movimento, o SKIP tracciato, o coda di revisione |
| P3 | **Niente indovinare** | Sotto soglia di confidenza → si registra in forma neutra (COGE "da classificare"), non si forza |
| P4 | **Il fornitore arricchisce, non blocca** | L'assenza di fornitore non genera più ambiguità (vedi §7) |
| P5 | **Eventi separati ma riusabili** | Le voci evento vanno in un bucket dedicato, pronto per la futura riconciliazione manuale |
| P6 | **Regole modificabili senza deploy** | Il set di regole vive in tabella DB, non nel codice (vedi §9) |
| P7 | **Il sistema impara** | Ogni classificazione manuale genera alias/regole riusabili all'import successivo (vedi §7-8) |

---

## 2. Architettura: pipeline a gate sequenziali

Ogni riga normalizzata attraversa **gate in ordine**. Il primo gate che "cattura" la riga ne
determina l'esito; i successivi non vengono valutati. Questo rende il comportamento prevedibile e
facile da modificare (basta cambiare l'ordine/priorità delle regole in tabella).

```
RawRow
  │  parse + normalize (formato) + NORMALIZZAZIONE TESTO (§3)
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│ GATE A — ESCLUSIONI DETERMINISTICHE (SKIP, tracciate)                 │
│   A1 POS / Satispay  → SKIP_POS            (duplicato Billy)          │
│   A2 Giroconto interno (simmetrico CA↔BPM, ATM) → SKIP_GIROCONTO      │
│   A3 Spese ricorrenti (match con recurring_expenses) → SKIP_RICORRENTE│
└───────────────┬─────────────────────────────────────────────────────┘
                │ (non catturata)
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ GATE B — EVENTI                                                       │
│   keyword evento (normalizzate) → PARK_EVENTO (bucket riusabile)      │
└───────────────┬─────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ GATE C — CLASSIFICAZIONE POSITIVA (con confidence 0..1)              │
│   C1 Ricavi deterministici (Stripe, Billy per colonna)               │
│   C2 Costi bancari (bolli, canoni, commissioni)                      │
│   C3 Fornitori (rubrica IBAN → alias token → fuzzy)  [non bloccante]  │
│   C4 Contributi / finanziamenti / soci (partite speciali)            │
│                                                                       │
│   confidence ≥ 0.90 → BOOK (movimento)                               │
│   0.60 ≤ conf < 0.90 → BOOK + flag "verifica suggerita"             │
│   confidence < 0.60 → coda TRIAGE con suggerimenti (§8)              │
└─────────────────────────────────────────────────────────────────────┘
```

**Differenza chiave rispetto a v1:** sparisce il "tutto o niente". Una riga senza fornitore o senza
COGE certo **non** finisce più in `AMBIGUOUS`: viene **registrata** con conto transitorio "da
classificare" e marcata per il triage. Il dato c'è (saldi e cassa corretti), la rifinitura è
asincrona.

---

## 3. Normalizzazione del testo (anti-wordwrap)

**Problema reale (dai file):** gli estratti conto spezzano e incollano le parole con a-capo "hard":

| Nel file | Significato | Effetto su `CONTAINS` |
|----------|-------------|------------------------|
| `AFFIT TO SALA` | affitto sala | manca `AFFITTO` |
| `ACCON TO`, `EVEN TO`, `EVE NTO` | acconto / evento | manca la keyword |
| `CAPARRAEVENTO`, `CAPARRACONFIRMATORIA` | parole incollate | manca `EVENTO` isolato |
| `GIORG IA CUOMO` | nome spezzato | rompe il match nominativo |

**Soluzione — funzione `normalizeDesc()` applicata prima di ogni matching keyword/fornitore:**

1. `UPPERCASE` + trim.
2. Rimozione caratteri di controllo e `<*>`, `<`, `>`, `*`, doppi apostrofi.
3. Collasso degli spazi multipli in uno.
4. Generazione di **due viste** della descrizione su cui fare match:
   - **vista "spaziata"**: testo con spazi singoli (per match di frasi: `INCASSO POS`, `ACCREDITO POS`).
   - **vista "compatta"**: testo con **tutti gli spazi rimossi** (per keyword spezzate/incollate:
     `AFFITTO`, `EVENTO`, `CAPARRA`, `MATRIMONIO`).
5. Le regole keyword dichiarano su quale vista cercare (`SPACED` | `COMPACT`).

> Esempio: keyword evento `EVENTO` cercata su vista COMPACT cattura sia `CAPARRAEVENTO` sia
> `CAPARRA EVE NTO` (→ `CAPARRAEVENTO`/`CAPARRAEVE NTO`→`CAPARRAEVENTO`). La frase `INCASSO POS`
> si cerca su vista SPACED per evitare falsi incollamenti.

**Estrazione entità (parser semantico della descrizione):**

| Entità | Pattern (regex, su testo normalizzato) | Uso |
|--------|----------------------------------------|-----|
| **IBAN controparte** | `IBAN:?\s*(IT\d{2}[A-Z0-9]{10,30})` | chiave forte rubrica (§7) |
| **Ordinante (entrata)** | `ORD:\s*(.+?)\s+DT\.ORD` (CA) · `BON\.?DA\s+(.+?)\s+(STRIPE|SATISPAY|…|$)` (BPM) | nome controparte |
| **Beneficiario (uscita)** | `SOCIET[A']?\s+AGRICOLA\s+AGO\w*\s*\d+\s+(.+?)\s+(RIF|V/ORDINE|DESCR)` (CA) | nome fornitore |
| **Data evento** | `(\d{1,2})[/.\- ]?(\d{1,2})[/.\- ]?(\d{2,4})` qualificata da keyword evento | abbinamento evento |
| **Codice Stripe** | `PO(\d{4})(\d{2})(\d{2})` | data competenza |
| **Chiave Aggancio** | campo `Chiave`/`Chiave Aggancio` (formato `numeroMovBanca/importo`) | **chiave di dedup cross-sorgente** Billy↔CA↔BPM (vedi §5) |

Le entità estratte vengono salvate sulla riga (anche se va in triage), così il motore di
suggerimento e l'apprendimento le riusano.

---

## 4. Gate A — Esclusioni deterministiche (SKIP)

Esiti che **non** generano movimento. Due livelli di scarto:
- **Tracciato/reversibile** (`SKIP_POS`, `SKIP_GIROCONTO`): registrato in `import_scartati` con
  motivo e `raw_data`, per audit ed eventuale ripescaggio. Non sono errori.
- **Non contabilizzato, traccia leggera** (`SKIP_RICORRENTE`): righe gestite da un **modulo
  dedicato** → **nessun movimento**, ma **conteggio in `import_log` + traccia minima** (data,
  importo, causale, motivo) in `import_scartati` per ispezione (decisione Q5). Vedi A3.

### A1 — POS e Satispay (duplicati di Billy) → `SKIP_POS`
Gli incassi elettronici sono già registrati per scontrino in Billy. Dalle banche vanno esclusi.

| Sorgente | Condizione |
|----------|------------|
| CA | causale `INCASSO TRAMITE POS` **oppure** desc contiene `INCASSO POS` / `NUMIA` / `ACCREDITO POS` |
| BPM | causale `090` / `092` **oppure** desc contiene `INCAS. TRAMITE P.O.S` / `INC.POS` / `NUMIA` |
| BPM | desc contiene `NEXI` **e** `ACCREDITO POS` (POS che arriva come bonifico, causale `480`) |
| BPM/CA | desc contiene `SATISPAY EUROPE` (payout Satispay: vendite già in Billy) |

> **Nota 1 — Stripe NON è escluso:** è e-commerce/Alveare, non passa da Billy, quindi in banca
> rappresenta l'unico ricavo reale (vedi C1). Discriminante: `STRIPE` resta ricavo, `NUMIA`/`NEXI
> POS`/`SATISPAY` sono duplicati.
>
> **Nota 2 — le commissioni NON sono incassi POS (R2):** qui si filtra solo l'**incasso** POS
> (duplicato di Billy). La **commissione** addebitata dalla banca **resta** e va a costo (§6 C2),
> perché Billy registra al lordo. Non confondere `INCASSO POS` (elimina) con `COMMISSIONI…` (tieni).

### A2 — Giroconti interni (simmetrici) → `SKIP_GIROCONTO`
Trasferimenti tra conti propri: non sono né ricavi né costi.

| Caso | Sorgente | Condizione |
|------|----------|------------|
| Versamento contante ATM | BPM | causale `78A` |
| CA → BPM (entrata su BPM) | BPM | causale `480` + desc contiene `SOCIETA AGRICOLA AGOSTINELLI` |
| **CA → BPM (uscita su CA)** ⚠️ nuovo | CA | causale `DISPOSIZIONE DI PAGAMENTO` + beneficiario contiene `SOCIETA AGRICOLA AGOSTINELLI` (spesso `…AGOSTINELLI SRL BPM`) |

> ⚠️ In v1 era rilevato solo il lato BPM in entrata; il lato CA in uscita (`−5000/−10000`) diventava
> un falso costo. La regola va resa **simmetrica**.

### A3 — Spese ricorrenti e finanziamenti → `SKIP_RICORRENTE` (ELIMINATE, non salvate)
**Gestite da un modulo dedicato**, quindi **non diventano movimenti** (requisito R5). Si conserva una
**traccia leggera** in `import_scartati` (data, importo, causale, motivo) e un **conteggio** in
`import_log` (decisione Q5), così resta ispezionabile cosa è stato escluso senza sporcare la
contabilità.

**Cosa rientra (R5):** mutui, affitti, canoni e **canoni mensili** (`CANONE NOWBANKING`,
`CANONE … CARTA`), **commissioni fisse mensili** (es. canone fisso POS Nexi), **qualsiasi bollo**
(bollo c/c `BOLLO E/C` e bollo auto), assicurazioni, **finanziamenti** — sia le **rate** in uscita
sia l'**erogazione** una-tantum in entrata (vedi §15 Q2 e §16).

**Meccanismo (no keyword cieche):** match della riga bancaria contro le ricorrenze **attive** del
modulo dedicato per:
- importo (± tolleranza), e
- finestra temporale attesa (cadenza), e
- controparte/causale (IBAN o token beneficiario, quando disponibile).

In assenza di un match strutturato, fallback keyword **editabile**:
`CANONE`, `ASSICURAZ`, `POLIZZA`, `RATA`, `MUTUO`, `LEASING`, `AFFITTO` (non-evento), `BOLLO`,
`FINANZIAMENTO`.

> ⚠️ **Confine con le commissioni da tenere (R2).** Le commissioni che riconciliano il lordo Billy →
> netto **restano** (§6 C2): commissioni **per-operazione** (`COMMISSIONI SU BONIFICO`,
> `COMMIS.SU ADDEB. SEPA`, `COMMISSIONI ADDEBITO UTENZE`) e la commissione POS Nexi **variabile**
> (`COMM NEXI EC`, importo che varia mese su mese). Va in A3 (eliminata) solo l'eventuale **canone
> POS fisso** (importo costante). I **canoni** bancari e `BOLLO E/C` (imposta di bollo c/c) sono
> periodici fissi → A3. Casi dubbi → §15.

---

## 5. Gate B — Parcheggio eventi (`PARK_EVENTO`, riusabile)

Tutte le voci legate a eventi vengono **separate** (non diventano movimenti ora) e parcheggiate in
una coda dedicata `eventi_da_riconciliare`, pronta per la futura evolutiva di riconciliazione
manuale con l'anagrafica eventi. **Non si implementa ora la riconciliazione**, solo il parcheggio
strutturato.

### Riconoscimento robusto (anti-falsi-positivi)
Una riga è "evento" se, sulla **vista COMPACT**, contiene **almeno una keyword forte** OPPURE
(**keyword debole** + **contesto evento**).

| Tipo | Keyword |
|------|---------|
| **Forti** (da sole bastano) | `MATRIMONIO`, `BATTESIMO`, `CRESIMA`, `COMUNIONE`, `COMPLEANNO`, `ANNIVERSARIO`, `CERIMONIA`, `DICIOTTESIMO`, `18ESIMO`, `18ANNI`, `GENDER`, `LAUREA`, `AFFITTOSALA`, `CAPARRA` |
| **Deboli** (servono + contesto) | `ACCONTO`, `SALDO`, `AFFITTO`, `EVENTO`, `FESTA` |
| **Contesto evento** | presenza di una **data futura** estratta + entrata via `GIROCONTO/BONIFICO`/`480`/`ZI0`; oppure nome persona fisica ordinante |

### ⚠️ Falsi positivi da escludere esplicitamente
Le keyword deboli `SALDO`/`ACCONTO` compaiono anche in contesti **non-evento** che NON vanno
parcheggiati:

| Falso positivo | Esempio reale | Trattamento corretto |
|----------------|---------------|----------------------|
| `SALDO FATTURA` (fornitore) | "Grand Hotel … SALDO FATTURA al netto nota credito" | nota credito/rimborso (C4) |
| `SALDO DOCUM` (utenza) | "TELEPASS … SALDO DOCUM…" (SDD) | costo utenza/pedaggi (C3) |
| `ANTICIPO FATT` | "Briccola gi anticipo fatt nr 33" | ricavo da classificare (triage) |

→ Regola: keyword debole `SALDO`/`ACCONTO` **annullata** se la riga contiene anche `FATTURA`,
`FATT`, `DOCUM`, `NOTA CREDITO`, o è una **uscita** verso società (suffissi `SRL/SPA/SNC/SAS`).

### Sorgenti del parcheggio e dedup cross-sorgente
Gli eventi entrano nel parcheggio da **due vie**:
1. **Billy** — colonna `Agriturismo > 0` (vedi §10.1): nel registratore di cassa gli incassi-evento
   sono marcati su questa colonna, non per keyword.
2. **CA / BPM** — keyword evento sulla descrizione (forti, o deboli + contesto).

> ⚠️ **Lo stesso incasso-evento compare spesso in entrambe** (l'operatore lo registra su Billy *e* lo
> ritrova sull'estratto conto): nel campione **29 righe su 54** sono in questa condizione. Per non
> duplicare, il parcheggio **deduplica sulla `Chiave Aggancio`** (formato `numeroMovBanca/importo`,
> es. `46043/500`), che è **condivisa** tra Billy, CA e BPM. A parità di chiave si tiene **una** riga
> (preferendo quella con la descrizione più ricca, tipicamente l'estratto conto).

### Dati conservati nel parcheggio
Per ogni riga parcheggiata si salvano: **`Chiave Aggancio`** (dedup), data movimento, importo, **data
evento estratta**, **nome ordinante**, **IBAN**, tipo presunto (`CAPARRA`/`ACCONTO`/`SALDO`),
descrizione normalizzata, fonte. Così la futura riconciliazione potrà fare auto-match con
`eventi.data_evento` + nominativo.

---

## 6. Gate C — Classificazione positiva con confidence

Ogni riga sopravvissuta ai gate A/B viene classificata con un **punteggio di confidenza** e una
lista di **segnali** che l'hanno determinata (per spiegabilità e debug).

### C1 — Ricavi deterministici (confidence 1.0)
| Regola | COGE | BU | IVA | Metodo | Note movimento |
|--------|------|----|----|--------|----------------|
| Billy `AGRITURISMO` > 0 **solo se incasso POS** (altrimenti → PARK_EVENTO, §10.1) | `30.01.001` | 1 | 10% | da Billy | carve-out POS |
| Billy colonna CARNE_10 > 0 | `30.03.001` | 3 | 10% | da Billy | — |
| Billy colonna ORTOFRUTTA_4 > 0 | `30.03.002` | 3 | 4% | da Billy | — |
| Banca desc contiene `STRIPE` | `30.03.003` | 3 | 0% | `ALVEARE_STRIPE` | **tag Alveare** (R3) |

> **R3 — Tag Alveare.** I movimenti Stripe sui conti banca sono **incassi Alveare**. Oltre a
> conto = 5 (Stripe/Alveare) e metodo `ALVEARE_STRIPE`, l'origine va resa **esplicita nel movimento**:
> - prefisso nella **descrizione**: `"[ALVEARE] " + descrizione originale`, e
> - campo `note` = `"Incasso Alveare (Stripe)"`.
>
> Così l'origine Alveare è leggibile a colpo d'occhio nell'elenco movimenti e filtrabile nei report.
> (Forma del tag da confermare → §15 Q4.)

### C2 — Commissioni bancarie da TENERE (confidence 1.0) — requisito R2
Billy registra gli incassi al **lordo**; le commissioni addebitate dalla banca servono a riconciliare
verso il **netto**, quindi vanno mantenute come costo. Rientrano qui **solo** le commissioni
**per-operazione / variabili** (NON i canoni fissi, che vanno in §4 A3 ed eliminati).

| Regola (desc, vista SPACED) | COGE | BU |
|------|------|----|
| `COMMISSIONI SU BONIFICO` · `COMMIS.SU ADDEB. SEPA` · `COMMISSIONI ADDEBITO UTENZE` (per-operazione) | `40.02.002` Spese conto | 5 |
| `NEXI` + `COMM NEXI EC` (commissione POS volume-based, importo variabile) | `40.02.001` Commissioni POS | 5 |

> **Spostati in §4 A3 (eliminati):** `CANONE NOWBANKING`, `CANONE … CARTA`, `BOLLO E/C` e l'eventuale
> **canone POS fisso** — sono periodici fissi gestiti dal modulo ricorrenti (R5).

### C3 — Fornitori / utenze (confidence variabile) — **non bloccante**
Vedi §7. L'esito dipende dal grado di match:
- match su **IBAN noto** → confidence 0.95, COGE/BU/fornitore dalla rubrica.
- match su **alias token esatto** → 0.85.
- match **fuzzy** (sotto soglia) → 0.5 → triage con suggerimento.
- nessun match → **si registra comunque** come costo su COGE transitorio `49.99.999` "Costi da
  classificare", BU 5, fornitore null, e si invia al triage (NON più `FORNITORE_NON_RICONOSCIUTO`
  che scartava).

### C4 — Partite speciali (entrate non operative) — confidence alta ma COGE dedicato
Casi reali importanti (spesso di importo elevato) che non sono ricavi operativi:

| Pattern desc | Natura | COGE proposto (vedi §11) |
|--------------|--------|--------------------------|
| `ORGANISMO PAGATORE` · `AGEA` · `PAC` · `REGIME DI PAGAMENTO UNICO` · `REGIONE LOMBARDIA … MAND` | Contributo pubblico | `30.04.001` Contributi/PAC |
| `VERSAMENTO SOCIO` · `VERSAMENTO SOCI` | Apporto soci (patrimoniale) | `90.02.001` Versamenti soci |
| `RIBA` · `PAGAM. RIBA` · `PAG.EFF` (uscita) | Pagamento fornitore via RIBA | `49.99.999` + riconcilia con scadenzario |

> Queste regole evitano che ~82.375 € di PAC finiscano nel calderone `COGE_NON_DETERMINABILE`.
>
> ⚠️ **Finanziamenti (R5, deciso Q2).** Sia le **rate** in uscita sia l'**erogazione** una-tantum in
> entrata (es. `ASCONFIDI … EROGAZIONE FINANZIAMENTO` +38.800) vengono **escluse dall'import** (§4 A3)
> e gestite dal modulo dedicato. **Attenzione:** oggi quel modulo **non** genera il movimento di
> erogazione → finché non viene esteso (§15 TODO, §16) l'erogazione va inserita a mano per non
> sbilanciare il saldo banca.

---

## 7. Motore fornitori intelligente

Obiettivo: **massimizzare l'attribuzione automatica del fornitore senza mai bloccare** la
registrazione del movimento. Il fornitore diventa un **arricchimento progressivo**.

### 7.1 Gerarchia di matching (dal più forte al più debole)
1. **IBAN** della controparte → `controparti.iban` (chiave forte e stabile; l'IBAN è presente in
   quasi tutte le descrizioni CA e in molte BPM). Match → fornitore + COGE/BU di default.
2. **Token esatto**: il nome beneficiario/ordinante estratto viene **tokenizzato** (rimozione di
   `SRL/SPA/SNC/SAS/DI/E/&/RIF/CRO/…`) e confrontato con gli alias normalizzati. Risolve i casi che
   oggi falliscono per spazi/forme: `GRUPPO ITALIANOVINI` ↔ `GRUPPO ITALIANO VINI`, `ORMA BIANCA`
   distinta da `ORMA BIRRA`.
3. **Fuzzy** (similarità, es. trigrammi/Levenshtein normalizzato ≥ soglia): genera **suggerimenti**
   per il triage, non auto-book (per evitare attribuzioni sbagliate).
4. **Nessun match** → movimento su COGE transitorio (vedi C3), fornitore null, → triage.

### 7.2 Rubrica controparti (cervello del sistema)
Nuova tabella `controparti`: mappa `(nome_normalizzato | IBAN)` → `(fornitore_id, tipo, coge_default,
bu_default)`. Si popola in tre modi:
- seed iniziale dai fornitori esistenti (V6/V8);
- **apprendimento automatico** dalle classificazioni manuali (§7.3);
- import massivo opzionale di un'anagrafica clienti/fornitori.

`tipo ∈ {FORNITORE, CLIENTE, SOCIO, ENTE_PUBBLICO, BANCA, INTERNO, PERSONALE}` — permette di
indirizzare la riga al COGE/BU corretto anche quando non è un "fornitore merci".

### 7.3 Auto-apprendimento alias
Quando l'operatore classifica una riga in triage assegnandole un fornitore:
1. si crea/aggiorna una riga in `controparti` con l'**IBAN** estratto (match perfetto al prossimo giro);
2. si genera un **pattern alias** dal token nome (`fornitore_alias_matching`, `match_type=CONTAINS`);
3. al prossimo import `refreshLookups()` ricarica già le nuove regole (comportamento attuale) →
   le righe analoghe vengono **auto-catalogate** senza più passare dal triage.

> Effetto: il tasso di ambiguità **decresce ad ogni import**. I primi 1-2 estratti conto richiedono
> più lavoro manuale, poi il sistema converge.

### 7.4 Distinzione società vs persona fisica
Euristica per indirizzare il suggerimento (non per auto-book):
- beneficiario con suffisso `SRL/SPA/SNC/SAS/SOCIETA/AZIENDA AGRICOLA/SAS DI` → candidato
  **FORNITORE** (costo merci/servizi).
- beneficiario `NOME COGNOME` senza suffisso, importo "stipendio-like", ricorrente mensile →
  candidato **PERSONALE/manodopera** (es. Maggioni, Giordano, Ciobanu, Bernasconi, Pischeddu).

---

## 8. Motore di catalogazione delle ambiguità (triage)

La coda di revisione non è una lista passiva: è un **assistente che propone** e **impara**.

### 8.1 Stati della riga in triage
`DA_CLASSIFICARE → (SUGGERITO) → CLASSIFICATO | SCARTATO | PARCHEGGIATO_EVENTO`

### 8.2 Suggerimenti automatici (per ogni riga in coda)
Il sistema calcola e mostra i **top-3 candidati** ordinati per confidenza, con motivazione:
- controparti con IBAN/nome simile (rubrica);
- COGE usati storicamente per quella controparte o per descrizioni simili;
- BU dedotta dal COGE candidato;
- importo/cadenza compatibili con una ricorrenza nota.

L'operatore conferma con **un click** (o corregge). La conferma:
- crea il movimento (sostituendo il conto transitorio),
- alimenta la rubrica e gli alias (§7.3),
- opzionalmente crea una **regola** persistente (§9) se l'operatore spunta "applica sempre".

### 8.3 Azioni massive
- "Classifica tutti i simili a questo" (stessa controparte/IBAN) → batch.
- "Tutti i RIBA → costo fornitori generico in attesa di scadenzario".
- "Tutti i `progetto kairos` → COGE X" (una volta deciso).

### 8.4 Coda eventi separata
Le righe `PARK_EVENTO` vivono in una coda distinta (`eventi_da_riconciliare`), non mescolata ai
costi da classificare, perché il loro workflow (abbinamento all'anagrafica eventi) è diverso e
arriverà con l'evolutiva dedicata.

---

## 9. Regole data-driven: schema e tabelle

Per soddisfare il requisito "**voglio poter modificare e aggiungere regole**", il set di regole
**esce dal codice** e vive in tabella. Il codice diventa un **motore generico** che valuta le regole
in ordine di priorità.

### 9.1 `regole_classificazione`
| Colonna | Tipo | Note |
|---------|------|------|
| `id` | serial | |
| `priorita` | int | ordine di valutazione (gate A prima di B prima di C) |
| `sorgente` | varchar | `BILLY` / `CA` / `BPM` / `*` |
| `tipo_movimento` | varchar | `ENTRATA` / `USCITA` / `*` |
| `campo` | varchar | `CAUSALE` / `DESC_SPACED` / `DESC_COMPACT` / `IBAN` / `COL_BILLY` |
| `match_type` | varchar | `EQUALS` / `CONTAINS` / `STARTS_WITH` / `REGEX` / `IN_LIST` |
| `pattern` | text | valore o regex |
| `azione` | varchar | `SKIP_POS` / `SKIP_GIROCONTO` / `SKIP_RICORRENTE` / `PARK_EVENTO` / `MAP` / `MAP_FORNITORE` |
| `coge_codice` | varchar | per azione `MAP` |
| `bu_id` | smallint | per azione `MAP` |
| `metodo_codice` | varchar | opzionale override |
| `confidence` | numeric | 0..1 |
| `attivo` | bool | |
| `note` | text | spiegazione umana della regola |

Il seed iniziale di questa tabella è il **§10** qui sotto. L'utente aggiunge/modifica righe da UI o
SQL senza redeploy.

### 9.2 `controparti` (rubrica) — vedi §7.2
### 9.3 `eventi_da_riconciliare` — coda PARK_EVENTO (§5), predisposta per la futura riconciliazione
Base dati dedicata ai movimenti-evento messi da parte (R4). **Non** sono movimenti contabili finché
non verranno riconciliati dall'evolutiva futura; si conserva tutto il necessario per quel passo.

| Colonna | Tipo | Note |
|---------|------|------|
| `id` | UUID PK | |
| `import_log_id` | UUID FK | import di provenienza |
| `fonte` | varchar | `CA` / `BPM` / `BILLY` |
| `chiave_aggancio` | varchar | **dedup cross-sorgente** (`numeroMovBanca/importo`) — UNIQUE logico per evitare doppioni Billy/CA/BPM |
| `data_movimento` | date | |
| `importo` | numeric(14,2) | sempre > 0 |
| `tipo` | varchar | `ENTRATA` / `USCITA` |
| `conto_bancario_id` | smallint | 1/2/… |
| `descrizione_norm` | text | descrizione normalizzata |
| `tipo_evento_presunto` | varchar | `CAPARRA` / `ACCONTO` / `SALDO` / `AFFITTO_SALA` / null |
| `keyword_match` | varchar | keyword che ha attivato il parcheggio |
| `controparte_nome` | text | ordinante estratto |
| `controparte_iban` | text | IBAN estratto |
| `data_evento_estratta` | date | data trovata in descrizione (se presente) |
| `evento_id` | UUID | null finché non riconciliato (FK futura → `eventi`) |
| `stato` | varchar | `DA_RICONCILIARE` / `RICONCILIATO` / `SCARTATO` |
| `raw_data` | jsonb | riga grezza originale |
| `created_at` | timestamptz | |

> Indici suggeriti: `(chiave_aggancio)` (dedup), `(stato)`, `(data_evento_estratta)`,
> `(controparte_iban)` per il futuro auto-match con `eventi.data_evento` + nominativo.
### 9.4 `import_scartati` — log SKIP_* (§4) con `motivo`, `raw_data`, reversibile
### 9.5 Estensione `import_ambiguita`
Aggiungere: `confidence numeric`, `suggerimenti jsonb`, `controparte_nome text`, `controparte_iban
text`, `coge_suggerito_id int`, `bu_suggerita smallint`.

---

## 10. Catalogo regole per sorgente (seed di `regole_classificazione`)

Ordine = priorità. La prima che matcha vince.

### 10.1 Billy (solo entrate, registratore cassa)
| Pri | Campo / condizione | Azione | COGE | BU | IVA |
|----:|--------------------|--------|------|----|----|
| 10 | col `AGRITURISMO` > 0 **e** desc = incasso POS (`INCASSO POS`/`NUMIA`/`ACCREDITO POS`) → carve-out | MAP (ricavo POS agriturismo) | 30.01.001 | 1 | 10% |
| 20 | desc contiene `KAIROS` o altro non-evento esplicito | TRIAGE | 39.99.999 | — | — |
| 100 | **col `AGRITURISMO` > 0** (qualsiasi altra riga) | **PARK_EVENTO** | — | — | — |
| 110 | desc COMPACT contiene keyword evento | PARK_EVENTO | — | — | — |
| 210 | col CARNE_10 > 0 (+ ALTRO) | MAP | 30.03.001 | 3 | 10% |
| 220 | col ORTOFRUTTA_4 > 0 (+ ALTRO) | MAP | 30.03.002 | 3 | 4% |
| 900 | fallback | TRIAGE | 39.99.999 | — | — |

> **Scoperta sui dati (campione Gen–Apr 2026, foglio `Corrispettivi`, 151 righe `S`):** la colonna
> `Agriturismo` **non** rappresenta i coperti quotidiani ma gli **incassi-evento** (caparre/saldi di
> cerimonie). Su 54 righe con `Agriturismo>0`: **48 eventi** (39 con keyword esplicita + 9 giroconti
> generici 500/1000 €), **5 versamenti cash** su importi tondi (eventi pagati in contanti), e **solo
> 3** incassi POS reali (carve-out pri.10) + 1 "progetto Kairos". Da qui: **`Agriturismo>0` ⇒ evento**
> salvo il carve-out POS/Kairos.
>
> ⚠️ **DEDUP CROSS-SORGENTE obbligatorio:** questi incassi-evento **ricompaiono** negli estratti
> CA/BPM (**29 delle 54** righe Billy-agri hanno la **stessa `Chiave Aggancio`** già presente in
> banca). Il parcheggio eventi deduplica sulla **`Chiave Aggancio`** (formato `numeroMovBanca/importo`,
> es. `46043/500`), condivisa tra Billy, CA e BPM. Vedi §5 e §9.3.

### 10.2 Crédit Agricole
| Pri | Condizione | Azione | COGE | BU |
|----:|-----------|--------|------|----|
| 10 | causale `INCASSO TRAMITE POS` ∨ desc `INCASSO POS`/`NUMIA` | SKIP_POS | — | — |
| 11 | desc `SATISPAY EUROPE` | SKIP_POS | — | — |
| 20 | uscita + beneficiario `SOCIETA AGRICOLA AGOSTINELLI` (…BPM) | SKIP_GIROCONTO | — | — |
| 30 | **canoni/bolli/assicur./rate/finanz.** (`CANONE`, `BOLLO` qualsiasi, `ASSICURAZ`, `POLIZZA`, `RATA`, `MUTUO`, `LEASING`, `FINANZIAMENTO`) ∨ match `recurring_expenses` | **SKIP_RICORRENTE (elimina)** | — | — |
| 100 | keyword evento (forte, o debole+contesto) e **non** `FATTURA/DOCUM` | PARK_EVENTO | — | — |
| 200 | desc `STRIPE` → **tag `[ALVEARE]`** (R3) | MAP | 30.03.003 | 3 |
| 300 | `COMMISSIONI SU BONIFICO`/`COMMISSIONI ADDEBITO UTENZE`/`COMMIS.SU ADDEB` (per-operazione, R2) | MAP | 40.02.002 | 5 |
| 310 | `NEXI` + `COMM NEXI EC` (commissione POS variabile, R2) | MAP | 40.02.001 | 5 |
| 320 | `TELEPASS` | MAP_FORNITORE (Telepass) | 40.06.001 | 5 |
| 400 | `ORGANISMO PAGATORE`/`PAC`/`REGIME DI PAGAMENTO UNICO`/`AGEA` | MAP | 30.04.001 | 1 |
| 410 | `EROGAZIONE FINANZIAMENTO`/`ASCONFIDI` (entrata una-tantum) | **SKIP_RICORRENTE (elimina)** ⚠️ §16 | — | — |
| 500 | uscita `DISPOSIZIONE DI PAGAMENTO`/`EFFETTI RITIRATI` → motore fornitori (§7) | MAP_FORNITORE | (rubrica/49.99.999) | (rubrica/5) |
| 510 | `RIBA`/`PAGAM. RIBA`/`PAG.EFF` senza nominativo | TRIAGE | 49.99.999 | 5 |
| 900 | entrata non riconosciuta | TRIAGE | 39.99.999 | — |

### 10.3 Banco BPM
| Pri | Condizione | Azione | COGE | BU |
|----:|-----------|--------|------|----|
| 10 | causale `090`/`092` ∨ desc `NUMIA`/`INC.POS`/`INCAS. TRAMITE P.O.S` | SKIP_POS | — | — |
| 11 | causale `480` + desc `NEXI` + `ACCREDITO POS` | SKIP_POS | — | — |
| 12 | desc `SATISPAY EUROPE` | SKIP_POS | — | — |
| 20 | causale `78A` (versamento ATM) | SKIP_GIROCONTO | — | — |
| 21 | causale `480` + desc `SOCIETA AGRICOLA AGOSTINELLI` | SKIP_GIROCONTO | — | — |
| 30 | canoni/bolli/assicur./rate/finanz. ∨ match `recurring_expenses` | **SKIP_RICORRENTE (elimina)** | — | — |
| 100 | keyword evento (anche causale `ZI0` estero, es. "acconto festa") | PARK_EVENTO | — | — |
| 200 | desc `STRIPE` (causale `480`) → **tag `[ALVEARE]`** (R3) | MAP | 30.03.003 | 3 |
| 400 | desc `VERSAMENTO SOCIO` | MAP | 90.02.001 | 5 |
| 410 | causale `349` `RIMBORSO CARTA` | TRIAGE (rettifica costo) | 49.99.999 | 5 |
| 900 | entrata non riconosciuta (es. "progetto kairos", "spesa X", "anticipo fatt") | TRIAGE | 39.99.999 | — |

---

## 11. Nuovi conti COGE da introdurre

Da validare con il commercialista; codici proposti coerenti col piano esistente.

| Codice | Descrizione | Tipo | Uso |
|--------|-------------|------|-----|
| `30.04` / `30.04.001` | Contributi pubblici e PAC | RICAVO | C4 contributi |
| `39.99.999` | Ricavi da classificare (transitorio) | RICAVO | entrate in triage |
| `49.99.999` | Costi da classificare (transitorio) | COSTO | uscite in triage |
| `90.01.001` | Finanziamenti ricevuti | PATRIMONIALE | **usato dal modulo ricorrenti** (non dall'import) per generare l'erogazione quando si crea una ricorrenza di tipo finanziamento — §15 TODO / §16 |
| `90.02.001` | Versamenti soci | PATRIMONIALE | apporti capitale |

> I conti `39.99.999`/`49.99.999` sono **transitori**: vanno monitorati (KPI §13) e devono tendere
> a zero man mano che il triage li riassegna. I conti `90.*` sono fuori dal Conto Economico
> (movimenti patrimoniali/finanziari): vanno esclusi dai report di P&L.

---

## 12. Piano di rollout per fasi

| Fase | Contenuto | Rischio | Reversibile |
|------|-----------|---------|-------------|
| **F0** | `normalizeDesc()` + doppia vista + estrazione IBAN/ordinante/beneficiario | basso | sì |
| **F1** | Gate A (SKIP_POS, SKIP_GIROCONTO simmetrico, SKIP_RICORRENTE) + tabella `import_scartati` | basso | sì (riga tracciata) |
| **F2** | Gate B (PARK_EVENTO) + coda `eventi_da_riconciliare` | basso | sì |
| **F3** | Fornitore non bloccante: COGE transitori + triage invece di scarto | medio | sì |
| **F4** | Rubrica `controparti` + matching IBAN/token/fuzzy + auto-apprendimento | medio | sì |
| **F5** | Motore regole data-driven (`regole_classificazione`) + migrazione del §10 in seed | medio | sì |
| **F6** | UI triage con suggerimenti + azioni massive | medio | — |
| **F7** (futuro) | Riconciliazione manuale eventi (pesca da `eventi_da_riconciliare`) | — | — |

**Fix puntuali da includere già in F0/F1** (bug emersi dai dati reali):
- **Dedup CA collidente**: il `riferimento_esterno` CA = `chiave + descr[:30]` collassa righe diverse
  (es. tre `COMMISSIONI ADDEBITO UTENZE - BONIFICI N. …` nello stesso giorno → primi 30 char
  identici). Usare un rif più discriminante: `chiave + importo + hash(descr)` o `descr[:80]`.
- **Giroconto CA→BPM lato uscita** non rilevato (A2).
- **Satispay/Nexi-bonifico** che sfuggono al filtro per sola causale (A1).

---

## 13. KPI di qualità della classificazione

Da esporre in dashboard import per misurare la convergenza:

| KPI | Formula | Target |
|-----|---------|--------|
| Tasso auto-catalogazione | `BOOK_auto / righe_totali` | ↑ verso > 90% |
| Tasso ambiguità | `TRIAGE / righe_totali` | ↓ verso < 10% |
| Saldo conti transitori | somma movimenti su `39.99.999`+`49.99.999` | → 0 |
| Copertura fornitori | `righe_uscita_con_fornitore / righe_uscita` | ↑ |
| Falsi SKIP | righe ripescate manualmente da `import_scartati` | ≈ 0 |
| Convergenza apprendimento | triage(import N) vs triage(import N-1) a parità di volume | decrescente |

---

## 14. Glossario esiti / motivi

| Esito | Significato | Genera movimento? | Coda |
|-------|-------------|-------------------|------|
| `BOOK` | classificata con confidenza ≥ 0.90 | sì | — |
| `BOOK_REVIEW` | classificata 0.60–0.90 | sì (flag verifica) | revisione soft |
| `SKIP_POS` | duplicato Billy (POS/Satispay) | no | `import_scartati` |
| `SKIP_GIROCONTO` | trasferimento interno | no | `import_scartati` |
| `SKIP_RICORRENTE` | spesa ricorrente/finanziamento: gestita da modulo dedicato | no | conteggio `import_log` + traccia leggera `import_scartati` |
| `PARK_EVENTO` | voce evento, separata e riusabile | no (per ora) | `eventi_da_riconciliare` |
| `TRIAGE` | confidenza < 0.60 | sì, su COGE transitorio | `import_ambiguita` |

---

## 15. Decisioni confermate (v2.2)

| # | Tema | Decisione presa |
|---|------|-----------------|
| Q1 | Commissione Nexi mensile (`COMM NEXI EC`) | **TENUTA** come costo POS (`40.02.001`): è volume-based, serve al lordo→netto Billy (R2). |
| Q2 | Erogazione finanziamento in entrata | **ESCLUSA** dall'import (SKIP_RICORRENTE). Oggi il modulo finanziamenti **non** registra l'entrata → ⚠️ **evolutiva richiesta** (vedi TODO sotto e §16). |
| Q3 | `BOLLO` (qualsiasi: bollo c/c, bollo auto) | **ESCLUSO** dall'import: gestito interamente dal modulo ricorrenti. |
| Q4 | Tag Alveare sui movimenti Stripe | **ENTRAMBI**: prefisso `[ALVEARE]` in descrizione **e** campo `note`. |
| Q5 | Tracciamento `SKIP_RICORRENTE` | **Conteggio in `import_log` + traccia leggera** in `import_scartati` (data, importo, causale, motivo) — nessun movimento, ma resta ispezionabile. |

> ⚠️ **TODO evolutiva modulo spese ricorrenti (da Q2):** estendere il modulo affinché, alla
> creazione di una ricorrenza di tipo `FINANZIAMENTO`, generi automaticamente **due flussi**: il
> movimento di **erogazione** una-tantum in entrata (oggi assente — per questo i finanziamenti
> vengono esclusi dall'import bancario) e le **rate** ricorrenti in uscita. Finché non implementata,
> l'erogazione va inserita manualmente per non rompere la quadratura del saldo banca.

---

## 16. Spese ricorrenti e finanziamenti da creare nel modulo dedicato

Estratto **dai report di esempio** (`Credit Agricole.csv`, `Bpm.csv`): voci che con le nuove regole
**vengono escluse dall'import** (`SKIP_RICORRENTE`, §4 A3) e che quindi vanno **censite nel modulo
spese ricorrenti / finanziamenti**, altrimenti spariscono dalla contabilità.

> **Nota fonte importante:** nel campione, **tutte** le ricorrenti e i finanziamenti sono sul
> **Crédit Agricole**. Il **Banco BPM** nel periodo contiene **solo entrate** (Stripe, POS, eventi,
> versamenti soci, giroconti interni, rimborso carta) → **nessuna** ricorrente da censire da BPM.

### 16.1 Costi fissi del conto (periodici)
| Voce | Importo | Cadenza | Causale CA | Esempi (riga CSV) |
|------|---------|---------|------------|-------------------|
| Imposta di bollo c/c (`BOLLO E/C`) | ~25,21 | trimestrale | COMMISSIONI/SPESE | r.2 |
| Canone NowBanking Corporate | 6,10 | mensile | COMMISSIONI/SPESE | r.3, r.42, r.90 |
| Canone mensile carta CA Debit Visa ****0883 | 3,00 | mensile | COMMISSIONI/SPESE | r.39, r.89 |

### 16.2 Assicurazioni (verificare cadenza: annuale o rateale)
| Beneficiario | Importo | Causale CA | Riga |
|--------------|---------|------------|------|
| Cattolica (Assicurazioni) | 400,66 | DISPOSIZIONE DI PAGAMENTO | r.63 |
| Stradi Assicurazioni sas | 464,00 | DISPOSIZIONE DI PAGAMENTO | r.174 |
| Agenzia Generale Agrifides Srl | 780,00 | DISPOSIZIONE DI PAGAMENTO | r.76 |

### 16.3 Finanziamenti
| Voce | Importo | Tipo | Riga | Nota |
|------|---------|------|------|------|
| ASCONFIDI Lombardia – erogazione finanziamento | **+38.800,00** | **erogazione** (entrata una-tantum) | r.137 | ⚠️ oggi il modulo **non** la genera → va prevista (vedi §15 TODO) |
| Rate del finanziamento | n/d nel campione | rate (uscita ricorrente) | — | da censire con piano di ammortamento (importo, cadenza, n. rate) |

### 16.4 Borderline — da decidere insieme
| Voce | Importo | Perché borderline |
|------|---------|-------------------|
| Telepass (SDD pedaggi) | 129,12 / 102,25 / 190,66 (r.45, r.92, r.181) | SDD mensile ma **importo variabile** (consumo). Ora è classificato come **costo fornitore** Telepass (`40.06.001`, §10.2 pri.320). Se preferisci trattarlo come ricorrente, va spostato in A3 ed escluso. |

> **Azione propedeutica al primo import "pulito":** creare nel modulo ricorrenti le voci 16.1–16.3
> (importo, cadenza, conto, IBAN/causale di match) così il motore A3 le riconosce ed esclude, e il
> modulo le rigenera con la periodicità corretta. Per i finanziamenti, applicare l'evolutiva §15 TODO
> (generazione automatica dell'erogazione in entrata).

---

## 17. Interventi necessari oltre alla logica di import (checklist)

Implementare i gate (§2–§8) è **solo una parte**. Perché il sistema funzioni end-to-end servono anche
gli interventi qui sotto. Sono raggruppati per tipo; in coda la **checklist consolidata** con la fase
(§12) di riferimento.

### 17.1 Migrazioni DB — nuovi conti COGE (validare col commercialista)
| Codice | Descrizione | Tipo | Nota |
|--------|-------------|------|------|
| `30.04` + `30.04.001` | Contributi pubblici e PAC | RICAVO | nuovo nodo padre `30.04` |
| `39.99.999` | Ricavi da classificare | RICAVO (transitorio) | deve tendere a 0 |
| `49.99.999` | Costi da classificare | COSTO (transitorio) | deve tendere a 0 |
| `90` + `90.01.001` | Finanziamenti ricevuti | PATRIMONIALE | nuovo nodo padre `90` fuori P&L |
| `90.02.001` | Versamenti soci | PATRIMONIALE | fuori P&L |

### 17.2 Migrazioni DB — nuove tabelle / estensioni
- **`regole_classificazione`** (motore regole data-driven, §9.1).
- **`controparti`** (rubrica IBAN/nome → fornitore/tipo/COGE/BU, §7.2).
- **`eventi_da_riconciliare`** (bucket PARK_EVENTO con `chiave_aggancio`, §9.3).
- **`import_scartati`** (log SKIP_POS/GIROCONTO + traccia leggera ricorrenti, §4/§9.4).
- **ALTER `import_ambiguita`**: `confidence`, `suggerimenti jsonb`, `controparte_nome`,
  `controparte_iban`, `coge_suggerito_id`, `bu_suggerita` (§9.5).

### 17.3 Lookup / enum da popolare
- **Esiti import**: `SKIP_POS`, `SKIP_GIROCONTO`, `SKIP_RICORRENTE`, `PARK_EVENTO`, `TRIAGE`,
  `BOOK`, `BOOK_REVIEW` (nuova `lk_esiti_import` o estensione dei motivi).
- **Stati `eventi_da_riconciliare`**: `DA_RICONCILIARE` / `RICONCILIATO` / `SCARTATO`.
- **`controparti.tipo`**: `FORNITORE/CLIENTE/SOCIO/ENTE_PUBBLICO/BANCA/INTERNO/PERSONALE`.
- **Verifiche:** aliquota IVA `0%` presente in `aliquote_iva` (serve a Stripe); i metodi pagamento
  esistenti (`V6`) coprono già tutti i casi → **nessun nuovo metodo previsto**; BU 1–5 sufficienti
  (le partite `90.*` non hanno BU → valutare un BU/flag "non applicabile / patrimoniale").

### 17.4 Seed dati
- **Seed `regole_classificazione`** = traduzione del §10 (Billy/CA/BPM) in righe.
- **Seed `controparti`** dai fornitori esistenti (`V6`/`V8`) + IBAN dove noti.
- **Seed spese ricorrenti/finanziamenti** del §16 nel modulo dedicato.
- **Correzione `fornitore_alias_matching`**: pattern errati emersi dai dati (`GRUPPO ITALIANO VI`
  non matcha `GRUPPO ITALIANOVINI`; `ORMA BIRRA` ≠ `ORMA BIANCA`) → migrare a match per token.

### 17.5 Estensioni Postgres
- **`pg_trgm`** (similarità trigrammi) per il fuzzy matching dei nomi controparte (§7.1 livello 3).

### 17.6 Modifiche al MODULO SPESE RICORRENTI (modulo separato)
- **Evolutiva erogazione finanziamento (Q2/§15):** alla creazione di una ricorrenza tipo
  `FINANZIAMENTO`, generare automaticamente il movimento di **erogazione** in entrata (oggi assente).
- **Engine di match attive ↔ import (A3):** API che, data una riga bancaria (importo/cadenza/IBAN),
  dice se corrisponde a una ricorrenza attiva → usata dal Gate A per lo `SKIP_RICORRENTE`.

### 17.7 Reporting / P&L / Dashboard
- **Escludere i conti `90.*`** (patrimoniali) dal Conto Economico.
- **Includere `30.04`** (contributi) tra i ricavi nei report.
- **Monitorare i transitori** `39.99.999`/`49.99.999` (KPI §13) — alert se ≠ 0.
- **Materialized view + cache**: verificare che i nuovi conti/righe siano coperti dal
  `MvRefreshService` e dall'invalidazione cache già esistenti.

### 17.8 Modello movimenti
- **Tag Alveare**: scrivere `[ALVEARE]` in descrizione **e** valorizzare `note` (Q4) sui movimenti Stripe.
- **`fornitore` opzionale**: confermare nullability (è già nullable) → fornitore non bloccante (§7).
- **Flag `verifica_suggerita`** sui movimenti `BOOK_REVIEW` (confidence 0.60–0.90).
- Conto bancario `5` (Stripe/Alveare) già esistente (`V6`).

### 17.9 Codice — motori da realizzare
- **`normalizeDesc()`** + doppia vista (SPACED/COMPACT) + estrazione entità IBAN/ordinante/beneficiario/chiave (§3).
- **Interprete regole data-driven** (legge `regole_classificazione`, valuta per priorità) — affianca/sostituisce `MovimentoMappingEngineImpl`.
- **Matching fornitori** IBAN → token → fuzzy + **confidence scoring** (§6/§7).
- **Auto-apprendimento** controparti/alias dalle classificazioni manuali (§7.3).
- **Dedup**: cross-sorgente eventi su `chiave_aggancio` (§5); **fix dedup CA** (`chiave+descr[:30]` collide → chiave+importo+hash); **giroconto CA→BPM lato uscita** (§4 A2).

### 17.10 API / UI
- **Triage**: lista ambiguità con top-3 suggerimenti, classifica one-click, azioni massive (§8).
- **Coda eventi**: vista/gestione `eventi_da_riconciliare`.
- **Audit scartati**: vista `import_scartati` (POS/giroconti/ricorrenti).
- **CRUD `regole_classificazione`**: l'utente modifica/aggiunge regole senza deploy (§9).
- **Dashboard KPI import** (§13).

### 17.11 Configurazione
- Soglie confidence (`0.90` book / `0.60` triage) parametrizzabili.
- Tolleranze del match ricorrenti (importo ±, finestra giorni).

### 17.12 Checklist consolidata (con fase §12)
| # | Intervento | Tipo | Fase |
|---|-----------|------|------|
| 1 | `normalizeDesc()` + estrazione entità (incl. `chiave_aggancio`) | codice | F0 |
| 2 | Nuovi COGE `30.04`, `39.99.999`, `49.99.999`, `90.01.001`, `90.02.001` | migrazione | F1 |
| 3 | Tabella `import_scartati` + esiti SKIP | migrazione | F1 |
| 4 | Gate A (POS/giroconto simmetrico/ricorrenti) + fix dedup CA | codice | F1 |
| 5 | Tabella `eventi_da_riconciliare` (+`chiave_aggancio`) | migrazione | F2 |
| 6 | Gate B eventi (Billy `Agriturismo>0` + keyword) + dedup cross-sorgente | codice | F2 |
| 7 | Conti transitori + fornitore non bloccante + tag Alveare | codice | F3 |
| 8 | Tabella `controparti` + `pg_trgm` + matching IBAN/token/fuzzy + apprendimento | migrazione+codice | F4 |
| 9 | Tabella `regole_classificazione` + interprete + seed §10 | migrazione+codice | F5 |
| 10 | ALTER `import_ambiguita` + UI triage + suggerimenti + azioni massive | migrazione+UI | F6 |
| 11 | CRUD regole + dashboard KPI + audit scartati | UI | F6 |
| 12 | Reporting: escludere `90.*`, monitorare transitori, MV/cache | reporting | F3→F6 |
| 13 | Modulo ricorrenti: match attive↔import + erogazione finanziamento | altro modulo | F1 / TODO Q2 |
| 14 | Seed: controparti, ricorrenti §16, fix alias errati | seed dati | F4/F5 |

---

### Relazione con la documentazione esistente
- `IMPORT_BULK_REGOLE.md` = stato **attuale** del codice (v1).
- Questo documento = **target** (v2) verso cui evolvere, per fasi (§12).
- Riferimento dominio/ID fissi: memory `project_agostinelli`, `project_import_bulk`.
