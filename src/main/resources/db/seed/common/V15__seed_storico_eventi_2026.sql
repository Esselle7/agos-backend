-- ============================================================================
-- V15 — Seed storico eventi 2026 (fino al 28/06/2026)
-- ----------------------------------------------------------------------------
-- 56 eventi importati dai fogli "Eseguiti 2026" + "Previsione 2026"
-- (solo righe con data <= 2026-06-28). Totale ricavi: EUR 96,514.00.
--
-- Per ogni evento:
--   * 1 riga in `eventi`  -> stato SALDATO, BU2 (Cerimonie ed Eventi),
--     importo_totale_preventivato = importo_incassato = "Ricavo totale".
--   * 1+ righe in `movimenti` -> ENTRATA / tipo_evento_movimento=SALDO,
--     importo_lordo = incasso, collegata all'evento. La somma dei movimenti di
--     un evento = importo_incassato dell'evento.
--     data_movimento = data_competenza = data_finanziaria = data_liquidita
--     = data evento (competenza economica = data evento; data_competenza
--     valorizzata perche' mv_conto_economico_mensile filtra IS NOT NULL).
--     conto_bancario_id riconciliato a mano dai bonifici import
--     (eventi_da_riconciliare): 1=Banco BPM, 2=Credit Agricole. SOLO dove esiste
--     un bonifico reale agganciato; dove l'import non ha prova -> NULL, niente
--     banca inventata, per non gonfiare i saldi banca. Onesto > cosmetico.
--   * 2 eventi (Molteni 03/05, Veronica 20/06) hanno prova di pagamento su 2
--     banche: spezzati in piu' movimenti (CA + BPM + residuo cash NULL).
--
-- Idempotente: re-run sicuro (salta gli eventi (data_evento, nome) gia' presenti;
-- i movimenti vengono creati solo per gli eventi effettivamente inseriti).
--
-- Eseguibile sia via Flyway (profilo %dev, classpath:db/seed/dev) sia a mano
-- in produzione:  psql "$DB_URL" -f V15__seed_storico_eventi_2026.sql
-- ============================================================================

WITH dati(data_evento, nome, tipo, ricavo) AS (
    VALUES
        ('2026-01-03'::date, 'Botta', 'BANCHETTO_PRIVATO', 700.00::numeric),
        ('2026-01-10'::date, 'Evento del 10/01/2026', 'BANCHETTO_PRIVATO', 2500.00::numeric),
        ('2026-01-23'::date, 'chef&chef', 'BANCHETTO_PRIVATO', 600.00::numeric),
        ('2026-01-24'::date, '18esimo Marika lupo', 'BANCHETTO_PRIVATO', 755.00::numeric),
        ('2026-02-07'::date, '18esimo', 'BANCHETTO_PRIVATO', 1350.00::numeric),
        ('2026-02-15'::date, 'Battesimo pranzo Carmela', 'BANCHETTO_PRIVATO', 2300.00::numeric),
        ('2026-02-21'::date, 'Ceramella', 'BANCHETTO_PRIVATO', 1550.00::numeric),
        ('2026-02-22'::date, 'Battesimo pom Alice Croce', 'BANCHETTO_PRIVATO', 3100.00::numeric),
        ('2026-02-27'::date, '18esimo Spanò', 'BANCHETTO_PRIVATO', 1430.00::numeric),
        ('2026-02-28'::date, '18esimo Aiani', 'BANCHETTO_PRIVATO', 700.00::numeric),
        ('2026-03-01'::date, 'Compleanno Briccola', 'BANCHETTO_PRIVATO', 2300.00::numeric),
        ('2026-03-06'::date, '18esimo Figlia Moreno', 'BANCHETTO_PRIVATO', 725.00::numeric),
        ('2026-03-07'::date, 'Patrizia Casolari', 'BANCHETTO_PRIVATO', 2145.00::numeric),
        ('2026-03-13'::date, 'Spinelli', 'BANCHETTO_PRIVATO', 1784.00::numeric),
        ('2026-03-14'::date, 'Alessandro ceramella', 'BANCHETTO_PRIVATO', 600.00::numeric),
        ('2026-03-14'::date, '18esimo Valli', 'BANCHETTO_PRIVATO', 1350.00::numeric),
        ('2026-03-17'::date, 'Slow food', 'BANCHETTO_PRIVATO', 1100.00::numeric),
        ('2026-03-19'::date, 'Falconeri', 'BANCHETTO_PRIVATO', 300.00::numeric),
        ('2026-03-21'::date, 'Complenno Luigi e Sonia', 'BANCHETTO_PRIVATO', 2100.00::numeric),
        ('2026-03-22'::date, 'Gender reveal Letizia Seminara', 'BANCHETTO_PRIVATO', 1055.00::numeric),
        ('2026-03-27'::date, '18esimo figlio Anna di martino', 'BANCHETTO_PRIVATO', 2490.00::numeric),
        ('2026-03-28'::date, 'Cavadini', 'BANCHETTO_PRIVATO', 1250.00::numeric),
        ('2026-03-29'::date, 'Gianni', 'BANCHETTO_PRIVATO', 1000.00::numeric),
        ('2026-04-11'::date, '18esimo Gallazzi', 'BANCHETTO_PRIVATO', 985.00::numeric),
        ('2026-04-12'::date, 'Battesimo Luana', 'BANCHETTO_PRIVATO', 2800.00::numeric),
        ('2026-04-16'::date, 'laurea con menù Montalbano', 'BANCHETTO_PRIVATO', 1700.00::numeric),
        ('2026-04-18'::date, 'Matrimonio Romina pozzi', 'MATRIMONIO', 6000.00::numeric),
        ('2026-04-19'::date, 'Pelli', 'BANCHETTO_PRIVATO', 920.00::numeric),
        ('2026-04-25'::date, '18esimo Fabrizio Miglio', 'BANCHETTO_PRIVATO', 945.00::numeric),
        ('2026-05-01'::date, '18esimo', 'BANCHETTO_PRIVATO', 800.00::numeric),
        ('2026-05-02'::date, '18 esimo Mirella', 'BANCHETTO_PRIVATO', 800.00::numeric),
        ('2026-05-03'::date, 'Battesimo Laura Molteni', 'BANCHETTO_PRIVATO', 3300.00::numeric),
        ('2026-05-08'::date, 'Compleanno Rebecca', 'BANCHETTO_PRIVATO', 700.00::numeric),
        ('2026-05-09'::date, '18esimo Teresa', 'BANCHETTO_PRIVATO', 2750.00::numeric),
        ('2026-05-10'::date, 'Comunione Cilente (baita brunate)', 'BANCHETTO_PRIVATO', 2330.00::numeric),
        ('2026-05-16'::date, 'rezzonico', 'BANCHETTO_PRIVATO', 4350.00::numeric),
        ('2026-05-17'::date, 'Pranzo Alberto', 'BANCHETTO_PRIVATO', 2190.00::numeric),
        ('2026-05-17'::date, 'cena affitto Dario', 'BANCHETTO_PRIVATO', 700.00::numeric),
        ('2026-05-22'::date, '18esimo Solero', 'BANCHETTO_PRIVATO', 1100.00::numeric),
        ('2026-05-23'::date, 'Pranzo 8eventi', 'BANCHETTO_PRIVATO', 1700.00::numeric),
        ('2026-05-23'::date, 'Figlia Alina', 'BANCHETTO_PRIVATO', 500.00::numeric),
        ('2026-05-24'::date, 'Diego Dominioni', 'BANCHETTO_PRIVATO', 1500.00::numeric),
        ('2026-05-29'::date, '18esimo di conza', 'BANCHETTO_PRIVATO', 800.00::numeric),
        ('2026-05-30'::date, 'Gender Mela', 'BANCHETTO_PRIVATO', 700.00::numeric),
        ('2026-05-30'::date, 'Carolina Bosco', 'BANCHETTO_PRIVATO', 1700.00::numeric),
        ('2026-05-31'::date, 'Miriam', 'BANCHETTO_PRIVATO', 900.00::numeric),
        ('2026-06-01'::date, '18esimo Matilde Zanini', 'BANCHETTO_PRIVATO', 1900.00::numeric),
        ('2026-06-05'::date, 'Buffet Laura gruppo scuola', 'BANCHETTO_PRIVATO', 1000.00::numeric),
        ('2026-06-06'::date, 'Greg', 'BANCHETTO_PRIVATO', 5300.00::numeric),
        ('2026-06-07'::date, 'Battesimo Beatrice', 'BANCHETTO_PRIVATO', 2160.00::numeric),
        ('2026-06-13'::date, 'Matrimonio (chef &chef) sig. Matteo', 'MATRIMONIO', 1000.00::numeric),
        ('2026-06-19'::date, '18esimo Iris brunori', 'BANCHETTO_PRIVATO', 800.00::numeric),
        ('2026-06-20'::date, '18esimo Veronica', 'BANCHETTO_PRIVATO', 2100.00::numeric),
        ('2026-06-21'::date, 'Battesimo Giorgia', 'BANCHETTO_PRIVATO', 3300.00::numeric),
        ('2026-06-27'::date, 'Matrimonio Gianluca', 'MATRIMONIO', 5000.00::numeric),
        ('2026-06-28'::date, 'Compleanno Benedetta event planner', 'BANCHETTO_PRIVATO', 600.00::numeric)
),
mov(data_evento, nome, banca, importo) AS (
    VALUES
        ('2026-01-03'::date, 'Botta', NULL::smallint, 700.00::numeric),
        ('2026-01-10'::date, 'Evento del 10/01/2026', 2::smallint, 2500.00::numeric),
        ('2026-01-23'::date, 'chef&chef', NULL::smallint, 600.00::numeric),
        ('2026-01-24'::date, '18esimo Marika lupo', 1::smallint, 755.00::numeric),
        ('2026-02-07'::date, '18esimo', NULL::smallint, 1350.00::numeric),
        ('2026-02-15'::date, 'Battesimo pranzo Carmela', NULL::smallint, 2300.00::numeric),
        ('2026-02-21'::date, 'Ceramella', NULL::smallint, 1550.00::numeric),
        ('2026-02-22'::date, 'Battesimo pom Alice Croce', NULL::smallint, 3100.00::numeric),
        ('2026-02-27'::date, '18esimo Spanò', 2::smallint, 1430.00::numeric),
        ('2026-02-28'::date, '18esimo Aiani', NULL::smallint, 700.00::numeric),
        ('2026-03-01'::date, 'Compleanno Briccola', NULL::smallint, 2300.00::numeric),
        ('2026-03-06'::date, '18esimo Figlia Moreno', 2::smallint, 725.00::numeric),
        ('2026-03-07'::date, 'Patrizia Casolari', 1::smallint, 2145.00::numeric),
        ('2026-03-13'::date, 'Spinelli', 2::smallint, 1784.00::numeric),
        ('2026-03-14'::date, 'Alessandro ceramella', NULL::smallint, 600.00::numeric),
        ('2026-03-14'::date, '18esimo Valli', 2::smallint, 1350.00::numeric),
        ('2026-03-17'::date, 'Slow food', NULL::smallint, 1100.00::numeric),
        ('2026-03-19'::date, 'Falconeri', NULL::smallint, 300.00::numeric),
        ('2026-03-21'::date, 'Complenno Luigi e Sonia', 2::smallint, 2100.00::numeric),
        ('2026-03-22'::date, 'Gender reveal Letizia Seminara', 2::smallint, 1055.00::numeric),
        ('2026-03-27'::date, '18esimo figlio Anna di martino', 2::smallint, 2490.00::numeric),
        ('2026-03-28'::date, 'Cavadini', 2::smallint, 1250.00::numeric),
        ('2026-03-29'::date, 'Gianni', NULL::smallint, 1000.00::numeric),
        ('2026-04-11'::date, '18esimo Gallazzi', 2::smallint, 985.00::numeric),
        ('2026-04-12'::date, 'Battesimo Luana', 2::smallint, 2800.00::numeric),
        ('2026-04-16'::date, 'laurea con menù Montalbano', NULL::smallint, 1700.00::numeric),
        ('2026-04-18'::date, 'Matrimonio Romina pozzi', 1::smallint, 6000.00::numeric),
        ('2026-04-19'::date, 'Pelli', 2::smallint, 920.00::numeric),
        ('2026-04-25'::date, '18esimo Fabrizio Miglio', 2::smallint, 945.00::numeric),
        ('2026-05-01'::date, '18esimo', NULL::smallint, 800.00::numeric),
        ('2026-05-02'::date, '18 esimo Mirella', 2::smallint, 800.00::numeric),
        ('2026-05-03'::date, 'Battesimo Laura Molteni', 2::smallint, 1500.00::numeric),
        ('2026-05-03'::date, 'Battesimo Laura Molteni', 1::smallint, 1750.00::numeric),
        ('2026-05-03'::date, 'Battesimo Laura Molteni', NULL::smallint, 50.00::numeric),
        ('2026-05-08'::date, 'Compleanno Rebecca', NULL::smallint, 700.00::numeric),
        ('2026-05-09'::date, '18esimo Teresa', 2::smallint, 2750.00::numeric),
        ('2026-05-10'::date, 'Comunione Cilente (baita brunate)', NULL::smallint, 2330.00::numeric),
        ('2026-05-16'::date, 'rezzonico', 1::smallint, 4350.00::numeric),
        ('2026-05-17'::date, 'Pranzo Alberto', 2::smallint, 2190.00::numeric),
        ('2026-05-17'::date, 'cena affitto Dario', NULL::smallint, 700.00::numeric),
        ('2026-05-22'::date, '18esimo Solero', 2::smallint, 1100.00::numeric),
        ('2026-05-23'::date, 'Pranzo 8eventi', 2::smallint, 1700.00::numeric),
        ('2026-05-23'::date, 'Figlia Alina', NULL::smallint, 500.00::numeric),
        ('2026-05-24'::date, 'Diego Dominioni', NULL::smallint, 1500.00::numeric),
        ('2026-05-29'::date, '18esimo di conza', 2::smallint, 800.00::numeric),
        ('2026-05-30'::date, 'Gender Mela', 2::smallint, 700.00::numeric),
        ('2026-05-30'::date, 'Carolina Bosco', 2::smallint, 1700.00::numeric),
        ('2026-05-31'::date, 'Miriam', 2::smallint, 900.00::numeric),
        ('2026-06-01'::date, '18esimo Matilde Zanini', 2::smallint, 1900.00::numeric),
        ('2026-06-05'::date, 'Buffet Laura gruppo scuola', NULL::smallint, 1000.00::numeric),
        ('2026-06-06'::date, 'Greg', 1::smallint, 5300.00::numeric),
        ('2026-06-07'::date, 'Battesimo Beatrice', 2::smallint, 2160.00::numeric),
        ('2026-06-13'::date, 'Matrimonio (chef &chef) sig. Matteo', 2::smallint, 1000.00::numeric),
        ('2026-06-19'::date, '18esimo Iris brunori', NULL::smallint, 800.00::numeric),
        ('2026-06-20'::date, '18esimo Veronica', 2::smallint, 500.00::numeric),
        ('2026-06-20'::date, '18esimo Veronica', 1::smallint, 1000.00::numeric),
        ('2026-06-20'::date, '18esimo Veronica', NULL::smallint, 600.00::numeric),
        ('2026-06-21'::date, 'Battesimo Giorgia', 2::smallint, 3300.00::numeric),
        ('2026-06-27'::date, 'Matrimonio Gianluca', 2::smallint, 5000.00::numeric),
        ('2026-06-28'::date, 'Compleanno Benedetta event planner', NULL::smallint, 600.00::numeric)
),
nuovi_eventi AS (
    INSERT INTO eventi (
        nome, tipo, data_evento, importo_totale_preventivato,
        importo_incassato, caparre_incassate, costi_diretti_imputati,
        stato, business_unit_id, numero_totale_partecipanti, note, created_by
    )
    SELECT d.nome, d.tipo, d.data_evento, d.ricavo,
           d.ricavo, 0, 0,
           'SALDATO', 2, 0, 'Seed storico eventi 2026',
           '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
    FROM dati d
    WHERE NOT EXISTS (
        SELECT 1 FROM eventi e
        WHERE e.data_evento = d.data_evento AND e.nome = d.nome
    )
    RETURNING id, nome, data_evento, business_unit_id
)
INSERT INTO movimenti (
    data_movimento, data_competenza, data_finanziaria, data_liquidita,
    tipo, importo_lordo, importo_commissione,
    conto_coge_id, conto_bancario_id, business_unit_id, stato, fonte,
    tipo_evento_movimento, evento_id, descrizione, created_by
)
SELECT ne.data_evento, ne.data_evento, ne.data_evento, ne.data_evento,
       'ENTRATA', m.importo, 0,
       (SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02.002'),  -- Saldi eventi
       m.banca, ne.business_unit_id, 'ATTIVO', 'MANUALE',
       'SALDO', ne.id, '[EVENTO] ' || ne.nome || ' – SALDO',
       '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
FROM nuovi_eventi ne
JOIN mov m ON m.data_evento = ne.data_evento AND m.nome = ne.nome;

-- Refresh delle MV impattate (P&L, cash flow, redditivita' eventi) in modo
-- non-CONCURRENTLY (valido dentro la transazione del runner) e RESILIENTE:
-- refresha solo quelle esistenti, cosi' un futuro drop/rename non rompe il seed.
-- (mv_kpi_mensili e mv_riconciliazione_bancaria sono state rimosse in V14;
--  i KPI sono calcolati live da DashboardService.)
DO $$
DECLARE v text;
BEGIN
    FOREACH v IN ARRAY ARRAY['mv_conto_economico_mensile','mv_cash_flow_statement','mv_redditivita_eventi','mv_saldi_conti'] LOOP
        IF EXISTS (SELECT 1 FROM pg_matviews WHERE matviewname = v) THEN
            EXECUTE format('REFRESH MATERIALIZED VIEW %I', v);
        END IF;
    END LOOP;
END $$;
