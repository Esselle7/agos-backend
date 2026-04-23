package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per il modulo Movimenti.
 * Usa @TestSecurity con UUID fisso (V11) per soddisfare il FK created_by.
 * Richiede PostgreSQL attivo su localhost:5432 (agosdb_test).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MovimentiIntegrationTest {

    /** UUID dell'utente di test – seeded in V11 con UUID fisso. */
    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";

    @Inject EntityManager em;

    // Dati validi risolti dal DB (IDs SERIAL, non hardcoded)
    private static Integer validContoCoge;
    private static Integer validMetodoPagamento;

    @BeforeEach
    void resolveIds() {
        if (validContoCoge == null) {
            validContoCoge = ((Number) em
                    .createNativeQuery("SELECT id FROM piano_dei_conti_coge LIMIT 1")
                    .getSingleResult()).intValue();
        }
        if (validMetodoPagamento == null) {
            validMetodoPagamento = ((Number) em
                    .createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'")
                    .getSingleResult()).intValue();
        }
    }

    // ── Lista e filtri ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiDefaultPaginata() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content", notNullValue())
                .body("page", equalTo(0))
                .body("size", greaterThan(0))
                .body("totalElements", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiSizeMax() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    @Test
    @Order(3)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testListaDipendente() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void testListaSenzaToken() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(401);
    }

    @Test
    @Order(5)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testFiltraPerTipo() {
        given()
            .queryParam("tipo", "ENTRATA")
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content.tipo", everyItem(equalTo("ENTRATA")));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimento() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "150.00", "Pagamento test", null))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("tipo", equalTo("ENTRATA"))
                .body("importo", equalTo(150.0f))
                .body("stato", equalTo("REGISTRATO"))
                .body("fonte", equalTo("MANUALE"));
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoConCommissione() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequestConCommissione("ENTRATA", "97.00", "100.00"))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("importo", equalTo(97.0f))
                .body("importoCommissione", equalTo(3.0f));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaERecuperaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("USCITA", "250.00", "Spesa test", null))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
            .when().get("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("tipo", equalTo("USCITA"))
                .body("importo", equalTo(250.0f));
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoNonEsistente() {
        given()
            .when().get("/api/movimenti/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
    }

    @Test
    @Order(14)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAggiornaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "100.00", "Da aggiornare", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"descrizione":"Descrizione aggiornata","importo":200.00}
                    """)
            .when().put("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("descrizione", equalTo("Descrizione aggiornata"))
                .body("importo", equalTo(200.0f));
    }

    @Test
    @Order(15)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAnnullaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("USCITA", "50.00", "Da annullare", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        given()
            .when().delete("/api/movimenti/" + id)
            .then()
                .statusCode(204);

        given()
            .when().get("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"));
    }

    @Test
    @Order(16)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testAnnullaDipendenteForbidden() {
        given()
            .when().delete("/api/movimenti/00000000-0000-0000-0000-000000000001")
            .then()
                .statusCode(403);
    }

    // ── Validazione ────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoSenzaImporto() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","dataMovimento":"2025-01-01",
                     "contoBancarioId":1,"metodoPagamentoId":1,
                     "businessUnitId":1,"contoCoge":1,"descrizione":"test"}
                    """)
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoTipoEventoSenzaEventoId() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "100.00", "Test", "CAPARRA"))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    // ── Bulk import ────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImport() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"movimenti":[
                      %s,
                      %s
                    ]}
                    """.formatted(
                        buildCreateRequest("ENTRATA", "100.00", "Bulk 1", null),
                        buildCreateRequest("USCITA",  "200.00", "Bulk 2", null)
                    ))
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(200)
                .body("importati", equalTo(2))
                .body("duplicati", equalTo(0))
                .body("errori", equalTo(0));
    }

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImportDeduplication() {
        String rif = "REF-DEDUP-" + System.currentTimeMillis();
        String mov = buildCreateRequestConRif("ENTRATA", "300.00", "Dedup test", rif);

        // Prima importazione: ok
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[" + mov + "]}")
            .when().post("/api/movimenti/bulk")
            .then().statusCode(200).body("importati", equalTo(1));

        // Seconda importazione con stesso riferimento: duplicato
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[" + mov + "]}")
            .when().post("/api/movimenti/bulk")
            .then().statusCode(200)
            .body("importati", equalTo(0))
            .body("duplicati", equalTo(1));
    }

    @Test
    @Order(32)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testBulkImportDipendenteForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[]}")
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(403);
    }

    // ── Riconciliazione ────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testGetNonRiconciliati() {
        given()
            .when().get("/api/movimenti/riconciliazione/non-riconciliati")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(41)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMatchAutomatico() {
        given()
            .contentType(ContentType.JSON)
            .when().post("/api/movimenti/riconciliazione/match-automatico")
            .then()
                .statusCode(200)
                .body("matched", notNullValue());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String buildCreateRequest(String tipo, String importo, String descrizione, String tipoEvento) {
        String tipoEventoJson = tipoEvento != null
                ? "\"tipoEventoMovimento\":\"" + tipoEvento + "\","
                : "";
        return """
                {"tipo":"%s","importo":%s,"dataMovimento":"2025-06-01",
                 "contoBancarioId":1,"metodoPagamentoId":%d,
                 "businessUnitId":1,"contoCoge":%d,
                 %s"descrizione":"%s"}
                """.formatted(tipo, importo, validMetodoPagamento, validContoCoge,
                tipoEventoJson, descrizione);
    }

    private String buildCreateRequestConCommissione(String tipo, String importo, String importoLordo) {
        return """
                {"tipo":"%s","importo":%s,"importoLordo":%s,
                 "dataMovimento":"2025-06-01","contoBancarioId":1,
                 "metodoPagamentoId":%d,"businessUnitId":1,"contoCoge":%d,
                 "descrizione":"Test commissione"}
                """.formatted(tipo, importo, importoLordo,
                validMetodoPagamento, validContoCoge);
    }

    private String buildCreateRequestConRif(String tipo, String importo, String descrizione, String rif) {
        return """
                {"tipo":"%s","importo":%s,"dataMovimento":"2025-06-01",
                 "contoBancarioId":1,"metodoPagamentoId":%d,
                 "businessUnitId":1,"contoCoge":%d,
                 "descrizione":"%s","fonte":"IMPORT_BILLY","riferimentoEsterno":"%s"}
                """.formatted(tipo, importo, validMetodoPagamento, validContoCoge,
                descrizione, rif);
    }
}
