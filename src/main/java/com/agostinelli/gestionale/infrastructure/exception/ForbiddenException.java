package com.agostinelli.gestionale.infrastructure.exception;

import jakarta.ws.rs.core.Response;

/**
 * Accesso vietato per mancanza di permessi (HTTP 403).
 */
public class ForbiddenException extends ApiException {
    public ForbiddenException() {
        super(Response.Status.FORBIDDEN, "FORBIDDEN", "Accesso non autorizzato");
    }

    public ForbiddenException(String message) {
        super(Response.Status.FORBIDDEN, "FORBIDDEN", message);
    }
}
