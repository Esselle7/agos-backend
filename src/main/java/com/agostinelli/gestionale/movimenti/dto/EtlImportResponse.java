package com.agostinelli.gestionale.movimenti.dto;

import java.util.List;
import java.util.UUID;

/**
 * Risposta del flusso ETL (import file Billy / BPM / CA).
 * Distinta da {@link BulkImportResponse} (usato dal bulk JSON manuale) per non
 * rompere il contratto esistente.
 */
public record EtlImportResponse(
        UUID importLogId,
        int importati,
        int duplicati,
        int ambigui,
        int scartati,
        int parcheggiati,
        List<EtlRowError> errori
) {}
