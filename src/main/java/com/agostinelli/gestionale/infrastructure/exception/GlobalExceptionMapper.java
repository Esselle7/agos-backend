package com.agostinelli.gestionale.infrastructure.exception;

import com.agostinelli.gestionale.shared.dto.ErrorResponse;
import io.quarkus.arc.ArcUndeclaredThrowableException;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Throwable> {

    @Override
    public Response toResponse(Throwable exception) {
        Throwable ex = unwrap(exception);
        if (ex instanceof ConstraintViolationException cve) {
            return handleConstraintViolation(cve);
        }
        if (ex instanceof org.hibernate.exception.ConstraintViolationException hcve) {
            return handleDbConstraintViolation(hcve);
        }
        if (ex instanceof ApiException apiEx) {
            return handleApiException(apiEx);
        }
        if (ex instanceof jakarta.ws.rs.WebApplicationException wae) {
            Response.Status status = Response.Status.fromStatusCode(wae.getResponse().getStatus());
            if (status == null) status = Response.Status.INTERNAL_SERVER_ERROR;
            return buildResponse(status, ErrorResponse.of(status.name(), wae.getMessage()));
        }
        return handleUnexpected(ex);
    }

    private Throwable unwrap(Throwable ex) {
        if (ex.getCause() != null && (ex instanceof ArcUndeclaredThrowableException
                || ex instanceof jakarta.transaction.RollbackException)) {
            return unwrap(ex.getCause());
        }
        return ex;
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

    private Response handleDbConstraintViolation(org.hibernate.exception.ConstraintViolationException ex) {
        log.warn("Violazione vincolo univoco DB: {}", ex.getConstraintName());
        return buildResponse(Response.Status.CONFLICT,
                ErrorResponse.of("DUPLICATE_KEY", "Elemento già esistente con questo valore"));
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
