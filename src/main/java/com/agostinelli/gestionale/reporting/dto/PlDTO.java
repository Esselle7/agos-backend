package com.agostinelli.gestionale.reporting.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PlDTO(
        BuRefDTO bu,
        LocalDate from,
        LocalDate to,
        RicaviDTO ricavi,
        CostiDTO costi,
        BigDecimal ebitda,
        BigDecimal ammortamenti,
        BigDecimal ebit,
        BigDecimal oneriFinanziari,
        BigDecimal ebt,
        BigDecimal imposte,
        BigDecimal utileNetto,
        BigDecimal marginePct,
        QualitaDTO qualita
) {
    public record RicaviDTO(BigDecimal totale, List<VoceDTO> perCategoria) {}

    public record CostiDTO(BigDecimal totale, BigDecimal capex, List<VoceDTO> perCategoria) {}

    /**
     * Attendibilita' del numero esposto: NON cambia il numero, gli mette accanto quanto di esso
     * non e' ancora certo. Serve a non far leggere un conto economico come se fosse chiuso quando
     * una parte e' ancora su conti transitori o fuori perimetro.
     */
    public record QualitaDTO(
            BigDecimal nonClassificatoRicavi,
            BigDecimal nonClassificatoRicaviPct,
            BigDecimal nonClassificatoCosti,
            BigDecimal nonClassificatoCostiPct,
            BigDecimal creditiEventiAperti,
            boolean perimetroIncompleto,
            String perimetroNota
    ) {}
}
