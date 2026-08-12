-- V24 — il saldo di un conto conta solo i movimenti POSTERIORI alla data del saldo iniziale.
--
-- Bug: `conti_bancari.data_saldo_iniziale` veniva raccolta dalla UI ("Saldo al <data>") e non
-- letta da nessuna formula di saldo. Chi inserisce un saldo a metà periodo si ritrova i movimenti
-- anteriori sottratti da un saldo che li contiene già (misurato 2026-08-07: liquidità -28.929,02).
--
-- Semantica: "Saldo AL giorno X" = saldo a fine giornata X → conta ciò che viene DOPO (`>`).
-- Confine sulla data in cui il denaro si muove davvero (data_finanziaria), col fallback su
-- data_movimento per i movimenti non ancora liquidati.

DROP MATERIALIZED VIEW IF EXISTS mv_saldi_conti;

CREATE MATERIALIZED VIEW mv_saldi_conti AS
 SELECT cb.id AS conto_id,
    cb.nome,
    cb.tipo,
    cb.saldo_iniziale,
    COALESCE(sum(
        CASE
            WHEN ((m.tipo)::text = 'ENTRATA'::text) THEN m.importo_lordo
            WHEN ((m.tipo)::text = 'USCITA'::text) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END), (0)::numeric) AS movimenti_netti,
    (cb.saldo_iniziale + COALESCE(sum(
        CASE
            WHEN ((m.tipo)::text = 'ENTRATA'::text) THEN m.importo_lordo
            WHEN ((m.tipo)::text = 'USCITA'::text) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END), (0)::numeric)) AS saldo_calcolato
   FROM (conti_bancari cb
     LEFT JOIN movimenti m ON (((m.conto_bancario_id = cb.id)
        AND ((m.stato)::text <> 'ANNULLATO'::text)
        AND (cb.data_saldo_iniziale IS NULL
             OR COALESCE(m.data_finanziaria, m.data_movimento) > cb.data_saldo_iniziale))))
  WHERE (cb.is_active = true)
  GROUP BY cb.id, cb.nome, cb.tipo, cb.saldo_iniziale;

CREATE UNIQUE INDEX idx_mv_saldi_conti ON mv_saldi_conti USING btree (conto_id);

REFRESH MATERIALIZED VIEW mv_saldi_conti;
