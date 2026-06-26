-- ============================================================
-- V7 — Motore di apprendimento a KEYWORD (sostituisce il learning per IBAN)
-- ============================================================
-- Gli estratti conto reali NON portano l'IBAN sui costi: l'apprendimento per IBAN
-- (tabella `controparti`) non scattava mai. Lo sostituiamo con una classificazione a
-- KEYWORD apprese dalla descrizione, di due nature (vedi PROMPT-KEYWORD-LEARNING.md §3.2):
--   * IDENTITA  → nome/ragione sociale/codice → fornitore + COGE + BU.
--   * DOMINIO   → concetto. Si articola in DUE azioni, per rispettare il vincolo eventi:
--       - azione BOOK        → contabilizza su BU + COGE (es. PRANZO→ristorazione).
--       - azione PARK_EVENTO → NON genera movimento: alimenta il Gate B che parcheggia
--         l'evento in `eventi_da_riconciliare` (es. MATRIMONIO). Sostituisce le liste
--         keyword evento prima hardcoded nel mapping engine, rendendole editabili da UI.
--
-- COGE sempre per CODICE (mai id). signature_hash = sha256(hex) dei token UPPERCASE
-- ordinati e uniti con '|' (stesso algoritmo del KeywordExtractor lato Java) per il dedup.

-- ── keyword_firma : gruppo di token con un target e una natura ──────────────────────
CREATE TABLE keyword_firma (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    natura           varchar(10)  NOT NULL,                  -- DOMINIO | IDENTITA
    azione           varchar(12)  NOT NULL DEFAULT 'BOOK',   -- BOOK | PARK_EVENTO
    tipo_movimento   varchar(10)  NOT NULL DEFAULT '*',      -- ENTRATA | USCITA | *
    sorgente         varchar(10)  NOT NULL DEFAULT '*',      -- BILLY | BPM | CA | *
    bu_id            smallint,                               -- target BU (null per PARK_EVENTO)
    coge_codice      varchar(20),                            -- target COGE per CODICE (null per PARK_EVENTO)
    fornitore_id     uuid,                                   -- solo se natura = IDENTITA
    evento_forza     varchar(8),                             -- FORTE | DEBOLE (solo PARK_EVENTO)
    tipo_evento      varchar(15),                            -- opz. CAPARRA|ACCONTO|SALDO|AFFITTO_SALA
    confidence       numeric(3,2) NOT NULL DEFAULT 1.00,
    origine          varchar(10)  NOT NULL,                  -- APPRESA | MANUALE | SEED
    stato            varchar(15)  NOT NULL DEFAULT 'ATTIVA', -- ATTIVA | IN_CONFLITTO | DISATTIVATA
    signature_hash   varchar(64)  NOT NULL,
    descrizione_origine text,
    movimento_origine_id uuid,
    created_by       uuid,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz,
    note             text,
    CONSTRAINT keyword_firma_bu_fkey   FOREIGN KEY (bu_id) REFERENCES business_units(id),
    CONSTRAINT keyword_firma_forn_fkey FOREIGN KEY (fornitore_id) REFERENCES fornitori(id) ON DELETE SET NULL,
    CONSTRAINT keyword_firma_natura_ck CHECK (natura IN ('DOMINIO','IDENTITA')),
    CONSTRAINT keyword_firma_azione_ck CHECK (azione IN ('BOOK','PARK_EVENTO')),
    CONSTRAINT keyword_firma_forn_ck   CHECK (fornitore_id IS NULL OR natura = 'IDENTITA'),
    -- una firma che contabilizza deve avere COGE+BU; una che parcheggia non li usa
    CONSTRAINT keyword_firma_book_ck   CHECK (
        (azione = 'BOOK' AND coge_codice IS NOT NULL AND bu_id IS NOT NULL)
        OR (azione = 'PARK_EVENTO' AND natura = 'DOMINIO')
    )
);
-- Dedup: stessa firma (token) nello stesso scope = una sola riga.
CREATE UNIQUE INDEX uq_keyword_firma_sig ON keyword_firma(signature_hash, tipo_movimento, sorgente);
CREATE INDEX idx_keyword_firma_stato ON keyword_firma(stato);

