package com.agostinelli.gestionale.reporting.dto;

import java.util.UUID;

public record JobStatusDTO(
        UUID jobId,
        String status,
        PlDTO result,
        String error
) {}
