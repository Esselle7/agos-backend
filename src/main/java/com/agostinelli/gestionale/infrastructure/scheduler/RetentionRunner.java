package com.agostinelli.gestionale.infrastructure.scheduler;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Esecutore dei DELETE di retention. Bean separato da {@link RetentionScheduler}
 * perché @Transactional NON scatta sull'auto-invocazione (proxy CDI): ogni purge
 * deve essere chiamato da fuori per girare nella sua transazione (REQUIRES_NEW),
 * così un fallimento isolato non annulla gli altri. Stesso pattern di MvRefreshRunner.
 */
@ApplicationScoped
public class RetentionRunner {

    @Inject
    EntityManager em;

    @ConfigProperty(name = "retention.audit.mesi", defaultValue = "6")
    int auditMesi;

    @ConfigProperty(name = "retention.import.mesi", defaultValue = "3")
    int importMesi;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int purgeAudit() {
        return em.createNativeQuery(
                "DELETE FROM audit_log WHERE created_at < now() - make_interval(months => :m)")
                .setParameter("m", auditMesi).executeUpdate();
    }

    // Tiene il triage pendente: cancella solo le ambiguità già classificate.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int purgeAmbiguitaClassificate() {
        return em.createNativeQuery(
                "DELETE FROM import_ambiguita WHERE created_at < now() - make_interval(months => :m)"
                        + " AND stato <> 'DA_CLASSIFICARE'")
                .setParameter("m", importMesi).executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int purgeScartati() {
        return em.createNativeQuery(
                "DELETE FROM import_scartati WHERE created_at < now() - make_interval(months => :m)")
                .setParameter("m", importMesi).executeUpdate();
    }

    // Solo import senza movimenti collegati: la provenance dei movimenti resta intatta.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public int purgeImportLogOrfani() {
        return em.createNativeQuery(
                "DELETE FROM import_log il WHERE il.data_import < now() - make_interval(months => :m)"
                        + " AND NOT EXISTS (SELECT 1 FROM movimenti m WHERE m.fonte_importazione_id = il.id)")
                .setParameter("m", importMesi).executeUpdate();
    }
}
