-- ============================================================================
-- V26 — Anagrafica eventi 2026 completa (file cliente "Eventi Agriturismo 2026.xlsx",
--       fogli "Eseguiti 2026" + "Previsione 2026", ricevuto il 07/08/2026).
-- ----------------------------------------------------------------------------
-- PERCHE': dopo il reset del 05/08 l'anagrafica aveva 58 eventi; l'import di luglio
-- ha lasciato 26 incassi (18.924,00 EUR) parcheggiati in `eventi_da_riconciliare`
-- perche' gli eventi corrispondenti non esistevano. Qui entra TUTTO il 2026:
-- 94 eventi (58 storici chiusi + 36 aperti/futuri).
--
-- REGOLE (una sola ragione di cambiare per ognuna):
--  1. NON duplica: ogni riga del file viene agganciata a un evento esistente sulla
--     stessa data con nome "normalizzato" uguale o contenuto (V15 aveva salvato
--     'Greg' dove il file ora scrive 'Matrimonio Greg'). Solo il resto viene INSERITO.
--  2. VALORE ZERO: nessun movimento viene creato. Lo storico eventi resta consultabile
--     ma contabilmente azzerato (i 64 movimenti-evento a DB sono tutti ANNULLATO):
--     sum(mv_saldi_conti.saldo_calcolato) NON cambia per effetto di questa migration.
--     I soldi entrano solo dall'attribuzione degli incassi parcheggiati
--     (EventiService.registraPagamento), che e' l'unica porta del ricavo evento.
--  3. STORICI (data <= 30/06): stato SALDATO, preventivato = incassato = Ricavo totale.
--     Stessa forma dei 56 gia' seminati da V15, cosi' la lista eventi resta omogenea.
--  4. APERTI (data >= 01/07) + 06/06 e 28/06: stato CONFERMATO, incassato = 0.
--     Sono gli eventi verso cui puntano i bonifici di luglio ancora in coda:
--     SALDATO li renderebbe non pagabili (EventiService:252) e incassato > 0 senza
--     movimenti dietro verrebbe comunque azzerato dal primo ricalcolaIncassi.
--     Il preventivato pieno (Ricavo totale) tiene il residuo capiente: senza,
--     l'attribuzione muore con IMPORTO_SUPERA_RESIDUO (EventiService:292-299).
--     06/06 e 28/06 sono a giugno ma hanno un bonifico di luglio in coda
--     (PASCHETTO DAVIDE 1.500 -> "Matrimonio Greg"; GIOVACCHINI 300 -> 28/06).
--  5. PREVENTIVATO ALLINEATO AL FILE (fonte del cliente) solo dove l'unica voce e'
--     il 'Preventivo iniziale' di V15: dove il cliente ha inserito a mano voci di
--     dettaglio (Menu adulti, Torta, ...) il suo dato vince e non viene toccato.
--  6. VOCI: `eventi.importo_totale_preventivato` e' ricalcolato dal service come
--     somma delle voci (V18). Ogni evento nuovo nasce quindi con le sue voci
--     Affitto/Menu prese dalle colonne del file: senza, il primo ricalcolo
--     porterebbe il preventivato a zero.
--
-- FUORI: la riga 32 di "Previsione 2026" (Munzone / Matrimonio, 2027) e' esclusa —
-- non ha una data, e inventarla falserebbe competenza economica e calendario.
-- Il foglio "2025" e' fuori scopo (solo nomi di mese, la ripartenza e' sul 2026).
--
-- Idempotente: rieseguibile senza effetti (il match aggancia cio' che ha gia' creato).
-- ============================================================================

CREATE TEMPORARY TABLE _v26_file (
    data_evento date        NOT NULL,
    nome        text        NOT NULL,
    contatto    text,
    tipo        varchar(50) NOT NULL,
    totale      numeric(12,2) NOT NULL,
    affitto     numeric(12,2) NOT NULL,
    menu        numeric(12,2) NOT NULL,
    classe      varchar(20) NOT NULL
) ON COMMIT DROP;

