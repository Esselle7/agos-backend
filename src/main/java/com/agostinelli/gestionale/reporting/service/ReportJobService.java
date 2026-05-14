package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.JobStatusDTO;
import com.agostinelli.gestionale.reporting.dto.PlDTO;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@ApplicationScoped
public class ReportJobService {

    private final ConcurrentHashMap<UUID, JobState> store = new ConcurrentHashMap<>();

    private record JobState(
            String status,
            PlDTO result,
            String error,
            Instant completedAt
    ) {}

    public UUID submitJob(Supplier<PlDTO> computation) {
        UUID id = UUID.randomUUID();
        store.put(id, new JobState("PENDING", null, null, null));
        CompletableFuture.supplyAsync(computation)
                .thenAccept(result -> store.put(id, new JobState("READY", result, null, Instant.now())))
                .exceptionally(ex -> {
                    store.put(id, new JobState("ERROR", null, ex.getMessage(), Instant.now()));
                    return null;
                });
        return id;
    }

    public JobStatusDTO getJobStatus(UUID jobId) {
        JobState state = store.get(jobId);
        if (state == null) {
            throw new ApiException(Response.Status.NOT_FOUND, "JOB_NOT_FOUND",
                    "Job non trovato: " + jobId);
        }
        return new JobStatusDTO(jobId, state.status(), state.result(), state.error());
    }

    // WHY in-memory e non DB: per MVP è sufficiente; i job durano < 30 secondi.
    // LIMITE DOCUMENTATO: job persi al restart JVM (Neon può mettere in idle il serverless).
    @Scheduled(every = "10m")
    void cleanupOldJobs() {
        store.entrySet().removeIf(e ->
                e.getValue().completedAt() != null &&
                        e.getValue().completedAt().isBefore(Instant.now().minus(1, ChronoUnit.HOURS)));
    }
}
