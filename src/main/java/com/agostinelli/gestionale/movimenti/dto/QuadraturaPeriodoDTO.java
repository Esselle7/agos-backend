package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pannello di quadratura di periodo (PROMPT-RICONCILIAZIONE-PERIODO §5): confronto Σ Billy
 * elettronico ↔ Σ POS banca scomposto per causa. È <b>informativo</b>: i ricavi sono comunque
 * contabilizzati da Billy. Sostituisce la vecchia vista "Incassi POS da ripartire" a scontrino.
 */
public record QuadraturaPeriodoDTO(
        UUID importLogId,
        LocalDate importDataOra,
        int anno,
        BigDecimal billyElettronicoNonAgri,
        BigDecimal billyContabilizzato,
        BigDecimal posBancaTotale,
        BigDecimal posBancaCore,
        BigDecimal sigmaBpm,
        BigDecimal sigmaCa,
        BigDecimal assegnatoBpm,
        BigDecimal assegnatoCa,
        BigDecimal codaTesta,
        BigDecimal codaFondo,
        BigDecimal residuoCore,
        LocalDate maxDelBanca,
        List<String> note,
        List<String> approssimazioni,
        List<InAttesaDTO> inAttesa
) {
    /** Scontrino Billy venduto dopo l'ultima DEL banca: in attesa di accredito (non contabilizzato). */
    public record InAttesaDTO(LocalDate data, BigDecimal importo, String rif, String descrizione) {}
}
