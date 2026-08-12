-- V27 — Riconoscimento strutturato delle rate ricorrenti nell'import bancario.
-- Testo stabile con cui l'addebito si riconosce in estratto conto: mandato SDD (BPM:
-- "SDD CORE: 2C1071113500569T"), nome del creditore (CA: "SDD A : TELEPASS S.P.A."),
-- numero di contratto ("MUTUO N.1273"). Opzionale: senza di esso il match usa i token del
-- nome del piano (misurato: copre 10 addebiti ricorrenti reali su 11, 0 falsi positivi;
-- l'11° e' TIM, nome troppo corto per fare da token). Vedi docs/specs/ricorrenti-match-strutturato.md.
ALTER TABLE recurring_expense_plan
    ADD COLUMN riferimento_estratto_conto VARCHAR(120);

COMMENT ON COLUMN recurring_expense_plan.riferimento_estratto_conto IS
    'Testo da cercare nella descrizione bancaria per riconoscere l''addebito di questa rata (mandato SDD, creditore, n. contratto). NULL = si usano i token del nome del piano.';