INSERT INTO _v26_file (data_evento, nome, contatto, tipo, totale, affitto, menu, classe) VALUES
    ('2026-01-03'::date, 'Botta', NULL, 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'SALDATO'),
    ('2026-01-10'::date, 'Evento del 10/01/2026', NULL, 'BANCHETTO_PRIVATO', 2500.00, 600.00, 1900.00, 'SALDATO'),
    ('2026-01-23'::date, 'chef&chef', NULL, 'BANCHETTO_PRIVATO', 600.00, 600.00, 0.00, 'SALDATO'),
    ('2026-01-24'::date, '18esimo Marika lupo', 'Marika lupo', 'BANCHETTO_PRIVATO', 755.00, 700.00, 55.00, 'SALDATO'),
    ('2026-02-07'::date, '18esimo', NULL, 'BANCHETTO_PRIVATO', 1350.00, 600.00, 750.00, 'SALDATO'),
    ('2026-02-15'::date, 'Battesimo pranzo Carmela', 'Carmela', 'BANCHETTO_PRIVATO', 2300.00, 500.00, 1800.00, 'SALDATO'),
    ('2026-02-21'::date, 'Ceramella', 'Ceramella', 'BANCHETTO_PRIVATO', 1550.00, 500.00, 1050.00, 'SALDATO'),
    ('2026-02-22'::date, 'Battesimo pom Alice Croce', 'Alice Croce', 'BANCHETTO_PRIVATO', 3100.00, 500.00, 2600.00, 'SALDATO'),
    ('2026-02-27'::date, '18esimo Spanò', 'Spanò', 'BANCHETTO_PRIVATO', 1430.00, 600.00, 830.00, 'SALDATO'),
    ('2026-02-28'::date, '18esimo Aiani', 'Aiani', 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'SALDATO'),
    ('2026-03-01'::date, 'Compleanno Briccola', 'Briccola', 'BANCHETTO_PRIVATO', 2300.00, 500.00, 1800.00, 'SALDATO'),
    ('2026-03-06'::date, '18esimo Figlia Moreno', 'Figlia Moreno', 'BANCHETTO_PRIVATO', 725.00, 600.00, 125.00, 'SALDATO'),
    ('2026-03-07'::date, 'Patrizia Casolari', 'Patrizia Casolari', 'BANCHETTO_PRIVATO', 2145.00, 600.00, 1545.00, 'SALDATO'),
    ('2026-03-13'::date, 'Spinelli', 'Spinelli', 'BANCHETTO_PRIVATO', 1784.00, 600.00, 1184.00, 'SALDATO'),
    ('2026-03-14'::date, 'Alessandro ceramella', 'Alessandro ceramella', 'BANCHETTO_PRIVATO', 600.00, 600.00, 0.00, 'SALDATO'),
    ('2026-03-14'::date, '18esimo Valli', 'Valli', 'BANCHETTO_PRIVATO', 1350.00, 800.00, 550.00, 'SALDATO'),
    ('2026-03-17'::date, 'Slow food', 'Slow food', 'BANCHETTO_PRIVATO', 1100.00, 0.00, 1100.00, 'SALDATO'),
    ('2026-03-19'::date, 'Falconeri', 'Falconeri', 'BANCHETTO_PRIVATO', 300.00, 300.00, 0.00, 'SALDATO'),
    ('2026-03-21'::date, 'Complenno Luigi e Sonia', 'Luigi e Sonia', 'BANCHETTO_PRIVATO', 2100.00, 500.00, 1600.00, 'SALDATO'),
    ('2026-03-22'::date, 'Gender reveal Letizia Seminara', 'Letizia Seminara', 'BANCHETTO_PRIVATO', 1055.00, 700.00, 355.00, 'SALDATO'),
    ('2026-03-27'::date, '18esimo figlio Anna di martino', 'figlio Anna di martino', 'BANCHETTO_PRIVATO', 2490.00, 500.00, 1990.00, 'SALDATO'),
    ('2026-03-28'::date, 'Cavadini', 'Cavadini', 'BANCHETTO_PRIVATO', 1250.00, 850.00, 400.00, 'SALDATO'),
    ('2026-03-29'::date, 'Gianni', 'Gianni', 'BANCHETTO_PRIVATO', 1000.00, 0.00, 1000.00, 'SALDATO'),
    ('2026-04-11'::date, '18esimo Gallazzi', 'Gallazzi', 'BANCHETTO_PRIVATO', 985.00, 800.00, 185.00, 'SALDATO'),
    ('2026-04-12'::date, 'Battesimo Luana', 'Luana', 'BANCHETTO_PRIVATO', 2800.00, 500.00, 2300.00, 'SALDATO'),
    ('2026-04-16'::date, 'laurea con menù Montalbano', 'Montalbano', 'BANCHETTO_PRIVATO', 1700.00, 500.00, 1200.00, 'SALDATO'),
    ('2026-04-18'::date, 'Matrimonio Romina pozzi', 'Romina pozzi', 'MATRIMONIO', 6000.00, 500.00, 5500.00, 'SALDATO'),
    ('2026-04-19'::date, 'Pelli', 'Pelli', 'BANCHETTO_PRIVATO', 920.00, 700.00, 220.00, 'SALDATO'),
    ('2026-04-25'::date, '18esimo Fabrizio Miglio', 'Fabrizio Miglio', 'BANCHETTO_PRIVATO', 945.00, 800.00, 145.00, 'SALDATO'),
    ('2026-05-01'::date, '18esimo', NULL, 'BANCHETTO_PRIVATO', 800.00, 800.00, 0.00, 'SALDATO'),
    ('2026-05-02'::date, '18 esimo Mirella', 'Mirella', 'BANCHETTO_PRIVATO', 800.00, 800.00, 0.00, 'SALDATO'),
    ('2026-05-03'::date, 'Battesimo Laura Molteni', 'Laura Molteni', 'BANCHETTO_PRIVATO', 3300.00, 500.00, 2800.00, 'SALDATO'),
    ('2026-05-08'::date, 'Compleanno Rebecca', 'Rebecca', 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'SALDATO'),
    ('2026-05-09'::date, '18esimo Teresa', 'Teresa', 'BANCHETTO_PRIVATO', 2750.00, 500.00, 2250.00, 'SALDATO'),
    ('2026-05-10'::date, 'Comunione Cilente (baita brunate)', 'Cilente (baita brunate)', 'BANCHETTO_PRIVATO', 2330.00, 500.00, 1830.00, 'SALDATO'),
    ('2026-05-16'::date, 'rezzonico', NULL, 'BANCHETTO_PRIVATO', 4350.00, 500.00, 3850.00, 'SALDATO'),
    ('2026-05-17'::date, 'Pranzo Alberto', 'Alberto', 'BANCHETTO_PRIVATO', 2190.00, 600.00, 1590.00, 'SALDATO'),
    ('2026-05-17'::date, 'cena affitto Dario', 'Dario', 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'SALDATO'),
    ('2026-05-22'::date, '18esimo Solero', 'Solero', 'BANCHETTO_PRIVATO', 800.00, 800.00, 0.00, 'SALDATO'),
    ('2026-05-23'::date, 'Pranzo 8eventi', '8eventi', 'BANCHETTO_PRIVATO', 2200.00, 500.00, 1700.00, 'SALDATO'),
    ('2026-05-23'::date, 'Figlia Alina', NULL, 'BANCHETTO_PRIVATO', 600.00, 500.00, 100.00, 'SALDATO'),
    ('2026-05-24'::date, 'Diego Dominioni', 'Diego Dominioni', 'BANCHETTO_PRIVATO', 1000.00, 0.00, 1000.00, 'SALDATO'),
    ('2026-05-29'::date, '18esimo di conza', 'di conza', 'BANCHETTO_PRIVATO', 920.00, 800.00, 120.00, 'SALDATO'),
    ('2026-05-30'::date, 'Gender Mela', 'Mela', 'BANCHETTO_PRIVATO', 1430.00, 700.00, 730.00, 'SALDATO'),
    ('2026-05-30'::date, 'Carolina Bosco', 'Carolina Bosco', 'BANCHETTO_PRIVATO', 1800.00, 600.00, 1200.00, 'SALDATO'),
    ('2026-05-31'::date, 'Miriam', NULL, 'BANCHETTO_PRIVATO', 756.00, 700.00, 56.00, 'SALDATO'),
    ('2026-06-01'::date, '18esimo Matilde Zanini', 'Matilde Zanini', 'BANCHETTO_PRIVATO', 1360.00, 800.00, 560.00, 'SALDATO'),
    ('2026-06-05'::date, 'Buffet Laura gruppo scuola', 'Laura gruppo scuola', 'BANCHETTO_PRIVATO', 1362.00, 0.00, 1362.00, 'SALDATO'),
    ('2026-06-06'::date, 'Matrimonio Greg', NULL, 'MATRIMONIO', 8400.00, 500.00, 7900.00, 'CONFERMATO'),
    ('2026-06-07'::date, 'Battesimo Beatrice', 'Beatrice', 'BANCHETTO_PRIVATO', 2240.00, 600.00, 1640.00, 'SALDATO'),
    ('2026-06-09'::date, 'Guess', 'Guess', 'BANCHETTO_PRIVATO', 3150.00, 600.00, 2550.00, 'SALDATO'),
    ('2026-06-10'::date, '18esimo Berselli Marta', 'Berselli Marta', 'BANCHETTO_PRIVATO', 750.00, 700.00, 50.00, 'SALDATO'),
    ('2026-06-13'::date, 'Matrimonio (chef &chef) sig. Matteo', '(chef &chef) sig. Matteo', 'MATRIMONIO', 1000.00, 1000.00, 0.00, 'SALDATO'),
    ('2026-06-19'::date, '18esimo Iris brunori', 'Iris brunori', 'BANCHETTO_PRIVATO', 980.00, 800.00, 180.00, 'SALDATO'),
    ('2026-06-20'::date, '18esimo Veronica', 'Veronica', 'BANCHETTO_PRIVATO', 1921.00, 600.00, 1321.00, 'SALDATO'),
    ('2026-06-21'::date, 'Battesimo Giorgia', 'Giorgia', 'BANCHETTO_PRIVATO', 3300.00, 500.00, 2800.00, 'SALDATO'),
    ('2026-06-25'::date, 'affitto chef', NULL, 'BANCHETTO_PRIVATO', 1500.00, 1500.00, 0.00, 'SALDATO'),
    ('2026-06-26'::date, 'affitto 60esimo + asporto Bursi', 'Bursi', 'BANCHETTO_PRIVATO', 1100.00, 700.00, 400.00, 'SALDATO'),
    ('2026-06-27'::date, 'Matrimonio Gianluca', 'Gianluca', 'MATRIMONIO', 9200.00, 500.00, 8700.00, 'SALDATO'),
    ('2026-06-28'::date, 'Compleanno Benedetta event planner', 'Benedetta event planner', 'BANCHETTO_PRIVATO', 600.00, 600.00, 0.00, 'CONFERMATO'),
    ('2026-07-01'::date, 'Pensionamento rif. Meani', 'rif. Meani', 'BANCHETTO_PRIVATO', 2550.00, 500.00, 2050.00, 'CONFERMATO'),
    ('2026-07-04'::date, 'Matrimonio eleonora doria', 'eleonora doria', 'MATRIMONIO', 5100.00, 500.00, 4600.00, 'CONFERMATO'),
    ('2026-07-05'::date, 'Compleanno Stefania', 'Stefania', 'BANCHETTO_PRIVATO', 2900.00, 600.00, 2300.00, 'CONFERMATO'),
    ('2026-07-06'::date, '18esimo Erica balzaretti', 'Erica balzaretti', 'BANCHETTO_PRIVATO', 1050.00, 800.00, 250.00, 'CONFERMATO'),
    ('2026-07-08'::date, '18esimo Marianna', 'Marianna', 'BANCHETTO_PRIVATO', 930.00, 800.00, 130.00, 'CONFERMATO'),
    ('2026-07-09'::date, 'Matrimonio roberta', 'roberta', 'MATRIMONIO', 3600.00, 500.00, 3100.00, 'CONFERMATO'),
    ('2026-07-10'::date, '18esimo Cappelli barbara', 'Cappelli barbara', 'BANCHETTO_PRIVATO', 940.00, 900.00, 40.00, 'CONFERMATO'),
    ('2026-07-11'::date, '30 esimo cena Rasile', 'Rasile', 'BANCHETTO_PRIVATO', 2500.00, 600.00, 1900.00, 'CONFERMATO'),
    ('2026-07-12'::date, 'GENDER', NULL, 'BANCHETTO_PRIVATO', 770.00, 700.00, 70.00, 'CONFERMATO'),
    ('2026-07-16'::date, 'Cena speaking Toastmaster (Marzia Radaelli)', 'Toastmaster (Marzia Radaelli)', 'BANCHETTO_PRIVATO', 1350.00, 0.00, 1350.00, 'CONFERMATO'),
    ('2026-07-18'::date, 'Compleanno Alessia Corti', 'Alessia Corti', 'BANCHETTO_PRIVATO', 1080.00, 700.00, 380.00, 'CONFERMATO'),
    ('2026-07-19'::date, 'battesimo Elena Molteni', 'Elena Molteni', 'BANCHETTO_PRIVATO', 3550.00, 500.00, 3050.00, 'CONFERMATO'),
    ('2026-07-25'::date, 'afftto 30esimo Sperduto', 'Sperduto', 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'CONFERMATO'),
    ('2026-07-26'::date, 'Carlo operaio', 'Carlo operaio', 'BANCHETTO_PRIVATO', 500.00, 0.00, 500.00, 'CONFERMATO'),
    ('2026-08-16'::date, 'afftto Jaana', 'Jaana', 'BANCHETTO_PRIVATO', 1500.00, 1500.00, 0.00, 'CONFERMATO'),
    ('2026-08-29'::date, 'Matrimonio Locatelli', 'Locatelli', 'MATRIMONIO', 3200.00, 500.00, 2700.00, 'CONFERMATO'),
    ('2026-08-30'::date, '70esimo affitto Torchia', 'Torchia', 'BANCHETTO_PRIVATO', 700.00, 700.00, 0.00, 'CONFERMATO'),
    ('2026-08-30'::date, '18esimo Buttazzoni', 'Buttazzoni', 'BANCHETTO_PRIVATO', 800.00, 800.00, 0.00, 'CONFERMATO'),
    ('2026-09-04'::date, 'Pre- matrimonio Marika', 'Marika', 'MATRIMONIO', 1600.00, 600.00, 1000.00, 'CONFERMATO'),
    ('2026-09-05'::date, 'Battesimo Alessia Francolini', 'Alessia Francolini', 'BANCHETTO_PRIVATO', 2200.00, 600.00, 1600.00, 'CONFERMATO'),
    ('2026-09-06'::date, '18esimo stefania', 'stefania', 'BANCHETTO_PRIVATO', 1800.00, 650.00, 1150.00, 'CONFERMATO'),
    ('2026-09-12'::date, 'Cena Lina mamma Angelica', 'Lina mamma Angelica', 'BANCHETTO_PRIVATO', 2000.00, 600.00, 1400.00, 'CONFERMATO'),
    ('2026-09-13'::date, 'Pranzo Alberto', 'Alberto', 'BANCHETTO_PRIVATO', 1600.00, 600.00, 1000.00, 'CONFERMATO'),
    ('2026-09-18'::date, 'Festa ospedale Erika cella', 'Erika cella', 'BANCHETTO_PRIVATO', 3000.00, 0.00, 3000.00, 'CONFERMATO'),
    ('2026-09-19'::date, 'Matrimonio Sara cantaluppi', 'Sara cantaluppi', 'MATRIMONIO', 4200.00, 500.00, 3700.00, 'CONFERMATO'),
    ('2026-09-20'::date, 'Compleanno Ravera', 'Ravera', 'BANCHETTO_PRIVATO', 2300.00, 500.00, 1800.00, 'CONFERMATO'),
    ('2026-09-26'::date, 'Matrimonio Marina Lo Schiavo', 'Marina Lo Schiavo', 'MATRIMONIO', 1700.00, 500.00, 1200.00, 'CONFERMATO'),
    ('2026-09-27'::date, 'Compleanno Erica parrucchiera', 'Erica parrucchiera', 'BANCHETTO_PRIVATO', 2600.00, 500.00, 2100.00, 'CONFERMATO'),
    ('2026-10-17'::date, 'Comunione Negretti', 'Negretti', 'BANCHETTO_PRIVATO', 1600.00, 600.00, 1000.00, 'CONFERMATO'),
    ('2026-10-23'::date, '18esimo Cristina', 'Cristina', 'BANCHETTO_PRIVATO', 900.00, 900.00, 0.00, 'CONFERMATO'),
    ('2026-10-25'::date, 'Battesimo Koral battesimo figlia', 'Koral battesimo figlia', 'BANCHETTO_PRIVATO', 2600.00, 500.00, 2100.00, 'CONFERMATO'),
    ('2026-11-14'::date, 'Roberta Cuteri', 'Roberta Cuteri', 'BANCHETTO_PRIVATO', 3000.00, 500.00, 2500.00, 'CONFERMATO'),
    ('2026-12-04'::date, 'Chiara Tigano', 'Chiara Tigano', 'BANCHETTO_PRIVATO', 900.00, 900.00, 0.00, 'CONFERMATO'),
    ('2026-12-05'::date, 'Abarth', 'Abarth', 'BANCHETTO_PRIVATO', 3700.00, 700.00, 3000.00, 'CONFERMATO');

