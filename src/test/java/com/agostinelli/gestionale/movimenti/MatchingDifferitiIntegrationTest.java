package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviMatchingDifferitoRequest;
import com.agostinelli.gestionale.movimenti.importlayer.MatchingDifferitiService;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test delle due feature aggiunte (REQ utente):
 *
 * <b>Feature 1 — ritardo sui movimenti differiti (DA_LIQUIDARE non liquidi):</b>
 *   - campo derivato {@code giorniAllaScadenza} (negativo = in ritardo, positivo = manca alla scadenza);
 *   - endpoint {@code GET /api/movimenti/da-liquidare-in-ritardo} che lista solo gli scaduti.
 *
 * <b>Feature 2 — matching differiti (anti-doppia-registrazione):</b> quando l'import banche
 * intercetta una riga che combacia (importo al centesimo + descrizione) con un movimento
 * DA_LIQUIDARE già presente, la riga NON viene persistita come nuovo movimento ma parcheggiata
 * in {@code matching_differiti}; l'utente poi:
 *   - COLLEGA → liquida il movimento esistente (NESSUN nuovo movimento creato — niente doppione);
 *   - IGNORA  → crea comunque un nuovo movimento dalla riga banca (falso positivo).
 *
 * Richiede PostgreSQL su localhost:5432 (agosdb_test). Tutti i dati di test usano il prefisso
 * descrizione {@code TEST-MD-} per una pulizia mirata.
 */
