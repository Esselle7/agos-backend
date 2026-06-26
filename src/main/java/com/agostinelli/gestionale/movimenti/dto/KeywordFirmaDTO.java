package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Firma keyword per la pagina "Gestione Keyword" (list/create/update). COGE per CODICE.
 * Per le firme che parcheggiano (azione PARK_EVENTO) bu/coge sono null e contano
 * {@code eventoForza}/{@code tipoEvento}; per le firme che contabilizzano (BOOK) bu+coge
 * sono obbligatori e {@code fornitoreId} è ammesso solo se natura = IDENTITA.
 */
public record KeywordFirmaDTO(
        UUID id,
        String natura,          // DOMINIO | IDENTITA
        String azione,          // BOOK | PARK_EVENTO
        String tipoMovimento,   // ENTRATA | USCITA | *
        String sorgente,        // BILLY | BPM | CA | *
        Short buId,
        String cogeCodice,
        UUID fornitoreId,
        String eventoForza,     // FORTE | DEBOLE (solo PARK_EVENTO)
        String tipoEvento,
        BigDecimal confidence,
        String origine,         // APPRESA | MANUALE | SEED
        String stato,           // ATTIVA | IN_CONFLITTO | DISATTIVATA
        String note,
        List<String> token,
        String createdAt
) {}