-- Chiave di confronto: minuscolo, senza accenti, senza spazi/punteggiatura.
-- Vive in un solo posto (questa vista) ed e' usata sia dal match sia dalle guardie.
CREATE TEMPORARY VIEW _v26_norm AS
SELECT f.*,
       lower(regexp_replace(translate(f.nome,     'àèéìòùÀÈÉÌÒÙ', 'aeeiouAEEIOU'), '[^a-zA-Z0-9]', '', 'g')) AS nome_norm,
       lower(regexp_replace(translate(coalesce(f.contatto,''), 'àèéìòùÀÈÉÌÒÙ', 'aeeiouAEEIOU'), '[^a-zA-Z0-9]', '', 'g')) AS contatto_norm
FROM _v26_file f;

-- Risoluzione riga-file -> evento esistente (NULL = da inserire). Il LATERAL
-- garantisce al piu' un evento per riga, preferendo il nome identico.
CREATE TEMPORARY TABLE _v26_match ON COMMIT DROP AS
SELECT f.*, ex.id AS evento_id
FROM _v26_norm f
LEFT JOIN LATERAL (
    SELECT e.id, (en.nome_norm = f.nome_norm) AS esatto
    FROM eventi e
    CROSS JOIN LATERAL (SELECT lower(regexp_replace(translate(e.nome, 'àèéìòùÀÈÉÌÒÙ', 'aeeiouAEEIOU'), '[^a-zA-Z0-9]', '', 'g')) AS nome_norm) en
    WHERE e.is_segnaposto = false
      AND e.data_evento = f.data_evento
      AND (   en.nome_norm = f.nome_norm
           OR (f.contatto_norm <> '' AND en.nome_norm = f.contatto_norm)
           OR (length(en.nome_norm) >= 4 AND position(en.nome_norm IN f.nome_norm) > 0)
           OR (length(f.nome_norm)  >= 4 AND position(f.nome_norm  IN en.nome_norm) > 0))
    ORDER BY esatto DESC, e.id
    LIMIT 1
) ex ON true;

