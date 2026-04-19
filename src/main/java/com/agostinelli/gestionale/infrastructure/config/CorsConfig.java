package com.agostinelli.gestionale.infrastructure.config;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Lettura e validazione della configurazione CORS all'avvio.
 * Le allowed-origins vengono tracciate nel log per facilitare il debug in produzione.
 */
@Slf4j
@ApplicationScoped
public class CorsConfig {

    @ConfigProperty(name = "quarkus.http.cors.origins",
                    defaultValue = "http://localhost:4200")
    String allowedOrigins;

    @ConfigProperty(name = "quarkus.http.cors.methods",
                    defaultValue = "GET,POST,PUT,DELETE,OPTIONS")
    String allowedMethods;

    @ConfigProperty(name = "quarkus.http.cors.headers",
                    defaultValue = "Authorization,Content-Type,X-Requested-With")
    String allowedHeaders;

    public void logConfig() {
        log.info("CORS origins configurati: {}", allowedOrigins);
        log.info("CORS methods: {}", allowedMethods);
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public String getAllowedMethods() {
        return allowedMethods;
    }

    public String getAllowedHeaders() {
        return allowedHeaders;
    }
}
