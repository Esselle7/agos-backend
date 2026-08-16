package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una riga bancaria dell'import, com'è adesso (SPEC import-v2 R21/R22). È l'unità del registro:
 * ogni riga dei due file banca compare qui una volta e una sola, con la sua casa.
 *
 * <p>{@link #statoParola()} esiste perché <b>lo stato non è mai affidato al solo colore</b> (R22):
 * il badge porta sempre anche la parola.
 */
public record RigaImportDTO(
        /** Dove vive la riga: MOVIMENTO | SCARTATO | AMBIGUITA | EVENTO | RICORRENTE | DIFFERITO. */
        String origine,
        /** Id nella tabella d'origine: è quello che il wizard usa per aprirsi su QUESTA riga. */
        UUID id,
        LocalDate data,
        Short contoBancarioId,
        String conto,
        String tipo,                 // ENTRATA | USCITA
        BigDecimal importo,
        String causale,
        /** Il bucket del contatore: A_LIBRO | DA_CATALOGARE | FUORI_DAI_CONTI | ESCLUSO | DUPLICATA | PARTITA_DI_GIRO. */
        String stato,
        /** La stessa cosa, in italiano, da stampare nel badge accanto al colore. */
        String statoParola,
        /** Il perché, quando ce n'è uno: motivo dell'esclusione, proposta non applicata, nota. */
        String dettaglio) {
}
