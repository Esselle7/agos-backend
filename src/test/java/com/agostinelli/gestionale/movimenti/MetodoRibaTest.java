package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaCaParser;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec metodo-pagamento-riba (lotto 19/08/2026, punto 7).
 *
 * Una RiBa non è un addebito SEPA: finché le righe effetti finivano su RID_SDDMANDAT, chi leggeva
 * i movimenti per metodo vedeva addebiti SDD mai esistiti. I codici metodo sono referenziati per
 * STRINGA nel normalizer — un refuso non dà errore di compilazione, lo prende solo un test.
 *
 * Test di solo mapping: il normalizer è un POJO senza dipendenze iniettate.
 */
class MetodoRibaTest {

    private final MovimentoNormalizerImpl norm = new MovimentoNormalizerImpl();

    /** Riga BPM sintetica: in produzione non esiste nessuna 310/314 a libro, la regola va comunque difesa. */
    private RawMovimento bpm(String causale, String descrizione, String importoConSegno) {
        return norm.normalize(new RawRow(1, Map.of(
                Sorgente.KEY, Sorgente.BPM,
                "DATA_CONTABILE", "31/07/2026",
                "DATA_VALUTA", "31/07/2026",
                "IMPORTO", importoConSegno,
                "CAUSALE", causale,
                "DESCRIZIONE", descrizione,
                "CHIAVE", "test-" + causale)));
    }

    @Test
    void r3_bpm_effettiRitiratiERiba_sonoRIBA() {
        assertEquals("RIBA", bpm("310", "EFFETTI RITIRATI", "-1.000,00").metodoPagamentoCodice());
        assertEquals("RIBA", bpm("314", "RIBA A SCADENZA", "-2.049,17").metodoPagamentoCodice());
    }

    @Test
    void r5_bpm_commissioniSuEffetti_restanoAddebitoConto() {
        // La parola RIBA è nella descrizione e invita all'errore: 16I è la COMMISSIONE, non l'effetto.
        assertEquals("ADDEBITO_CONTO",
                bpm("16I", "COMMIS. RIBA-CONF/EFFETTI PAGATI", "-3,50").metodoPagamentoCodice());
    }

    @Test
    void r6_causaleSconosciuta_restaSenzaMetodo_manonRompe() {
        // Invariante I6 dell'import: nessuna ipotesi azzardata, la riga va in revisione manuale.
        assertNull(bpm("ZZZ", "CAUSALE INVENTATA", "-1,00").metodoPagamentoCodice());
    }

    /**
     * R4 + criterio di chiusura: le righe RiBa VERE dell'estratto CA di luglio 2026 — le stesse
     * tre già a libro in produzione — finiscono su RIBA passando dal parser reale, non da un mock.
     */
    @Test
    void r4_leRigheRealiDiLuglioFinisconoSuRIBA() throws Exception {
        Path csv = Path.of("..", "dati_luglio", "Movimenti_in_tempo_reale_2026_08_07_054028.csv");
        Assumptions.assumeTrue(Files.isReadable(csv), "estratto CA di luglio assente: test saltato");

        List<RawMovimento> movimenti;
        try (var in = new FileInputStream(csv.toFile())) {
            movimenti = new BancaCaParser().parse(in)
                    .stream().map(norm::normalize).toList();
        }

        var pagamentoEffetti = cerca(movimenti, "2049.17");
        assertEquals("RIBA", pagamentoEffetti.metodoPagamentoCodice(),
                "31/07 PAGAMENTO EFFETTI/RIBA: era RID_SDDMANDAT");
        assertEquals("USCITA", pagamentoEffetti.tipo());

        var accreditoEffetti = cerca(movimenti, "8676.82");
        assertEquals("RIBA", accreditoEffetti.metodoPagamentoCodice(),
                "29/07 ACCREDITO IMPORTO EFFETTI: era BONIFICO");
        assertEquals("ENTRATA", accreditoEffetti.tipo(),
                "stesso metodo nei due sensi: a distinguerle è il segno");

        // Controprova: una commissione bancaria dello stesso estratto NON diventa RIBA.
        var canone = movimenti.stream()
                .filter(m -> m.descrizione() != null && m.descrizione().contains("CANONE NOWBANKING"))
                .findFirst().orElseThrow(() -> new AssertionError("riga CANONE non trovata"));
        assertEquals("RID_SDDMANDAT", canone.metodoPagamentoCodice(),
                "COMMISSIONI/SPESE resta com'era: la spec tocca solo gli effetti");
    }

    private RawMovimento cerca(List<RawMovimento> movimenti, String importo) {
        return movimenti.stream()
                .filter(m -> m.importo() != null && m.importo().compareTo(new BigDecimal(importo)) == 0)
                .findFirst().orElseThrow(() -> new AssertionError("riga da " + importo + " non trovata"));
    }
}
