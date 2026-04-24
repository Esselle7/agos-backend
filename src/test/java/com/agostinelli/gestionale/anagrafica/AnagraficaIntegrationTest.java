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
    @Order(14)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaCategoriaConNomeVuoto() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"","tipo":"ENTRATA","buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(15)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaCategoriaParentBuIdDiverso() {
        int parentId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Parent BU1 per cross-BU test","tipo":"ENTRATA","buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then().statusCode(201).extract().path("id");

        // Sottocategoria in BU2 con parent in BU1 → deve fallire (stessa validazione del tipo)
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Figlio BU2","tipo":"ENTRATA","parentId":%d,"buId":2,"ordinamento":0}
                    """.formatted(parentId))
            .when().post("/api/categorie")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(16)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCreaCategoriaParentIdInesistente() {
        // parentId inesistente → 400 (INVALID_PARENT da CategorieService)
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Orfana","tipo":"ENTRATA","parentId":999999,"buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(17)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testUpdateCategoria() {
        int id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Categoria aggiornamento test","tipo":"USCITA","buId":1,"ordinamento":0}
                    """)
            .when().post("/api/categorie")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Categoria aggiornata","tipo":"USCITA","buId":1,"ordinamento":5}
                    """)
            .when().put("/api/categorie/" + id)
            .then()
                .statusCode(200)
                .body("nome",        equalTo("Categoria aggiornata"))
                .body("ordinamento", equalTo(5));
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

    @Test
    @Order(40)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testDipendentePuoCercareFornitore() {
        given()
            .when().get("/api/fornitori")
            .then()
                .statusCode(200)
                .body("content", notNullValue());
    }

    @Test
    @Order(41)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testDipendenteNonPuoCreareFornitore() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Dipendente Test"}
                    """)
            .when().post("/api/fornitori")
            .then()
                .statusCode(403);
    }

    @Test
    @Order(42)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testUpdateFornitore() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Da Aggiornare Srl"}
                    """)
            .when().post("/api/fornitori")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Aggiornato Srl","alias":"FornAgg","note":"Aggiornato in test"}
                    """)
            .when().put("/api/fornitori/" + id)
            .then()
                .statusCode(200)
                .body("ragioneSociale", equalTo("Fornitore Aggiornato Srl"))
                .body("alias",          equalTo("FornAgg"));
    }

    @Test
    @Order(43)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testFornitoreConPivaDuplicataFallisce() {
        // Usa una P.IVA di 11 cifre non presente nel seed
        String piva = "99887766551";

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore PIVA Prima","piva":"%s"}
                    """.formatted(piva))
            .when().post("/api/fornitori")
            .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore PIVA Doppia","piva":"%s"}
                    """.formatted(piva))
            .when().post("/api/fornitori")
            .then()
                .statusCode(409);
    }

    @Test
    @Order(44)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliasPatternVuotoRifiutato() {
        String id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Alias Vuoto Test"}
                    """)
            .when().post("/api/fornitori")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"pattern":"","matchType":"CONTAINS"}
                    """)
            .when().post("/api/fornitori/" + id + "/alias")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(45)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testCancellaAliasFornitoreErratoNotFound() {
        // Crea due fornitori separati
        String id1 = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Cross Alias Uno"}
                    """)
            .when().post("/api/fornitori")
            .then().statusCode(201).extract().path("id");

        String id2 = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"ragioneSociale":"Fornitore Cross Alias Due"}
                    """)
            .when().post("/api/fornitori")
            .then().statusCode(201).extract().path("id");

        // Crea alias per il primo fornitore
        int aliasId = given()
            .contentType(ContentType.JSON)
            .body("""
                    {"pattern":"CROSS_ALIAS_TEST","matchType":"CONTAINS"}
                    """)
            .when().post("/api/fornitori/" + id1 + "/alias")
            .then().statusCode(201).extract().path("id");

        // Tenta di cancellare l'alias del fornitore 1 usando il path del fornitore 2 → 404
        given()
            .when().delete("/api/fornitori/" + id2 + "/alias/" + aliasId)
            .then()
                .statusCode(404);

        // L'alias deve ancora esistere sul fornitore 1
        given()
            .when().get("/api/fornitori/" + id1)
            .then()
                .body("aliasList", hasSize(1));
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
