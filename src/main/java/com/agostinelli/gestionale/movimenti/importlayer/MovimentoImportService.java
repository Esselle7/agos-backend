package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.BulkImportResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/*
 * IMPORT FEATURE DISABLED (FUTURE RELEASE)
 * This service is intentionally left as a stub.
 * Full ETL processing will be introduced in a dedicated phase
 * to avoid coupling domain logic with file parsing logic.
 *
 * TODO: implementare l'ETL completo con MovimentoParser + Normalizer + MappingEngine
 *       quando la fase di import sarà schedulata nel backlog.
 */
@ApplicationScoped
public class MovimentoImportService {

    @Inject EntityManager em;

    /**
     * Riceve un file CSV Billy, registra i metadati dell'import
     * e restituisce una response dummy.
     *
     * @deprecated FUTURE IMPLEMENTATION – DO NOT USE IN CURRENT RELEASE
     */
    @Deprecated
    @Transactional
    public BulkImportResponse importBilly(InputStream file, String filename, UUID userId) {
        registraImportLog("IMPORT_BILLY", filename, userId);
        // TODO: parsare il CSV, normalizzare, mappare e chiamare bulkImport
        return new BulkImportResponse(0, 0, 0, List.of());
    }

    private void registraImportLog(String fonte, String filename, UUID userId) {
        em.createNativeQuery(
                "INSERT INTO import_log (fonte, filename, stato, imported_by) " +
                "VALUES (:fonte, :filename, 'IN_CORSO', :userId)")
                .setParameter("fonte", fonte)
                .setParameter("filename", filename != null ? filename : "unknown")
                .setParameter("userId", userId)
                .executeUpdate();
    }
}
