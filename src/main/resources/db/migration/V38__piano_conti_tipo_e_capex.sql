-- V38 — il tipo giusto ai conti che governano il conto economico.
--
-- Il tipo del conto (e il flag is_capex) è ciò che decide dove finisce un movimento nel P&L.
-- Cinque correzioni, tutte misurate su agosdb_sim (copia del dump di prod del 20/08/2026 18:16),
-- effetto su luglio 2026: EBITDA −16.136,04 → −9.908,39 (+6.227,65).
--
--   92 Costo Fido Temporaneo   COSTO   → PASSIVITA  rimborso sconto effetti agrari  costi −10.000,00
--   93 RIcavo Fido Temporaneo  RICAVO  → PASSIVITA  accredito dello stesso sconto   ricavi −8.676,82
--   91 Investimenti            is_capex→ true       Mallamace/Elle Emme/Imat Felco  costi  −5.135,35
--   50.01.010, 50.02, 50.02.001..004  is_capex→true stanno sotto 50 INVESTIMENTI     costi    −284,85
--   20.01.007 Leasing furgone  PASSIVITA → COSTO    il canone di leasing è un costo costi   +515,73
--
-- Perché una migration e non il CRUD /piano-conti: is_capex NON è editabile da lì per scelta
-- deliberata (PianoContiCogeRepository:93 lo preserva, CespitiService:267 documenta il perché) e
-- il PUT è full-overwrite: aprirlo rischierebbe di spegnere in silenzio gli 11 conti oggi
-- correttamente capex. Per i conti 50.x è per giunta la correzione di un difetto di seed
-- (V4__seed_dati_correnti.sql li crea con il flag sbagliato), e un seed si corregge in migration.
--
-- Difensiva e idempotente: `WHERE codice IN (...)`. I conti 91/92/93 sono stati creati a runtime
-- dalla pagina /piano-conti in produzione: in dev e test non esistono e la UPDATE è un no-op.
--
-- Prerequisito: V37 (entrate_investimento). Senza, l'ENTRATA da 490,00 sul conto 91 — che qui
-- diventa capex — uscirebbe dal cash flow e le entrate di luglio calerebbero a 41.569,14.

UPDATE piano_dei_conti_coge SET tipo = 'PASSIVITA'
 WHERE codice IN ('92', '93') AND tipo <> 'PASSIVITA';

UPDATE piano_dei_conti_coge SET tipo = 'COSTO'
 WHERE codice = '20.01.007' AND tipo <> 'COSTO';

UPDATE piano_dei_conti_coge SET is_capex = true
 WHERE codice IN ('91', '50.01.010', '50.02', '50.02.001', '50.02.002', '50.02.003', '50.02.004')
   AND is_capex = false;

-- Senza questo il P&L resta fermo ai valori vecchi: le viste sono materializzate.
SELECT fn_refresh_all_mv();
