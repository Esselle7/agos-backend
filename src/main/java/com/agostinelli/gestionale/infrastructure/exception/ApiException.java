package com.agostinelli.gestionale.infrastructure.exception;

import jakarta.ws.rs.core.Response;

/**
 * Eccezione base dell'applicazione con HTTP status code incluso.
 * Tutte le eccezioni di dominio estendono questa classe.
 */
public class ApiException extends RuntimeException {

    private final Response.Status httpStatus;
    private final String code;

    public ApiException(Response.Status httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public Response.Status getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
