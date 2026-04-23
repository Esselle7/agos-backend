package com.agostinelli.gestionale.cassa;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per il modulo Cassa.
 * Usa @TestSecurity con UUID fisso (V11) per soddisfare il FK created_by.
 * Richiede PostgreSQL attivo su localhost:5432 (agosdb_test).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CassaIntegrationTest {

    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";

    // ── Saldo ──────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testGetSaldo() {
        given()
            .when().get("/api/cassa/saldo")
            .then()
                .statusCode(200)
                .body("saldo", notNullValue())
                .body("aggiornatoAl", notNullValue());
    }

    @Test
    @Order(2)
    void testGetSaldoSenzaToken() {
        given()
            .when().get("/api/cassa/saldo")
            .then()
                .statusCode(401);
    }

    // ── Lista movimenti ────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiCassaDefault() {
        given()
            .when().get("/api/cassa/movimenti")
            .then()
                .statusCode(200)
                .body("content", notNullValue())
                .body("page", equalTo(0));
    }

    @Test
    @Order(4)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiCassaSizeMax() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/cassa/movimenti")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoCassaEntrata() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":100.00,
                     "dataMovimento":"2025-06-01","descrizione":"Incasso contanti"}
                    """)
            .when().post("/api/cassa/movimenti")
            .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("tipo", equalTo("ENTRATA"))
                .body("importo", equalTo(100.0f))
                .body("stato", equalTo("REGISTRATO"));
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaERecuperaMovimentoCassa() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"USCITA","importo":50.00,
                     "dataMovimento":"2025-06-01","descrizione":"Spesa cassa"}
                    """)
            .when().post("/api/cassa/movimenti")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
            .when().get("/api/cassa/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("tipo", equalTo("USCITA"))
                .body("importo", equalTo(50.0f));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testSaldoAggiornaDopoCrea() {
        // Leggi saldo iniziale
        Float saldoPre = given()
            .when().get("/api/cassa/saldo")
            .then().statusCode(200)
            .extract().path("saldo");

        float importo = 75.50f;

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":%s,
                     "dataMovimento":"2025-06-15","descrizione":"Test saldo"}
                    """.formatted(importo))
            .when().post("/api/cassa/movimenti")
            .then().statusCode(201);

        Float saldoPost = given()
            .when().get("/api/cassa/saldo")
            .then().statusCode(200)
            .extract().path("saldo");

        // Il saldo deve essere aumentato dell'importo inserito
        org.junit.jupiter.api.Assertions.assertEquals(
                saldoPre + importo, saldoPost, 0.01f,
                "Saldo post deve includere il nuovo movimento ENTRATA");
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAnnullaMovimentoCassa() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"USCITA","importo":20.00,
                     "dataMovimento":"2025-06-01","descrizione":"Da annullare"}
                    """)
            .when().post("/api/cassa/movimenti")
            .then().statusCode(201)
            .extract().path("id");

        given()
            .when().delete("/api/cassa/movimenti/" + id)
            .then()
                .statusCode(204);

        given()
            .when().get("/api/cassa/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"));
    }

    @Test
    @Order(14)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testPrelievoRichiede_contoBancaId() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"PRELIEVO_DA_BANCA","importo":200.00,
                     "dataMovimento":"2025-06-01","descrizione":"Prelievo senza conto"}
                    """)
            .when().post("/api/cassa/movimenti")
            .then()
                .statusCode(400);
    }

    // ── Validazione ────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoSenzaImporto() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","dataMovimento":"2025-06-01"}
                    """)
            .when().post("/api/cassa/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testDipendentePuoLeggere() {
        given()
            .when().get("/api/cassa/movimenti")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testDipendenteNonPuoEliminare() {
        given()
            .when().delete("/api/cassa/movimenti/00000000-0000-0000-0000-000000000001")
            .then()
                .statusCode(403);
    }
}
