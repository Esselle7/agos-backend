-- ============================================================
-- V14 — Rimozione catena morta (riconciliazione bancaria + KPI mensili)
-- ============================================================
-- Verificato (codice, test, funzioni e viste SQL): zero reference applicative.
--   saldi_banca                 → mai scritta (no entity/repo/seed/INSERT), letta
--                                 solo dalla MV qui sotto.
--   mv_riconciliazione_bancaria → mai letta; dipende da saldi_banca (sempre vuota).
--   mv_kpi_mensili              → mai letta; i KPI sono calcolati da DashboardService
--                                 direttamente sulle tabelle base (movimenti/eventi).
--
-- Ordine: prima la MV (dipende da saldi_banca), poi la tabella. Sequence e index
-- posseduti da saldi_banca cadono in automatico col DROP TABLE.
-- ============================================================

DROP MATERIALIZED VIEW IF EXISTS mv_riconciliazione_bancaria;
DROP MATERIALIZED VIEW IF EXISTS mv_kpi_mensili;
DROP TABLE IF EXISTS saldi_banca;

-- fn_refresh_all_mv senza le due MV rimosse (restano le 4 vive).
CREATE OR REPLACE FUNCTION fn_refresh_all_mv() RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_saldi_conti;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_conto_economico_mensile;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_cash_flow_statement;
    REFRESH MATERIALIZED VIEW CONCURRENTLY mv_redditivita_eventi;
END;
$$;
