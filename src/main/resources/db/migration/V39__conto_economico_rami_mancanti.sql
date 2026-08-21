-- V39 — le righe che sparivano nel nulla: i due rami simmetrici mancanti nel conto economico.
--
-- mv_conto_economico_mensile aveva il ramo `USCITA su COSTO` e quello `ENTRATA su COSTO con
-- importo negativo` (storni fornitore, V31), ma NON i due simmetrici:
--   · `USCITA su conto RICAVO`  → oggi non è costo, non è meno-ricavo: sparisce
--   · `ENTRATA su conto capex`  → oggi non è ricavo, non è meno-investimento: sparisce
--
-- Misurato in produzione il 20/08/2026: 990,00 € muti — 490,00 (ENTRATA sul conto 91, rimborso
-- «fatture riparazione muro» del 15/07) + 500,00 (USCITA «carne» sul conto 30.03.001, competenza
-- 31/08, nata DOPO l'analisi del 19/08). Verifica a vista: il conto 91 somma 5.625,35 a DB e
-- l'endpoint ne esponeva 5.135,35.
--
-- Semantica scelta, simmetrica a quella già in vigore per i costi:
--   · USCITA su RICAVO  = storno di ricavo → RIDUCE i ricavi (e quindi l'EBITDA), come una nota
--     di credito al cliente. Non è un costo operativo: non sposta la voce di costo.
--   · ENTRATA su capex  = rimborso/disinvestimento → RIDUCE investimenti_capex. Fuori dall'EBITDA,
--     esattamente come l'uscita capex che nettizza.
--
-- Effetto su luglio 2026: EBITDA invariato. Il capex di luglio passa da 5.420,20 a 4.930,20
-- (i 490 nettizzano l'investimento) e i 500,00 cadono in agosto, che è il loro mese di competenza.
-- Questa migration non serve a spostare il risultato di luglio: serve a smettere di perdere righe.
--
-- Le altre colonne sono riportate identiche a V3 (una MV non si ALTER-a, si ricrea).

DROP MATERIALIZED VIEW IF EXISTS mv_conto_economico_mensile;

CREATE MATERIALIZED VIEW mv_conto_economico_mensile AS
 SELECT (EXTRACT(year FROM m.data_competenza))::integer AS anno,
    (EXTRACT(month FROM m.data_competenza))::integer AS mese,
    m.business_unit_id,
    bu.nome AS business_unit_nome,
    pc.codice AS codice_coge,
    pc.descrizione AS descrizione_coge,
    pc.tipo AS tipo_coge,
    pc.is_capex,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((pc.tipo)::text = 'RICAVO'::text)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            -- V39: storno di ricavo (USCITA su conto RICAVO) — simmetrico a V31 sui costi
            WHEN (((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'RICAVO'::text)) THEN (- COALESCE(m.importo_imponibile, m.importo_lordo))
            ELSE (0)::numeric
        END) AS ricavi,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex) AND (COALESCE(m.importo_imponibile, m.importo_lordo) < (0)::numeric)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            ELSE (0)::numeric
        END) AS costi_operativi,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'USCITA'::text) AND pc.is_capex) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            -- V39: rimborso su conto capex — riduce l'investimento, non è un ricavo
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND pc.is_capex) THEN (- COALESCE(m.importo_imponibile, m.importo_lordo))
            ELSE (0)::numeric
        END) AS investimenti_capex,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'ONERE_FINANZIARIO'::text)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            ELSE (0)::numeric
        END) AS oneri_finanziari,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'IMPOSTA'::text)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            ELSE (0)::numeric
        END) AS imposte,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((pc.tipo)::text = 'RICAVO'::text)) THEN COALESCE(m.importo_imponibile, m.importo_lordo)
            -- V39: lo storno di ricavo abbassa l'EBITDA dello stesso importo
            WHEN (((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'RICAVO'::text)) THEN (- COALESCE(m.importo_imponibile, m.importo_lordo))
            WHEN ((((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex)) OR (((m.tipo)::text = 'ENTRATA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex) AND (COALESCE(m.importo_imponibile, m.importo_lordo) < (0)::numeric))) THEN (- COALESCE(m.importo_imponibile, m.importo_lordo))
            ELSE (0)::numeric
        END) AS ebitda_proxy,
    count(*) AS n_movimenti
   FROM ((movimenti m
     JOIN business_units bu ON ((bu.id = m.business_unit_id)))
     JOIN piano_dei_conti_coge pc ON ((pc.id = m.conto_coge_id)))
  WHERE (((m.stato)::text <> 'ANNULLATO'::text) AND (m.data_competenza IS NOT NULL))
  GROUP BY ((EXTRACT(year FROM m.data_competenza))::integer), ((EXTRACT(month FROM m.data_competenza))::integer), m.business_unit_id, bu.nome, pc.codice, pc.descrizione, pc.tipo, pc.is_capex;

-- L'indice unico serve al REFRESH ... CONCURRENTLY di fn_refresh_all_mv().
CREATE UNIQUE INDEX idx_mv_conto_eco ON mv_conto_economico_mensile USING btree (anno, mese, business_unit_id, codice_coge);
