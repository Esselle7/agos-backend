-- V28 — la regola SKIP_RICORRENTE non deve più intercettare canoni e bolli bancari.
--
-- Bug: le regole 1 (CA) e 2 (BPM) hanno priorità 30, cioè girano PRIMA di classifyUscita. Il loro
-- pattern conteneva CANONE e BOLLO, quindi righe come «CANONE CARTA - CANONE ANNUALE DEBIT BUSINESS»
-- o «BOLLO E/C ART.13» finivano parcheggiate in ricorrenti_da_riconciliare, chiedendo all'operatore
-- di creare un piano di ammortamento per un bollo da 29,42 €.
--
-- Ma classifyUscita (MovimentoMappingEngineImpl, «desc.contains("BOLLO E/C") || desc.contains("CANONE")
-- || desc.contains("COMMISSIONI")») le manda già correttamente su 40.02.002 «Spese tenuta conto
-- bancario»: infatti 37 righe identiche per natura erano contabilizzate e 5 parcheggiate.
-- Misurato sull'import di luglio 2026: 5 righe / 98,94 € parcheggiate a torto.
--
-- Restano nel pattern i veri piani: ASSICURAZ, POLIZZA, MUTUO, LEASING, FINANZIAMENTO, ASCONFIDI.
--
-- Idempotente: filtra sul valore atteso e usa replace(); rieseguirla non cambia nulla.

UPDATE regole_classificazione
SET pattern = 'ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,ASCONFIDI'
WHERE azione = 'SKIP_RICORRENTE'
  AND pattern = 'CANONE,ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,BOLLO,ASCONFIDI';
