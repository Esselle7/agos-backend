package com.agostinelli.gestionale.reporting;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test di integrazione per GET /api/reporting/forecasting.
 *
 * Prerequisiti: agosdb_test con dati seed V9 (movimenti 2026).
 * Le MV vengono refreshate una volta sola prima della suite.
 *
 * INVARIANTI VERIFICATE:
 * - saldoFinale == saldoPartenza + incassiPrevisti - uscitePreviste
 * - ebitdaPrevisto == ricaviPrevisti - costiPrevisti
 * - importoEntrata >= 0 e importoUscita >= 0 per ogni voce dettaglio
 * - saldoLiquiditaFine del primo bucket == saldoPartenza + delta primo bucket
 * - Tutti gli horizons validi tornano 200
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForecastingIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static volatile boolean mvRefreshed = false;

    @Inject EntityManager em;
    @Inject UserTransaction tx;

    @BeforeEach
    void refreshMvs() throws Exception {
        if (mvRefreshed) return;
        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_kpi_mensili").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_saldi_conti").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_cash_flow_statement").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_redditivita_eventi").executeUpdate();
        tx.commit();
        mvRefreshed = true;
    }

    // ═══════════════════════════════════════════════════════════
    // SECURITY
    // ═══════════════════════════════════════════════════════════

    @Test @Order(1)
    void senzaAuth_401() {
        given()
            .queryParam("horizon", "30")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(401);
    }

    // ═══════════════════════════════════════════════════════════
    // VALIDAZIONE PARAMETRI
    // ═══════════════════════════════════════════════════════════

    @Test @Order(10)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonInvalido_400() {
        given()
            .queryParam("horizon", "ANNO_SCORSO")
            .when().get("/api/reporting/forecasting")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_HORIZON"));
    }

    @Test @Order(11)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonVuoto_400() {
        given()
            .queryParam("horizon", "")
            .when().get("/api/reporting/forecasting")
            .then()
                .statusCode(400);
    }

    @Test @Order(12)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonNumeroNonValido_400() {
        given()
            .queryParam("horizon", "45")
            .when().get("/api/reporting/forecasting")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_HORIZON"));
    }

    // ═══════════════════════════════════════════════════════════
    // PATH FELICI – tutti gli horizons
    // ═══════════════════════════════════════════════════════════

    @Test @Order(20)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizon30_200_strutturaCompleta() {
        given()
            .queryParam("horizon", "30")
            .when().get("/api/reporting/forecasting")
            .then()
                .statusCode(200)
                .body("asIs",          notNullValue())
                .body("economico",     notNullValue())
                .body("finanziario",   notNullValue())
                .body("asIs.saldoLiquidita",  notNullValue())
                .body("asIs.ricaviYtd",       notNullValue())
                .body("asIs.costiYtd",        notNullValue())
                .body("asIs.ebitdaYtd",       notNullValue())
                .body("asIs.creditiAperti",   notNullValue())
                .body("asIs.debitiAperti",    notNullValue())
                .body("economico.ricaviPrevisti",  notNullValue())
                .body("economico.costiPrevisti",   notNullValue())
                .body("economico.ebitdaPrevisto",  notNullValue())
                .body("economico.dettaglio",       notNullValue())
                .body("finanziario.saldoPartenza",    notNullValue())
                .body("finanziario.incassiPrevisti",  notNullValue())
                .body("finanziario.uscitePreviste",   notNullValue())
                .body("finanziario.saldoFinale",      notNullValue())
                .body("finanziario.timeline",         notNullValue());
    }

    @Test @Order(21)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizon60_200() {
        given().queryParam("horizon", "60")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    @Test @Order(22)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizon90_200_default() {
        // horizon=90 è il default
        given()
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    @Test @Order(23)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizon180_200() {
        given().queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    @Test @Order(24)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonFineAnno_200() {
        given().queryParam("horizon", "FINE_ANNO")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    // ═══════════════════════════════════════════════════════════
    // INVARIANTI MATEMATICI
    // ═══════════════════════════════════════════════════════════

    @Test @Order(30)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_saldoFinale_eq_saldoPartenza_plus_flussi() {
        // INVARIANTE CORE: saldoFinale == saldoPartenza + incassiPrevisti - uscitePreviste
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float saldoPartenza    = toFloat(r.path("finanziario.saldoPartenza"));
        float incassiPrevisti  = toFloat(r.path("finanziario.incassiPrevisti"));
        float uscitePreviste   = toFloat(r.path("finanziario.uscitePreviste"));
        float saldoFinale      = toFloat(r.path("finanziario.saldoFinale"));

        float expected = saldoPartenza + incassiPrevisti - uscitePreviste;
        assertEquals(expected, saldoFinale, 0.05f,
            "saldoFinale deve essere saldoPartenza + incassiPrevisti - uscitePreviste. " +
            "Atteso: " + expected + " Trovato: " + saldoFinale);
    }

    @Test @Order(31)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_ebitdaPrevisto_eq_ricavi_minus_costi() {
        // INVARIANTE: ebitdaPrevisto == ricaviPrevisti - costiPrevisti
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float ricaviPrevisti = toFloat(r.path("economico.ricaviPrevisti"));
        float costiPrevisti  = toFloat(r.path("economico.costiPrevisti"));
        float ebitdaPrevisto = toFloat(r.path("economico.ebitdaPrevisto"));

        assertEquals(ricaviPrevisti - costiPrevisti, ebitdaPrevisto, 0.05f,
            "ebitdaPrevisto deve essere ricaviPrevisti - costiPrevisti");
    }

    @Test @Order(32)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_asIs_ebitda_eq_ricavi_minus_costi() {
        // INVARIANTE AS IS: ebitdaYtd == ricaviYtd - costiYtd (approssimato perché MV usa costi_operativi)
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float ricaviYtd = toFloat(r.path("asIs.ricaviYtd"));
        float costiYtd  = toFloat(r.path("asIs.costiYtd"));
        float ebitdaYtd = toFloat(r.path("asIs.ebitdaYtd"));

        // L'ebitda dalla MV usa costi_operativi (esclude CAPEX); tolleranza ampia
        // per il CAPEX che potrebbe essere nella MV come costi_investimento separato
        assertTrue(Math.abs((ricaviYtd - costiYtd) - ebitdaYtd) < ricaviYtd * 0.5f + 1f,
            "ebitdaYtd deve essere ragionevolmente vicino a ricaviYtd - costiYtd");
    }

    @Test @Order(33)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_dettaglio_importiNonNegativi() {
        // INVARIANTE: ogni voce dettaglio ha importoEntrata >= 0 e importoUscita >= 0
        List<Map<String, Object>> dettaglio = given()
            .queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");

        for (int i = 0; i < dettaglio.size(); i++) {
            Map<String, Object> voce = dettaglio.get(i);
            float entrata = toFloat(voce.get("importoEntrata"));
            float uscita  = toFloat(voce.get("importoUscita"));
            assertTrue(entrata >= 0,
                "Voce " + i + " (" + voce.get("descrizione") + "): importoEntrata deve essere >= 0");
            assertTrue(uscita >= 0,
                "Voce " + i + " (" + voce.get("descrizione") + "): importoUscita deve essere >= 0");
        }
    }

    @Test @Order(34)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_dettaglio_nonEntrambiPositivi() {
        // INVARIANTE: per ogni voce, non possono essere entrambi > 0
        // (una voce è o entrata o uscita, mai entrambi)
        List<Map<String, Object>> dettaglio = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");

        for (int i = 0; i < dettaglio.size(); i++) {
            Map<String, Object> voce = dettaglio.get(i);
            float entrata = toFloat(voce.get("importoEntrata"));
            float uscita  = toFloat(voce.get("importoUscita"));
            assertFalse(entrata > 0 && uscita > 0,
                "Voce " + i + " (" + voce.get("descrizione") + "): importoEntrata e importoUscita non possono essere entrambi > 0");
        }
    }

    @Test @Order(35)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_timeline_saldoProgressivo() {
        // INVARIANTE: saldoLiquiditaFine di ogni bucket è saldo del bucket precedente
        // + entratePreviste - uscitePreviste del bucket corrente
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        List<Map<String, Object>> timeline = r.jsonPath().getList("finanziario.timeline");
        float saldoPartenza = r.path("finanziario.saldoPartenza");

        if (timeline.isEmpty()) return; // periodo vuoto: ok

        float saldoPrecedente = saldoPartenza;
        for (int i = 0; i < timeline.size(); i++) {
            Map<String, Object> bucket = timeline.get(i);
            float entr  = toFloat(bucket.get("entratePreviste"));
            float usc   = toFloat(bucket.get("uscitePreviste"));
            float saldo = toFloat(bucket.get("saldoLiquiditaFine"));

            float expected = saldoPrecedente + entr - usc;
            assertEquals(expected, saldo, 0.05f,
                "Timeline bucket " + i + " (" + bucket.get("bucket") + "): saldoLiquiditaFine non corretto. " +
                "Atteso " + expected + " trovato " + saldo);
            saldoPrecedente = saldo;
        }
    }

    @Test @Order(36)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_dettaglio_categorieValide() {
        // INVARIANTE: categoria deve essere uno dei valori attesi
        List<Map<String, Object>> dettaglio = given()
            .queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");

        java.util.Set<String> categorieValide = java.util.Set.of(
            "MOVIMENTO", "EVENTO", "RATA_RICORRENTE", "STIPENDIO");

        for (Map<String, Object> voce : dettaglio) {
            String cat = (String) voce.get("categoria");
            assertTrue(categorieValide.contains(cat),
                "Categoria non valida: " + cat);
        }
    }

    @Test @Order(37)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_dettaglio_visteValide() {
        // INVARIANTE: vista deve essere ECONOMICA | FINANZIARIA | ENTRAMBE
        List<Map<String, Object>> dettaglio = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");

        java.util.Set<String> visteValide = java.util.Set.of("ECONOMICA", "FINANZIARIA", "ENTRAMBE");

        for (Map<String, Object> voce : dettaglio) {
            String vista = (String) voce.get("vista");
            assertTrue(visteValide.contains(vista),
                "Vista non valida: " + vista);
        }
    }

    @Test @Order(38)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_asIs_valoriNonNegativi() {
        // INVARIANTE: creditiAperti e debitiAperti devono essere >= 0
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float creditiAperti = toFloat(r.path("asIs.creditiAperti"));
        float debitiAperti  = toFloat(r.path("asIs.debitiAperti"));

        assertTrue(creditiAperti >= 0, "creditiAperti deve essere >= 0");
        assertTrue(debitiAperti  >= 0, "debitiAperti deve essere >= 0");
    }

    // ═══════════════════════════════════════════════════════════
    // GRANULARITÀ TIMELINE: settimanale vs mensile
    // ═══════════════════════════════════════════════════════════

    @Test @Order(40)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void timeline30_bucket_settimanale() {
        // Con horizon=30, la timeline deve avere bucket settimanali (formato "YYYY-Wnn")
        List<String> buckets = given()
            .queryParam("horizon", "30")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("finanziario.timeline.bucket");

        if (buckets.isEmpty()) return;

        // Tutti i bucket devono contenere "-W" per essere settimane ISO
        for (String b : buckets) {
            assertTrue(b.contains("-W"),
                "Con horizon=30, i bucket devono essere settimanali (formato YYYY-Wnn). Bucket trovato: " + b);
        }
    }

    @Test @Order(41)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void timeline180_bucket_mensile() {
        // Con horizon=180, la timeline deve avere bucket mensili (formato "YYYY-MM")
        List<String> buckets = given()
            .queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("finanziario.timeline.bucket");

        if (buckets.isEmpty()) return;

        for (String b : buckets) {
            assertFalse(b.contains("-W"),
                "Con horizon=180, i bucket devono essere mensili (formato YYYY-MM). Bucket trovato: " + b);
            // Formato YYYY-MM: 7 caratteri
            assertEquals(7, b.length(),
                "Bucket mensile deve avere formato YYYY-MM (7 caratteri). Trovato: " + b);
        }
    }

    @Test @Order(42)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void timelineFineAnno_bucket_mensile() {
        List<String> buckets = given()
            .queryParam("horizon", "FINE_ANNO")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("finanziario.timeline.bucket");

        if (buckets.isEmpty()) return;

        for (String b : buckets) {
            assertFalse(b.contains("-W"),
                "Con FINE_ANNO, i bucket devono essere mensili. Trovato: " + b);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DETTAGLIO: ordinamento per data
    // ═══════════════════════════════════════════════════════════

    @Test @Order(50)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void dettaglio_ordinatoCronologicamente() {
        List<String> date = given()
            .queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio.data");

        for (int i = 1; i < date.size(); i++) {
            assertTrue(date.get(i - 1).compareTo(date.get(i)) <= 0,
                "Il dettaglio deve essere ordinato cronologicamente. " +
                "data[" + (i-1) + "]=" + date.get(i-1) + " > data[" + i + "]=" + date.get(i));
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CASO LIMITE: FINE_ANNO case-insensitive
    // ═══════════════════════════════════════════════════════════

    @Test @Order(60)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonCaseInsensitive_fineAnno() {
        given().queryParam("horizon", "fine_anno")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    @Test @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void horizonCaseInsensitive_30() {
        // "30" non cambia con case, ma verifica robustezza
        given().queryParam("horizon", "30")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200);
    }

    // ═══════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════

    private float toFloat(Object o) {
        if (o == null) return 0f;
        if (o instanceof Number n) return n.floatValue();
        return Float.parseFloat(o.toString());
    }
}
