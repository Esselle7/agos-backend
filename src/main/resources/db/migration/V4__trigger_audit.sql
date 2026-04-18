-- ============================================================
-- V4 - TRIGGER DI AUDIT E AGGIORNAMENTO AUTOMATICO
-- ============================================================

-- ============================================================
-- FUNZIONE GENERICA PER AUDIT LOG
-- Unica funzione riutilizzata da tutti i trigger di audit
-- ============================================================

CREATE OR REPLACE FUNCTION fn_audit_generic()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_nuovi)
        VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'INSERT', to_jsonb(NEW));

    ELSIF TG_OP = 'UPDATE' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_precedenti, dati_nuovi)
        VALUES (TG_TABLE_NAME, NEW.id::TEXT, 'UPDATE', to_jsonb(OLD), to_jsonb(NEW));

    ELSIF TG_OP = 'DELETE' THEN
        INSERT INTO audit_log (tabella, record_id, operazione, dati_precedenti)
        VALUES (TG_TABLE_NAME, OLD.id::TEXT, 'DELETE', to_jsonb(OLD));
    END IF;

    RETURN NULL; -- AFTER trigger: valore di ritorno ignorato per DML
END;
$$;

-- ============================================================
-- TRIGGER AUDIT su MOVIMENTI
-- Nota: trigger AFTER ROW su tabella partizionata in PG 13+
-- si propaga automaticamente a tutte le partizioni (attuali e future)
-- ============================================================

CREATE TRIGGER trg_audit_movimenti
    AFTER INSERT OR UPDATE OR DELETE ON movimenti
    FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- TRIGGER AUDIT su EVENTI
-- ============================================================

CREATE TRIGGER trg_audit_eventi
    AFTER INSERT OR UPDATE OR DELETE ON eventi
    FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- TRIGGER AUDIT su CASSA_MOVIMENTI
-- ============================================================

CREATE TRIGGER trg_audit_cassa_movimenti
    AFTER INSERT OR UPDATE OR DELETE ON cassa_movimenti
    FOR EACH ROW EXECUTE FUNCTION fn_audit_generic();

-- ============================================================
-- TRIGGER: aggiorna importo_incassato e caparre_incassate
-- sulla tabella eventi quando un movimento viene
-- associato/modificato/rimosso da quell'evento.
--
-- Logica:
-- - Se il movimento ha evento_id, ricalcola i totali dell'evento
-- - Se evento_id è cambiato (UPDATE), ricalcola anche il vecchio evento
-- - Ricalcolo sempre completo via SELECT SUM (idempotente)
-- ============================================================

CREATE OR REPLACE FUNCTION fn_aggiorna_totali_evento()
RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    v_evento_id UUID;
BEGIN
    -- In caso di UPDATE con cambio evento, ricalcola il vecchio evento
    IF TG_OP = 'UPDATE'
       AND OLD.evento_id IS DISTINCT FROM NEW.evento_id
       AND OLD.evento_id IS NOT NULL
    THEN
        PERFORM fn_ricalcola_evento(OLD.evento_id);
    END IF;

    -- Determina l'evento da aggiornare
    IF TG_OP = 'DELETE' THEN
        v_evento_id := OLD.evento_id;
    ELSE
        v_evento_id := NEW.evento_id;
    END IF;

    IF v_evento_id IS NOT NULL THEN
        PERFORM fn_ricalcola_evento(v_evento_id);
    END IF;

    RETURN NULL;
END;
$$;

-- Funzione helper separata per il ricalcolo (riutilizzabile anche da job di manutenzione)
CREATE OR REPLACE FUNCTION fn_ricalcola_evento(p_evento_id UUID)
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE eventi SET
        importo_incassato = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id = p_evento_id
              AND  m.tipo      = 'ENTRATA'
              AND  m.stato    != 'ANNULLATO'
        ), 0),
        caparre_incassate = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id             = p_evento_id
              AND  m.tipo_evento_movimento = 'CAPARRA'
              AND  m.tipo                  = 'ENTRATA'
              AND  m.stato                != 'ANNULLATO'
        ), 0),
        costi_diretti_imputati = COALESCE((
            SELECT SUM(m.importo_lordo)
            FROM   movimenti m
            WHERE  m.evento_id = p_evento_id
              AND  m.tipo      = 'USCITA'
              AND  m.stato    != 'ANNULLATO'
        ), 0)
    WHERE id = p_evento_id;
END;
$$;

-- Il trigger aggiornatotali_evento deve scattare DOPO il trigger di audit
-- (in PostgreSQL i trigger AFTER vengono eseguiti in ordine alfabetico)
-- trg_aggiorna_totali_evento < trg_audit_movimenti => NON corretto
-- Per forzare l'ordine usiamo un nome che viene dopo "audit" alfabeticamente
CREATE TRIGGER trg_z_aggiorna_totali_evento
    AFTER INSERT OR UPDATE OR DELETE ON movimenti
    FOR EACH ROW EXECUTE FUNCTION fn_aggiorna_totali_evento();
