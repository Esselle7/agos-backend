-- V31 - Rende la MV P&L robusta agli storni costo.
--
-- Scenario non gestito in V29: ENTRATA con importo_lordo < 0 su un conto COSTO
-- (es. nota di credito fornitore) non veniva catturata da nessun branch della MV
-- e spariva dal P&L. L'importo_lordo negativo è già usato legittimamente per i
-- RIMBORSO evento (ENTRATA negativa su RICAVO), che invece erano già gestiti.
--
-- Fix: estende costi_operativi ed ebitda_proxy per sommare anche le ENTRATA
-- negative su conti COSTO (il segno negativo riduce automaticamente i costi).

DROP MATERIALIZED VIEW IF EXISTS mv_conto_economico_mensile CASCADE;

CREATE MATERIALIZED VIEW mv_conto_economico_mensile AS
SELECT
    EXTRACT(YEAR  FROM m.data_competenza)::INT AS anno,
    EXTRACT(MONTH FROM m.data_competenza)::INT AS mese,
    m.business_unit_id,
    bu.nome                                    AS business_unit_nome,
    pc.codice                                  AS codice_coge,
    pc.descrizione                             AS descrizione_coge,
    pc.tipo                                    AS tipo_coge,
    pc.is_capex,
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND pc.tipo = 'RICAVO'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS ricavi,
    -- Costi operativi: uscite ordinarie + storni fornitore (ENTRATA negativa su COSTO)
    SUM(CASE WHEN m.tipo = 'USCITA'  AND pc.tipo = 'COSTO' AND NOT pc.is_capex
             THEN  COALESCE(m.importo_imponibile, m.importo_lordo)
             WHEN m.tipo = 'ENTRATA' AND pc.tipo = 'COSTO' AND NOT pc.is_capex
                  AND COALESCE(m.importo_imponibile, m.importo_lordo) < 0
             THEN  COALESCE(m.importo_imponibile, m.importo_lordo)
             ELSE 0 END)                                                         AS costi_operativi,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.is_capex
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS investimenti_capex,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.tipo = 'ONERE_FINANZIARIO'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS oneri_finanziari,
    SUM(CASE WHEN m.tipo = 'USCITA' AND pc.tipo = 'IMPOSTA'
             THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END)   AS imposte,
    -- ebitda_proxy = ricavi − costi operativi (con storni costo che aumentano l'EBITDA)
    SUM(CASE WHEN m.tipo = 'ENTRATA' AND pc.tipo = 'RICAVO'
             THEN  COALESCE(m.importo_imponibile, m.importo_lordo)
             WHEN (m.tipo = 'USCITA' AND pc.tipo = 'COSTO' AND NOT pc.is_capex)
               OR (m.tipo = 'ENTRATA' AND pc.tipo = 'COSTO' AND NOT pc.is_capex
                   AND COALESCE(m.importo_imponibile, m.importo_lordo) < 0)
             THEN -COALESCE(m.importo_imponibile, m.importo_lordo)
             ELSE 0 END)                                                         AS ebitda_proxy,
    COUNT(*)                                                                     AS n_movimenti
FROM movimenti m
JOIN business_units bu         ON bu.id = m.business_unit_id
JOIN piano_dei_conti_coge pc   ON pc.id = m.conto_coge_id
WHERE m.stato             != 'ANNULLATO'
  AND m.data_competenza   IS NOT NULL
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
WITH DATA;

CREATE UNIQUE INDEX idx_mv_conto_eco
    ON mv_conto_economico_mensile (anno, mese, business_unit_id, codice_coge);
