package com.agostinelli.gestionale.infrastructure.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.jboss.logging.MDC;

import java.io.IOException;

/**
 * Audit logger che traccia metodo, path, status, utente e durata per ogni richiesta.
 * Usa MDC per correlare le righe di log sullo stesso thread.
 */
@Slf4j
@Provider
@ApplicationScoped
@Priority(Priorities.USER + 100)
public class AuditFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String START_TIME_KEY = "audit.startTime";

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        requestContext.setProperty(START_TIME_KEY, System.currentTimeMillis());

        String userId = extractUserId(requestContext);
        MDC.put("userId", userId);
        MDC.put("method", requestContext.getMethod());
        MDC.put("path", requestContext.getUriInfo().getPath());
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {
        long startTime = (Long) requestContext.getProperty(START_TIME_KEY);
        long duration = System.currentTimeMillis() - startTime;

        log.info("[{}] [{}] [{}] [{}] [{}ms]",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                responseContext.getStatus(),
                MDC.get("userId"),
                duration);

        MDC.remove("userId");
        MDC.remove("method");
        MDC.remove("path");
    }

    private String extractUserId(ContainerRequestContext ctx) {
        if (ctx.getSecurityContext() != null
                && ctx.getSecurityContext().getUserPrincipal() != null) {
            return ctx.getSecurityContext().getUserPrincipal().getName();
        }
        return "anonymous";
    }
}