-- Fail fast: due righe del file non possono agganciare lo stesso evento (match sballato).
DO $$
DECLARE d int;
BEGIN
    SELECT count(*) INTO d FROM (
        SELECT evento_id FROM _v26_match WHERE evento_id IS NOT NULL
        GROUP BY evento_id HAVING count(*) > 1) t;
    IF d > 0 THEN
        RAISE EXCEPTION 'V26: % eventi agganciati da piu di una riga del file: match ambiguo, migration interrotta', d;
    END IF;
END $$;

-- ── 1a. Eventi gia' presenti con la sola voce 'Preventivo iniziale' di V15 ────
UPDATE evento_voce v
SET importo_preventivo = m.totale,
    importo_consuntivo = CASE WHEN v.importo_consuntivo IS NULL THEN NULL ELSE m.totale END,
    updated_at = now()
FROM _v26_match m
WHERE v.evento_id = m.evento_id
  AND v.label = 'Preventivo iniziale'
  AND v.importo_preventivo IS DISTINCT FROM m.totale
  AND NOT EXISTS (SELECT 1 FROM evento_voce v2
                  WHERE v2.evento_id = m.evento_id AND v2.label <> 'Preventivo iniziale');

-- ── 1b. Eventi gia' presenti con voci di DETTAGLIO che non quadrano col file ──
--       Il foglio del cliente e' la fonte di verita' (decisione committente 08/08/2026):
--       dove le voci inserite a mano danno un totale diverso, vince il file e le voci
--       vengono riscritte sulla sua ripartizione (Affitto / Menu).
--       Misurato sul dump di prod del 07/08: sono esattamente 2 eventi —
--         08/07 Marianna       950,00 (Affitto 700 + Sicurezza 100 + Torta 150) -> 930,00
--         19/07 Elena molteni 4365,00 (500 + 3375 + 300 + 190)                  -> 3550,00
--       Allineare il solo totale non reggerebbe: il service lo ricalcola come somma
--       delle voci (V18, EventiService:1035) e il valore tornerebbe indietro.
--       La ripartizione precedente NON si perde in silenzio: finisce in eventi.note.
--       Idempotente: il blocco e' guardato da "somma voci <> totale del file", quindi
--       alla seconda esecuzione non trova piu' nulla da fare.

