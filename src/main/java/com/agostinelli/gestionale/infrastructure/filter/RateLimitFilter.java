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
        String clientKey = extractClientKey(requestContext);
        AtomicInteger counter = requestCounts.computeIfAbsent(clientKey, k -> new AtomicInteger(0));
        int current = counter.incrementAndGet();

        int remaining = Math.max(0, maxRequestsPerMinute - current);

        if (current > maxRequestsPerMinute) {
            log.warn("Rate limit superato per client: {}", clientKey);
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

    /**
     * Chiave del bucket: l'utente autenticato (sub JWT) se presente, altrimenti l'IP.
     * Keyare per-utente evita che più utenti legittimi dietro lo stesso IP/NAT (es. l'ufficio
     * dell'agriturismo su un'unica connessione) condividano e saturino lo stesso bucket.
     * Prefissi "u:"/"ip:" così un IP non può impersonare il bucket di un sub.
     */
    String extractClientKey(ContainerRequestContext ctx) {
        if (ctx.getSecurityContext() != null && ctx.getSecurityContext().getUserPrincipal() != null) {
            return "u:" + ctx.getSecurityContext().getUserPrincipal().getName();
        }
        return "ip:" + extractClientIp(ctx);
    }

    private String extractClientIp(ContainerRequestContext ctx) {
        // security: topologia reale = Cloudflare → Traefik → backend. Cloudflare imposta
        // CF-Connecting-IP con il vero IP client e NON è appendibile dal client (a patto che
        // l'origine accetti traffico solo da Cloudflare — vedi hardening firewall). È quindi
        // la sorgente corretta e non falsificabile per il bucket del rate limit per-utente.
        String cf = ctx.getHeaderString("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) {
            return cf.trim();
        }
        // Fallback (hit diretto su Traefik, senza Cloudflare): l'ultimo hop di X-Forwarded-For
        // è quello aggiunto dal proxy, non il valore forgiato dal client (che finisce a sinistra).
        String forwarded = ctx.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return ctx.getHeaderString("X-Real-IP") != null
                ? ctx.getHeaderString("X-Real-IP")
                : "unknown";
    }
}
