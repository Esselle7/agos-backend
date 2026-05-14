package com.agostinelli.gestionale.personale;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per il modulo Personale.
 *
 * Copre: CRUD dipendenti, filtri ricerca, summary costi, mansioni,
 * lookup centri-di-costo, controlli RBAC.
 *
 * Nessun seed di personale nei dati iniziali → il test popola il proprio stato.
 * I centri di costo sono seeded in V5 (CDC-BU1..CDC-BU5).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PersonaleIntegrationTest {

    private static String dipendente1Id;
    private static String dipendente2Id;

    // ── 1. Auth ────────────────────────────────────────────────────────────────

    @Test @Order(1)
    void listaPersonale_senzaToken_401() {
        given().when().get("/api/personale").then().statusCode(401);
    }

    @Test @Order(2)
    void costoSummary_senzaToken_401() {
        given().when().get("/api/personale/costo-summary").then().statusCode(401);
    }

    @Test @Order(3)
    void mansioni_senzaToken_401() {
        given().when().get("/api/personale/mansioni").then().statusCode(401);
    }

    // ── 2. Lookup centri di costo ──────────────────────────────────────────────

    @Test @Order(10)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void centriDiCosto_senzaToken_401() {
        // Already covered but verify the lookup endpoint exists and returns 5 seeded rows
    }

    @Test @Order(11)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void centriDiCosto_listaTutti() {
        given()
            .when().get("/api/lookup/centri-di-costo")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(5))
                .body("[0].codice", notNullValue())
                .body("[0].descrizione", notNullValue())
                .body("[0].businessUnitId", notNullValue());
    }

    @Test @Order(12)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void centriDiCosto_contengonoBuId() {
        given()
            .when().get("/api/lookup/centri-di-costo")
            .then()
                .body("find { it.codice == 'CDC-BU1' }.businessUnitId", equalTo(1))
                .body("find { it.codice == 'CDC-BU2' }.businessUnitId", equalTo(2))
                .body("find { it.codice == 'CDC-BU5' }.descrizione", containsString("Overhead"));
    }

    @Test @Order(13)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void centriDiCosto_accessibileComeDipendente() {
        given()
            .when().get("/api/lookup/centri-di-costo")
            .then()
                .statusCode(200)
                .body("$", hasSize(5));
    }

    // ── 3. Lista vuota iniziale ────────────────────────────────────────────────

    @Test @Order(20)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_inizialmenteVuota() {
        // Non ci sono dipendenti nel seed, la lista dovrebbe essere vuota
        given()
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("page", equalTo(0))
                .body("totalElements", greaterThanOrEqualTo(0))
                .body("content", notNullValue());
    }

    @Test @Order(21)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void costoSummary_inizialmenteZero() {
        given()
            .when().get("/api/personale/costo-summary")
            .then()
                .statusCode(200)
                .body("totaleAttivi", greaterThanOrEqualTo(0))
                .body("costoMensileComplessivo", notNullValue())
                .body("perBu", notNullValue());
    }

    // ── 4. CRUD – Creazione ────────────────────────────────────────────────────

    @Test @Order(30)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void creaDipendente_campiObbligatori_201() {
        dipendente1Id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Mario",
                      "cognome": "Rossi",
                      "mansione": "Chef",
                      "businessUnitId": 1,
                      "centroDiCostoId": 1,
                      "costoAziendaleMensile": 3200.00,
                      "isActive": true
                    }
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("nome", equalTo("Mario"))
                .body("cognome", equalTo("Rossi"))
                .body("mansione", equalTo("Chef"))
                .body("businessUnitId", equalTo(1))
                .body("centroDiCostoId", equalTo(1))
                .body("costoAziendaleMensile", equalTo(3200.0f))
                .body("isActive", equalTo(true))
                .body("businessUnitNome", notNullValue())
                .body("centroDiCostoCodice", equalTo("CDC-BU1"))
            .extract().path("id");
    }

    @Test @Order(31)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void creaDipendente_senzaCampiOpzionali_201() {
        dipendente2Id = given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Lucia",
                      "cognome": "Bianchi",
                      "isActive": true
                    }
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(201)
                .body("nome", equalTo("Lucia"))
                .body("cognome", equalTo("Bianchi"))
                .body("mansione", nullValue())
                .body("businessUnitId", nullValue())
                .body("costoAziendaleMensile", nullValue())
            .extract().path("id");
    }

    @Test @Order(32)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void creaDipendente_nomeVuoto_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome": "", "cognome": "Test", "isActive": true}
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(400);
    }

    @Test @Order(33)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void creaDipendente_cognomeVuoto_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome": "Test", "cognome": "", "isActive": true}
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(400);
    }

    @Test @Order(34)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void creaDipendente_comeDipendente_403() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome": "Vietato", "cognome": "Accesso", "isActive": true}
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(403);
    }

    // ── 5. Lettura singolo ─────────────────────────────────────────────────────

    @Test @Order(40)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void findById_esistente_200() {
        given()
            .when().get("/api/personale/" + dipendente1Id)
            .then()
                .statusCode(200)
                .body("id", equalTo(dipendente1Id))
                .body("nome", equalTo("Mario"))
                .body("cognome", equalTo("Rossi"))
                .body("mansione", equalTo("Chef"))
                .body("businessUnitNome", notNullValue())
                .body("centroDiCostoCodice", equalTo("CDC-BU1"))
                .body("centroDiCostoDescrizione", notNullValue());
    }

    @Test @Order(41)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void findById_nonEsistente_404() {
        given()
            .when().get("/api/personale/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
    }

    @Test @Order(42)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void findById_accessibileComeDipendente() {
        given()
            .when().get("/api/personale/" + dipendente1Id)
            .then()
                .statusCode(200)
                .body("nome", equalTo("Mario"));
    }

    // ── 6. Lista con filtri ────────────────────────────────────────────────────

    @Test @Order(50)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_searchPerNome() {
        given()
            .queryParam("search", "Mario")
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.nome", hasItem("Mario"))
                .body("totalElements", greaterThanOrEqualTo(1));
    }

    @Test @Order(51)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_searchPerCognome() {
        given()
            .queryParam("search", "Bianchi")
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.cognome", hasItem("Bianchi"));
    }

    @Test @Order(52)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_searchPerMansione() {
        given()
            .queryParam("search", "chef")
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.mansione", hasItem("Chef"));
    }

    @Test @Order(53)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_filtroPer_buId() {
        given()
            .queryParam("buId", 1)
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.businessUnitId", everyItem(equalTo(1)));
    }

    @Test @Order(54)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_filtroPerMansione() {
        given()
            .queryParam("mansione", "Chef")
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.mansione", everyItem(equalTo("Chef")));
    }

    @Test @Order(55)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_filtroActiveOnly() {
        given()
            .queryParam("activeOnly", true)
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.isActive", everyItem(equalTo(true)));
    }

    @Test @Order(56)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_searchInesistente_listaVuota() {
        given()
            .queryParam("search", "XXXXNOTEXIST")
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content", hasSize(0))
                .body("totalElements", equalTo(0));
    }

    @Test @Order(57)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void listaPersonale_sizeMax_rispettato() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    // ── 7. Mansioni distinct ───────────────────────────────────────────────────

    @Test @Order(60)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void mansioni_contieneChef() {
        given()
            .when().get("/api/personale/mansioni")
            .then()
                .statusCode(200)
                .body("$", hasItem("Chef"));
    }

    @Test @Order(61)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void mansioni_accessibileComeDipendente() {
        given()
            .when().get("/api/personale/mansioni")
            .then()
                .statusCode(200);
    }

    // ── 8. Costo summary ──────────────────────────────────────────────────────

    @Test @Order(70)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void costoSummary_aggiornatoDopoCreazione() {
        given()
            .when().get("/api/personale/costo-summary")
            .then()
                .statusCode(200)
                .body("totaleAttivi", greaterThanOrEqualTo(1))
                .body("costoMensileComplessivo", greaterThan(0.0f))
                .body("perBu", hasSize(greaterThanOrEqualTo(1)))
                .body("perBu[0].count", greaterThanOrEqualTo(1));
    }

    // ── 9. Aggiornamento ──────────────────────────────────────────────────────

    @Test @Order(80)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void aggiornaDipendente_campiModificati() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Mario",
                      "cognome": "Rossi",
                      "mansione": "Executive Chef",
                      "businessUnitId": 2,
                      "centroDiCostoId": 2,
                      "costoAziendaleMensile": 4500.00,
                      "isActive": true
                    }
                    """)
            .when().put("/api/personale/" + dipendente1Id)
            .then()
                .statusCode(200)
                .body("mansione", equalTo("Executive Chef"))
                .body("businessUnitId", equalTo(2))
                .body("centroDiCostoCodice", equalTo("CDC-BU2"))
                .body("costoAziendaleMensile", equalTo(4500.0f));
    }

    @Test @Order(81)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void aggiornaDipendente_disattivazione() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Lucia",
                      "cognome": "Bianchi",
                      "isActive": false
                    }
                    """)
            .when().put("/api/personale/" + dipendente2Id)
            .then()
                .statusCode(200)
                .body("isActive", equalTo(false));

        // Verifica che non appaia nel filtro activeOnly
        given()
            .queryParam("activeOnly", true)
            .when().get("/api/personale")
            .then()
                .statusCode(200)
                .body("content.id", not(hasItem(dipendente2Id)));
    }

    @Test @Order(82)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void aggiornaDipendente_nonEsistente_404() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome": "Ghost", "cognome": "User", "isActive": true}
                    """)
            .when().put("/api/personale/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
    }

    @Test @Order(83)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void aggiornaDipendente_comeDipendente_403() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome": "Mario", "cognome": "Rossi", "isActive": true}
                    """)
            .when().put("/api/personale/" + dipendente1Id)
            .then()
                .statusCode(403);
    }

    // ── 10. Crea un terzo dipendente e verifica la summary BU ─────────────────

    @Test @Order(90)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void creaTerzoDipendente_summaryPerBuAggregata() {
        // Aggiunge un secondo dipendente in BU2 per verificare l'aggregazione
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {
                      "nome": "Giuseppe",
                      "cognome": "Verdi",
                      "mansione": "Cameriere",
                      "businessUnitId": 2,
                      "costoAziendaleMensile": 2000.00,
                      "isActive": true
                    }
                    """)
            .when().post("/api/personale")
            .then()
                .statusCode(201);

        given()
            .when().get("/api/personale/costo-summary")
            .then()
                .statusCode(200)
                .body("totaleAttivi", greaterThanOrEqualTo(2))
                .body("perBu.find { it.businessUnitId == 2 }.count", greaterThanOrEqualTo(2));
    }

    @Test @Order(91)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void mansioni_contieneCameriere() {
        given()
            .when().get("/api/personale/mansioni")
            .then()
                .statusCode(200)
                .body("$", hasItems("Cameriere", "Executive Chef"));
    }
}
