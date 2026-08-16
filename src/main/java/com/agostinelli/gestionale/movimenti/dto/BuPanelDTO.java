package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Pannello "BU dell'import": i movimenti creati da UN import (movimenti.fonte_importazione_id)
 * raggruppati per Business Unit, con gli INCERTI in un gruppo a parte.
 *
 * Incertezza DERIVATA dal dato, non marcata dal motore: un movimento è "da assegnare" quando è
 * finito sul conto CoGe transitorio (39.99.999 ricavi / 49.99.999 costi) ed è ANCORA sulla BU di
 * fallback 5. Nel motore (MovimentoMappingEngineImpl) ogni assegnazione di quei due conti porta
 * con sé {@code bu = 5}, mentre le altre BU 5 sono classificazioni volute (spese bancarie
 * 40.02.*, giroconti 10.03.*, versamento soci): "bu = 5" da solo darebbe falsi positivi.
 * Scelta la BU giusta, la riga esce dalla coda e compare nel gruppo scelto, ancora marcata
 * {@code transitorio} (la CoGe si sistema nella sezione "Da catalogare": è un altro lavoro).
 *
 * Invariante: cambiare la BU di un movimento NON muove nessun saldo (la BU è una dimensione
 * analitica; mv_saldi_conti non la usa). Vedi docs/adr/006-bu-incerta-derivata-dal-transitorio.md.
 */
public record BuPanelDTO(
        UUID importLogId,
        Instant dataImport,
        String filename,
        long totaleMovimenti,          // = Σ gruppi.numero + incerti.numero (movimenti non annullati)
        List<Gruppo> gruppi,           // BU con almeno un movimento assegnato, ordinate per id BU
        Gruppo incerti                 // buId = null; la coda "da assegnare", è il lavoro da fare
) {

    /** Un gruppo del pannello: una BU vera, oppure gli incerti (buId null). */
    public record Gruppo(
            Short buId,
            String buCodice,
            String buNome,
            long numero,
            BigDecimal entrate,
            BigDecimal uscite,
            List<Riga> movimenti
    ) {}

    /** Una riga del pannello: il minimo per riconoscere il movimento e spostarlo di BU. */
    public record Riga(
            UUID id,
            String tipo,               // ENTRATA | USCITA
            BigDecimal importo,
            LocalDate dataMovimento,
            String descrizione,
            String cogeCodice,
            String cogeDescrizione,
            Short buId,
            boolean transitorio        // true ⇔ conto CoGe «da classificare» (39/49.99.999)
    ) {}
}
