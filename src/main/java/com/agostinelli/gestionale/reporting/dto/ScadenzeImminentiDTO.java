package com.agostinelli.gestionale.reporting.dto;

import java.util.List;

public record ScadenzeImminentiDTO(
        List<ScadenzaDTO>           eventi,
        List<ScadenzaDTO>           rateRicorrenti,
        List<UscitaDaLiquidareDTO>  usciteDaLiquidare,
        // Incassi attesi: ENTRATA economicamente registrata ma non ancora liquidata
        // (data_finanziaria IS NULL) con una data_liquidita attesa. Simmetrico a usciteDaLiquidare.
        List<UscitaDaLiquidareDTO>  entrateDaRicevere
) {}
