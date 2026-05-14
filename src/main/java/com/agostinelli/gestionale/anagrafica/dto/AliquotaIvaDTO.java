package com.agostinelli.gestionale.anagrafica.dto;

import java.math.BigDecimal;

/**
 * aliquota è il moltiplicatore decimale da usare direttamente nel campo aliquotaIva
 * di POST /api/movimenti (es. 0.10 per il 10%). Il DB lo archivia come percentuale
 * (10.0) e il backend divide per 100 prima di restituirlo.
 */
public record AliquotaIvaDTO(
        Integer id,
        BigDecimal aliquota,
        String descrizione
) {}
