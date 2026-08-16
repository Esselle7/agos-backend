package com.agostinelli.gestionale.movimenti.dto;

import com.agostinelli.gestionale.shared.dto.PagedResponse;

import java.math.BigDecimal;

/**
 * Il registro dell'import: la pagina di righe più i <b>totali del filtro corrente</b>.
 *
 * <p>I totali sono di TUTTE le righe che passano il filtro, non della pagina caricata: chi filtra
 * «da catalogare · Crédit Agricole» vuole sapere quanto vale quella coda, non quanto valgono le
 * prime cinquanta righe che ha davanti. Per questo li calcola il server, che l'insieme filtrato
 * ce l'ha già intero in mano.
 *
 * <p>Due colonne, entrate e uscite, mai un netto: un totale netto nasconde esattamente la cosa che
 * si sta controllando contro l'estratto conto (stessa regola del contatore, §6.1).
 */
public record RegistroImportDTO(
        PagedResponse<RigaImportDTO> pagina,
        long righeEntrate,
        BigDecimal totaleEntrate,
        long righeUscite,
        BigDecimal totaleUscite) {
}
