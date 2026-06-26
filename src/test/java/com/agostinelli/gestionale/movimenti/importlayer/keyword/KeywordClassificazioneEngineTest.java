package com.agostinelli.gestionale.movimenti.importlayer.keyword;

import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine.Firma;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine.KeywordMatch;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor.Natura;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) del risolutore puro di {@link KeywordClassificazioneEngine}: match in AND,
 * precedenza IDENTITÀ&gt;DOMINIO, scope per tipo_movimento, conflitto di match.
 */
class KeywordClassificazioneEngineTest {

    static Firma dominio(String coge, short bu, String tipo, String... token) {
        return new Firma(UUID.randomUUID(), Natura.DOMINIO, tipo, "*", bu, coge, null, "sig", Set.of(token));
    }
    static Firma identita(String coge, short bu, UUID forn, String tipo, String... token) {
        return new Firma(UUID.randomUUID(), Natura.IDENTITA, tipo, "*", bu, coge, forn, "sig", Set.of(token));
    }

    @Test
    void matchAnd_tuttiITokenDevonoEsserePresenti() {
        Firma f = identita("40.04.001", (short) 3, UUID.randomUUID(), "USCITA", "NICOLETA", "MIHAI");
        // manca MIHAI → nessun match
        assertTrue(KeywordClassificazioneEngine.risolvi(Set.of("NICOLETA", "ALTRO"), List.of(f), "USCITA", "CA").isEmpty());
        // tutti presenti → match
        assertTrue(KeywordClassificazioneEngine.risolvi(Set.of("NICOLETA", "MIHAI", "X"), List.of(f), "USCITA", "CA").isPresent());
    }

    @Test
    void precedenza_identitaVinceSuDominio() {
        UUID forn = UUID.randomUUID();
        Firma dom = dominio("30.01.001", (short) 1, "ENTRATA", "PRANZO");
        Firma id = identita("30.03.001", (short) 3, forn, "ENTRATA", "PRANZO");
        Optional<KeywordMatch> m = KeywordClassificazioneEngine.risolvi(
                Set.of("PRANZO"), List.of(dom, id), "ENTRATA", "CA");
        assertTrue(m.isPresent());
        assertFalse(m.get().conflitto());
        assertEquals(forn, m.get().fornitoreId(), "vince la firma IDENTITÀ");
        assertEquals("30.03.001", m.get().cogeCodice());
    }

    @Test
    void scope_tipoMovimentoIncompatibileNonMatcha() {
        Firma soloUscita = identita("40.05.001", (short) 5, UUID.randomUUID(), "USCITA", "ASSICURAZIONE");
        assertTrue(KeywordClassificazioneEngine.risolvi(
                Set.of("ASSICURAZIONE"), List.of(soloUscita), "ENTRATA", "CA").isEmpty(),
                "una firma USCITA non deve matchare una riga ENTRATA");
        assertTrue(KeywordClassificazioneEngine.risolvi(
                Set.of("ASSICURAZIONE"), List.of(soloUscita), "USCITA", "CA").isPresent());
    }

    @Test
    void conflittoDiMatch_targetDiversiStessaNatura() {
        Firma a = identita("40.04.001", (short) 3, UUID.randomUUID(), "USCITA", "ROSSI");
        Firma b = identita("40.11.001", (short) 5, UUID.randomUUID(), "USCITA", "ROSSI");
        Optional<KeywordMatch> m = KeywordClassificazioneEngine.risolvi(
                Set.of("ROSSI"), List.of(a, b), "USCITA", "CA");
        assertTrue(m.isPresent());
        assertTrue(m.get().conflitto(), "due firme IDENTITÀ con target diversi → conflitto di match");
    }

    @Test
    void piuSpecifica_vinceLaFirmaConPiuToken() {
        // stesso target → nessun conflitto; vince la più specifica (più token).
        Firma corta = identita("40.04.001", (short) 3, null, "USCITA", "PASINI");
        Firma lunga = identita("40.04.001", (short) 3, null, "USCITA", "PASINI", "VERDURE");
        Optional<KeywordMatch> m = KeywordClassificazioneEngine.risolvi(
                Set.of("PASINI", "VERDURE"), List.of(corta, lunga), "USCITA", "CA");
        assertTrue(m.isPresent());
        assertFalse(m.get().conflitto());
        assertEquals("40.04.001", m.get().cogeCodice());
    }
}
