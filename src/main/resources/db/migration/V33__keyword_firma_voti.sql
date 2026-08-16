-- V33 — Il voto sulla firma (SPEC import-v2 §4, R16): la promozione si COSTRUISCE, non si configura.
--
-- Constatazione misurata il 12/08/2026: keyword_firma non ha nessun contatore di conferme
-- (V7__keyword_learning.sql:18-51) e riconfermare una firma fa solo
-- `UPDATE keyword_firma SET updated_at = now()` (KeywordLearningService:85). Senza un contatore,
-- «la firma con storico pulito si promuove da sola» non è implementabile: non esiste lo storico.
--
-- Perché qui e non in audit_log: audit_log è in retention 6 mesi (V13:9). Il voto deve
-- sopravvivere alla retention, quindi vive sulla firma.
ALTER TABLE keyword_firma
    ADD COLUMN IF NOT EXISTS usi_confermati integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS usi_corretti   integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN keyword_firma.usi_confermati IS
    'R17: quante volte l''utente ha CONFERMATO dal wizard la proposta di questa firma.';
COMMENT ON COLUMN keyword_firma.usi_corretti IS
    'R17: quante volte l''ha CORRETTA. Uno solo basta a togliere per sempre la promozione (R18).';
