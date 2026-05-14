package com.agostinelli.gestionale.infrastructure.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Readiness probe: verifica la connessione al database con SELECT 1.
 * Se il DB non risponde, Kubernetes smette di instradare traffico al pod.
 */
@Slf4j
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {

    @Inject
    DataSource dataSource;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("readiness");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            if (rs.next()) {
                return builder.up().withData("database", "reachable").build();
            }
            return builder.down().withData("database", "no rows returned").build();
        } catch (Exception e) {
            log.error("Readiness check fallito", e);
            return builder.down().withData("error", e.getMessage()).build();
        }
    }
}
