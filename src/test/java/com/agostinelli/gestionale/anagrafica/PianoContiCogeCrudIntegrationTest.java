package com.agostinelli.gestionale.anagrafica;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * CRUD del piano dei conti COGE (gestione admin: GET già coperto da LookupEndpointsIntegrationTest).
 * I conti di test usano codici con prefisso {@code ZZ.} e vengono ripuliti a fine classe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PianoContiCogeCrudIntegrationTest {

    @Inject EntityManager em;

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void create_creaNuovoConto_eCompareInLista() {
        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.01","descrizione":"Conto di test","tipo":"RICAVO","parentId":null}
                """)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(201)
                .body("codice", equalTo("ZZ.01"))
                .body("nome", equalTo("Conto di test"))
                .body("tipo", equalTo("RICAVO"))
                .body("livello", equalTo(2))      // 1 punto → livello 2
                .body("id", notNullValue());

        given().queryParam("tipo", "all")
            .when().get("/api/piano-dei-conti")
            .then().statusCode(200)
                .body("codice", hasItem("ZZ.01"));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void create_codiceDuplicato_409() {
        String body = """
            {"codice":"ZZ.02","descrizione":"Primo","tipo":"COSTO","parentId":null}
            """;
        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/piano-dei-conti").then().statusCode(201);

        given().contentType(ContentType.JSON).body(body)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(409).body("code", equalTo("CODICE_DUPLICATO"));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void create_tipoNonValido_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.03","descrizione":"Tipo errato","tipo":"INVENTATO","parentId":null}
                """)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(400).body("code", equalTo("TIPO_NON_VALIDO"));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void create_descrizioneVuota_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.04","descrizione":"","tipo":"RICAVO","parentId":null}
                """)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(400);  // bean validation @NotBlank
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void update_cambiaLabelECodice_propagaCogeCodice() {
        Integer id = given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.50","descrizione":"Da rinominare","tipo":"RICAVO","parentId":null}
                """)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(201).extract().path("id");

        insertRegola("ZZ.50");

        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.51","descrizione":"Rinominato","tipo":"RICAVO","parentId":null}
                """)
            .when().put("/api/piano-dei-conti/" + id)
            .then().statusCode(200)
                .body("codice", equalTo("ZZ.51"))
                .body("nome", equalTo("Rinominato"));

        // cascade: la regola di classificazione che puntava a ZZ.50 ora punta a ZZ.51
        String cogeRegola = (String) em.createNativeQuery(
                "SELECT coge_codice FROM regole_classificazione WHERE pattern = 'ZZTESTCASCADE'")
                .getSingleResult();
        assertEquals("ZZ.51", cogeRegola, "il rename del codice deve propagarsi alle regole");
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void update_contoInesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.99","descrizione":"x","tipo":"RICAVO","parentId":null}
                """)
            .when().put("/api/piano-dei-conti/999999")
            .then().statusCode(404).body("code", equalTo("CONTO_NON_TROVATO"));
    }

    @Test
    @TestSecurity(user = "test-dip", roles = {"DIPENDENTE"})
    void create_nonAdmin_403() {
        given().contentType(ContentType.JSON)
            .body("""
                {"codice":"ZZ.77","descrizione":"vietato","tipo":"RICAVO","parentId":null}
                """)
            .when().post("/api/piano-dei-conti")
            .then().statusCode(403);
    }

    @Transactional
    void insertRegola(String cogeCodice) {
        em.createNativeQuery(
                "INSERT INTO regole_classificazione (priorita, campo, match_type, pattern, azione, coge_codice) " +
                "VALUES (100, 'DESCRIZIONE', 'CONTAINS', 'ZZTESTCASCADE', 'BOOK', :coge)")
                .setParameter("coge", cogeCodice)
                .executeUpdate();
    }

    @AfterAll
    void cleanup() {
        cleanupTx();
    }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM regole_classificazione WHERE pattern = 'ZZTESTCASCADE'").executeUpdate();
        em.createNativeQuery("DELETE FROM piano_dei_conti_coge WHERE codice LIKE 'ZZ.%'").executeUpdate();
    }
}
