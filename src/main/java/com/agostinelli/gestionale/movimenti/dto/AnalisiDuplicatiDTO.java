package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Analisi delle coppie di eventi sospette duplicate ancora presenti nella coda
 * {@code eventi_da_riconciliare}: per ognuna, il livello di confidenza e le
 * motivazioni leggibili che spiegano perché sono (o non sono) lo stesso incasso.
 * Alimenta la UI di revisione duplicati.
 */
public record AnalisiDuplicatiDTO(
        int eventiInCoda,
        int coppieSospette,
        List<CoppiaSospettaDTO> coppie) {

    /** Una coppia sospetta, con confidenza, punteggio (0-100) e motivazioni. */
    public record CoppiaSospettaDTO(
            String confidenza,        // CERTA | PROBABILE
            int punteggio,
            EventoBreveDTO eventoA,
            EventoBreveDTO eventoB,
            List<MotivoDTO> motivi) {}

    /** Sintesi di un evento in coda, per il confronto affiancato. */
    public record EventoBreveDTO(
            UUID id,
            String fonte,             // IMPORT_BILLY | IMPORT_BANCA
            LocalDate dataMovimento,
            BigDecimal importo,
            String tipo,
            String controparteNome,
            String controparteIban,
            LocalDate dataEvento,
            String tipoEvento,
            String descrizione) {}

    /** Una motivazione: segnale, dettaglio leggibile, tono (FORTE/MEDIO/DEBOLE/CONFLITTO). */
    public record MotivoDTO(String segnale, String dettaglio, String tono) {}
}
