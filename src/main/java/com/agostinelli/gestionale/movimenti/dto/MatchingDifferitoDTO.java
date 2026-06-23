package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Riga di "Matching differiti" (V11): una riga bancaria intercettata dall'import che combacia
 * (importo al centesimo + descrizione uguale) con un movimento MANUALE già presente in stato
 * DA_LIQUIDARE (non ancora liquidato). La riga banca NON è stata persistita come movimento per
 * evitare doppia registrazione: l'utente decide se COLLEGA (liquida il movimento esistente con
 * i dati della riga banca) oppure IGNORA (crea comunque un nuovo movimento dalla riga banca).
 *
 * Espone sia i dati della riga banca che un riepilogo del movimento Da Liquidare abbinato,
 * così la UI di smistamento mostra entrambi i lati del match all'utente.
 */
public record MatchingDifferitoDTO(
        UUID id,
        UUID importLogId,
        // Lato "movimento Da Liquidare già in gestionale"
        UUID movimentoId,
        String movimentoTipo,           // ENTRATA | USCITA
        LocalDate movimentoDataMovimento,
        LocalDate movimentoDataLiquidita,
        BigDecimal movimentoImporto,
        String movimentoDescrizione,
        String movimentoStato,          // sempre DA_LIQUIDARE al momento del match
        String movimentoFonte,
        // Lato "riga banca intercettata dall'import"
        String fonte,                    // IMPORT_BANCA | IMPORT_BILLY
        Integer rigaNumero,
        LocalDate dataBanca,
        BigDecimal importo,
        String descrizione,
        Short contoBancarioId,
        // Stato risoluzione
        String stato,                    // DA_RICONCILIARE | COLLEGATO | IGNORATO
        String note,
        LocalDate risoltoAt,
        UUID risoltoBy,
        LocalDate createdAt
) {}
