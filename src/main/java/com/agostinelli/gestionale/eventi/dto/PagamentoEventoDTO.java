package com.agostinelli.gestionale.eventi.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Dettaglio di un pagamento (movimento) collegato a un evento.
 *
 * Per i ruoli non-ADMIN, gli ADMIN-only fields ({@code importo}, {@code note})
 * vengono restituiti come {@code null}. Le date e il tipo restano visibili
 * per consentire al frontend di costruire il journey stepper.
 */
public record PagamentoEventoDTO(
        UUID movimentoId,

        /** CAPARRA | ACCONTO | SALDO | PENALE | RIMBORSO. */
        String tipo,

        /** ADMIN-only. Per RIMBORSO è negativo. {@code null} per non-ADMIN. */
        BigDecimal importo,

        /** Data effettiva del pagamento (data finanziaria). Sempre visibile. */
        LocalDate dataFinanziaria,

        /** ADMIN-only. {@code null} per non-ADMIN. */
        String note,

        String stato
) {}
