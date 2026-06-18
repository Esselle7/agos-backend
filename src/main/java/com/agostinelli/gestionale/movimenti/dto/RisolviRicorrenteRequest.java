package com.agostinelli.gestionale.movimenti.dto;

import java.util.UUID;

/**
 * Risoluzione di una ricorrente parcheggiata (V9):
 *  - COLLEGA: la collega a un piano ricorrente esistente (recurringPlanId), senza effetti contabili;
 *  - IGNORA: la archivia (non è una ricorrente da seguire).
 */
public record RisolviRicorrenteRequest(
        String azione,            // COLLEGA | IGNORA
        UUID recurringPlanId,     // obbligatorio per COLLEGA
        String nota
) {}
