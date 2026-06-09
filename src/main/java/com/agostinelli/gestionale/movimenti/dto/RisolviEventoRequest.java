package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Risoluzione di una voce in eventi_da_riconciliare.
 * azione:
 *   SCARTA     → la riga non è un evento: stato SCARTATO, nessun movimento;
 *   CLASSIFICA → crea un movimento (richiede cogeId + businessUnitId) e segna RICONCILIATO;
 *   RICONCILIA → collega a un evento dell'anagrafica (eventoId opzionale) e segna RICONCILIATO.
 */
public record RisolviEventoRequest(
        @NotNull String azione,      // SCARTA | CLASSIFICA | RICONCILIA
        Integer cogeId,
        Short businessUnitId,
        UUID eventoId,
        String nota
) {}