-- Fail fast: una voce agganciata a un costo diretto non si cancella alla leggera
-- (evento_costi_diretti resterebbe orfano). Se capitasse, la migration si ferma.
DO $$
DECLARE k int;
BEGIN
    SELECT count(*) INTO k
    FROM evento_voce v JOIN _v26_match m ON m.evento_id = v.evento_id
    WHERE v.costo_diretto_id IS NOT NULL
      AND (SELECT coalesce(sum(v2.importo_preventivo),0) FROM evento_voce v2
           WHERE v2.evento_id = m.evento_id) IS DISTINCT FROM m.totale;
    IF k > 0 THEN
        RAISE EXCEPTION 'V26: % voci con costo diretto in un evento da riallineare al file: intervento manuale', k;
    END IF;
END $$;

CREATE TEMPORARY TABLE _v26_riallinea ON COMMIT DROP AS
SELECT m.evento_id, m.totale, m.affitto, m.menu,
       (SELECT string_agg(v.label || ' ' || to_char(v.importo_preventivo, 'FM999G999D00'), ' + '
                          ORDER BY v.id)
        FROM evento_voce v WHERE v.evento_id = m.evento_id) AS voci_prima
FROM _v26_match m
WHERE m.evento_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM evento_voce v2
              WHERE v2.evento_id = m.evento_id AND v2.label <> 'Preventivo iniziale')
  AND (SELECT coalesce(sum(v3.importo_preventivo),0) FROM evento_voce v3
       WHERE v3.evento_id = m.evento_id) IS DISTINCT FROM m.totale;

