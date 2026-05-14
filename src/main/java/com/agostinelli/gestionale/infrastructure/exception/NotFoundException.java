package com.agostinelli.gestionale.infrastructure.exception;

import jakarta.ws.rs.core.Response;

/**
 * Risorsa non trovata (HTTP 404).
 */
public class NotFoundException extends ApiException {
    public NotFoundException(String message) {
        super(Response.Status.NOT_FOUND, "NOT_FOUND", message);
    }

    public NotFoundException(String resource, Object id) {
        super(Response.Status.NOT_FOUND, "NOT_FOUND", resource + " non trovato: " + id);
    }
}
