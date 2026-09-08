package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il canone mensile del Crédit Agricole non deve restare {@code CAUSALE_NON_MAPPATA}.
 *
 * <p>Riprodotto sui dati veri: nell'import dell'08/09/2026 la riga 5 del file CA — canone di
 * agosto, 13,50 € — è finita in {@code import_ambiguita} con motivo {@code CAUSALE_NON_MAPPATA},
 * e da lì <b>non è mai diventata un movimento</b>: quei 13,50 € non erano in nessun saldo. La
 * causale {@code INTERESSI/COMPETENZE} non era nella mappa di {@code metodoCa}, mentre la sua
 * gemella BPM (662, 660, 16H…16Z, 18D, 195, 669) c'era da sempre.
 *
 * <p>È un canone <b>mensile</b>: senza questa mappa il buco si ripresenta a ogni import.
 *
 * <p>Test di solo mapping — il normalizer è un POJO senza dipendenze iniettate, come
 * {@code MetodoRibaTest}.
 */
class MetodoCanoneCaTest {

    private final MovimentoNormalizerImpl norm = new MovimentoNormalizerImpl();

    /** Riga CA. {@code Map.of} non ammette null, quindi i campi vuoti si omettono. */
    private RawMovimento ca(String causale, String descrizione, String uscite) {
        Map<String, String> campi = new HashMap<>();
        campi.put(Sorgente.KEY, Sorgente.CA);
        campi.put("DATA_OPERAZIONE", "31/08/2026");
        campi.put("DATA_VALUTA", "31/08/2026");
        campi.put("DIVISA", "EUR");
        campi.put("CAUSALE", causale);
        campi.put("DESCRIZIONE", descrizione);
        campi.put("USCITE", uscite);
        return norm.normalize(new RawRow(5, campi));
    }

    /**
     * La riga esatta che si è fermata in produzione l'08/09/2026 (import_ambiguita, riga 5,
     * raw_data verbatim). Se questo torna null, il canone di agosto torna fuori dai saldi.
     */
    @Test
    void canoneMensileCa_haUnMetodo_eNonRestaInRevisione() {
        RawMovimento r = ca("INTERESSI/COMPETENZE", "CANONE MENSILE C/C - 202608", "-13,50");

        assertEquals("ADDEBITO_CONTO", r.metodoPagamentoCodice(),
                "il canone c/c è un addebito diretto della banca, come la gemella BPM 662 / 16H");
        assertNotNull(r.metodoPagamentoCodice(),
                "metodo null su fonte IMPORT_BANCA ⇒ CAUSALE_NON_MAPPATA ⇒ riga fuori dai saldi");
    }

    /** Stessa causale, le altre righe che la banca ci mette dentro: competenze e interessi. */
    @Test
    void stessaCausale_ancheCompetenzeEInteressi() {
        assertEquals("ADDEBITO_CONTO",
                ca("INTERESSI/COMPETENZE", "COMPETENZE TRIMESTRALI", "-2,10").metodoPagamentoCodice());
        assertEquals("ADDEBITO_CONTO",
                ca("INTERESSI/COMPETENZE", "INTERESSI PASSIVI SU C/C", "-41,88").metodoPagamentoCodice());
    }

    /**
     * La rete di sicurezza resta: una causale che la banca non ci ha mai mandato NON deve
     * ricevere un metodo inventato — meglio la revisione manuale di un'ipotesi azzardata (I6).
     */
    @Test
    void causaleSconosciuta_restaSenzaMetodo() {
        assertNull(ca("CAUSALE MAI VISTA", "QUALCOSA", "-1,00").metodoPagamentoCodice());
    }
}
