package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Una riga bancaria che NON è entrata nei conti (coda «Righe fuori dai conti», audit §7.4).
 * I campi contabili (tipo, conto, descrizione) sono ri-derivati server-side da {@code raw_data}
 * col normalizzatore dell'import: la riga in tabella conserva solo il grezzo (SPEC I3).
 *
 * @param motivoLeggibile il «perché» detto in italiano, da mostrare a schermo al posto del codice.
 * @param normalizzabile  false = raw_data non più interpretabile (colonne cambiate): la riga si
 *                        vede ma non si può contabilizzare. Fail fast visibile, non un movimento
 *                        inventato.
 */
public record ScartatoDTO(
        UUID id,
        UUID importLogId,
        String fonte,
        int rigaNumero,
        String motivo,
        String motivoLeggibile,
        LocalDate dataMovimento,
        BigDecimal importo,
        String tipo,                 // ENTRATA | USCITA
        String descrizione,
        Short contoBancarioId,
        String contoNome,
        String stato,                // DA_VEDERE | CONTABILIZZATA | IGNORATA
        UUID movimentoId,
        boolean normalizzabile
) {}