UPDATE eventi e
SET note = coalesce(e.note || E'\n', '')
           || '[V26 08/08/2026] Preventivo riallineato al file cliente ('
           || to_char(r.totale, 'FM999G999D00') || ' EUR). Ripartizione precedente: ' || r.voci_prima || '.'
FROM _v26_riallinea r
WHERE e.id = r.evento_id;

DELETE FROM evento_voce v USING _v26_riallinea r WHERE v.evento_id = r.evento_id;

INSERT INTO evento_voce (evento_id, catalogo_id, label, importo_preventivo, importo_consuntivo, origine, created_by)
SELECT r.evento_id, c.id, c.label, v.importo, NULL, 'MANUALE',
       '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
FROM _v26_riallinea r
CROSS JOIN LATERAL (VALUES ('Affitto', r.affitto), ('Menu', r.menu)) AS v(label, importo)
JOIN evento_voce_catalogo c ON c.label = v.label
WHERE v.importo > 0;

-- ── 1c. Totale sull'evento = totale del file, senza eccezioni ────────────────
UPDATE eventi e
SET importo_totale_preventivato = m.totale
FROM _v26_match m
WHERE e.id = m.evento_id
  AND e.importo_totale_preventivato IS DISTINCT FROM m.totale;

-- ── 2. Eventi gia' presenti: stato e incassato ──────────────────────────────
--     CONFERMATO + incassato 0 = pagabile (i bonifici di luglio possono entrare).
--     SALDATO + incassato = preventivato = storico chiuso, coerente con V15.
UPDATE eventi e
SET stato             = m.classe,
    importo_incassato = CASE WHEN m.classe = 'SALDATO' THEN e.importo_totale_preventivato ELSE 0 END,
    caparre_incassate = 0
