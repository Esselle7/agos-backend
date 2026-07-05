package com.agostinelli.gestionale.anagrafica.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Acquisto operativo di un cespite: crea il bene + il movimento di acquisto CAPEX collegato.
 *
 * La durata è espressa in ANNI (aliquota% = 100/vitaAnni). Il pagamento è opzionale:
 * se {@code dataPagamento} è valorizzata il movimento nasce REGISTRATO (liquidato), altrimenti
 * DA_LIQUIDARE con scadenza {@code dataScadenza} (default = dataAcquisto).
 */
public record CespiteAcquistoRequest(
        @NotBlank String descrizione,

        /** Conto COGE CAPEX (immobilizzazione, is_capex=true) su cui capitalizzare l'acquisto. */
        @NotNull Integer contoCogeId,

        @NotNull @DecimalMin("0.01") BigDecimal costoStorico,

        /** Vita utile in anni → aliquota di ammortamento a quote costanti. */
        @NotNull @Min(1) @Max(50) Integer vitaAnni,

        @NotNull LocalDate dataAcquisto,

        @NotNull Short businessUnitId,

        // ── Pagamento (opzionale) ────────────────────────────────────────────────
        LocalDate dataPagamento,
        Short contoBancarioId,
        Integer metodoPagamentoId,
        /** Scadenza usata se non pagato subito (DA_LIQUIDARE); default = dataAcquisto. */
        LocalDate dataScadenza
) {}
