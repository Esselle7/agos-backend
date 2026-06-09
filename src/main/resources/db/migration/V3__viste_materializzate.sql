-- ============================================================
-- V3 - VISTE MATERIALIZZATE (forma finale)
--
-- mv_conto_economico_mensile : gestione storni costo (V31)
-- mv_cash_flow_statement     : raggruppa per data_finanziaria, esclude
--                              PASSIVITA/ONERE_FINANZIARIO/ATTIVITA (V32)
-- + funzione fn_refresh_all_mv per refresh concorrente.
-- Dipende da V1 e V2.
-- ============================================================

-- ============================================================
-- mv_kpi_mensili  (KPI economici, per data_movimento = competenza)
-- ============================================================
CREATE MATERIALIZED VIEW mv_kpi_mensili AS
 SELECT (EXTRACT(year FROM m.data_movimento))::integer AS anno,
    (EXTRACT(month FROM m.data_movimento))::integer AS mese,
    m.business_unit_id,
    bu.nome AS business_unit_nome,
    sum(
        CASE
            WHEN ((m.tipo)::text = 'ENTRATA'::text) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS totale_entrate,
    sum(
        CASE
            WHEN ((m.tipo)::text = 'USCITA'::text) THEN m.importo_lordo
            ELSE (0)::numeric
        END) AS totale_uscite,
    sum(
        CASE
            WHEN ((m.tipo)::text = 'ENTRATA'::text) THEN m.importo_lordo
            ELSE (- m.importo_lordo)
        END) AS margine,
    count(*) AS n_movimenti
   FROM (movimenti m
     JOIN business_units bu ON ((bu.id = m.business_unit_id)))
  WHERE ((m.stato)::text <> 'ANNULLATO'::text)
  GROUP BY ((EXTRACT(year FROM m.data_movimento))::integer), ((EXTRACT(month FROM m.data_movimento))::integer), m.business_unit_id, bu.nome;

CREATE UNIQUE INDEX idx_mv_kpi_mensili ON mv_kpi_mensili USING btree (anno, mese, business_unit_id);

-- ============================================================
-- mv_saldi_conti
-- ============================================================
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
     LEFT JOIN movimenti m ON (((m.conto_bancario_id = cb.id) AND ((m.stato)::text <> 'ANNULLATO'::text))))
  WHERE (cb.is_active = true)
  GROUP BY cb.id, cb.nome, cb.tipo, cb.saldo_iniziale;

CREATE UNIQUE INDEX idx_mv_saldi_conti ON mv_saldi_conti USING btree (conto_id);

