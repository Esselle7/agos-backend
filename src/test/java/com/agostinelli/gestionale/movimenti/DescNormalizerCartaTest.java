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
}
