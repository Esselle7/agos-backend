package com.agostinelli.gestionale.infrastructure.filter;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.io.IOException;

/**
 * Aggiunge header di sicurezza standard a ogni risposta HTTP.
 * Previene clickjacking, MIME sniffing e impone HTTPS in produzione.
 */
@Provider
@ApplicationScoped
@Priority(Priorities.HEADER_DECORATOR)
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {
        responseContext.getHeaders().add("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().add("X-Frame-Options", "DENY");
        responseContext.getHeaders().add("Content-Security-Policy", "default-src 'self'");
        responseContext.getHeaders().add("Strict-Transport-Security", "max-age=31536000");
    }
}
