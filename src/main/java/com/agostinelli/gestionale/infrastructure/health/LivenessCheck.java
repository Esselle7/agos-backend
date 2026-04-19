package com.agostinelli.gestionale.infrastructure.health;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/**
 * Liveness probe: risponde sempre UP per segnalare che il processo è vivo.
 * Kubernetes usa questo endpoint per decidere se riavviare il pod.
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.up("liveness");
    }
}
