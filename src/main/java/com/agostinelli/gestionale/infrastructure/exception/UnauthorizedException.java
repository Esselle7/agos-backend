package com.agostinelli.gestionale.infrastructure.exception;

import jakarta.ws.rs.core.Response;

/**
 * Accesso non autenticato (HTTP 401).
 */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException() {
        super(Response.Status.UNAUTHORIZED, "UNAUTHORIZED", "Autenticazione richiesta");
    }

    public UnauthorizedException(String message) {
        super(Response.Status.UNAUTHORIZED, "UNAUTHORIZED", message);
    }
}
