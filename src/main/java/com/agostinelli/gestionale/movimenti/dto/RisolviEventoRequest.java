package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Risoluzione di una voce in eventi_da_riconciliare.
 * azione:
 *   SCARTA     → la riga non è un evento: stato SCARTATO, nessun movimento;
 *   CLASSIFICA → vietato sulle voci evento (invariante DACLASS): 409;
 *   RICONCILIA → attribuisce l'incasso a un evento e ne registra il pagamento, che è ciò che
 *                fa entrare il denaro nei saldi. Serve {@code tipo}; e o {@code eventoId}
 *                (evento reale) o {@code creaSegnaposto=true} (evento non ancora in anagrafica).
 */
public record RisolviEventoRequest(
        @NotNull String azione,      // SCARTA | CLASSIFICA | RICONCILIA
        Integer cogeId,
        Short businessUnitId,
        UUID eventoId,
        String nota,
        /** CAPARRA | ACCONTO | SALDO | PENALE | RIMBORSO (lk_tipi_evento_mov). */
        String tipo,
        /** true = crea un evento segnaposto per questo incasso invece di usare eventoId. */
        boolean creaSegnaposto
) {}
