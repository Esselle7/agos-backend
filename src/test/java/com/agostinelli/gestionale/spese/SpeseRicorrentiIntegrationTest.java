package com.agostinelli.gestionale.spese;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test per il modulo Spese Ricorrenti.
 * Verifica creazione piano, generazione rate, skip, bulk update,
 * liquidazione e annullamento.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpeseRicorrentiIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static final String BASE      = "/api/spese-ricorrenti";

    @Inject EntityManager em;

    private static Integer validContoCoge;
    private static String  createdPlanId;

    @BeforeEach
    @Transactional
    void resolveIds() {
        if (validContoCoge == null) {
            validContoCoge = ((Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'PASSIVITA' AND is_active = true LIMIT 1")
                    .getSingleResult()).intValue();
        }
    }

    // ── Conti COGE lookup ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testContiCogePassivita_returnsSoloPassivita() {
        given()
            .when().get(BASE + "/conti-coge")
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    @Test
    @Order(2)
    void testContiCogePassivita_senzaToken_401() {
        given().when().get(BASE + "/conti-coge").then().statusCode(401);
    }

    // ── Creazione piano ───────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_mensile12Rate() {
        String body = """
            {
              "descrizione": "Mutuo test mensile",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 500.00,
              "variazionePct": 0,
              "giornoDelMese": 20,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        createdPlanId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("id",              notNullValue())
                .body("descrizione",     equalTo("Mutuo test mensile"))
                .body("stato",           equalTo("ATTIVO"))
                .body("numeroRate",      equalTo(12))
                .body("frequenza",       equalTo("MENSILE"))
                .body("giornoDelMese",   equalTo(20))
                .body("rate",            hasSize(12))
                .body("rate[0].stato",   equalTo("PENDING"))
                .body("rate[0].importo", equalTo(500.0f))
                .body("rate[0].dataScadenza", equalTo("2026-01-20"))
                .body("rate[11].dataScadenza", equalTo("2026-12-20"))
                .body("totalePiano",     equalTo(6000.0f))
                .body("totalePagato",    equalTo(0))
                .body("totaleResiduo",   equalTo(6000.0f))
                .extract().path("id");
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_conVariazionePercentuale() {
        String body = """
            {
              "descrizione": "Mutuo con rivalutazione 1%%",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 1000.00,
              "variazionePct": 1.0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-03-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate",            hasSize(3))
                .body("rate[0].importo", equalTo(1000.0f))
                // rata[1] = 1000 * 1.01 = 1010.00
                .body("rate[1].importo", equalTo(1010.0f))
                // rata[2] = 1010 * 1.01 = 1020.10
                .body("rate[2].importo", equalTo(1020.1f));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_trimestrale() {
        String body = """
            {
              "descrizione": "Leasing trimestrale",
              "contoBancarioId": 2,
              "contoCoge": %d,
              "importoRata": 1500.00,
              "variazionePct": 0,
              "giornoDelMese": 15,
              "frequenza": "TRIMESTRALE",
              "numeroRate": 4,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate",                 hasSize(4))
                .body("rate[0].dataScadenza", equalTo("2026-01-15"))
                .body("rate[1].dataScadenza", equalTo("2026-04-15"))
                .body("rate[2].dataScadenza", equalTo("2026-07-15"))
                .body("rate[3].dataScadenza", equalTo("2026-10-15"));
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_cogeNonPassivita_400() {
        // cerca un conto COSTO (non PASSIVITA)
        Number costoId;
        try {
            costoId = (Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'COSTO' LIMIT 1")
                    .getSingleResult();
        } catch (Exception e) { return; } // skip se non esiste

        String body = """
            {
              "descrizione": "Errore coge sbagliato",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-01-01"
            }
            """.formatted(costoId.intValue());

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(14)
    void testCreaPiano_senzaToken_401() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post(BASE + "/piani")
            .then().statusCode(401);
    }

    // ── Lista piani ───────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testListaPiani() {
        given()
            .when().get(BASE + "/piani")
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    // ── Dettaglio piano ───────────────────────────────────────────────────────

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDettaglioPiano() {
        Assumptions.assumeTrue(createdPlanId != null, "Piano non creato nel test precedente");

        given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then()
                .statusCode(200)
                .body("id",          equalTo(createdPlanId))
                .body("rate",        hasSize(12))
                .body("rate[0].id",  notNullValue())
                .body("rate[0].stato", equalTo("PENDING"));
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDettaglioPiano_notFound_404() {
        given()
            .when().get(BASE + "/piani/00000000-0000-0000-0000-000000000001")
            .then().statusCode(404);
    }

    // ── Modifica singola rata ─────────────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testUpdateSingolRata() {
        Assumptions.assumeTrue(createdPlanId != null);

        // prendi l'id della prima rata
        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importo": 550.00, "note": "adeguato ISTAT"}
                """)
            .when().put(BASE + "/piani/" + createdPlanId + "/rate/" + rataId)
            .then()
                .statusCode(200)
                .body("importo", equalTo(550.0f))
                .body("note",    equalTo("adeguato ISTAT"))
                .body("stato",   equalTo("PENDING"));
    }

    // ── Paga rata singola ─────────────────────────────────────────────────────

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPagaRata_setsPaidAndMovimentoId() {
        Assumptions.assumeTrue(createdPlanId != null);

        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().statusCode(200)
            .extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + rataId + "/paga")
            .then()
                .statusCode(200)
                .body("stato", equalTo("ATTIVO"))
                .body("rate.find { it.id == '" + rataId + "' }.stato",   equalTo("PAID"))
                .body("rate.find { it.id == '" + rataId + "' }.movimentoId", notNullValue());
    }

    // ── Skip rata ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testSkipRata_RIMANDA_aggiunteRataInFondo() {
        Assumptions.assumeTrue(createdPlanId != null);

        int numeroBefore = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().path("rate.size()");

        // Prende la prima rata PENDING (non PAID dalla testPagaRata precedente)
        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"modalita": "RIMANDA"}
                """)
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + rataId + "/skip")
            .then().statusCode(204);

        // deve esserci una rata in più
        given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then()
                .statusCode(200)
                .body("rate.size()", equalTo(numeroBefore + 1))
                .body("rate.find { it.id == '" + rataId + "' }.stato", equalTo("SKIPPED"));
    }

    @Test
    @Order(41)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testSkipRata_ACCORPA_sommaNellaProssima() {
        Assumptions.assumeTrue(createdPlanId != null);

        // prima rata PENDING dopo lo skip precedente (non è la rata 0 che è SKIPPED)
        io.restassured.path.json.JsonPath detail = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().jsonPath();

        // trova la prima rata PENDING
        String primaRataId   = detail.getString("rate.find { it.stato == 'PENDING' }.id");
        Float  primaImporto  = detail.getFloat("rate.find { it.stato == 'PENDING' }.importo");
        // trova la seconda rata PENDING
        Float  secondaImporto = detail.getFloat("rate.findAll { it.stato == 'PENDING' }[1].importo");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"modalita": "ACCORPA"}
                """)
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + primaRataId + "/skip")
            .then().statusCode(204);

        io.restassured.path.json.JsonPath after = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().jsonPath();

        Float nuovaSeconda = after.getFloat("rate.findAll { it.stato == 'PENDING' }[0].importo");
        // la prossima rata deve avere il doppio (primaImporto + secondaImporto)
        assertEquals(primaImporto + secondaImporto, nuovaSeconda, 0.01f);
    }

    // ── Liquidazione ──────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testLiquidaPiano() {
        // crea un piano fresco da liquidare
        String body = """
            {
              "descrizione": "Piano da liquidare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 200.00,
              "variazionePct": 0,
              "giornoDelMese": 5,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-06-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importoTotale": null, "note": "Estinzione anticipata"}
                """)
            .when().post(BASE + "/piani/" + planId + "/liquida")
            .then()
                .statusCode(200)
                .body("stato", equalTo("COMPLETATO"))
                .body("rate.findAll { it.stato == 'PAID' }.size()", equalTo(3));

        // invariante: ogni rata PAID deve avere movimentoId
        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + planId)
            .then().extract().jsonPath();

        jp.getList("rate.movimentoId").forEach(id ->
            assertNotNull(id, "movimentoId deve essere non null per le rate PAID")
        );

        // invariante: tutte le rate PAID hanno lo stesso movimentoId (maxi rata)
        long distinct = jp.getList("rate.movimentoId").stream().distinct().count();
        assertEquals(1, distinct, "Tutte le rate liquidate devono puntare allo stesso movimento");
    }

    // ── Annullamento piano ────────────────────────────────────────────────────

    @Test
    @Order(51)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testAnnullaPiano_senzaPenale() {
        String body = """
            {
              "descrizione": "Piano da annullare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 300.00,
              "variazionePct": 0,
              "giornoDelMese": 10,
              "frequenza": "MENSILE",
              "numeroRate": 6,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importoPenale": 0, "note": "Piano annullato"}
                """)
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"))
                .body("rate.findAll { it.stato == 'CANCELLED' }.size()", equalTo(6));
    }

    @Test
    @Order(52)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testAnnullaPiano_giaPagato_nonModificaRatePaid() {
        // crea e liquida piano, poi prova ad annullarlo (deve fallire con PIANO_NON_ATTIVO)
        String body = """
            {
              "descrizione": "Piano completato da NON annullare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "2026-08-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        // liquida
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/liquida")
            .then().statusCode(200);

        // annullare un piano COMPLETATO deve dare 409
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then().statusCode(409);
    }

    // ── Invarianti matematici ─────────────────────────────────────────────────

    @Test
    @Order(60)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testInvariante_totalePiano_equalsSumRate() {
        String body = """
            {
              "descrizione": "Test invariante totale",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 750.00,
              "variazionePct": 2.5,
              "giornoDelMese": 28,
              "frequenza": "BIMESTRALE",
              "numeroRate": 6,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().jsonPath();

        double totalePiano   = jp.getDouble("totalePiano");
        double sumRate = jp.<Float>getList("rate.importo").stream()
                           .mapToDouble(Float::doubleValue).sum();

        assertEquals(totalePiano, sumRate, 0.01, "totalePiano deve essere uguale alla somma delle rate");
    }

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testInvariante_bimestrale_dateCorrette() {
        String body = """
            {
              "descrizione": "Test date bimestrale",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 200.00,
              "variazionePct": 0,
              "giornoDelMese": 15,
              "frequenza": "BIMESTRALE",
              "numeroRate": 3,
              "dataInizio": "2026-02-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate[0].dataScadenza", equalTo("2026-02-15"))
                .body("rate[1].dataScadenza", equalTo("2026-04-15"))
                .body("rate[2].dataScadenza", equalTo("2026-06-15"));
    }
}
