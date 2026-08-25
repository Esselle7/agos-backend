-- Recupero delle rate di finanziamento scritte "FIN.TO" (audit §10.4).
--
-- Le regole 1 e 2 (SKIP_RICORRENTE, priorita' 30) hanno un pattern IN_LIST che contiene
-- FINANZIAMENTO, ma l'estratto conto BPM scrive la forma abbreviata:
--     RIMBORSO FINANZ. - PAG.RATE SU FIN.TO 1273/05796807 INT.: SOCIETA' AGRICOLA...
-- Ne' "FINANZIAMENTO" ne' "\bRATA\b" compaiono ("PAG.RATE", non "RATA"), quindi 3 righe reali
-- del corpus finiscono a smistamento manuale pur essendo rate di un piano gia' censito.
--
-- Falsi positivi misurati: 0. Le sole 3 righe del corpus (1.134) che contengono "FIN.TO" sono
-- esattamente queste tre rate. Replay: 3 righe TRANSITORIO -> SKIP_RICORRENTE, 0 righe perse.
--
-- Perche' entrambe le regole: 1 e 2 sono identiche tranne la sorgente (CA / BPM). E' una
-- duplicazione nota — c'e' anche una TERZA copia della stessa lista in Java
-- (MovimentoMappingEngineImpl.isRicorrente(), con un commento che la dichiara). Qui si
-- correggono le due righe a DB, che e' il fix senza deploy; l'allineamento della copia Java
-- e' una voce di pulizia separata, non necessaria a chiudere queste 3 righe.
UPDATE regole_classificazione
   SET pattern = 'ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,FIN.TO,ASCONFIDI'
 WHERE id IN (1, 2)
   AND azione = 'SKIP_RICORRENTE'
   AND pattern = 'ASSICURAZ,POLIZZA,MUTUO,LEASING,FINANZIAMENTO,ASCONFIDI';
