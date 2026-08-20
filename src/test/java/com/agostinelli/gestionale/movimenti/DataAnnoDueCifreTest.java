package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.parser.Valori;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Riproduzione del 500 su PUT /api/movimenti/import/eventi/{id}/risolvi (20/08/2026).
 *
 * Dato reale: le due righe "CELLA ERIKA" in coda (prod, id edd6d354-… e 6035559a-…) hanno
 * raw_data.DATA_CONTABILE = "14/07/26" e data_movimento NULL. Da lì l'NPE in
 * ImportTriageService.riconciliaEvento:1043.
 */
class DataAnnoDueCifreTest {

    @Test
    void annoAQuattroCifre_vieneNormalizzato() {
        assertEquals("14/07/2026", Valori.toItSlash("14/07/2026"));
    }

    @Test
    void annoADueCifre_deveEssereNormalizzato() {
        assertEquals("14/07/2026", Valori.toItSlash("14/07/26"),
                "una data con anno a 2 cifre deve arrivare al normalizer in dd/MM/yyyy, "
                + "altrimenti parseItDate torna null e la riga finisce in coda senza data");
    }
}
