package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BulkImportRequest(
        @NotNull @Size(max = 500)
        List<MovimentoCreateRequest> movimenti
) {}
