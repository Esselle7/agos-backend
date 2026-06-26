package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record MovimentoDTO(
        UUID id,
        String tipo,
        BigDecimal importo,
        BigDecimal importoImponibile,
        BigDecimal importoIva,
        BigDecimal importoCommissione,
        /** Data di competenza economica (= data_movimento, sempre valorizzata). */
        LocalDate dataMovimento,
        LocalDate dataCompetenza,
        /** Data di liquidazione effettiva. null = DA_LIQUIDARE. */
        LocalDate dataFinanziaria,
        /** Scadenza finanziaria attesa. Obbligatoria se dataFinanziaria è null. */
        LocalDate dataLiquidita,
        Short contoBancarioId,
        Integer metodoPagamentoId,
        Short businessUnitId,
        Integer contoCoge,
        Long categoriaId,
        UUID fornitoreId,
        UUID eventoId,
        String tipoEventoMovimento,
        String descrizione,
        String note,
        String stato,
        String fonte,
        String riferimentoEsterno,
        String allegatoPath,
        Instant createdAt,
        UUID createdBy,
        /** Campo derivato (non persistito): giorni mancanti alla scadenza (dataLiquidita - oggi).
         *  Valorizzato solo per movimenti in stato DA_LIQUIDARE con dataLiquidita != null.
         *  > 0  → giorni alla scadenza (non ancora in ritardo);
         *  == 0 → scade oggi;
         *  < 0  → ritardo: per USCITA "sei in ritardo di |n| giorni sul pagamento",
         *         per ENTRATA "qualcuno è in ritardo di |n| giorni sul pagarmi".
         *  Le rate generate in automatico dai piani di spesa ricorrente sono sempre REGISTRATE,
         *  quindi questo campo resta null per loro (lo scheduler le liquida alla scadenza). */
        Long giorniAllaScadenza
) {}
