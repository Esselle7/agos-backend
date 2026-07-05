-- V19 — Voci a quantità × prezzo unitario + listino prezzi precaricato.
--
-- I preventivi/consuntivi reali sono righe "quantità × prezzo unitario = totale"
-- (es. 96 menù adulti × 65 = 6.240). Preventivo → consuntivo cambiano soprattutto
-- le QUANTITÀ (5→6 caraffe, 10→113 cocktail). Il catalogo diventa un listino con
-- prezzo di default, così l'utente sceglie la voce e inserisce solo la quantità.
--
-- importo_preventivo = quantita_preventivo × prezzo_unitario
-- importo_consuntivo = quantita_consuntivo × prezzo_unitario (NULL se q.consuntivo NULL)
-- I due importi restano colonne (denormalizzate, ricalcolate dal service).

-- ── evento_voce: quantità + prezzo unitario ──────────────────────────────────
ALTER TABLE evento_voce ADD COLUMN prezzo_unitario     numeric(15,2) NOT NULL DEFAULT 0;
ALTER TABLE evento_voce ADD COLUMN quantita_preventivo numeric(12,2) NOT NULL DEFAULT 1;
ALTER TABLE evento_voce ADD COLUMN quantita_consuntivo numeric(12,2);

ALTER TABLE evento_voce ADD CONSTRAINT evento_voce_qta_prev_check CHECK (quantita_preventivo >= 0);
ALTER TABLE evento_voce ADD CONSTRAINT evento_voce_qta_cons_check CHECK (quantita_consuntivo IS NULL OR quantita_consuntivo >= 0);
ALTER TABLE evento_voce ADD CONSTRAINT evento_voce_prezzo_check   CHECK (prezzo_unitario >= 0);

-- Backfill righe esistenti (incl. "Preventivo iniziale" di V18): 1 × prezzo = importo,
-- così i totali restano identici. q.consuntivo = 1 dove esiste già un consuntivo.
UPDATE evento_voce
   SET prezzo_unitario     = importo_preventivo,
       quantita_preventivo = 1,
       quantita_consuntivo = CASE WHEN importo_consuntivo IS NOT NULL THEN 1 ELSE NULL END;

-- ── evento_voce_catalogo: listino (prezzo default + unità) ────────────────────
ALTER TABLE evento_voce_catalogo ADD COLUMN prezzo_default numeric(15,2);
ALTER TABLE evento_voce_catalogo ADD COLUMN unita          character varying(30);

-- 'Menu' generico è superato dal listino specifico (Menù adulti/bambini)
UPDATE evento_voce_catalogo SET is_default = false WHERE label = 'Menu';

-- Listino reale (Agriturismo Agostinelli). Upsert idempotente per label.
INSERT INTO evento_voce_catalogo (label, is_default, ordine, prezzo_default, unita) VALUES
    ('Menù adulti',                  true,   1,  65.00, 'persona'),
    ('Menù bambini',                 true,   2,  25.00, 'persona'),
    ('Affitto',                      true,   3,   0.00, NULL),
    ('Location in esclusiva',        false,  4, 500.00, NULL),
    ('Caraffa Spritz',               false, 10,  45.00, 'caraffa'),
    ('Gin Tonic',                    false, 11,   7.00, 'cocktail'),
    ('Gin Lemon',                    false, 12,   7.00, 'cocktail'),
    ('Bottiglia Spumante',           false, 13,  15.00, 'bottiglia'),
    ('Bottiglia Rosso Valtellina',   false, 14,  20.00, 'bottiglia'),
    ('Analcolici / bibite',          false, 15,   3.00, NULL),
    ('Amari',                        false, 16,   3.00, NULL),
    ('Birra artigianale (fusto 24 lt)', false, 17, 250.00, 'fusto')
ON CONFLICT (label) DO UPDATE
    SET is_default     = EXCLUDED.is_default,
        ordine         = EXCLUDED.ordine,
        prezzo_default = EXCLUDED.prezzo_default,
        unita          = EXCLUDED.unita;