-- ============================================================
-- mv_conto_economico_mensile  (per data_competenza, storni costo)
-- ============================================================
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
            WHEN ((((m.tipo)::text = 'USCITA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex)) OR (((m.tipo)::text = 'ENTRATA'::text) AND ((pc.tipo)::text = 'COSTO'::text) AND (NOT pc.is_capex) AND (COALESCE(m.importo_imponibile, m.importo_lordo) < (0)::numeric))) THEN (- COALESCE(m.importo_imponibile, m.importo_lordo))
            ELSE (0)::numeric
        END) AS ebitda_proxy,
    count(*) AS n_movimenti
   FROM ((movimenti m
     JOIN business_units bu ON ((bu.id = m.business_unit_id)))
     JOIN piano_dei_conti_coge pc ON ((pc.id = m.conto_coge_id)))
  WHERE (((m.stato)::text <> 'ANNULLATO'::text) AND (m.data_competenza IS NOT NULL))
  GROUP BY ((EXTRACT(year FROM m.data_competenza))::integer), ((EXTRACT(month FROM m.data_competenza))::integer), m.business_unit_id, bu.nome, pc.codice, pc.descrizione, pc.tipo, pc.is_capex;

CREATE UNIQUE INDEX idx_mv_conto_eco ON mv_conto_economico_mensile USING btree (anno, mese, business_unit_id, codice_coge);

-- ============================================================
-- mv_cash_flow_statement  (per data_finanziaria, esclude finanz./patrim.)
-- ============================================================
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

CREATE UNIQUE INDEX idx_mv_cash_flow ON mv_cash_flow_statement USING btree (anno, mese, conto_bancario_id);

-- ============================================================
-- mv_redditivita_eventi
-- ============================================================
CREATE MATERIALIZED VIEW mv_redditivita_eventi AS
 SELECT e.id AS evento_id,
    e.nome AS evento_nome,
    e.tipo AS evento_tipo,
    e.data_evento,
    e.stato,
    e.importo_totale_preventivato,
    e.n_ospiti,
    COALESCE(sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((m.tipo_evento_movimento)::text = ANY ((ARRAY['CAPARRA'::character varying, 'ACCONTO'::character varying, 'SALDO'::character varying])::text[]))) THEN m.importo_lordo
            ELSE (0)::numeric
        END), (0)::numeric) AS ricavi_incassati,
    COALESCE(sum(
        CASE
            WHEN ((m.tipo)::text = 'USCITA'::text) THEN m.importo_lordo
            ELSE (0)::numeric
        END), (0)::numeric) AS costi_diretti,
    COALESCE(sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((m.tipo_evento_movimento)::text = ANY ((ARRAY['CAPARRA'::character varying, 'ACCONTO'::character varying, 'SALDO'::character varying])::text[]))) THEN m.importo_lordo
            WHEN ((m.tipo)::text = 'USCITA'::text) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END), (0)::numeric) AS margine_netto,
        CASE
            WHEN (e.importo_totale_preventivato > (0)::numeric) THEN round(((COALESCE(sum(
            CASE
                WHEN (((m.tipo)::text = 'ENTRATA'::text) AND ((m.tipo_evento_movimento)::text = ANY ((ARRAY['CAPARRA'::character varying, 'ACCONTO'::character varying, 'SALDO'::character varying])::text[]))) THEN m.importo_lordo
                ELSE (0)::numeric
            END), (0)::numeric) / e.importo_totale_preventivato) * (100)::numeric), 2)
            ELSE NULL::numeric
        END AS perc_incassato
   FROM (eventi e
     LEFT JOIN movimenti m ON (((m.evento_id = e.id) AND ((m.stato)::text <> 'ANNULLATO'::text))))
  GROUP BY e.id, e.nome, e.tipo, e.data_evento, e.stato, e.importo_totale_preventivato, e.n_ospiti;

CREATE UNIQUE INDEX idx_mv_redditivita_eventi ON mv_redditivita_eventi USING btree (evento_id);

-- ============================================================
-- mv_riconciliazione_bancaria
-- ============================================================
CREATE MATERIALIZED VIEW mv_riconciliazione_bancaria AS
 SELECT cb.id AS conto_id,
    cb.nome AS conto_nome,
    sb.data_riferimento,
    sb.saldo AS saldo_reale,
    (cb.saldo_iniziale + COALESCE(sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND (m.data_finanziaria <= sb.data_riferimento)) THEN m.importo_lordo
            WHEN (((m.tipo)::text = 'USCITA'::text) AND (m.data_finanziaria <= sb.data_riferimento)) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END), (0)::numeric)) AS saldo_calcolato,
    (sb.saldo - (cb.saldo_iniziale + COALESCE(sum(
        CASE
            WHEN (((m.tipo)::text = 'ENTRATA'::text) AND (m.data_finanziaria <= sb.data_riferimento)) THEN m.importo_lordo
            WHEN (((m.tipo)::text = 'USCITA'::text) AND (m.data_finanziaria <= sb.data_riferimento)) THEN (- m.importo_lordo)
            ELSE (0)::numeric
        END), (0)::numeric))) AS scarto
   FROM ((saldi_banca sb
     JOIN conti_bancari cb ON ((cb.id = sb.conto_id)))
     LEFT JOIN movimenti m ON (((m.conto_bancario_id = cb.id) AND ((m.stato)::text <> 'ANNULLATO'::text) AND (m.data_finanziaria IS NOT NULL))))
  GROUP BY cb.id, cb.nome, sb.data_riferimento, sb.saldo, cb.saldo_iniziale;

CREATE UNIQUE INDEX idx_mv_riconciliazione ON mv_riconciliazione_bancaria USING btree (conto_id, data_riferimento);

-- ============================================================
-- Funzione di refresh concorrente di tutte le MV
-- ============================================================
CREATE FUNCTION fn_refresh_all_mv() RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_kpi_mensili;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_saldi_conti;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_conto_economico_mensile;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_cash_flow_statement;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_redditivita_eventi;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_riconciliazione_bancaria;
END;
$$;
