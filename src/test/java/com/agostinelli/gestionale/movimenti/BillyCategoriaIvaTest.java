package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BillyParser;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.BillyCategoria;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) di {@link BillyCategoria} sull'aliquota delle due categorie dello spaccio.
 *
 * <p><b>Perche' esiste.</b> Fino a V41 «Prodotti trasformati» finiva sul conto dell'ortofrutta
 * al 4 %. Misurato sui 235 scontrini reali (dati_luglio/corrispettivi-12 (1).csv +
 * esempi_dati_storici/corrispettivi-12.csv): ortofrutta 4,00 %, trasformati 10,00 % su 20
 * scontrini per 6.270,24 €. Il conto unico non poteva avere un default corretto per entrambe.
 *
 * <p>L'ancoraggio del terzo test e' uno <b>scontrino vero</b> (07/06/2026, 172,00 €), di cui
 * Billy dichiara 15,64 € di IVA: e' la prova che lo scorporo {@code lordo/(1+a)} con l'aliquota
 * giusta riproduce il numero di Billy al centesimo, e con quella sbagliata no (6,62 €).
 */
class BillyCategoriaIvaTest {

    private static final BigDecimal IVA_10 = new BigDecimal("0.10");
    private static final BigDecimal IVA_04 = new BigDecimal("0.04");

    @Test
    void prodottiTrasformati_conto30_03_004_al10() {
        BillyCategoria.Esito e = BillyCategoria.classifica(scontrino("PRODOTTI_TRASFORMATI", "172.00"));

        assertNotNull(e);
        assertEquals("30.03.004", e.cogeCodice(), "i trasformati hanno un conto proprio (V41)");
        assertEquals(0, IVA_10.compareTo(e.aliquotaIva()), "Billy misura 10% sui trasformati");
    }

    @Test
    void ortofrutta_resta_su30_03_002_al4() {
        BillyCategoria.Esito e = BillyCategoria.classifica(scontrino("ORTOFRUTTA_4", "130.36"));

        assertNotNull(e);
        assertEquals("30.03.002", e.cogeCodice());
        assertEquals(0, IVA_04.compareTo(e.aliquotaIva()));
    }

    /** Scontrino reale del 07/06/2026: lordo 172,00, IVA dichiarata da Billy 15,64. */
    @Test
    void scorporoConAliquotaDiBilly_riproduceLIvaDelloScontrinoReale() {
        BigDecimal lordo = new BigDecimal("172.00");
        BigDecimal ivaDichiarataDaBilly = new BigDecimal("15.64");

        BigDecimal aliquota = BillyCategoria.classifica(scontrino("PRODOTTI_TRASFORMATI", "172.00")).aliquotaIva();
        BigDecimal imponibile = lordo.divide(BigDecimal.ONE.add(aliquota), 2, RoundingMode.HALF_UP);
        BigDecimal iva = lordo.subtract(imponibile); // identico a MovimentiService.applyDerivedAmounts

        assertEquals(0, ivaDichiarataDaBilly.compareTo(iva),
                "lo scorporo deve dare l'IVA che Billy dichiara sullo scontrino");
        assertEquals(0, lordo.compareTo(imponibile.add(iva)), "invariante I1: imponibile + iva = lordo");
    }

    // ── Percorso reale: parser → normalizer → categoria ────────────────────────

    /**
     * CSV corrispettivi con l'intestazione VERA di `dati_luglio/corrispettivi-12 (1).csv` e due
     * scontrini veri di luglio, piu' il riepilogo giornaliero e la riga TOTALI della seconda
     * sezione. Inline e non da file perche' `esempi_dati_storici/` e `dati_luglio/` stanno FUORI
     * dal repo git: un test che vi dipendesse si skipperebbe in CI, come gia' succede a
     * {@code EtlParserGeneralitaTest.billy_csvNativo}.
     */
    private static final String CSV_REALE = String.join("\n",
            "Elaborazione corrispettivi",
            "dal 01-07-2026 al 31-07-2026;",
            "Data;Importo;Numero (Pagamento);Carne;Agriturismo;Prodotti trasformati;Servizi;Ortofrutta;"
                    + "Iva Imponibile;Iva Importo;Contanti;Elettronico;Non riscosso servizi;"
                    + "Non riscosso beni;Non riscosso fattura;Buoni pasto;Omaggi",
            "17-07-2026 09:15:52;47,00;DCW2026/1005-0377 (E);;;47,00;;;42,73;4,27;0,00;47,00;0,00;0,00;0,00;0,00;0,00",
            "22-07-2026 16:43:45;35,00;DCW2026/1279-1179 (E);;;;;35,00;33,65;1,35;0,00;35,00;0,00;0,00;0,00;0,00;0,00",
            "17-07-2026;1127,00;2;;1080,00;47,00;;;1024,55;102,45;0,00;0,00;;1127,00;;;;;",
            "Totale;17738,60;",
            "Data;Totale;Scontrini/Annulli;Carne;Agriturismo;Prodotti trasformati;Servizi;Ortofrutta;"
                    + "Iva Imponibile 10;Iva Importo 10;Iva Imponibile 4;Iva Importo 4",
            "TOTALI;17738,60;31;1767,24;15794,00;47,00;0,00;130,36;16007,50;1600,74;125,34;5,02");

