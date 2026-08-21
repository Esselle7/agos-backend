-- V37 — il cash flow guadagna il secchio che gli manca: entrate_investimento.
--
-- Bug strutturale: mv_cash_flow_statement ha entrate_operative, uscite_operative,
-- uscite_investimento, uscite_finanziarie, entrate_finanziarie — e nessun secchio per una
-- ENTRATA su conto capex. entrate_operative esclude esplicitamente `pc.is_capex`, quindi
-- quella riga non cade da nessuna parte e sparisce dal cash flow.
--
-- Oggi non fa danno perché nessun conto marcato capex ha entrate. Ma la migration successiva
-- (V38) marca `91 Investimenti` come capex, e sul conto 91 c'è un'ENTRATA da 490,00 €
-- (rimborso «fatture riparazione muro» del 15/07/2026). Misurato su agosdb_sim: senza questa
-- colonna le entrate di luglio passerebbero da 42.059,14 a 41.569,14.
--
-- L'intervento è ADDITIVO: nessuna riga cambia secchio, si aggiunge un secchio che non esiste.
-- Le altre colonne sono riportate identiche a V3 (una MV non si ALTER-a, si ricrea).
-- ReportingService.getCashFlowMensile somma la nuova colonna nel totale entrate.

DROP MATERIALIZED VIEW IF EXISTS mv_cash_flow_statement;

CREATE MATERIALIZED VIEW mv_cash_flow_statement AS
 SELECT (EXTRACT(year FROM m.data_finanziaria))::integer AS anno,
    (EXTRACT(month FROM m.data_finanziaria))::integer AS mese,
    m.conto_bancario_id,
    cb.nome AS conto_nome,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND (NOT pc.is_capex) AND ((pc.tipo)::text <> ALL ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying, 'ATTIVITA'::character varying])::text[]))) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS entrate_operative,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'USCITA'::text) AND (NOT pc.is_capex) AND ((pc.tipo)::text <> ALL ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying, 'ATTIVITA'::character varying])::text[]))) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS uscite_operative,
    sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND (NOT pc.is_capex) AND ((pc.tipo)::text <> ALL ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying, 'ATTIVITA'::character varying])::text[]))) THEN m.importo_lordo
            WHEN (((m.tipo)::text = 'USCITA'::text) AND (NOT pc.is_capex) AND ((pc.tipo)::text <> ALL ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying, 'ATTIVITA'::character varying])::text[]))) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END) AS flusso_operativo_netto,
    sum(
        CASE
            WHEN (pc.is_capex AND ((m.tipo)::text = 'USCITA'::text)) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS uscite_investimento,
    -- NUOVA (V37): simmetrica a uscite_investimento. Un rimborso su un conto capex è un
    -- disinvestimento, non un ricavo operativo: sta nel flusso d'investimento, col suo segno.
    sum(
        CASE
            WHEN (pc.is_capex AND ((m.tipo)::text = 'ENTRATA'::text)) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS entrate_investimento,
    sum(
        CASE
            WHEN (((pc.tipo)::text = ANY ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying])::text[])) AND ((m.tipo)::text = 'USCITA'::text)) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS uscite_finanziarie,
    sum(
        CASE
            WHEN (((pc.tipo)::text = ANY ((ARRAY['PASSIVITA'::character varying, 'ONERE_FINANZIARIO'::character varying])::text[])) AND ((m.tipo)::text = 'ENTRATA'::text)) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS entrate_finanziarie
   FROM ((movimenti m
     JOIN conti_bancari cb ON ((cb.id = m.conto_bancario_id)))
     JOIN piano_dei_conti_coge pc ON ((pc.id = m.conto_coge_id)))
  WHERE (((m.stato)::text <> 'ANNULLATO'::text) AND (m.data_finanziaria IS NOT NULL))
  GROUP BY ((EXTRACT(year FROM m.data_finanziaria))::integer), ((EXTRACT(month FROM m.data_finanziaria))::integer), m.conto_bancario_id, cb.nome;

-- L'indice unico serve al REFRESH ... CONCURRENTLY di fn_refresh_all_mv().
CREATE UNIQUE INDEX idx_mv_cash_flow ON mv_cash_flow_statement USING btree (anno, mese, conto_bancario_id);
