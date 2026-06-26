package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Centro di smistamento (ImportTriageService): CRUD regole data-driven + rami d'errore
 * (404/403) finora non coperti. Le regole di test usano pattern con prefisso {@code ZZTEST}
 * e vengono ripulite a fine classe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportTriageIntegrationTest {

    private static final String NIL = "00000000-0000-0000-0000-000000000000";

    @Inject EntityManager em;

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void regolaCrud_happyPath() {
        int id = given().contentType(ContentType.JSON)
            .body("""
                {"priorita":999,"sorgente":"*","tipoMovimento":"*","campo":"CAUSALE",
                 "matchType":"CONTAINS","pattern":"ZZTEST_TRIAGE","azione":"SKIP_POS","attivo":true}
                """)
            .when().post("/api/movimenti/import/regole")
            .then().statusCode(201).body("id", notNullValue())
            .extract().path("id");

        given().queryParam("attiva", false)
            .when().put("/api/movimenti/import/regole/" + id + "/attiva")
            .then().statusCode(204);

        given().when().delete("/api/movimenti/import/regole/" + id)
            .then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void setRegolaAttiva_inesistente_404() {
        given().queryParam("attiva", true)
            .when().put("/api/movimenti/import/regole/999999/attiva")
            .then().statusCode(404).body("code", equalTo("REGOLA_NON_TROVATA"));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void deleteRegola_inesistente_404() {
        given().when().delete("/api/movimenti/import/regole/999999")
            .then().statusCode(404).body("code", equalTo("REGOLA_NON_TROVATA"));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void classificaTransitorio_movimentoInesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"cogeId":1,"businessUnitId":1,"apprendiKeyword":false}
                """)
            .when().put("/api/movimenti/import/transitori/" + NIL + "/classifica")
            .then().statusCode(404).body("code", equalTo("MOVIMENTO_NON_TROVATO"));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void risolviEvento_inesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"azione":"SCARTA"}
                """)
            .when().put("/api/movimenti/import/eventi/" + NIL + "/risolvi")
            .then().statusCode(404).body("code", equalTo("EVENTO_NON_TROVATO"));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void risolviRicorrente_inesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"azione":"IGNORA"}
                """)
            .when().put("/api/movimenti/import/ricorrenti/" + NIL + "/risolvi")
            .then().statusCode(404).body("code", equalTo("RICORRENTE_NON_TROVATA"));
    }

    @Test
    @TestSecurity(user = "test-dip", roles = {"DIPENDENTE"})
    void regole_nonAdmin_403() {
        given().when().get("/api/movimenti/import/regole").then().statusCode(403);
    }

    @AfterAll
    void cleanup() {
        cleanupTx();
    }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM regole_classificazione WHERE pattern LIKE 'ZZTEST%'").executeUpdate();
    }
}
