package com.agostinelli.gestionale.infrastructure.exception;

import com.agostinelli.gestionale.shared.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapper globale che converte qualsiasi eccezione in una risposta JSON strutturata.
 * Centralizza la gestione degli errori eliminando try-catch nei resource layer.
 */
@Slf4j
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable exception) {
        if (exception instanceof ConstraintViolationException cve) {
            return handleConstraintViolation(cve);
        }
        if (exception instanceof ApiException apiEx) {
            return handleApiException(apiEx);
        }
        if (exception instanceof jakarta.ws.rs.NotFoundException) {
            return buildResponse(Response.Status.NOT_FOUND,
                    ErrorResponse.of("NOT_FOUND", "Risorsa non trovata"));
        }
        return handleUnexpected(exception);
    }

    private Response handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> details = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> extractFieldName(v.getPropertyPath().toString()),
                        v -> v.getMessage(),
                        (a, b) -> a
                ));
        return buildResponse(Response.Status.BAD_REQUEST,
                ErrorResponse.of("VALIDATION_ERROR", "Errori di validazione", details));
    }

    private Response handleApiException(ApiException ex) {
        return buildResponse(ex.getHttpStatus(),
                ErrorResponse.of(ex.getCode(), ex.getMessage()));
    }

    private Response handleUnexpected(Throwable ex) {
        log.error("Errore imprevisto", ex);
        return buildResponse(Response.Status.INTERNAL_SERVER_ERROR,
                ErrorResponse.of("INTERNAL_ERROR", "Errore interno del server"));
    }

    private Response buildResponse(Response.Status status, ErrorResponse body) {
        return Response.status(status).entity(body).build();
    }

    private String extractFieldName(String propertyPath) {
        String[] parts = propertyPath.split("\\.");
        return parts[parts.length - 1];
    }
}
