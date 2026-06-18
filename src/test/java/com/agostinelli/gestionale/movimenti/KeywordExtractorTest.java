package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.FirmaCandidata;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.Natura;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.TipoToken;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.TokenTipizzato;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) dell'estrazione keyword sui 4 esempi reali (PROMPT-KEYWORD-LEARNING.md §1/§7):
 * codici + nomi estratti, rumore/verbi/articoli esclusi, codici volatili (rif. mbvt…) scartati,
 * dominio riconosciuto, regola di validità e "fornitore solo da IDENTITÀ".
 */
class KeywordExtractorTest {

    // Stopword e dizionario di dominio (mirror del seed V7, sottoinsieme sufficiente ai casi).
    static final Set<String> STOP = Set.of(
            "ADDEBITO", "DIRETTO", "SDD", "B2B", "SPA", "SRL", "RIF", "CRO", "FAVORE", "BON", "DA",
            "VS", "BONIF", "NOTPROVIDE", "VOSTRA", "DISPOSIZIONE", "DISP", "PAGAMENTO", "GENERICO",
            "BONIFICO", "ACCREDITO");
    static final Set<String> DOMINIO = Set.of(
            "MATRIMONIO", "CERIMONIA", "PRANZO", "CENA", "ACCONTO", "SALDO", "SPACCIO");

    @Test
    void sdd_estraeCodiceMandatoEFornitoreTim() {
        String desc = "ADDEBITO DIRETTO SDD - SDD B2B : MU01010000003797890138202511060000 TIM SPA";
        List<TokenTipizzato> tk = KeywordExtractor.classifica(desc, EntitaEstratte.EMPTY, STOP, DOMINIO);

        assertTrue(tk.stream().anyMatch(t -> t.token().equals("MU01010000003797890138202511060000")
                && t.tipo() == TipoToken.CODICE), "il mandato SDD è un CODICE");
        assertTrue(tk.stream().anyMatch(t -> t.token().equals("TIM") && t.tipo() == TipoToken.IDENTITA),
                "TIM (creditore dopo il mandato) è IDENTITÀ");
        assertTrue(tk.stream().noneMatch(t -> t.token().equals("SPA")), "SPA è stopword");

        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(desc, EntitaEstratte.EMPTY, STOP, DOMINIO);
        assertTrue(firme.stream().anyMatch(f -> f.valori().equals(Set.of("MU01010000003797890138202511060000"))),
                "firma codice mandato");
        assertTrue(firme.stream().anyMatch(f -> f.valori().equals(Set.of("TIM"))), "firma identità TIM");
    }

    @Test
    void bonifico_estraeNomiPropriEKairos() {
        String desc = "BONIF. VS. FAVORE - BON.DA ANDREEA NICOLETA MIHAI PROGETTO KAIROS IONESCU BRAYAN";
        EntitaEstratte ent = new EntitaEstratte(null,
                "ANDREEA NICOLETA MIHAI PROGETTO KAIROS IONESCU BRAYAN", null, null);

        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(desc, ent, STOP, DOMINIO);
        assertFalse(firme.isEmpty(), "deve estrarre almeno una firma identità");
        Set<String> tuttiToken = firme.stream().flatMap(f -> f.valori().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(tuttiToken.containsAll(Set.of("NICOLETA", "MIHAI", "IONESCU", "BRAYAN", "KAIROS")),
                "i nomi propri e KAIROS sono catturati come identità");
        assertTrue(tuttiToken.stream().noneMatch(t -> Set.of("BONIF", "VS", "FAVORE").contains(t)),
                "il rumore bancario non finisce nelle firme");
        assertTrue(firme.stream().allMatch(f -> f.natura() == Natura.IDENTITA),
                "le firme apprese hanno natura IDENTITÀ (fornitore solo da IDENTITÀ)");
    }

    @Test
    void disposizione_scartaRiferimentoVolatileTieneSelecover() {
        String desc = "vostra disposizione - vs.disp. rif. mbvt91830637/00970175 favore selecover srl notprovide";
        List<TokenTipizzato> tk = KeywordExtractor.classifica(desc, EntitaEstratte.EMPTY, STOP, DOMINIO);

        assertTrue(tk.stream().noneMatch(t -> t.token().startsWith("MBVT")),
                "il rif. mbvt… volatile è scartato come rumore");
        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(desc, EntitaEstratte.EMPTY, STOP, DOMINIO);
        assertTrue(firme.stream().anyMatch(f -> f.valori().equals(Set.of("SELECOVER"))),
                "resta la firma identità SELECOVER");
        assertTrue(firme.stream().flatMap(f -> f.valori().stream()).noneMatch(t -> t.startsWith("MBVT")),
                "nessuna firma contiene il riferimento volatile");
    }

    @Test
    void dominio_riconosceMatrimonioEPranzoSenzaFirmeIdentita() {
        List<TokenTipizzato> tkEvento = KeywordExtractor.classifica(
                "ACCONTO MATRIMONIO MARIO ROSSI", EntitaEstratte.EMPTY, STOP, DOMINIO);
        assertTrue(tkEvento.stream().anyMatch(t -> t.token().equals("MATRIMONIO") && t.tipo() == TipoToken.DOMINIO),
                "MATRIMONIO è DOMINIO (→ evento, parcheggio)");
        // niente segmento nome → MARIO/ROSSI restano NORMALE, nessuna firma identità appresa.
        assertTrue(KeywordExtractor.estraiFirme("ACCONTO MATRIMONIO MARIO ROSSI", EntitaEstratte.EMPTY, STOP, DOMINIO).isEmpty(),
                "senza segmento-nome non si apprende un'identità da parole generiche");

        List<TokenTipizzato> tkPranzo = KeywordExtractor.classifica(
                "PRANZO DOMENICALE COPERTI 12", EntitaEstratte.EMPTY, STOP, DOMINIO);
        assertTrue(tkPranzo.stream().anyMatch(t -> t.token().equals("PRANZO") && t.tipo() == TipoToken.DOMINIO),
                "PRANZO è DOMINIO (→ ristorazione BU1)");
    }

    @Test
    void validita_unaSolaParolaNormaleNonEFirma() {
        // "BONIFICO TIZIO": TIZIO non è in un segmento-nome → NORMALE singolo → nessuna firma.
        List<FirmaCandidata> firme = KeywordExtractor.estraiFirme(
                "BONIFICO TIZIO", EntitaEstratte.EMPTY, STOP, DOMINIO);
        assertTrue(firme.isEmpty(), "una singola parola NORMALE non costituisce una firma");
    }

    @Test
    void signatureHash_coincideConSeedSql() {
        // sha256('MATRIMONIO') — stesso valore del seed V7 (encode(digest(...,'sha256'),'hex')).
        assertEquals("110bc395d0b7dd908d363dd9a00fb9b05d3a13becb1cf76580b64b45dbcc5a22",
                KeywordExtractor.signatureHash(List.of("MATRIMONIO")));
        // indipendente dall'ordine dei token (firma = insieme).
        assertEquals(KeywordExtractor.signatureHash(List.of("NICOLETA", "MIHAI")),
                KeywordExtractor.signatureHash(List.of("MIHAI", "NICOLETA")));
    }
}
