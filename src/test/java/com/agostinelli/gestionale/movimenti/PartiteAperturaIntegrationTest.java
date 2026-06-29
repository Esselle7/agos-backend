package com.agostinelli.gestionale.movimenti;

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
 * Partite di apertura (crediti/debiti pregressi al 31/12/2025). Verifica che vengano creati i
 * movimenti GIUSTI — stato DA_LIQUIDARE, fonte APERTURA, competenza 2025, senza banca — e che
 * NON inquinino il conto economico 2026 (competenza pregressa).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PartiteAperturaIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    @Inject EntityManager em;

    private String creaPartita(String tipo, int coge, short bu, double importo, String desc) {
        return given().contentType(ContentType.JSON)
            .body(("{\"tipo\":\"%s\",\"importo\":%s,\"importoLordo\":%s,\"dataMovimento\":\"2025-12-31\","
                 + "\"dataCompetenza\":\"2025-12-31\",\"dataFinanziaria\":null,\"dataLiquidita\":\"2026-03-31\","
                 + "\"businessUnitId\":%d,\"contoCoge\":%d,\"descrizione\":\"%s\",\"fonte\":\"APERTURA\"}")
                 .formatted(tipo, importo, importo, bu, coge, desc))
            .when().post("/api/movimenti")
            .then().statusCode(201)
                .body("stato", equalTo("DA_LIQUIDARE"))
                .body("fonte", equalTo("APERTURA"))
            .extract().path("id");
    }

    private double ricaviPl2026() {
        return ((Number) given().when().get("/api/reporting/pl?from=2026-01-01&to=2026-12-31")
                .then().statusCode(200).extract().path("ricavi.totale")).doubleValue();
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void credito_apparePartiteApertura_conStatoEFonteGiusti() {
        String id = creaPartita("ENTRATA", 33, (short) 2, 1500, "ZZ Credito apertura");
        given().when().get("/api/movimenti/partite-apertura?tipo=ENTRATA")
            .then().statusCode(200)
                .body("find { it.id == '" + id + "' }.descrizione", equalTo("ZZ Credito apertura"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void debito_apparePartiteApertura() {
        String id = creaPartita("USCITA", 38, (short) 1, 26692, "ZZ Debito fornitore apertura");
        given().when().get("/api/movimenti/partite-apertura?tipo=USCITA")
            .then().statusCode(200)
                .body("id", hasItem(id))
                .body("find { it.id == '" + id + "' }.tipo", equalTo("USCITA"));
        // e NON compare tra le ENTRATA
        given().when().get("/api/movimenti/partite-apertura?tipo=ENTRATA")
            .then().statusCode(200).body("id", not(hasItem(id)));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void partitaApertura_competenza2025_nonInquinaIlPl2026() {
        double base = ricaviPl2026();
        creaPartita("ENTRATA", 33, (short) 2, 9999, "ZZ Credito noPL");
        double dopo = ricaviPl2026();
        assertEquals(base, dopo, 0.01,
                "una partita con competenza 2025 NON deve aumentare i ricavi del P&L 2026");
    }

    @Test
    @TestSecurity(user = USER, roles = {"DIPENDENTE"})
    void partiteApertura_dipendentePuoLeggere() {
        given().when().get("/api/movimenti/partite-apertura?tipo=ENTRATA").then().statusCode(200);
    }

    @AfterAll
    void cleanup() { cleanupTx(); }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZ %' AND fonte = 'APERTURA'").executeUpdate();
    }
}
