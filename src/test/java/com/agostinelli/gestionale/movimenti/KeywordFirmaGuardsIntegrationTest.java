package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Guardie di validazione su create/update/delete di una keyword-firma
 * (KeywordLearningService): rami d'errore 400/404/409 finora non coperti.
 * Nessun test inserisce realmente una firma (tutti colpiscono una guardia
 * prima dell'INSERT) → nessuna pulizia necessaria.
 */
@QuarkusTest
class KeywordFirmaGuardsIntegrationTest {

    private static final String NIL = "00000000-0000-0000-0000-000000000000";
    private static final String USER = "00000000-0000-0000-0000-000000000099";

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void createFirma_senzaToken_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"natura":"DOMINIO","azione":"BOOK","cogeCodice":"30.01","buId":1}
                """)
            .when().post("/api/movimenti/keyword")
            .then().statusCode(400).body("code", equalTo("FIRMA_SENZA_TOKEN"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void createFirma_bookSenzaCogeBu_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"natura":"DOMINIO","azione":"BOOK","token":["ZZKW1"]}
                """)
            .when().post("/api/movimenti/keyword")
            .then().statusCode(400).body("code", equalTo("FIRMA_BOOK_INCOMPLETA"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void createFirma_fornitoreSuDominio_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"natura":"DOMINIO","azione":"BOOK","cogeCodice":"30.01","buId":1,
                 "fornitoreId":"%s","token":["ZZKW2"]}
                """.formatted(NIL))
            .when().post("/api/movimenti/keyword")
            .then().statusCode(400).body("code", equalTo("FORNITORE_SOLO_IDENTITA"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void updateFirma_inesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"natura":"DOMINIO","azione":"BOOK","cogeCodice":"30.01","buId":1,"token":["ZZKW3"]}
                """)
            .when().put("/api/movimenti/keyword/" + NIL)
            .then().statusCode(404).body("code", equalTo("FIRMA_NON_TROVATA"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void deleteFirma_inesistente_404() {
        given().when().delete("/api/movimenti/keyword/" + NIL)
            .then().statusCode(404).body("code", equalTo("FIRMA_NON_TROVATA"));
    }
}
