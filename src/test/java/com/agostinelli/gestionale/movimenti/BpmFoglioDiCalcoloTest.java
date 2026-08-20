package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.MovimentoNormalizerImpl;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.BancaBpmParser;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Valori;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SPEC docs/specs/bpm-luglio-2026-recupero.md — R2..R6.
 *
 * Un estratto conto BPM ri-salvato da un foglio di calcolo perde tre cose sulla stessa riga:
 * il secolo della data ("27/07/2026" → "27/07/26"), lo zero decimale finale dell'importo
 * ("400,70" → "400,7") e lo zero iniziale della causale ("092" → "92"). Il 19/08/2026 questo
 * ha fermato 62 righe BPM di luglio in coda, tutte senza data: 3.256,35 € fuori dai saldi.
 *
 * I test lavorano sul normalizzatore (POJO senza dipendenze iniettate) perché è il punto che
 * le code ri-attraversano a ogni lettura: ciò che passa di qui è ciò che si sblocca da solo.
 */
class BpmFoglioDiCalcoloTest {

    static final Path DIR = Path.of("..", "dati_luglio");
    static final String CSV_BPM = "MovimentiCC_OnLine_07_08_2026_05.51.09.csv";

    private final MovimentoNormalizerImpl norm = new MovimentoNormalizerImpl();
    private final ObjectMapper json = new ObjectMapper();

    // ── R2 — finestra del secolo, scritta e testata ───────────────────────────

    @Test
    void r2_annoTtoDueCifre_stanellafinestra2000() {
        assertEquals(LocalDate.of(2026, 7, 14), Valori.parseAnyDate("14/07/26"));
        assertEquals(LocalDate.of(2099, 1, 1), Valori.parseAnyDate("01/01/99"),
                "99 → 2099: la finestra è 2000+yy, non il secolo più vicino");
        assertEquals(LocalDate.of(2000, 2, 3), Valori.parseAnyDate("03/02/00"));
        assertEquals(LocalDate.of(2003, 2, 1), Valori.parseAnyDate("01/02/03"),
                "edge case ambiguo dd/MM/yy: si legge giorno/mese/anno, come tutto il resto del file");
        assertEquals(LocalDate.of(2026, 7, 14), Valori.parseAnyDate("14/07/2026"),
                "l'anno a 4 cifre non cambia di una virgola");
    }

    // ── R3 — causale numerica ri-paddata a 3, alfanumeriche intatte ───────────

    @Test
    void r3_causaleNumericaRipaddata() {
        assertEquals("092", Valori.causaleBanca("92"));
        assertEquals("090", Valori.causaleBanca("90"));
        assertEquals("008", Valori.causaleBanca("8"));
        assertEquals("480", Valori.causaleBanca("480"));
        assertEquals("78A", Valori.causaleBanca("78A"));
        assertEquals("ZI0", Valori.causaleBanca("ZI0"));
        assertEquals("50C", Valori.causaleBanca("50C"));
        assertEquals("GIROCONTO/BONIFICO", Valori.causaleBanca("GIROCONTO/BONIFICO"));
        assertNull(Valori.causaleBanca(null));
    }

    // ── R4 — metodoBpm riconosce l'incasso POS partendo dalla causale mangiata ─

    @Test
    void r4_causaleMangiata_restaUnIncassoPos() {
        RawMovimento n = normalizza(RAW_POS_SCARTATO);
        assertEquals("POS_BPM", n.metodoPagamentoCodice(),
                "092 mangiato in 92 deve tornare a essere un incasso POS");
        assertEquals(MovimentoNormalizerImpl.CIRCUITO_NUMIA, n.circuitoPos(),
                "senza circuito la riga non entra nella riconciliazione POS a periodo");
        assertEquals(LocalDate.of(2026, 7, 25), n.dataIncassoPos(),
                "la data reale dell'incasso viene dal 'DEL 25/07/26' della descrizione");
    }

    // ── R5 — equivalenza: file integro ≡ stessa copia passata da un foglio di calcolo ──

