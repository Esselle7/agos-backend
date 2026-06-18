package com.agostinelli.gestionale.movimenti.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Richiesta di classificazione manuale di una riga ambigua.
 * scarta=true → la riga viene marcata SCARTATO senza creare alcun movimento;
 * in quel caso cogeId / businessUnitId non sono richiesti.
 */
public record ClassificaAmbiguitaRequest(
        Integer cogeId,
        Short businessUnitId,
        Integer metodoPagamentoId,
        Short contoBancarioId,
        UUID fornitoreId,
        UUID eventoId,
        String tipoEventoMovimento,
        String nota,
        boolean apprendiKeyword,  // se true: estrae firme IDENTITA dalla descrizione → target scelto
        boolean scarta            // se true: marca SCARTATO senza creare movimento
) {}
