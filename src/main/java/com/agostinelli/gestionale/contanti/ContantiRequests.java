package com.agostinelli.gestionale.contanti;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * I body delle cinque operazioni: <b>solo</b> i campi che il modulo chiede a schermo.
 *
 * <p>Conto (sempre la cassa), metodo di pagamento, data finanziaria, stato, fonte ed evento non
 * compaiono qui apposta (SPEC modulo-contanti §1): quel che non esiste nel DTO non si può forzare
 * dal client. In particolare l'assenza di {@code eventoId} è l'invariante I4 — i ricavi evento
 * nascono solo da {@code EventiService.registraPagamento}.
 *
 * <p>Stanno in un file solo perché sono cinque record da tre campi: un file per ciascuno sarebbe
 * boilerplate senza informazione.
 */
public final class ContantiRequests {

    private ContantiRequests() {}

    /** Op. 1 — soldi che escono dalla banca ed entrano nel cassetto. */
    public record Prelievo(
            @NotNull @DecimalMin("0.01") BigDecimal importo,
            @NotNull LocalDate data,
            /** Da quale banca. Finisce nella descrizione, non in conto_bancario_id (invariante I2). */
            @NotNull Short contoBancarioId
    ) {}

    /** Op. 2 — soldi che escono dal cassetto e vanno in banca. */
    public record Deposito(
            @NotNull @DecimalMin("0.01") BigDecimal importo,
            @NotNull LocalDate data,
            @NotNull Short contoBancarioId
    ) {}

    /** Op. 3 — incasso in contanti. Il CoGe dev'essere di tipo RICAVO e non riservato agli eventi. */
    public record Incasso(
            @NotNull @DecimalMin("0.01") BigDecimal importo,
            @NotNull LocalDate data,
            @NotNull Integer contoCoge,
            @NotNull Short businessUnitId,
            @NotBlank @Size(max = 500) String descrizione
    ) {}

    /** Op. 4 — spesa in contanti. Il CoGe dev'essere di tipo COSTO. */
    public record Spesa(
            @NotNull @DecimalMin("0.01") BigDecimal importo,
            @NotNull LocalDate data,
            @NotNull Integer contoCoge,
            @NotNull Short businessUnitId,
            @NotBlank @Size(max = 500) String descrizione,
            UUID fornitoreId
    ) {}

    /**
     * Op. 5 — conta cassa. Si dichiara quanto <b>c'è davvero</b> nel cassetto; il delta contro il
     * saldo teorico lo calcola il server (R6). {@code contato} può essere 0: il cassetto vuoto è
     * una risposta legittima, non un importo invalido.
     */
    public record Conta(
            @NotNull @DecimalMin("0.00") BigDecimal contato,
            @NotNull LocalDate data,
            @NotBlank @Size(max = 400) String motivo
    ) {}
}
