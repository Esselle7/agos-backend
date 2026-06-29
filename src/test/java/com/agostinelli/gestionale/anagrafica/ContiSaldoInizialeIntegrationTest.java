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

/**
 * Saldo di apertura dei conti (PUT /api/conti/{id}/saldo-iniziale). Usa il conto 3 (Cassa) e lo
 * ripristina a 0 a fine classe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContiSaldoInizialeIntegrationTest {

    @Inject EntityManager em;

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_impostaSaldo_eCompareInLista() {
        given().contentType(ContentType.JSON)
            .body("""
                {"saldoIniziale":1234.56,"dataSaldoIniziale":"2025-12-31"}
                """)
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(200)
                .body("id", equalTo(3))
                .body("saldoIniziale", comparesEqualTo(1234.56f))
                .body("dataSaldoIniziale", equalTo("2025-12-31"));

        given().when().get("/api/conti")
            .then().statusCode(200)
                .body("find { it.id == 3 }.saldoIniziale", comparesEqualTo(1234.56f));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_contoInesistente_404() {
        given().contentType(ContentType.JSON)
            .body("{\"saldoIniziale\":100,\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/99/saldo-iniziale")
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_senzaSaldo_400() {
        given().contentType(ContentType.JSON)
            .body("{\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"DIPENDENTE"})
    void put_nonAdmin_403() {
        given().contentType(ContentType.JSON)
            .body("{\"saldoIniziale\":1,\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(403);
    }

    @AfterAll
    void cleanup() { reset(); }

    @Transactional
    void reset() {
        em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = 0, data_saldo_iniziale = NULL WHERE id = 3")
                .executeUpdate();
    }
}
