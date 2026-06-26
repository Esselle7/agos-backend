package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import io.quarkus.arc.All;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Risolve la {@link ImportStrategy} per la chiave fonte richiesta. */
@ApplicationScoped
public class ImportStrategyFactory {

    @Inject
    @All
    List<ImportStrategy> strategies;

    public ImportStrategy get(String fonteStr) {
        return strategies.stream()
                .filter(s -> s.supports(fonteStr))
                .findFirst()
                .orElseThrow(() -> new ApiException(Response.Status.BAD_REQUEST, "FONTE_NON_SUPPORTATA",
                        "Nessuna strategia di import per la fonte: " + fonteStr));
    }
}