-- ── keyword_token : i token che compongono una firma (match in AND) ─────────────────
CREATE TABLE keyword_token (
    id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    firma_id  uuid NOT NULL REFERENCES keyword_firma(id) ON DELETE CASCADE,
    token     varchar(60) NOT NULL,
    tipo      varchar(10) NOT NULL,                          -- IDENTITA | CODICE | DOMINIO | NORMALE
    CONSTRAINT uq_keyword_token UNIQUE (firma_id, token)
);
CREATE INDEX idx_keyword_token_token ON keyword_token(token);  -- inverted index per il match O(1)

-- ── keyword_conflitto : coda conflitti a step (apprendimento / match) ───────────────
CREATE TABLE keyword_conflitto (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo             varchar(20) NOT NULL,                   -- APPRENDIMENTO | MATCH
    signature_hash   varchar(64),
    firma_esistente_id uuid REFERENCES keyword_firma(id) ON DELETE CASCADE,
    movimento_id     uuid,
    target_esistente jsonb,                                  -- {bu,coge,fornitore}
    target_nuovo     jsonb,
    descrizione      text,
    stato            varchar(12) NOT NULL DEFAULT 'APERTO',  -- APERTO | RISOLTO | IGNORATO
    created_at       timestamptz NOT NULL DEFAULT now(),
    risolto_at       timestamptz,
    risolto_by       uuid
);
CREATE INDEX idx_keyword_conflitto_stato ON keyword_conflitto(stato);

-- ── keyword_stopword : rumore editabile escluso dall'estrazione ─────────────────────
CREATE TABLE keyword_stopword (
    token     varchar(60) PRIMARY KEY,
    categoria varchar(20)   -- ARTICOLO | PREPOSIZIONE | CONGIUNZIONE | VERBO | RUMORE_BANCARIO
);

