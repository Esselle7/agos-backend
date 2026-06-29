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
 * CRUD cespiti + verifica del calcolo ammortamenti date-aware (ReportingService.computeAmmortamenti):
 * un cespite attivo pesa sul P&L del periodo, uno già esaurito (fine vita prima del periodo) NON pesa.
 * Conto CAPEX usato: 50.01.001 (id 72 in seed V4). Marker descrizione "ZZ ..." per la pulizia.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CespitiIntegrationTest {

    @Inject EntityManager em;

    private static final String CONTO_CAPEX = "72"; // 50.01.001 Lavastoviglie
    private static final String PL_2026 = "/api/reporting/pl?from=2026-01-01&to=2026-12-31";

    private String creaCespite(String desc, String costo, String aliquota, String dataAcquisto) {
        return given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"%s","contoCogeId":%s,"costoStorico":%s,"aliquotaAmmortamento":%s,"dataAcquisto":"%s"}
                """.formatted(desc, CONTO_CAPEX, costo, aliquota, dataAcquisto))
            .when().post("/api/cespiti")
            .then().statusCode(201).extract().path("id");
    }

    private double ammortamentiPl() {
        return ((Number) given().when().get(PL_2026)
                .then().statusCode(200).extract().path("ammortamenti")).doubleValue();
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void create_calcolaQuoteDerivate() {
        given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"ZZ Forno","contoCogeId":72,"costoStorico":12000,"aliquotaAmmortamento":10,"dataAcquisto":"2024-06-01"}
                """)
            .when().post("/api/cespiti")
            .then().statusCode(201)
                .body("ammortamentoMensile", comparesEqualTo(100.0f))
                .body("ammortamentoAnnuo", comparesEqualTo(1200.0f))
                .body("contoCogeCodice", equalTo("50.01.001"))
                .body("id", notNullValue());
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void create_costoZero_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"ZZ Bad","contoCogeId":72,"costoStorico":0,"aliquotaAmmortamento":10,"dataAcquisto":"2024-06-01"}
                """)
            .when().post("/api/cespiti").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void create_aliquotaOltre100_400() {
        given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"ZZ Bad2","contoCogeId":72,"costoStorico":1000,"aliquotaAmmortamento":150,"dataAcquisto":"2024-06-01"}
                """)
            .when().post("/api/cespiti").then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void update_inesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"ZZ X","contoCogeId":72,"costoStorico":1000,"aliquotaAmmortamento":10,"dataAcquisto":"2024-01-01"}
                """)
            .when().put("/api/cespiti/00000000-0000-0000-0000-000000000000")
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void delete_creaEsparisce() {
        String id = creaCespite("ZZ Da eliminare", "2000", "20", "2023-01-01");
        given().when().delete("/api/cespiti/" + id).then().statusCode(204);
        given().when().get("/api/cespiti")
            .then().statusCode(200).body("descrizione", not(hasItem("ZZ Da eliminare")));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"DIPENDENTE"})
    void create_nonAdmin_403() {
        given().contentType(ContentType.JSON)
            .body("""
                {"descrizione":"ZZ Vietato","contoCogeId":72,"costoStorico":1000,"aliquotaAmmortamento":10,"dataAcquisto":"2024-01-01"}
                """)
            .when().post("/api/cespiti").then().statusCode(403);
    }

    /** Il cuore del fix: attivo conta, esaurito no. */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void ammortamenti_dateAware_attivoConta_esauritoNo() {
        double base = ammortamentiPl();

        // attivo: 12000 @10% dal 2024-06 → 1200/anno, vivo nel 2026
        creaCespite("ZZ Amm Attivo", "12000", "10", "2024-06-01");
        double conAttivo = ammortamentiPl();
        assertEquals(base + 1200.0, conAttivo, 0.01, "il cespite attivo deve aggiungere 1200 al 2026");

        // esaurito: 5000 @20% dal 2010 → vita 5 anni, finita nel 2015 → 0 nel 2026
        creaCespite("ZZ Amm Esaurito", "5000", "20", "2010-01-01");
        double conEsaurito = ammortamentiPl();
        assertEquals(conAttivo, conEsaurito, 0.01,
                "il cespite a fine vita NON deve pesare sul 2026 (fix date-aware)");
    }

    @AfterAll
    void cleanup() { cleanupTx(); }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM cespiti WHERE descrizione LIKE 'ZZ %'").executeUpdate();
    }
}
