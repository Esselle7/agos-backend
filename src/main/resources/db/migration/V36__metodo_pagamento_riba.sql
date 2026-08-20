-- V36 — RiBa come metodo di pagamento (spec docs/specs/debug-con-cliente/metodo-pagamento-riba.md)
--
-- Perché: le righe di effetti/RiBa finivano su RID_SDDMANDAT (addebito SEPA) o su BONIFICO.
-- Una ricevuta bancaria non è né l'uno né l'altro: chi legge i movimenti per metodo vedeva
-- addebiti SDD che non sono mai esistiti.
--
-- Id esplicito 13 (il 12 è l'ultimo speso) e ON CONFLICT: la stessa forma di V6.
-- Il codice nasce QUI, prima che il normalizer lo nomini: metodo_pagamento_id è una FK e
-- l'ordine di rilascio è migration → backend, mai il contrario.
INSERT INTO metodi_pagamento (id, codice, descrizione, is_active) VALUES
    (13, 'RIBA', 'Ri.Ba. — ricevuta bancaria (effetti)', true)
ON CONFLICT (id) DO NOTHING;

-- Allinea la sequence al nuovo massimo.
SELECT setval(pg_get_serial_sequence('metodi_pagamento','id'), COALESCE((SELECT MAX(id) FROM metodi_pagamento), 1));