-- ════════════════════════════════════════════════════════════════════════════════════
-- SEED stopword
-- ════════════════════════════════════════════════════════════════════════════════════
INSERT INTO keyword_stopword (token, categoria) VALUES
    -- articoli / preposizioni / congiunzioni IT
    ('IL','ARTICOLO'),('LO','ARTICOLO'),('LA','ARTICOLO'),('I','ARTICOLO'),('GLI','ARTICOLO'),
    ('LE','ARTICOLO'),('UN','ARTICOLO'),('UNO','ARTICOLO'),('UNA','ARTICOLO'),
    ('DI','PREPOSIZIONE'),('A','PREPOSIZIONE'),('DA','PREPOSIZIONE'),('IN','PREPOSIZIONE'),
    ('CON','PREPOSIZIONE'),('SU','PREPOSIZIONE'),('PER','PREPOSIZIONE'),('TRA','PREPOSIZIONE'),
    ('FRA','PREPOSIZIONE'),('AL','PREPOSIZIONE'),('DEL','PREPOSIZIONE'),('DELLA','PREPOSIZIONE'),
    ('DEI','PREPOSIZIONE'),('DELLE','PREPOSIZIONE'),('DELLO','PREPOSIZIONE'),('ALLO','PREPOSIZIONE'),
    ('ALLA','PREPOSIZIONE'),('AI','PREPOSIZIONE'),('AGLI','PREPOSIZIONE'),('NEL','PREPOSIZIONE'),
    ('E','CONGIUNZIONE'),('ED','CONGIUNZIONE'),('O','CONGIUNZIONE'),('AD','CONGIUNZIONE'),
    -- verbi / forme comuni (approssimazione, no POS reale)
    ('PAGARE','VERBO'),('PAGATO','VERBO'),('PAGAMENTO','VERBO'),('PAGAM','VERBO'),
    ('ADDEBITARE','VERBO'),('ADDEBITO','VERBO'),('ACCREDITARE','VERBO'),('ACCREDITO','VERBO'),
    ('STORNARE','VERBO'),('STORNO','VERBO'),('EMETTERE','VERBO'),('EMESSO','VERBO'),
    ('RICEVUTO','VERBO'),('RIMBORSO','VERBO'),('RIMBORSATO','VERBO'),('VERSAMENTO','VERBO'),
    ('DISPOSIZIONE','VERBO'),('DISP','VERBO'),
    -- rumore bancario
    ('BONIF','RUMORE_BANCARIO'),('BON','RUMORE_BANCARIO'),('VS','RUMORE_BANCARIO'),
    ('FAVORE','RUMORE_BANCARIO'),('DIRETTO','RUMORE_BANCARIO'),('SDD','RUMORE_BANCARIO'),
    ('B2B','RUMORE_BANCARIO'),('RIF','RUMORE_BANCARIO'),('CRO','RUMORE_BANCARIO'),
    ('DESCR','RUMORE_BANCARIO'),('DESCRIZIONE','RUMORE_BANCARIO'),('OPERAZIONE','RUMORE_BANCARIO'),
    ('SCT','RUMORE_BANCARIO'),('ISTANTANEO','RUMORE_BANCARIO'),('BONIFICO','RUMORE_BANCARIO'),
    ('GIROCONTO','RUMORE_BANCARIO'),('TRASFERIMENTO','RUMORE_BANCARIO'),('GENERICO','RUMORE_BANCARIO'),
    ('UTENZE','RUMORE_BANCARIO'),('COMMISSIONI','RUMORE_BANCARIO'),('COMMIS','RUMORE_BANCARIO'),
    ('SPESE','RUMORE_BANCARIO'),('VOSTRA','RUMORE_BANCARIO'),('INCASSO','RUMORE_BANCARIO'),
    ('INCAS','RUMORE_BANCARIO'),('POS','RUMORE_BANCARIO'),('NUMIA','RUMORE_BANCARIO'),
    ('NEXI','RUMORE_BANCARIO'),('BNCMT','RUMORE_BANCARIO'),('INTER','RUMORE_BANCARIO'),
    ('AMEX','RUMORE_BANCARIO'),('DEL','RUMORE_BANCARIO'),('PDV','RUMORE_BANCARIO'),
    ('EUR','RUMORE_BANCARIO'),('ITA','RUMORE_BANCARIO'),('IT','RUMORE_BANCARIO'),
    ('SOC','RUMORE_BANCARIO'),('SOCIETA','RUMORE_BANCARIO'),('AGRICOLA','RUMORE_BANCARIO'),
    ('AGOSTINELLI','RUMORE_BANCARIO'),('SRL','RUMORE_BANCARIO'),('SPA','RUMORE_BANCARIO'),
    ('SNC','RUMORE_BANCARIO'),('SAS','RUMORE_BANCARIO'),('SS','RUMORE_BANCARIO'),
    ('NOTPROVIDE','RUMORE_BANCARIO'),('ORD','RUMORE_BANCARIO'),('CORE','RUMORE_BANCARIO'),
    ('CARD','RUMORE_BANCARIO'),('SEPA','RUMORE_BANCARIO'),('CBILL','RUMORE_BANCARIO')
ON CONFLICT (token) DO NOTHING;

