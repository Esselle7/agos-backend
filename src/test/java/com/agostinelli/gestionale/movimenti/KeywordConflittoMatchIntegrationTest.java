package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.KeywordFirmaDTO;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoMappingEngineImpl;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test della SPEC docs/specs/keyword-conflitti-match.md: un conflitto di tipo MATCH
 * ("In import") deve esporre le firme ATTIVE che si contendono la riga (prima erano —/— invisibili)
 * e disattivarne/modificarne una deve risolvere l'ambiguità alla radice. Un conflitto di tipo
 * APPRENDIMENTO ("In apprendimento") NON usa quell'elenco (porta già i suoi due target).
 *
 * <p>Dati sintetici iniettati a runtime (nessuna migration); {@link #clean()} prima/dopo ogni test.
 */
@QuarkusTest
class KeywordConflittoMatchIntegrationTest {

    @Inject KeywordLearningService learning;
    @Inject KeywordClassificazioneEngine engine;
    @Inject MovimentoMappingEngineImpl mapping;
    @Inject EntityManager em;

    static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000099"); // utente seed di test

    @BeforeEach void reset() { clean(); mapping.refreshLookups(); }
    @AfterEach  void after() { clean(); }

    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM keyword_conflitto").executeUpdate();
            em.createNativeQuery("DELETE FROM keyword_firma WHERE origine <> 'SEED'").executeUpdate();
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZTEST%'").executeUpdate();
        });
        engine.refresh();
    }

    @Test
    void match_espone2Colpevoli_eDisattivandoneUnaRisolveAllaRadice() {
        // ARRANGE: due firme BOOK attive con token DIVERSI (niente FIRMA_DUPLICATA) che però matchano
        // entrambe la stessa riga, con target DIVERGENTI → conflitto di match.
        UUID f1 = learning.createFirma(firmaBook(List.of("ROSSITEST"), (short) 5, "40.11.001"));
        UUID f2 = learning.createFirma(firmaBook(List.of("ALFATEST"),  (short) 3, "40.04.002"));
        String descr = "ZZTEST ROSSITEST ALFATEST SRL";

        // Predizione H1a: il motore rileva il conflitto (non cataloga alla cieca).
        var match = engine.classifica(rawUscita(descr), Sorgente.CA);
        assertTrue(match.isPresent() && match.get().conflitto(), "due target divergenti → conflitto di match");

        // Un movimento reale + il record MATCH, come li produce l'import.
        UUID movId = insertMovimento(descr, "USCITA");
        UUID confId = learning.registraConflittoMatch("sig-match", movId, descr);

        // ACT + ASSERT H1b: l'endpoint espone ENTRAMBE le firme colpevoli (prima: nessuna, —/—).
        List<KeywordFirmaDTO> colpevoli = learning.firmeConflittoMatch(confId);
        assertEquals(Set.of(f1, f2), colpevoli.stream().map(KeywordFirmaDTO::id).collect(Collectors.toSet()),
                "il conflitto MATCH espone le 2 firme che se lo contendono");

        // ACT + ASSERT H2: disattivo una firma (come il bottone inline) → ambiguità risolta alla radice.
        learning.updateFirma(f1, firmaConStato(List.of("ROSSITEST"), (short) 5, "40.11.001", "DISATTIVATA"));
        List<KeywordFirmaDTO> dopo = learning.firmeConflittoMatch(confId);
        assertEquals(1, dopo.size(), "disattivata una firma resta un solo target");
        assertEquals(f2, dopo.get(0).id(), "resta la firma non toccata");
        // E il motore ora catalogherebbe senza conflitto.
        var dopoMatch = engine.classifica(rawUscita(descr), Sorgente.CA);
        assertTrue(dopoMatch.isPresent() && !dopoMatch.get().conflitto(), "niente più conflitto: target unico");
    }

    @Test
    void match_modificaTarget_faConvergere() {
        // Variante di H2: invece di disattivare, MODIFICO il target di una firma perché coincida con
        // l'altra → i target convergono → nessun conflitto (ma entrambe restano attive e mostrabili).
        UUID f1 = learning.createFirma(firmaBook(List.of("VERDITEST"), (short) 5, "40.11.001"));
        UUID f2 = learning.createFirma(firmaBook(List.of("BLUTEST"),   (short) 3, "40.04.002"));
        String descr = "ZZTEST VERDITEST BLUTEST SRL";
        UUID movId = insertMovimento(descr, "USCITA");
        UUID confId = learning.registraConflittoMatch("sig-match2", movId, descr);
        assertEquals(2, learning.firmeConflittoMatch(confId).size());

        // Allineo f2 al target di f1 (stessa BU + COGE) → convergenza.
        learning.updateFirma(f2, firmaBook(List.of("BLUTEST"), (short) 5, "40.11.001"));
        var m = engine.classifica(rawUscita(descr), Sorgente.CA);
        assertTrue(m.isPresent() && !m.get().conflitto(), "target allineati → catalogazione senza conflitto");
    }

    @Test
    void apprendimento_endpointRitornaVuoto() {
        // H3: un conflitto di APPRENDIMENTO porta già i suoi due target → firmeConflittoMatch = [].
        learning.apprendi("ZZTEST FAVORE PASINITEST NEROTEST", new EntitaEstratte(null, null, "PASINITEST NEROTEST", null),
                "USCITA", (short) 3, cogeId("40.04.002"), primoFornitore(), UUID.randomUUID(), null);
        learning.apprendi("ZZTEST FAVORE PASINITEST NEROTEST", new EntitaEstratte(null, null, "PASINITEST NEROTEST", null),
                "USCITA", (short) 5, cogeId("40.11.001"), null, UUID.randomUUID(), null);

        UUID confId = (UUID) em.createNativeQuery(
                "SELECT id FROM keyword_conflitto WHERE tipo = 'APPRENDIMENTO' AND stato = 'APERTO' ORDER BY created_at DESC LIMIT 1")
                .getSingleResult();
        assertTrue(learning.firmeConflittoMatch(confId).isEmpty(),
                "APPRENDIMENTO non usa l'elenco firme colpevoli (ha già targetEsistente/targetNuovo)");
    }

    @Test
    void match_movimentoAnnullato_ritornaVuotoSenzaCrash() {
        // H4 (edge): movimento del conflitto annullato → nessuna firma, nessuna eccezione.
        learning.createFirma(firmaBook(List.of("GIALLOTEST"), (short) 5, "40.11.001"));
        learning.createFirma(firmaBook(List.of("GRIGIOTEST"), (short) 3, "40.04.002"));
        String descr = "ZZTEST GIALLOTEST GRIGIOTEST SRL";
        UUID movId = insertMovimento(descr, "USCITA");
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE movimenti SET stato = 'ANNULLATO' WHERE id = :id").setParameter("id", movId).executeUpdate());
        UUID confId = learning.registraConflittoMatch("sig-annullato", movId, descr);
        assertTrue(learning.firmeConflittoMatch(confId).isEmpty(), "movimento annullato → lista vuota, nessun crash");
    }

    // ── FIX: la rivalutazione auto-chiude i conflitti risolti e ri-cataloga il movimento ──────

    @Test
    void rivaluta_dopoDeleteFirma_chiudeEcatalogaSulTargetUnico() {
        // Movimento incastrato sul transitorio DACLASS (49.99.999), due firme divergenti → conflitto.
        UUID f1 = learning.createFirma(firmaBook(List.of("ROSSITEST"), (short) 2, "40.11.001"));
        UUID f2 = learning.createFirma(firmaBook(List.of("ALFATEST"),  (short) 3, "40.04.002"));
        String descr = "ZZTEST PAGAMENTO ROSSITEST ALFATEST SRL";
        UUID movId = insertMovimento(descr, "USCITA", "49.99.999");
        UUID confId = learning.registraConflittoMatch("sig-fix1", movId, descr);

        // L'utente elimina una firma (bottone Elimina): l'ambiguità sparisce.
        learning.deleteFirma(f2);

        // RIVALUTAZIONE: chiude il conflitto DA SOLO e cataloga il movimento sul target rimasto.
        var esito = learning.rivalutaConflittiMatch(USER);
        assertEquals("RISOLTO", statoConflitto(confId), "conflitto non più ambiguo → chiuso da solo");
        assertEquals("40.11.001", cogeDelMovimento(movId), "movimento ri-catalogato sul target di ROSSITEST");
        assertEquals(1, esito.catalogati(), "un movimento tolto da 'Da catalogare'");
        assertTrue(esito.chiusi() >= 1);
    }

    @Test
    void rivaluta_ancoraAmbiguo_lasciaApertoENonCataloga() {
        // Entrambe le firme restano attive → l'ambiguità persiste → il conflitto NON va chiuso.
        learning.createFirma(firmaBook(List.of("ROSSITEST"), (short) 2, "40.11.001"));
        learning.createFirma(firmaBook(List.of("ALFATEST"),  (short) 3, "40.04.002"));
        String descr = "ZZTEST PAGAMENTO ROSSITEST ALFATEST SRL";
        UUID movId = insertMovimento(descr, "USCITA", "49.99.999");
        UUID confId = learning.registraConflittoMatch("sig-fix2", movId, descr);

        var esito = learning.rivalutaConflittiMatch(USER);
        assertEquals("APERTO", statoConflitto(confId), "ancora due target divergenti → resta aperto");
        assertEquals("49.99.999", cogeDelMovimento(movId), "movimento non toccato");
        assertEquals(0, esito.catalogati());
    }

    @Test
    void rivaluta_invariante_nonSovrascriveCatalogazioneManuale() {
        // Il movimento NON è più sul transitorio (già catalogato a mano su 40.04.002): la
        // rivalutazione chiude il conflitto ma NON deve sovrascrivere la catalogazione.
        UUID f1 = learning.createFirma(firmaBook(List.of("ROSSITEST"), (short) 2, "40.11.001"));
        learning.createFirma(firmaBook(List.of("ALFATEST"), (short) 3, "40.04.002"));
        String descr = "ZZTEST PAGAMENTO ROSSITEST ALFATEST SRL";
        UUID movId = insertMovimento(descr, "USCITA", "40.04.002"); // NON DACLASS
        UUID confId = learning.registraConflittoMatch("sig-fix3", movId, descr);
        learning.deleteFirma(f1);

        var esito = learning.rivalutaConflittiMatch(USER);
        assertEquals("RISOLTO", statoConflitto(confId), "conflitto chiuso");
        assertEquals("40.04.002", cogeDelMovimento(movId), "INVARIANTE: catalogazione manuale non sovrascritta");
        assertEquals(0, esito.catalogati(), "nessuna ri-catalogazione su un movimento non-transitorio");
    }

    @Test
    void rivaluta_eliminandoTutteLeFirme_chiudeMaLasciaSulTransitorio() {
        // Elimino ENTRAMBE le firme: nessun target → il conflitto si chiude ma il movimento resta
        // da catalogare (non c'è keyword che lo classifichi).
        UUID f1 = learning.createFirma(firmaBook(List.of("ROSSITEST"), (short) 2, "40.11.001"));
        UUID f2 = learning.createFirma(firmaBook(List.of("ALFATEST"),  (short) 3, "40.04.002"));
        String descr = "ZZTEST PAGAMENTO ROSSITEST ALFATEST SRL";
        UUID movId = insertMovimento(descr, "USCITA", "49.99.999");
        UUID confId = learning.registraConflittoMatch("sig-fix4", movId, descr);
        learning.deleteFirma(f1);
        learning.deleteFirma(f2);

        var esito = learning.rivalutaConflittiMatch(USER);
        assertEquals("RISOLTO", statoConflitto(confId), "nessuna firma litiga più → chiuso");
        assertEquals("49.99.999", cogeDelMovimento(movId), "nessun target → resta sul transitorio");
        assertEquals(0, esito.catalogati());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private KeywordFirmaDTO firmaBook(List<String> token, short bu, String coge) {
        return new KeywordFirmaDTO(null, "DOMINIO", "BOOK", "*", "*", bu, coge, null, null, null,
                null, "MANUALE", "ATTIVA", null, token, null);
    }
    private KeywordFirmaDTO firmaConStato(List<String> token, short bu, String coge, String stato) {
        return new KeywordFirmaDTO(null, "DOMINIO", "BOOK", "*", "*", bu, coge, null, null, null,
                null, "MANUALE", stato, null, token, null);
    }

    private RawMovimento rawUscita(String desc) {
        return new RawMovimento(
                1, "IMPORT_BANCA",
                LocalDate.of(2026, 5, 10), null, new BigDecimal("100.00"), "USCITA", desc,
                (short) 2, "BONIFICO", BigDecimal.ZERO, null,
                "RIF-" + desc.hashCode(), null,
                null, null, null, null,
                desc.replaceAll("\\s+", ""), null, EntitaEstratte.EMPTY,
                new RawRow(1, Map.of(Sorgente.KEY, "CA")),
                null, null);
    }

    private UUID insertMovimento(String descr, String tipo) { return insertMovimento(descr, tipo, "40.04.002"); }

    private UUID insertMovimento(String descr, String tipo, String cogeCodice) {
        UUID id = UUID.randomUUID();
        Integer coge = cogeId(cogeCodice);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (:id, DATE '2026-05-10', DATE '2026-05-10', DATE '2026-05-10', DATE '2026-05-10',
                :tipo, 100, 0, :coge, NULL, 3, 'ATTIVO', 'MANUALE', :descr, CAST(:u AS uuid), now())
            """)
            .setParameter("id", id).setParameter("tipo", tipo).setParameter("coge", coge)
            .setParameter("descr", descr).setParameter("u", USER)
            .executeUpdate());
        return id;
    }

    private String cogeDelMovimento(UUID movId) {
        return (String) em.createNativeQuery(
                "SELECT p.codice FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id WHERE m.id = :id")
                .setParameter("id", movId).getSingleResult();
    }
    private String statoConflitto(UUID confId) {
        return (String) em.createNativeQuery("SELECT stato FROM keyword_conflitto WHERE id = :id")
                .setParameter("id", confId).getSingleResult();
    }

    private UUID primoFornitore() {
        return (UUID) em.createNativeQuery("SELECT id FROM fornitori LIMIT 1").getSingleResult();
    }
    private Integer cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }
}
