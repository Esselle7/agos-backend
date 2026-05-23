-- ============================================================
-- V28 - Link 1:1 OPZIONALE tra users e personale (vista "I Miei")
-- ============================================================
--
-- Stato preesistente:
--   V12 ha già creato users.personale_id come FK NULLable (NO ACTION on delete).
--
-- Modifiche di questa migration (additive, idempotenti):
--   1. Sostituisce la FK esistente con ON DELETE SET NULL (per non bloccare
--      la cancellazione di un record personale se un utente vi è collegato).
--   2. Aggiunge il vincolo UNIQUE su personale_id, in modo che ogni record
--      personale possa essere collegato a UN SOLO utente applicativo
--      (relazione 1:1 dal lato users).
--   3. Operazioni racchiuse in DO block per non rompere i DB dove i vincoli
--      sono già stati creati (es. ambienti di sviluppo riapplicati).
-- ============================================================

DO $$
DECLARE
    fk_name TEXT;
BEGIN
    -- 1. Trova ed elimina la FK esistente su users.personale_id (creata da V12)
    SELECT conname INTO fk_name
      FROM pg_constraint
     WHERE conrelid = 'users'::regclass
       AND contype  = 'f'
       AND pg_get_constraintdef(oid) LIKE '%personale_id%';

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', fk_name);
    END IF;

    -- 2. Crea la nuova FK con ON DELETE SET NULL
    ALTER TABLE users
        ADD CONSTRAINT fk_users_personale
        FOREIGN KEY (personale_id) REFERENCES personale(id) ON DELETE SET NULL;

    -- 3. Aggiungi UNIQUE constraint se non esiste già
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'users'::regclass
           AND contype  = 'u'
           AND pg_get_constraintdef(oid) LIKE '%(personale_id)%'
    ) THEN
        ALTER TABLE users
            ADD CONSTRAINT uq_users_personale_id UNIQUE (personale_id);
    END IF;
END $$;