-- ════════════════════════════════════════════════════════════════════════════════════
-- SEED dizionario DOMINIO (firma a 1 token ciascuna). signature_hash = sha256(UPPER(token))
-- (stesso algoritmo del KeywordExtractor lato Java). Un solo statement (CTE) genera gli
-- uuid delle firme e collega i token, così è robusto a prescindere dalla transazione.
-- ════════════════════════════════════════════════════════════════════════════════════
WITH seed(token, azione, tipo_mov, bu_id, coge, forza) AS (
    VALUES
    -- ── DOMINIO-EVENTO → PARK_EVENTO (no movimento). Replica le liste prima hardcoded
    --    nel mapping engine (Gate B) + arricchimenti del prompt. FORTE = parcheggia da sola;
    --    DEBOLE = parcheggia solo con contesto evento (ordinante/data).
    ('MATRIMONIO','PARK_EVENTO','ENTRATA',NULL::smallint,NULL::varchar,'FORTE'),
    ('BATTESIMO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('CRESIMA','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('COMUNIONE','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('COMPLEANNO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('ANNIVERSARIO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('CERIMONIA','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('DICIOTTESIMO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('18ESIMO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('18ANNI','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('GENDER','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('LAUREA','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('NOZZE','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('BANCHETTO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('RICEVIMENTO','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('AFFITTOSALA','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('CAPARRA','PARK_EVENTO','ENTRATA',NULL,NULL,'FORTE'),
    ('ACCONTO','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    ('SALDO','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    ('AFFITTO','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    ('EVENTO','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    ('FESTA','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    ('RICORRENZA','PARK_EVENTO','ENTRATA',NULL,NULL,'DEBOLE'),
    -- ── DOMINIO-CATEGORIA → BOOK (genera movimento su BU + COGE) ──
    -- BU1 Ristorazione e Agriturismo (ENTRATA → incassi ristorazione 30.01.001)
    ('PRANZO','BOOK','ENTRATA',1,'30.01.001',NULL),
    ('CENA','BOOK','ENTRATA',1,'30.01.001',NULL),
    ('MENU','BOOK','ENTRATA',1,'30.01.001',NULL),
    ('COPERTI','BOOK','ENTRATA',1,'30.01.001',NULL),
    ('RISTORANTE','BOOK','ENTRATA',1,'30.01.001',NULL),
    ('AGRITURISMO','BOOK','ENTRATA',1,'30.01.001',NULL),
    -- B&B / ospitalità → 30.01.002
    ('PERNOTTAMENTO','BOOK','ENTRATA',1,'30.01.002',NULL),
    ('OSPITALITA','BOOK','ENTRATA',1,'30.01.002',NULL),
    -- BU3 Vendita Prodotti e Spaccio (ENTRATA)
    ('SPACCIO','BOOK','ENTRATA',3,'30.03.001',NULL),
    ('MACELLERIA','BOOK','ENTRATA',3,'30.03.001',NULL),
    ('SALUMI','BOOK','ENTRATA',3,'30.03.001',NULL),
    ('ORTOFRUTTA','BOOK','ENTRATA',3,'30.03.002',NULL),
    -- BU4 Manutenzione Verde (ENTRATA)
    ('GIARDINAGGIO','BOOK','ENTRATA',4,'30.04.001',NULL),
    ('POTATURA','BOOK','ENTRATA',4,'30.04.001',NULL),
    ('SFALCIO','BOOK','ENTRATA',4,'30.04.001',NULL),
    -- BU5 Overhead (USCITA → assicurazioni 40.05.001). Backstop: di norma intercettate
    -- prima dal Gate A SKIP_RICORRENTE; utili se la riga raggiunge il classify.
    ('ASSICURAZIONE','BOOK','USCITA',5,'40.05.001',NULL),
    ('POLIZZA','BOOK','USCITA',5,'40.05.001',NULL)
),
ins AS (
    INSERT INTO keyword_firma (natura, azione, tipo_movimento, sorgente, bu_id, coge_codice,
                               evento_forza, origine, stato, signature_hash)
    SELECT 'DOMINIO', azione, tipo_mov, '*', bu_id, coge, forza, 'SEED', 'ATTIVA',
           encode(digest(upper(token), 'sha256'), 'hex')
    FROM seed
    RETURNING id, signature_hash, tipo_movimento
)
INSERT INTO keyword_token (firma_id, token, tipo)
SELECT i.id, upper(s.token), 'DOMINIO'
FROM seed s
JOIN ins i ON i.signature_hash = encode(digest(upper(s.token), 'sha256'), 'hex')
          AND i.tipo_movimento = s.tipo_mov;

-- ════════════════════════════════════════════════════════════════════════════════════
-- DROP della rubrica controparti: l'apprendimento per IBAN è dismesso (vedi §2.1).
-- Era usata SOLO dall'import. `fornitore_alias_matching` e `regole_classificazione` RESTANO.
-- ════════════════════════════════════════════════════════════════════════════════════
DROP TABLE IF EXISTS controparti;
