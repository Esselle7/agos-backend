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
 * Unit test (NO DB) della nuova estrazione controparte per i pagamenti carta (causale 118) e le
 * utenze CBILL su BPM: prima questi nomi-esercente erano solo testo "NORMALE" e non generavano
 * keyword apprendibili. Ora l'esercente/creditore diventa {@code beneficiario} → IDENTITÀ → firma.
 */
class DescNormalizerCartaTest {

    static final Set<String> STOP = Set.of("DEBIT", "PAGAMENTO", "CARTA", "ITA", "DELLE", "BOLL", "CBILL");
    static final Set<String> DOMINIO = Set.of();

    @Test
    void carta_estraeEsercenteComeBeneficiario() {
        String d = "DEBIT PAGAMENTO - CARTA 5354-CIP GARDEN VIA VARESINA 279 COMO 22100 ITA -DA CONTAB";
        EntitaEstratte e = DescNormalizer.extract(d, Sorgente.BPM);
        assertEquals("CIP GARDEN", e.beneficiario(), "esercente tra numero carta e indirizzo");

        // catena completa: dall'esercente nasce una firma IDENTITÀ apprendibile
        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(d, e, STOP, DOMINIO);
        assertTrue(firme.stream().anyMatch(f -> f.valori().equals(Set.of("CIP", "GARDEN"))),
                "firma identità CIP + GARDEN");
    }

    @Test
    void carta_conOrarioESocieta() {
        String d = "DEBIT PAGAMENTO - CARTA 5354-COMEDILMANGINO S.R.L. VIA DELLE FORNACI 10 OLGIATE COMAS 22077 -DA CONTAB";
        assertEquals("COMEDILMANGINO S.R.L.", DescNormalizer.extract(d, Sorgente.BPM).beneficiario());

        String r = "DEBIT PAGAMENTO - CARTA 5354-10:16-ROSSI & LERSA S.R.L. LUISAGO ITA";
        String benef = DescNormalizer.extract(r, Sorgente.BPM).beneficiario();
        assertNotNull(benef);
        assertTrue(benef.startsWith("ROSSI & LERSA"), "esercente dopo l'orario: " + benef);
        assertFalse(KeywordExtractor.estraiFirme(r, DescNormalizer.extract(r, Sorgente.BPM), STOP, DOMINIO).isEmpty(),
                "ROSSI & LERSA deve generare una keyword");
    }

    @Test
    void cbill_estraeCreditore() {
        assertEquals("COMO ACQUA SRL",
                DescNormalizer.extract("PAG. UTENZE VARIE - BOLL.CBILL COMO ACQUA SRL CBILL 300000000170308774", Sorgente.BPM).beneficiario());
        assertEquals("AGENZIA DELLE ENTRATE",
                DescNormalizer.extract("PAG. UTENZE VARIE - BOLL.CBILL AGENZIA DELLE ENTRATE - R CBILL 180033101524476350", Sorgente.BPM).beneficiario());
    }

    @Test
    void nonRegredisce_bonificoBonDaRestaOrdinante_eNessunFalsoPositivo() {
        // Un bonifico BON.DA continua a popolare ordinante e NON deve attivare il pattern carta/CBILL.
        EntitaEstratte e = DescNormalizer.extract("BONIF. VS. FAVORE - BON.DA MARIO ROSSI BONIFICO", Sorgente.BPM);
        assertEquals("MARIO ROSSI", e.ordinante());
        assertNull(e.beneficiario(), "nessun esercente carta in un bonifico");

        // Uno storno "RIMBORSO CARTA" non ha la forma CARTA <num>-<nome> → niente beneficiario.
        assertNull(DescNormalizer.extract("RIMBORSO CARTA DEBITO STORNO", Sorgente.BPM).beneficiario());
    }

    // ── Nuovi pattern (buchi "—" da CSV BPM/CA reali) ──────────────────────────

    @Test
    void bpm_vostraDisposizione_estraeBeneficiarioDopoFavore() {
        String d = "VOSTRA DISPOSIZIONE - VS.DISP. RIF. MBVT91830637/00970175 FAVORE SELECOVER SRL NOTPROVIDE";
        assertEquals("SELECOVER SRL", DescNormalizer.extract(d, Sorgente.BPM).beneficiario());
        // variante che termina con "- ADD.TOT" invece di NOTPROVIDE
        String d2 = "VOSTRA DISPOSIZIONE - VS.DISP. RIF. MB0B66945415/90173300 FAVORE NOSTRAN CARNI - ADD.TOT";
        assertEquals("NOSTRAN CARNI", DescNormalizer.extract(d2, Sorgente.BPM).beneficiario());
    }

    @Test
    void bpm_vostraDisp_nonRompeIlBonificoFavore() {
        // ANTI-REGRESSIONE: "BONIF. VS. FAVORE" contiene FAVORE ma NON è una vostra disposizione:
        // deve restare ordinante da BON.DA, beneficiario null (niente falso positivo VS.DISP).
        EntitaEstratte e = DescNormalizer.extract("BONIF. VS. FAVORE - BON.DA STRIPE PO20260608019UDPDUSW", Sorgente.BPM);
        assertTrue(e.ordinante() != null && e.ordinante().startsWith("STRIPE"), "ordinante STRIPE: " + e.ordinante());
        assertNull(e.beneficiario(), "il FAVORE del bonifico non deve attivare VOSTRA_DISP");
    }