    @Test
    void r5_fileIntegroEFileMangiato_produconoIStessiCampi() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(DIR), "Cartella dati_luglio assente: test saltato");
        Path csv = DIR.resolve(CSV_BPM);
        Assumptions.assumeTrue(Files.isRegularFile(csv), "CSV BPM assente: test saltato");

        byte[] integro = Files.readAllBytes(csv);
        byte[] mangiato = passaDaFoglioDiCalcolo(new String(integro, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);

        BancaBpmParser parser = new BancaBpmParser();
        List<RawRow> a = parser.parse(new ByteArrayInputStream(integro));
        List<RawRow> b = parser.parse(new ByteArrayInputStream(mangiato));

        assertEquals(a.size(), b.size(), "stesso numero di righe lette");
        assertFalse(a.isEmpty(), "il file di luglio 2026 non è vuoto");

        // Il danno dev'essere REALE, altrimenti l'equivalenza qui sotto è vera per costruzione
        // e questo test non potrebbe fallire mai (guardia vacua).
        long campiDanneggiati = 0;
        for (int i = 0; i < a.size(); i++) {
            for (String k : List.of("DATA_CONTABILE", "IMPORTO", "CAUSALE")) {
                if (!java.util.Objects.equals(a.get(i).campi().get(k), b.get(i).campi().get(k))) campiDanneggiati++;
            }
        }
        // Misurato sull'estratto conto di luglio 2026: 45 campi grezzi diversi — importi e causali.
        // Le DATE non compaiono più fra le differenze perché il parser le ri-normalizza già a
        // 4 cifre (è il fix R1): il danno sul disco c'è, ma non arriva più al normalizzatore.
        assertTrue(campiDanneggiati >= 40,
                "la copia 'da foglio di calcolo' deve differire davvero dal grezzo integro, "
                + "campi diversi trovati: " + campiDanneggiati);
        for (int i = 0; i < a.size(); i++) {
            RawMovimento na = norm.normalize(a.get(i));
            RawMovimento nb = norm.normalize(b.get(i));
            String dove = "riga " + (i + 1) + " (" + na.descrizione() + ")";
            assertEquals(na.dataMovimento(), nb.dataMovimento(), "data — " + dove);
            assertEquals(na.importo(), nb.importo(), "importo — " + dove);
            assertEquals(na.tipo(), nb.tipo(), "tipo — " + dove);
            assertEquals(na.metodoPagamentoCodice(), nb.metodoPagamentoCodice(), "metodo — " + dove);
            assertEquals(na.circuitoPos(), nb.circuitoPos(), "circuito POS — " + dove);
            assertEquals(na.dataIncassoPos(), nb.dataIncassoPos(), "DEL — " + dove);
            assertEquals(na.girosalto(), nb.girosalto(), "giroconto — " + dove);
            assertEquals(na.riferimentoEsterno(), nb.riferimentoEsterno(),
                    "riferimento esterno (chiave di dedup) — " + dove);
        }
        assertTrue(a.size() >= 60, "atteso l'estratto conto completo di luglio, letto " + a.size());
    }

    // ── R6 — retroattività: il grezzo già in coda, ri-normalizzato, torna leggibile ──
    // I quattro raw_data qui sotto sono copiati alla lettera dalle code di produzione
    // (dump agos_prod_2026-08-20): sono i byte veri, non una ricostruzione.

    static final String RAW_POS_SCARTATO = """
            {"CANALE": null, "CHIAVE": null, "DIVISA": "EUR", "CAUSALE": "92", "IMPORTO": "400,7",\
             "_SORGENTE": "BPM", "DATA_VALUTA": "27/07/26", "DESCRIZIONE": "inc.pos carte credit -\
             numia-inter  del 25/07/26 pdv 5413836/00003 societa' agricola agostinelli            co",\
             "DATA_CONTABILE": "27/07/26"}""";

    static final String RAW_AMBIGUITA = """
            {"CANALE": null, "CHIAVE": null, "DIVISA": "EUR", "CAUSALE": "480", "IMPORTO": "85,69",\
             "_SORGENTE": "BPM", "DATA_VALUTA": "31/07/26", "DESCRIZIONE": "bonif. vs. favore -\
             bon.da stripe technology europe ltd shopify y 5s8w0", "DATA_CONTABILE": "31/07/26"}""";

    static final String RAW_EVENTO = """
            {"CANALE": null, "CHIAVE": null, "DIVISA": "EUR", "CAUSALE": "480", "IMPORTO": "20",\
             "_SORGENTE": "BPM", "DATA_VALUTA": "14/07/26", "DESCRIZIONE": "bonif. vs. favore -\
             bon.da cella erika caparra 3 bis evento 18.09 .26", "DATA_CONTABILE": "14/07/26"}""";

    static final String RAW_RICORRENTE = """
            {"CANALE": null, "CHIAVE": null, "DIVISA": "EUR", "CAUSALE": "150", "IMPORTO": "-2501,17",\
             "_SORGENTE": "BPM", "DATA_VALUTA": "31/07/26", "DESCRIZIONE": "rimborso finanz. -\
             mutuo n.1273 5796807 rata 31/07/2026", "DATA_CONTABILE": "31/07/26"}""";

    @Test
    void r6_grezzoInCoda_riNormalizzatoTornaLeggibile() {
        RawMovimento pos = normalizza(RAW_POS_SCARTATO);
        assertEquals(LocalDate.of(2026, 7, 27), pos.dataMovimento());
        assertEquals(new BigDecimal("400.70"), pos.importo(), "lo zero decimale finale torna");
        assertEquals("ENTRATA", pos.tipo());
        assertEquals("POS_BPM", pos.metodoPagamentoCodice());
        assertEquals((Short) (short) 1, pos.contoBancarioId());

        RawMovimento amb = normalizza(RAW_AMBIGUITA);
        assertEquals(LocalDate.of(2026, 7, 31), amb.dataMovimento());
        assertEquals(new BigDecimal("85.69"), amb.importo());
        assertEquals("ENTRATA", amb.tipo());

        RawMovimento ev = normalizza(RAW_EVENTO);
        assertEquals(LocalDate.of(2026, 7, 14), ev.dataMovimento());
        assertEquals(new BigDecimal("20.00"), ev.importo(), "\"20\" da Excel è pur sempre 20,00 €");

        RawMovimento ric = normalizza(RAW_RICORRENTE);
        assertEquals(LocalDate.of(2026, 7, 31), ric.dataMovimento());
        assertEquals(new BigDecimal("2501.17"), ric.importo());
        assertEquals("USCITA", ric.tipo(), "il segno meno resta un'uscita");
        assertEquals("RID_SDDMANDAT", ric.metodoPagamentoCodice());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private RawMovimento normalizza(String rawJson) {
        try {
            Map<String, String> campi = json.readValue(rawJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return norm.normalize(new RawRow(1, campi));
        } catch (Exception e) {
            throw new AssertionError("grezzo non leggibile: " + e.getMessage(), e);
        }
    }

    /**
     * Riproduce il danno di un giro da foglio di calcolo sulle sole colonne che ne soffrono:
     * data (perde il secolo), importo (perde lo zero decimale finale), causale (perde lo zero
     * iniziale). Il file mangiato si costruisce QUI dal file vero, così non c'è una seconda
     * fixture da tenere allineata.
     */
    static String passaDaFoglioDiCalcolo(String csv) {
        StringBuilder out = new StringBuilder(csv.length());
        boolean header = true;
        for (String riga : csv.split("\n", -1)) {
            if (header) { out.append(riga).append('\n'); header = false; continue; }
            String[] col = riga.split(";", -1);
            if (col.length >= 6) {
                col[0] = mangiaAnno(col[0]);
                col[1] = mangiaAnno(col[1]);
                col[2] = mangiaDecimale(col[2]);
                col[4] = mangiaZeroIniziale(col[4]);
            }
            out.append(String.join(";", col)).append('\n');
        }
        return out.toString();
    }

    private static String mangiaAnno(String cella) {
        return cella.replaceAll("(\\d{1,2}/\\d{1,2}/)\\d{2}(\\d{2})", "$1$2");
    }

    private static String mangiaDecimale(String cella) {
        return cella.replaceAll("(,\\d)0(?=\"|$)", "$1").replaceAll(",00(?=\"|$)", "");
    }

    private static String mangiaZeroIniziale(String cella) {
        return cella.replaceAll("\"0+(\\d+)\"", "\"$1\"");
    }
}