    /**
     * Il contratto fra {@link BillyParser} (che scrive la chiave "PRODOTTI_TRASFORMATI") e
     * {@link BillyCategoria} (che la legge) e' una stringa nuda in due file: se uno dei due la
     * cambia, i test che costruiscono la mappa a mano restano verdi e la produzione si rompe.
     * Questo test percorre la catena vera. Verifica anche, per esecuzione, che il parser scarti
     * il riepilogo giornaliero e la riga TOTALI della seconda sezione (filtro sull'orario).
     */
    @Test
    void catenaReale_parserNormalizerCategoria() {
        List<com.agostinelli.gestionale.movimenti.importlayer.model.RawRow> righe =
                new BillyParser().parse(new ByteArrayInputStream(CSV_REALE.getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, righe.size(),
                "solo i 2 scontrini: riepilogo giornaliero e riga TOTALI scartati (nessun orario)");

        MovimentoNormalizerImpl norm = new MovimentoNormalizerImpl();
        RawMovimento trasformati = norm.normalize(righe.get(0));
        RawMovimento ortofrutta = norm.normalize(righe.get(1));

        BillyCategoria.Esito t = BillyCategoria.classifica(trasformati);
        assertNotNull(t, "la chiave PRODOTTI_TRASFORMATI deve arrivare dal parser fino a qui");
        assertEquals("30.03.004", t.cogeCodice());
        assertEquals(0, IVA_10.compareTo(t.aliquotaIva()));
        assertEquals(0, new BigDecimal("4.27").compareTo(scorporoIva(trasformati.importo(), t.aliquotaIva())),
                "scontrino 17/07 da 47,00: Billy dichiara 4,27 di IVA");

        BillyCategoria.Esito o = BillyCategoria.classifica(ortofrutta);
        assertNotNull(o);
        assertEquals("30.03.002", o.cogeCodice());
        assertEquals(0, IVA_04.compareTo(o.aliquotaIva()));
        assertEquals(0, new BigDecimal("1.35").compareTo(scorporoIva(ortofrutta.importo(), o.aliquotaIva())),
                "scontrino 22/07 da 35,00: Billy dichiara 1,35 di IVA");
    }

    /** Lo stesso calcolo di MovimentiService.applyDerivedAmounts. */
    private static BigDecimal scorporoIva(BigDecimal lordo, BigDecimal aliquota) {
        return lordo.subtract(lordo.divide(BigDecimal.ONE.add(aliquota), 2, RoundingMode.HALF_UP));
    }

    /** Scontrino Billy mono-categoria: la colonna passa dai campi grezzi del RawRow. */
    private RawMovimento scontrino(String colonna, String importo) {
        Map<String, String> campi = new HashMap<>();
        campi.put("_SORGENTE", "BILLY");
        campi.put(colonna, importo);
        RawRow row = new RawRow(1, campi);
        BigDecimal orto = "ORTOFRUTTA_4".equals(colonna) ? new BigDecimal(importo) : null;
        return new RawMovimento(1, "IMPORT_BILLY", LocalDate.of(2026, 6, 7), null,
                new BigDecimal(importo), "ENTRATA", "SCONTRINO", null, null, BigDecimal.ZERO, null,
                "rif", null, null, null, null, orto, "SCONTRINO", "", EntitaEstratte.EMPTY, row, null, null);
    }
}