FROM _v26_match m
WHERE e.id = m.evento_id
  AND (e.stato <> m.classe
       OR e.importo_incassato IS DISTINCT FROM
          (CASE WHEN m.classe = 'SALDATO' THEN e.importo_totale_preventivato ELSE 0::numeric END));

-- ── 3. Eventi mancanti: INSERT (nessun movimento) ───────────────────────────
CREATE TEMPORARY TABLE _v26_nuovi ON COMMIT DROP AS
WITH ins AS (
    INSERT INTO eventi (
        nome, tipo, data_evento, importo_totale_preventivato, importo_incassato,
        caparre_incassate, costi_diretti_imputati, stato, business_unit_id,
        contatto_nome, numero_totale_partecipanti, note, is_segnaposto, created_by)
    SELECT m.nome, m.tipo, m.data_evento, m.totale,
           CASE WHEN m.classe = 'SALDATO' THEN m.totale ELSE 0 END,
           0, 0, m.classe, 2,
           m.contatto, 0, 'Anagrafica eventi 2026 (file cliente 07/08/2026)', false,
           '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
    FROM _v26_match m
    WHERE m.evento_id IS NULL
    RETURNING id, nome, data_evento
)
SELECT i.id, f.data_evento, f.nome, f.affitto, f.menu
FROM ins i JOIN _v26_file f ON f.data_evento = i.data_evento AND f.nome = i.nome;

