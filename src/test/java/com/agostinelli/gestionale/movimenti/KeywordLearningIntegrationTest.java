package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.ApprendimentoKeywordEsito;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoMappingEngineImpl;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test del motore keyword (PROMPT-KEYWORD-LEARNING.md §7): apprendimento di una
 * firma IDENTITÀ → auto-catalogazione di una riga simile; conflitto di apprendimento → firma
 * IN_CONFLITTO + record conflitto; dizionario di dominio seed (MATRIMONIO=park, SPACCIO=book BU3).
 */
@QuarkusTest
class KeywordLearningIntegrationTest {

    @Inject KeywordLearningService learning;
    @Inject KeywordClassificazioneEngine engine;
    @Inject MovimentoMappingEngineImpl mapping;
    @Inject EntityManager em;

    @BeforeEach
    void reset() { clean(); mapping.refreshLookups(); }

    @AfterEach
    void resetAfter() { clean(); }

    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM keyword_conflitto").executeUpdate();
            em.createNativeQuery("DELETE FROM keyword_firma WHERE origine <> 'SEED'").executeUpdate();
        });
        engine.refresh();
    }

    @Test
    void apprendimentoIdentita_poiAutoCatalogazione() {
        UUID fornitore = primoFornitore();
        Integer coge = cogeId("40.11.001");

        // L'utente cataloga una riga "SELECOVER" → impara la firma IDENTITÀ.
        ApprendimentoKeywordEsito esito = learning.apprendi(
                "VS DISPOSIZIONE FAVORE SELECOVER SRL", new EntitaEstratte(null, null, "SELECOVER", null),
                "USCITA", (short) 5, coge, fornitore, UUID.randomUUID(), null);
        assertEquals(1, esito.firmeCreate(), "deve creare una firma IDENTITÀ");
        assertFalse(esito.conflittoGenerato());

        // Una riga simile al prossimo import viene auto-catalogata sul target appreso.
        MappingResult r = mapping.map(rawUscita("PAGAMENTO FATTURA SELECOVER SRL"));
        assertEquals(MappingResult.MappingOutcome.SUCCESS, r.outcome());
        assertEquals(coge, r.request().contoCoge(), "COGE dal target appreso");
        assertEquals(fornitore, r.request().fornitoreId(), "fornitore dal target appreso");
        assertNull(r.keywordConflittoSig(), "nessun conflitto");
    }

    @Test
    void conflittoDiApprendimento_firmaInConflittoERecord() {
        UUID f1 = primoFornitore();
        learning.apprendi("FAVORE PASINI VERDURE", new EntitaEstratte(null, null, "PASINI VERDURE", null),
                "USCITA", (short) 3, cogeId("40.04.002"), f1, UUID.randomUUID(), null);

        // Stessa firma (PASINI VERDURE), target diverso → conflitto a step (no catalog cieco).
        ApprendimentoKeywordEsito esito = learning.apprendi(
                "FAVORE PASINI VERDURE", new EntitaEstratte(null, null, "PASINI VERDURE", null),
                "USCITA", (short) 5, cogeId("40.11.001"), null, UUID.randomUUID(), null);
        assertTrue(esito.conflittoGenerato(), "target divergente → conflitto");

        long inConflitto = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM keyword_firma WHERE stato = 'IN_CONFLITTO'").getSingleResult()).longValue();
        assertTrue(inConflitto >= 1, "la firma esistente passa IN_CONFLITTO");
        long conflitti = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM keyword_conflitto WHERE tipo = 'APPRENDIMENTO' AND stato = 'APERTO'")
                .getSingleResult()).longValue();
        assertTrue(conflitti >= 1, "deve esistere un conflitto APERTO");
    }

    @Test
    void dominioSeed_matrimonioParcheggia_spaccioContabilizza() {
        // MATRIMONIO è una keyword evento (PARK_EVENTO): alimenta il Gate B, NON contabilizza.
        assertTrue(engine.eventiForti().contains("MATRIMONIO"), "MATRIMONIO nelle keyword evento forti");
        assertTrue(engine.classifica(rawEntrata("ACCONTO MATRIMONIO ROSSI"), Sorgente.CA).isEmpty(),
                "una keyword evento non produce un target da contabilizzare (verrà parcheggiata dal Gate B)");

        // SPACCIO è DOMINIO-CATEGORIA (BOOK): contabilizza su BU3 / 30.03.001.
        MappingResult r = mapping.map(rawEntrata("INCASSO VENDITA SPACCIO"));
        assertEquals(MappingResult.MappingOutcome.SUCCESS, r.outcome());
        assertEquals(cogeId("30.03.001"), r.request().contoCoge(), "SPACCIO → ricavi spaccio");
        assertEquals((short) 3, (short) r.request().businessUnitId(), "SPACCIO → BU3");
    }

    @Test
    void listFirme_tokenRaggruppatiPerFirma() {
        // Due firme BOOK con token distinti: listFirme deve restituire a ciascuna i SUOI token
        // (la lettura batch non deve mischiare i token tra firme).
        UUID a = learning.createFirma(firmaBook(List.of("ALFA", "BETA"), (short) 5, "40.11.001"));
        UUID b = learning.createFirma(firmaBook(List.of("GAMMA"), (short) 3, "30.03.001"));

        Map<UUID, List<String>> tokenById = learning.listFirme(null, null).stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO::id,
                        com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO::token));

        assertEquals(List.of("ALFA", "BETA"), tokenById.get(a), "token della firma A, ordinati");
        assertEquals(List.of("GAMMA"), tokenById.get(b), "token della firma B");
    }

    private com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO firmaBook(
            List<String> token, short bu, String coge) {
        return new com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO(
                null, "DOMINIO", "BOOK", "*", "*", bu, coge, null, null, null,
                null, "MANUALE", "ATTIVA", null, token, null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private RawMovimento rawUscita(String desc) { return raw("USCITA", desc); }
    private RawMovimento rawEntrata(String desc) { return raw("ENTRATA", desc); }

    private RawMovimento raw(String tipo, String desc) {
        return new RawMovimento(
                1, "IMPORT_BANCA",
                LocalDate.of(2026, 5, 10), null, new BigDecimal("100.00"), tipo, desc,
                (short) 2, "BONIFICO", BigDecimal.ZERO, null,
                "RIF-" + desc.hashCode(), null,
                null, null, null, null,
                desc.replaceAll("\\s+", ""), null, EntitaEstratte.EMPTY,
                new RawRow(1, Map.of(Sorgente.KEY, "CA")),
                null, null);
    }

    private UUID primoFornitore() {
        return (UUID) em.createNativeQuery("SELECT id FROM fornitori LIMIT 1").getSingleResult();
    }

    private Integer cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }
}