    @Test
    void bpm_addebitoSdd_estraeNomeInCoda() {
        assertEquals("ASSOCIAZIONE DEI CONFIDI DELLA LOMBARDIA COOP",
                DescNormalizer.extract("ADDEBITO DIRETTO SDD - SDD B2B : 981811800294901 ASSOCIAZIONE DEI CONFIDI DELLA LOMBARDIA COOP", Sorgente.BPM).beneficiario());
        assertEquals("ENEL ENERGIA",
                DescNormalizer.extract("ADDEBITO DIRETTO SDD - SDD CORE: 2C1071113500569T ENEL ENERGIA", Sorgente.BPM).beneficiario());
        assertEquals("TIM SPA",
                DescNormalizer.extract("ADDEBITO DIRETTO SDD - SDD B2B : MU010100000037978901382025110600001 TIM SPA", Sorgente.BPM).beneficiario());
    }

    @Test
    void ca_sddA_estraeBeneficiarioPrimaDelMarcatore() {
        assertEquals("NEXI PAYMENTS SPA",
                DescNormalizer.extract("SDD A : NEXI PAYMENTS SPA PV 1000001477621 ADDEBITO DIRITTO", Sorgente.CA).beneficiario());
        assertEquals("ASSOCIAZIONE DEI CONFIDI DELLA LOMBARDIA COOPERATI",
                DescNormalizer.extract("SDD A : ASSOCIAZIONE DEI CONFIDI DELLA LOMBARDIA COOPERATI SDD03 RIF. MUTUO N. 030910000075300", Sorgente.CA).beneficiario());
        assertEquals("TELEPASS S.P.A.",
                DescNormalizer.extract("SDD A : TELEPASS S.P.A. 9105578 SALDO DOCUM.014533173", Sorgente.CA).beneficiario());
    }

    @Test
    void finanziamento_titoloSinteticoPerLeRate() {
        assertEquals("FINANZIAMENTO 1273 5796807",
                DescNormalizer.extract("RIMBORSO FINANZ. - MUTUO N.1273 5796807 RATA 31/05/2026", Sorgente.BPM).beneficiario());
        assertEquals("FINANZIAMENTO 1273/05796807",
                DescNormalizer.extract("RIMBORSO FINANZ. - PAG.RATE SU FIN.TO 1273/05796807 INT.: SOC", Sorgente.BPM).beneficiario());
    }

    // ── Disposizioni CA troncate col nome in coda (nessun terminatore RIF/V-ORDINE/DT.ORD) ──────

    @Test
    void ca_disposizioneTroncata_nomeInCoda() {
        assertEquals("AIANI FLAVIO",
                DescNormalizer.extract("C7ZWE SOCIETA' AGRICOLA AGOS000000737189490 AIANI FLAVIO", Sorgente.CA).beneficiario());
        assertEquals("CONSORZIO AGRARIO",
                DescNormalizer.extract("C7ZWE SOCIETA' AGRICOLA AGOS000000739397718 CONSORZIO AGRARIO", Sorgente.CA).beneficiario());
        assertEquals("IL TAPPETO ERBOSO SOCIETA",
                DescNormalizer.extract("C7ZWE SOCIETA' AGRICOLA AGOS000000761143935 IL TAPPETO ERBOSO SOCIETA", Sorgente.CA).beneficiario());
    }

    @Test
    void ca_ordinanteTroncato_nomeInCoda() {
        assertEquals("GALLAZZI VALERIO FRANCESCHETTO",
                DescNormalizer.extract("ORD:GALLAZZI VALERIO FRANCESCHETTO", Sorgente.CA).ordinante());
    }

    @Test
    void ca_disposizioneLunga_nonRegredisce_siFermaAlTerminatore() {
        // Formato lungo con V/ORDINE dopo il nome: il "| fine stringa" NON deve allungare la cattura.
        assertEquals("SOGEGROSS SPA",
                DescNormalizer.extract("C7ZWE SOCIETA' AGRICOLA AGOS000000767168179 SOGEGROSS SPA RIF. CRO: NROSUPCBI 37040005 V/ORDINE E CONTO", Sorgente.CA).beneficiario());
        // Bonifico entrata con DT.ORD: l'ordinante si ferma a DT.ORD, non corre a fine stringa.
        assertEquals("PASCHETTO DAVIDE",
                DescNormalizer.extract("ORD:PASCHETTO DAVIDE DT.ORD:000000 DESCR.OPERAZIONE SCT:ACCONTO", Sorgente.CA).ordinante());
    }

    @Test
    void f24_i24_controparteAgenziaEntrate() {
        assertEquals("AGENZIA DELLE ENTRATE",
                DescNormalizer.extract("I24 AGENZIA ENTRATE - PAG.TO TELEMATICO - DATA INCASSO 02/03/2026 2026-02-27-22.33.26.867010009981", Sorgente.CA).beneficiario());
        assertEquals("AGENZIA DELLE ENTRATE",
                DescNormalizer.extract("F24CBI CODICE SIA C7ZWE 0000003797890138 DELEGA NUMERO: 006076698000000001", Sorgente.BPM).beneficiario());
    }
}