-- ── 4. Voci di preventivo dei nuovi eventi (Affitto / Menu dal file) ────────
--     Il consuntivo si valorizza solo sugli eventi gia' passati: sui futuri resta
--     NULL, altrimenti il confronto preventivo/consuntivo nascerebbe gia' "chiuso".
INSERT INTO evento_voce (evento_id, catalogo_id, label, importo_preventivo, importo_consuntivo, origine, created_by)
SELECT n.id, c.id, c.label, v.importo,
       CASE WHEN n.data_evento <= DATE '2026-07-26' THEN v.importo ELSE NULL END,
       'MANUALE', '14ff5893-fb29-43c0-b329-c33699523b5b'::uuid
FROM _v26_nuovi n
CROSS JOIN LATERAL (VALUES ('Affitto', n.affitto), ('Menu', n.menu)) AS v(label, importo)
JOIN evento_voce_catalogo c ON c.label = v.label
WHERE v.importo > 0;

-- Fail fast (invariante di chiusura, vale su TUTTE le 94 righe del file, non solo
-- sulle nuove): per ogni evento del file il preventivato deve essere uguale sia al
-- Ricavo totale del foglio sia alla somma delle sue voci. Se la seconda uguaglianza
-- cade, il primo ricalcolo del service (V18, EventiService:1035) cambierebbe il
-- valore da solo e l'allineamento al foglio non sopravviverebbe.
DO $$
DECLARE k int; j int;
BEGIN
    SELECT count(*) INTO k
    FROM eventi e JOIN _v26_match m ON m.evento_id = e.id OR (m.evento_id IS NULL
         AND e.data_evento = m.data_evento AND e.nome = m.nome)
    WHERE e.importo_totale_preventivato IS DISTINCT FROM
          (SELECT coalesce(sum(importo_preventivo), 0) FROM evento_voce WHERE evento_id = e.id);
    IF k > 0 THEN
        RAISE EXCEPTION 'V26: % eventi con preventivato != somma voci', k;
    END IF;

    SELECT count(*) INTO j
    FROM eventi e JOIN _v26_match m ON m.evento_id = e.id OR (m.evento_id IS NULL
         AND e.data_evento = m.data_evento AND e.nome = m.nome)
    WHERE e.importo_totale_preventivato IS DISTINCT FROM m.totale;
    IF j > 0 THEN
        RAISE EXCEPTION 'V26: % eventi con preventivato != Ricavo totale del file cliente', j;
    END IF;
END $$;

-- Nessun movimento creato: le MV monetarie non cambiano. Il refresh serve solo a
-- mv_redditivita_eventi, che elenca anche gli eventi senza incassi.
DO $$
DECLARE v text;
BEGIN
    FOREACH v IN ARRAY ARRAY['mv_redditivita_eventi'] LOOP
        IF EXISTS (SELECT 1 FROM pg_matviews WHERE matviewname = v) THEN
            EXECUTE format('REFRESH MATERIALIZED VIEW %I', v);
        END IF;
    END LOOP;
END $$;
