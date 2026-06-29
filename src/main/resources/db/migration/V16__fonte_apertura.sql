-- ============================================================================
-- V16 — Fonte 'APERTURA' per le partite pregresse al 31/12/2025
-- ----------------------------------------------------------------------------
-- Crediti/debiti di apertura (pre-2026) inseriti dalla pagina "Situazione iniziale"
-- usano fonte='APERTURA' così sono riconoscibili in tutto il gestionale come
-- valori che arrivano dall'anno precedente (etichetta "Apertura 2025").
-- (V15 è il seed storico eventi in db/seed/common: in db/migration la prossima è V16.)
-- ============================================================================
INSERT INTO lk_fonti_movimento (codice, descrizione)
VALUES ('APERTURA', 'Partita di apertura / saldo pregresso (ante 2026)')
ON CONFLICT (codice) DO NOTHING;
