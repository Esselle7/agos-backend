package com.agostinelli.gestionale.infrastructure.filter;

import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiter per IP: richieste/minuto configurabili, reset automatico via scheduler.
 * Aggiunge l'header X-RateLimit-Remaining a ogni risposta consentita.
 */
@Slf4j
@Provider
@ApplicationScoped
@Priority(Priorities.USER)
public class RateLimitFilter implements ContainerRequestFilter {

    @ConfigProperty(name = "app.rate-limit.max-requests-per-minute", defaultValue = "100")
    int maxRequestsPerMinute;

    private final ConcurrentHashMap<String, AtomicInteger> requestCounts = new ConcurrentHashMap<>();

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String clientIp = extractClientIp(requestContext);
        AtomicInteger counter = requestCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        int remaining = Math.max(0, maxRequestsPerMinute - current);

        if (current > maxRequestsPerMinute) {
            log.warn("Rate limit superato per IP: {}", clientIp);
            requestContext.abortWith(
                    Response.status(429)
                            .header("X-RateLimit-Remaining", "0")
                            .entity("Too Many Requests")
                            .build()
            );
            return;
        }

        requestContext.setProperty("X-RateLimit-Remaining", remaining);
    }

    @Scheduled(every = "1m")
    void resetCounters() {
        requestCounts.clear();
        log.debug("Rate limit counters azzerati");
    }

    private String extractClientIp(ContainerRequestContext ctx) {
        String forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return ctx.getHeaderString("X-Real-IP") != null
                ? ctx.getHeaderString("X-Real-IP")
                : "unknown";
    }
}