@QuarkusTest
class MatchingDifferitiIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    static final UUID USER_UUID = UUID.fromString(USER);
    static final Short BU = 1;
    static final short CONTO_BANCARIO = 1;     // Banco BPM (seed V4)
    static final int METODO_BONIFICO = 5;      // BONIFICO (seed V4)

    @Inject EntityManager em;
    @Inject MovimentiService movimentiService;
    @Inject MatchingDifferitiService matchingService;

    static Integer coge;

    @BeforeEach
    void setUp() {
        if (coge == null) {
            coge = ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge LIMIT 1")
                    .getSingleResult()).intValue();
        }
        clean();
    }

    @AfterEach
    void tearDown() { clean(); }

    void clean() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM matching_differiti WHERE descrizione LIKE 'TEST-MD-%'").executeUpdate();
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'TEST-MD-%'").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE filename LIKE 'TEST-MD-%'").executeUpdate();
        });
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Feature 1 — ritardo
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void ritardo_uscitaScaduta_compareNellaListaConGiorniNegativi() {
        // USCITA DA_LIQUIDARE con scadenza 10 giorni fa → in ritardo di 10 giorni
        UUID id = creaDaLiquidare("USCITA", new BigDecimal("321.00"),
                "TEST-MD-RITARDO-USCITA", LocalDate.now().minusDays(10));

        // Appare nella lista "in ritardo" con giorniAllaScadenza = -10
        given().queryParam("tipo", "USCITA").queryParam("size", 200)
            .when().get("/api/movimenti/da-liquidare-in-ritardo")
            .then().statusCode(200)
                .body("content.find { it.id == '" + id + "' }.giorniAllaScadenza", equalTo(-10));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void ritardo_scadenzaFutura_nonInRitardo_maGiorniPositivi() {
        UUID id = creaDaLiquidare("ENTRATA", new BigDecimal("55.00"),
                "TEST-MD-RITARDO-FUTURO", LocalDate.now().plusDays(7));

        // NON deve comparire tra gli "in ritardo" (scadenza nel futuro)
        given().queryParam("size", 200)
            .when().get("/api/movimenti/da-liquidare-in-ritardo")
            .then().statusCode(200)
                .body("content.find { it.id == '" + id + "' }", nullValue());

        // Ma sul dettaglio il campo derivato è positivo (+7)
        given().when().get("/api/movimenti/" + id)
            .then().statusCode(200)
                .body("giorniAllaScadenza", equalTo(7))
                .body("stato", equalTo("DA_LIQUIDARE"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void ritardo_movimentoLiquidato_nonHaGiorniAllaScadenza() {
        // Movimento liquidato (REGISTRATO) → il campo derivato è null (non pertinente)
        UUID id = creaRegistrato("USCITA", new BigDecimal("12.00"), "TEST-MD-LIQUIDATO");
        given().when().get("/api/movimenti/" + id)
            .then().statusCode(200)
                .body("stato", equalTo("REGISTRATO"))
                .body("giorniAllaScadenza", nullValue());
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Feature 2 — matching differiti
    // ════════════════════════════════════════════════════════════════════════════

    @Test
    void match_trovaSoloSuImportoAlCentesimoEDescrizione() {
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("123.45"),
                "TEST-MD-COLLEGA", LocalDate.now().plusDays(3));

        var idx = matchingService.buildIndiceDifferitiAperti();

        // match: stesso importo (anche con scala diversa 123.45 == 123.450) + token condiviso
        assertEquals(movId, matchingService.trovaMatch(idx, new BigDecimal("123.450"), "test-md-collega"),
                "deve riconoscere lo stesso importo al centesimo e la controparte");
        // niente match se importo diverso
        assertNull(matchingService.trovaMatch(idx, new BigDecimal("123.44"), "TEST-MD-COLLEGA"),
                "1 centesimo di differenza → niente match");
        // niente match se descrizione diversa
        assertNull(matchingService.trovaMatch(idx, new BigDecimal("123.45"), "ALTRA DESCRIZIONE"),
                "descrizione diversa → niente match");
    }

    @Test
    void match_riconosceLaControparte_ancheConDescrizioniDiverse() {
        // Caso reale: l'utente scrive "Pagamento fornitore LEONE...", la banca
        // "VOSTRA DISPOSIZIONE - VS.DISP. A LEONE S.R.L CARNI". Condividono solo "leone".
        // (le stringhe banca NON sono salvate, quindi non serve il prefisso TEST-MD).
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("500.00"),
                "TEST-MD-LEONE pagamento fornitore LEONE s.r.l. carne", LocalDate.now().plusDays(30));

        var idx = matchingService.buildIndiceDifferitiAperti();

        // stesso importo + controparte condivisa → match
        assertEquals(movId, matchingService.trovaMatch(idx, new BigDecimal("500.00"),
                "VOSTRA DISPOSIZIONE - VS.DISP. A LEONE S.R.L CARNI"));
        // stesso importo ma controparte diversa → niente match (no falso positivo)
        assertNull(matchingService.trovaMatch(idx, new BigDecimal("500.00"),
                "VOSTRA DISPOSIZIONE A ROSSI SPA"));
        // solo rumore bancario condiviso (nessun nome) → niente match
        assertNull(matchingService.trovaMatch(idx, new BigDecimal("500.00"),
                "PAGAMENTO BONIFICO DISPOSIZIONE VOSTRA"));
    }

    @Test
    void match_recuperaNomiCorti_eScartaIlRumoreSdd() {
        // Nome controparte di 3 lettere (TIM): col vecchio len>=4 si perdeva.
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("42.90"),
                "TEST-MD-TIM pagamento TIM spa", LocalDate.now().plusDays(15));
        var idx = matchingService.buildIndiceDifferitiAperti();

        // riga SDD reale: rumore (addebito/diretto/sdd/b2b/spa) scartato, resta "tim" → match
        assertEquals(movId, matchingService.trovaMatch(idx, new BigDecimal("42.90"),
                "ADDEBITO DIRETTO SDD - SDD B2B : MU0101000000379789 TIM SPA"));
        // stesso importo, controparte diversa (ENEL) → niente match
        assertNull(matchingService.trovaMatch(idx, new BigDecimal("42.90"),
                "ADDEBITO DIRETTO SDD - SDD CORE: 2C1071 ENEL ENERGIA"));
    }

    @Test
    void risolvi_COLLEGA_liquidaEsistente_senzaCreareDoppione() {
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("200.00"),
                "TEST-MD-COLLEGA", LocalDate.now().plusDays(5));
        UUID logId = creaImportLog();
        LocalDate dataBanca = LocalDate.now().minusDays(2);

        // Simula l'intercettazione in fase di import: la riga banca combacia → parcheggiata
        UUID matchId = parcheggia(logId, movId, "USCITA", new BigDecimal("200.00"),
                "TEST-MD-COLLEGA", dataBanca);

        assertEquals(1, countMovimenti("TEST-MD-COLLEGA"), "prima della risoluzione esiste solo l'originale");

        matchingService.risolvi(matchId, new RisolviMatchingDifferitoRequest("COLLEGA", METODO_BONIFICO, "ok"), USER_UUID);

        // Nessun doppione: c'è ancora UN solo movimento con quella descrizione
        assertEquals(1, countMovimenti("TEST-MD-COLLEGA"),
                "COLLEGA non deve creare un nuovo movimento (anti-doppia-registrazione)");

        // L'originale è stato liquidato con la data della riga banca
        Object[] m = movimento(movId);
        assertEquals("REGISTRATO", m[0], "il movimento esistente passa a REGISTRATO");
        assertEquals(dataBanca, ((java.sql.Date) m[1]).toLocalDate(),
                "dataFinanziaria = data della riga banca (non oggi)");

        assertEquals("COLLEGATO", statoMatch(matchId));
    }

    @Test
    void risolvi_IGNORA_creaNuovoMovimento_eLasciaApertoLoriginale() {
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("80.00"),
                "TEST-MD-IGNORA", LocalDate.now().plusDays(5));
        UUID logId = creaImportLog();

        UUID matchId = parcheggia(logId, movId, "USCITA", new BigDecimal("80.00"),
                "TEST-MD-IGNORA", LocalDate.now().minusDays(1));

        assertEquals(1, countMovimenti("TEST-MD-IGNORA"));

        matchingService.risolvi(matchId, new RisolviMatchingDifferitoRequest("IGNORA", null, "falso positivo"), USER_UUID);

        // Falso positivo: la riga banca diventa un nuovo movimento → ora ce ne sono 2
        assertEquals(2, countMovimenti("TEST-MD-IGNORA"),
                "IGNORA crea un nuovo movimento dalla riga banca");
        // L'originale resta aperto (DA_LIQUIDARE)
        assertEquals("DA_LIQUIDARE", movimento(movId)[0]);
        assertEquals("IGNORATO", statoMatch(matchId));
    }

    @Test
    void list_ritornaEntrambiILati_delMatch() {
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("77.00"),
                "TEST-MD-COLLEGA", LocalDate.now().plusDays(4));
        UUID logId = creaImportLog();
        parcheggia(logId, movId, "USCITA", new BigDecimal("77.00"), "TEST-MD-COLLEGA", LocalDate.now().minusDays(1));

        var page = matchingService.list("DA_RICONCILIARE", 0, 50);
        var dto = page.content().stream().filter(d -> d.movimentoId().equals(movId)).findFirst().orElseThrow();
        assertEquals("USCITA", dto.movimentoTipo(), "deve esporre i campi del movimento abbinato (JOIN)");
        assertEquals("TEST-MD-COLLEGA", dto.descrizione());
    }

    @Test
    void risolvi_dueVolte_seconda_fallisce() {
        UUID movId = creaDaLiquidare("USCITA", new BigDecimal("99.00"),
                "TEST-MD-COLLEGA", LocalDate.now().plusDays(5));
        UUID logId = creaImportLog();
        UUID matchId = parcheggia(logId, movId, "USCITA", new BigDecimal("99.00"),
                "TEST-MD-COLLEGA", LocalDate.now().minusDays(1));

        matchingService.risolvi(matchId, new RisolviMatchingDifferitoRequest("COLLEGA", METODO_BONIFICO, null), USER_UUID);
        // seconda risoluzione → conflitto (già risolto)
        assertThrows(Exception.class, () ->
                matchingService.risolvi(matchId, new RisolviMatchingDifferitoRequest("IGNORA", null, null), USER_UUID));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Crea un movimento DA_LIQUIDARE (manuale differito) e ne ritorna l'id. createMovimento è @Transactional. */
    UUID creaDaLiquidare(String tipo, BigDecimal importo, String descr, LocalDate scadenza) {
        MovimentoCreateRequest req = new MovimentoCreateRequest(
                tipo, importo, null, null,
                LocalDate.now(), null, null, scadenza,
                null, null, BU, coge,
                null, null, null, null,
                descr, null, null, "MANUALE", null);
        return movimentiService.createMovimento(req, USER_UUID).id();
    }

    /** Crea un movimento già liquidato (REGISTRATO). */
    UUID creaRegistrato(String tipo, BigDecimal importo, String descr) {
        MovimentoCreateRequest req = new MovimentoCreateRequest(
                tipo, importo, null, null,
                LocalDate.now(), null, LocalDate.now(), null,
                CONTO_BANCARIO, METODO_BONIFICO, BU, coge,
                null, null, null, null,
                descr, null, null, "MANUALE", null);
        return movimentiService.createMovimento(req, USER_UUID).id();
    }

    /** Inserisce un import_log minimo e ritorna l'id. */
    UUID creaImportLog() {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("INSERT INTO import_log (id, fonte, filename, stato) " +
                                "VALUES (:id, 'IMPORT_BANCA', 'TEST-MD-log.csv', 'COMPLETATO')")
                        .setParameter("id", id).executeUpdate());
        return id;
    }

    /** Replica ciò che fa l'import quando trova un match: salva la riga in matching_differiti. */
    UUID parcheggia(UUID logId, UUID movId, String tipo, BigDecimal importo, String descr, LocalDate dataBanca) {
        MovimentoCreateRequest reqBanca = new MovimentoCreateRequest(
                tipo, importo, null, null,
                dataBanca, null, dataBanca, null,
                CONTO_BANCARIO, METODO_BONIFICO, BU, coge,
                null, null, null, null,
                descr, null, null, "IMPORT_BANCA", null);
        QuarkusTransaction.requiringNew().run(() ->
                matchingService.salvaMatch(logId, movId, reqBanca, "IMPORT_BANCA", 1));
        return (UUID) em.createNativeQuery(
                        "SELECT id FROM matching_differiti WHERE movimento_id = :m AND stato = 'DA_RICONCILIARE' " +
                        "ORDER BY created_at DESC LIMIT 1")
                .setParameter("m", movId).getSingleResult();
    }

    long countMovimenti(String descr) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM movimenti WHERE descrizione = :d AND stato <> 'ANNULLATO'")
                .setParameter("d", descr).getSingleResult()).longValue();
    }

    /** Ritorna [stato, data_finanziaria] del movimento. */
    Object[] movimento(UUID id) {
        return (Object[]) em.createNativeQuery(
                        "SELECT stato, data_finanziaria FROM movimenti WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    String statoMatch(UUID id) {
        return (String) em.createNativeQuery("SELECT stato FROM matching_differiti WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
}
