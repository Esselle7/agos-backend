-- ============================================================
-- V12 — Forecast baseline (Feature: previsionale "stimato" su base storica)
-- ============================================================
-- Affianca al previsionale CERTO (eventi confermati, rate, stipendi, movimenti da liquidare) una
-- stima LEGGERA dei soli ricavi cash ad alta frequenza (cassa ristorante, carne, ortofrutta), che
-- oggi NON compaiono nel forecast perché datati al passato (data_competenza storica).
--
-- Metodo: media mobile per giorno-della-settimana, ricalcolata di notte (NON a runtime). Una riga
-- per (conto_coge_id, dow): la pagina Previsioni legge solo questa tabella → costo runtime nullo.
-- ~21 righe (3 conti allowlist × 7 giorni).
--
-- dow = EXTRACT(DOW FROM data_movimento): 0=domenica .. 6=sabato (convenzione Postgres).
-- n_giorni = giorni distinti osservati nella finestra per quel dow → soglia anti-rumore.
-- ============================================================

CREATE TABLE forecast_baseline (
    conto_coge_id integer    NOT NULL REFERENCES piano_dei_conti_coge(id),
    dow           smallint   NOT NULL,
    media_attesa  numeric(12,2) NOT NULL,
    n_giorni      integer    NOT NULL DEFAULT 0,
    updated_at    timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT forecast_baseline_pkey PRIMARY KEY (conto_coge_id, dow),
    CONSTRAINT forecast_baseline_dow_chk CHECK (dow BETWEEN 0 AND 6)
);
