package com.agostinelli.gestionale.anagrafica.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cespite con i campi derivati di comodo per la UI: quota mensile/annua di ammortamento e
 * quanto risulta già ammortizzato a oggi (a quote costanti dalla data di acquisto).
 */
public record CespiteDTO(
        UUID id,
        String descrizione,
        Integer contoCogeId,
        String contoCogeCodice,
        String contoCogeDescrizione,
        BigDecimal costoStorico,
        BigDecimal aliquotaAmmortamento,
        LocalDate dataAcquisto,
        boolean isActive,
        BigDecimal ammortamentoMensile,
        BigDecimal ammortamentoAnnuo,
        BigDecimal giaAmmortizzato,
        BigDecimal valoreResiduo
) {}
