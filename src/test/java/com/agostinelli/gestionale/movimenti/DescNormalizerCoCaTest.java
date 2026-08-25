package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.DescNormalizer;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.FirmaCandidata;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) dell'estrazione esercente sui pagamenti carta di Crédit Agricole, formato
 * {@code "... C/O <esercente> <località> [PV] ITA"} (audit keyword del 24/08/2026, §8.5).
 *
 * <p>Prima di questo pattern quelle righe erano <b>strutturalmente inapprendibili</b>: niente
 * {@code ORD:}, niente {@code FAVORE}, nessun CODICE ≥10 caratteri da cui {@code segmentoNomi()}
 * potesse ripartire, quindi {@code estraiFirme()} non produceva alcuna firma.
 *
 * <p><b>Gli attesi di questo test sono quelli REALI, non quelli desiderabili: 5 esatte su 10.</b>
 * Nel file grezzo il confine fra esercente e località non esiste (verificato con {@code cat -A}:
 * spazi singoli, nessun padding), quindi quando manca una forma societaria su cui tagliare
 * l'euristica include 1-3 token di località. Sbaglia sempre <b>per eccesso</b>, mai catalogando
 * un'altra controparte, e l'operatore spegne i token in eccesso nel wizard prima di salvare la
 * firma. Un test che asserisse 10/10 sarebbe un test che mente.
 */
class DescNormalizerCoCaTest {

    private static String benef(String desc) {
        return DescNormalizer.extract(desc, Sorgente.CA).beneficiario();
    }

    // ── Le 5 righe CON forma societaria: esercente esatto ────────────────────────────────

    @Test
    void co_conFormaSocietaria_tagliaAllaFormaEsclusaLaLocalita() {
        assertEquals("ROSSI & LERSA S.R.L.",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 28/07/26 ORE 14:45 C/O ROSSI & LERSA S.R.L. LUISAGO CO ITA"));
        assertEquals("ROSSI & LERSA S.R.L.",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 30/06/26 ORE 10:25 C/O ROSSI & LERSA S.R.L. LUISAGO CO ITA"));
        assertEquals("COMEDILMANGINO S.R.L",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 17/07/26 ORE 17:36 C/O COMEDILMANGINO S.R.L OLGIATE COMAS ITA"));
        assertEquals("COMEDILMANGINO S.R.L",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 14/07/26 ORE 13:40 C/O COMEDILMANGINO S.R.L OLGIATE COMAS ITA"));
        assertEquals("IMAT FELCO SPA",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 03/07/26 ORE 17:51 C/O IMAT FELCO SPA COMO CO ITA"));
    }

    // ── Le 5 righe SENZA forma societaria: esercente + 1-3 token di località (atteso reale) ──

    @Test
    void co_senzaFormaSocietaria_includeLaLocalita_erroreMisuratoPerEccesso() {
        // "CAVALLASCA" + "ALTA VALLE" (Intelvi): la sigla provincia "IN" in coda viene tolta.
        assertEquals("CAVALLASCA ALTA VALLE",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 23/07/26 ORE 11:16 C/O CAVALLASCA ALTA VALLE IN ITA"));
        assertEquals("CAVALLASCA ALTA VALLE",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 30/06/26 ORE 17:30 C/O CAVALLASCA ALTA VALLE IN ITA"));
        // "SMART WASH DI AULAKH" + "ALBAIRATE": la sigla provincia "MI" viene tolta.
        assertEquals("SMART WASH DI AULAKH ALBAIRATE",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 16/07/26 ORE 08:20 C/O SMART WASH DI AULAKH ALBAIRATE MI ITA"));
        assertEquals("SMART WASH DI AULAKH ALBAIRATE",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 14/07/26 ORE 08:40 C/O SMART WASH DI AULAKH ALBAIRATE MI ITA"));
        // "MEDIAWORLD" + 3 token: la coda non è una sigla di 2 lettere, quindi resta tutta.
        assertEquals("MEDIAWORLD MONTANO L MONTANO LUCCO",
                benef("POS CARTA CA DEBIT VISA N. 0883 DEL 03/07/26 ORE 15:44 C/O MEDIAWORLD MONTANO L MONTANO LUCCO ITA"));
    }

    // ── Il punto del fix: da "nessuna firma possibile" a "firma apprendibile" ─────────────

    @Test
    void co_rendeLaRigaApprendibile() {
        String d = "POS CARTA CA DEBIT VISA N. 0883 DEL 17/07/26 ORE 17:36 C/O COMEDILMANGINO S.R.L OLGIATE COMAS ITA";
        EntitaEstratte e = DescNormalizer.extract(d, Sorgente.CA);
        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(d, e, Set.of("POS", "CARTA", "DEBIT", "VISA", "ITA", "SRL"), Set.of());
        assertTrue(firme.stream().anyMatch(f -> f.valori().contains("COMEDILMANGINO")),
                "dall'esercente estratto deve nascere una firma su COMEDILMANGINO, prima impossibile: " + firme);
    }

    // ── Anti-regressione: il ramo C/O è in CODA alla cascata e non scavalca nessuno ───────

    @Test
    void co_nonScavalcaIPatternCaEsistenti() {
        // Disposizione CA normale: vince BENEF_CA, il ramo C/O non viene nemmeno consultato.
        assertEquals("SOGEGROSS SPA",
                benef("C7ZWE SOCIETA' AGRICOLA AGOS000000767168179 SOGEGROSS SPA RIF. CRO: NROSUPCBI 37040005 V/ORDINE E CONTO"));
        // Addebito SDD CA: vince SDD_A_CA.
        assertEquals("NEXI PAYMENTS SPA",
                benef("SDD A : NEXI PAYMENTS SPA PV 1000001477621 ADDEBITO DIRITTO"));
        // F24: la controparte deterministica resta l'Agenzia delle Entrate.
        assertEquals("AGENZIA DELLE ENTRATE",
                benef("I24 AGENZIA ENTRATE - PAG.TO TELEMATICO - DATA INCASSO 02/03/2026 2026-02-27-22.33.26.867010009981"));
        // Riga CA senza C/O e senza altri marcatori: nessun beneficiario inventato.
        assertNull(benef("COMMISSIONI SU BONIFICO ISTANTANEO"));
    }

    @Test
    void co_nonTocca_bpm() {
        // I due formati sono disgiunti: nel corpus reale "C/O" compare 0 volte sui file BPM.
        // Anche in una riga BPM artificiale che lo contenga, il ramo C/O non esiste: su BPM
        // decide MERCHANT_CARTA, col suo criterio (taglio al marcatore di indirizzo "VIA").
        assertEquals("C/O QUALCOSA",
                DescNormalizer.extract("DEBIT PAGAMENTO - CARTA 5354-C/O QUALCOSA VIA ROMA 1 COMO ITA", Sorgente.BPM).beneficiario());
    }
}
