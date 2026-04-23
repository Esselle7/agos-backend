package com.agostinelli.gestionale.anagrafica;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per il modulo Anagrafica.
 * Usa @TestSecurity per bypassare il JWT reale: il DB è gestito da DevServices.
 * Eseguire con: ./mvnw test (richiede Docker daemon attivo per DevServices).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnagraficaIntegrationTest {

    // ── BUSINESS UNIT ──────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testGetBuList() {
        given()
            .when().get("/api/bu")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(5))
                .body("[0].codice", equalTo("BU1"))
                .body("[0].nome",   containsString("Ristorazione"))
                .body("[0].colore", notNullValue());
    }

    @Test
    @Order(2)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testGetBuListComeDipendente() {
        given()
            .when().get("/api/bu")
            .then()
                .statusCode(200)
                .body("$", hasSize(5));
    }

    @Test
    @Order(3)
    void testGetBuListSenzaToken() {
        given()
            .when().get("/api/bu")
            .then()
                .statusCode(401);
    }

    // ── CATEGORIE ──────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testGetCategorieSenzaBuId() {
        given()
            .when().get("/api/categorie")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(11)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaERecuperaCategoria() {
        // Crea categoria radice per BU1
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Test Categoria",
                      "tipo": "ENTRATA",
                      "buId": 1,
                      "ordinamento": 0
                    }
                    """)
            .when().post("/api/categorie")
            .then()
                .statusCode(201)
                .body("nome", equalTo("Test Categoria"))
                .body("tipo", equalTo("ENTRATA"))
                .extract().path("id");

        // Verifica che appaia nell'albero
        given()
            .queryParam("tipo", "ENTRATA")
            .queryParam("buId", 1)
            .when().get("/api/categorie")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("find { it.id == " + id + " }.nome", equalTo("Test Categoria"));
    }

    @Test
    @Order(12)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaCategoriaConParentTipoErrato() {
        // Prima crea una categoria ENTRATA
        int parentId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Parent ENTRATA","tipo":"ENTRATA","buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then().statusCode(201)
            .extract().path("id");

        // Poi prova a creare una USCITA come figlia → deve fallire
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Figlio USCITA","tipo":"USCITA","parentId":%d,"buId":1,"ordinamento":0}
                    """.formatted(parentId))
            .when().post("/api/categorie")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(13)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testCreaCategoriaComeDipendenteForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Test","tipo":"ENTRATA","buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then()
                .statusCode(403);
    }

    // ── FORNITORI ──────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testSearchFornitoriDefault() {
        given()
            .when().get("/api/fornitori")
            .then()
                .statusCode(200)
                .body("content", notNullValue())
                .body("page", equalTo(0))
                .body("size", greaterThan(0))
                .body("totalElements", greaterThan(0));
    }

    @Test
    @Order(21)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testSearchFornitoriConQuery() {
        given()
            .queryParam("search", "Pasini")
            .when().get("/api/fornitori")
            .then()
                .statusCode(200)
                .body("content.ragioneSociale", hasItem(containsStringIgnoringCase("Pasini")));
    }

    @Test
    @Order(22)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testSearchFornitoriSizeMax() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/fornitori")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    @Test
    @Order(23)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaFornitoreERecuperaDettaglio() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "ragioneSociale": "Test Fornitore Srl",
                      "alias": "TestF",
                      "piva": "12345678901",
                      "codiceSdi": "ABC1234"
                    }
                    """)
            .when().post("/api/fornitori")
            .then()
                .statusCode(201)
                .body("ragioneSociale", equalTo("Test Fornitore Srl"))
                .body("piva", equalTo("12345678901"))
                .extract().path("id");

        // Dettaglio completo
        given()
            .when().get("/api/fornitori/" + id)
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("ragioneSociale", equalTo("Test Fornitore Srl"))
                .body("aliasList", hasSize(0));
    }

    @Test
    @Order(24)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testFornitoreNonEsistente() {
        given()
            .when().get("/api/fornitori/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
    }

    @Test
    @Order(25)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaECancellaAlias() {
        // Crea fornitore
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Alias Test"}
                    """)
            .when().post("/api/fornitori")
            .then().statusCode(201)
            .extract().path("id");

        // Aggiungi alias
        int aliasId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"pattern":"ALIAS TEST","matchType":"CONTAINS"}
                    """)
            .when().post("/api/fornitori/" + id + "/alias")
            .then()
                .statusCode(201)
                .body("pattern", equalTo("ALIAS TEST"))
                .body("matchType", equalTo("CONTAINS"))
                .extract().path("id");

        // Verifica che sia nel dettaglio
        given()
            .when().get("/api/fornitori/" + id)
            .then()
                .body("aliasList", hasSize(1));

        // Cancella alias
        given()
            .when().delete("/api/fornitori/" + id + "/alias/" + aliasId)
            .then()
                .statusCode(204);

        // Verifica cancellazione
        given()
            .when().get("/api/fornitori/" + id)
            .then()
                .body("aliasList", hasSize(0));
    }

    @Test
    @Order(26)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaFornitoreValidazioneNomeVuoto() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":""}
                    """)
            .when().post("/api/fornitori")
            .then()
                .statusCode(400);
    }

    // ── CONTI BANCARI ──────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testGetContiList() {
        given()
            .when().get("/api/conti")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(5))
                .body("[0].nome", notNullValue())
                .body("[0].tipo", notNullValue())
                .body("[0].saldoCalcolato", notNullValue());
    }

    @Test
    @Order(31)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testContiContengonoBPM() {
        given()
            .when().get("/api/conti")
            .then()
                .body("find { it.id == 1 }.nome", containsString("BPM"))
                .body("find { it.id == 3 }.tipo", equalTo("CASSA"))
                .body("find { it.id == 4 }.tipo", equalTo("DIGITALE"));
    }
}
