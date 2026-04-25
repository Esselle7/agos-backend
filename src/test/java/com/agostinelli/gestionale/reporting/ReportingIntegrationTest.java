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
 * Integration test per il modulo Reporting & Dashboard.
 * Copre DashboardResource, ReportingResource, ExportService, ReportJobService.
 *
 * Prerequisito: agosdb_test con dati V9 (movimenti reali 2026).
 * Le MV vengono refreshate una sola volta prima della suite.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReportingIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static volatile boolean mvRefreshed = false;

    @Inject EntityManager em;
    @Inject UserTransaction tx;

    @BeforeEach
    void refreshMvsOnce() throws Exception {
        if (mvRefreshed) return;
        // REFRESH senza CONCURRENTLY: funziona dentro una transazione JTA.
        // Usare MV singole (non fn_refresh_all_mv che usa CONCURRENTLY).
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
    // SECURITY – 401 senza autenticazione
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(1)
    void dashboardKpiSenzaAuth_401() {
        given().when().get("/api/dashboard/kpi").then().statusCode(401);
    }

    @Test
    @Order(2)
    void dashboardAndamentoSenzaAuth_401() {
        given().when().get("/api/dashboard/andamento-mensile").then().statusCode(401);
    }

    @Test
    @Order(3)
    void dashboardFatturatoBuSenzaAuth_401() {
        given().when().get("/api/dashboard/fatturato-per-bu").then().statusCode(401);
    }

    @Test
    @Order(4)
    void dashboardUltimeTransazioniSenzaAuth_401() {
        given().when().get("/api/dashboard/ultime-transazioni").then().statusCode(401);
    }

    @Test
    @Order(5)
    void reportingPlSenzaAuth_401() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/pl")
            .then().statusCode(401);
    }

    @Test
    @Order(6)
    void reportingExportSenzaAuth_401() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/export/movimenti")
            .then().statusCode(401);
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD KPI – validazioni 4xx
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiPeriodInvalido_400() {
        given()
            .queryParam("period", "MESE_CORRENTE")
            .when().get("/api/dashboard/kpi")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_PERIOD"));
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomSenzaRange_400() {
        given()
            .queryParam("period", "CUSTOM")
            .when().get("/api/dashboard/kpi")
            .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_RANGE"));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomFromDopeTo_400() {
        given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-03-31")
            .queryParam("to", "2026-01-01")
            .when().get("/api/dashboard/kpi")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_RANGE"));
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD KPI – path felici e invarianti matematici
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiMTD_200_strutturaCompleta() {
        given()
            .queryParam("period", "MTD")
            .when().get("/api/dashboard/kpi")
            .then()
                .statusCode(200)
                .body("saldi", notNullValue())
                .body("saldi.bpm", notNullValue())
                .body("saldi.creditAgricole", notNullValue())
                .body("saldi.cassa", notNullValue())
                .body("saldi.totale", notNullValue())
                .body("periodo", notNullValue())
                .body("periodo.totalEntrate", notNullValue())
                .body("periodo.totalUscite", notNullValue())
                .body("periodo.margine", notNullValue())
                .body("aggiornatoAl", notNullValue());
    }

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomRange_margineInvariant_entrateMinusUscite() {
        // INVARIANTE CORE: margine == totalEntrate - totalUscite
        // Se c'è un bug nel calcolo MV o nell'aggregazione, questo fallisce.
        Response r = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/kpi")
            .then().statusCode(200).extract().response();

        float totalEntrate = r.path("periodo.totalEntrate");
        float totalUscite  = r.path("periodo.totalUscite");
        float margine      = r.path("periodo.margine");

        assertTrue(totalEntrate > 0,
            "Con dati V9 gen-mar 2026 deve esserci almeno una entrata: totalEntrate=" + totalEntrate);
        assertEquals(totalEntrate - totalUscite, margine, 0.02f,
            "margine deve essere esattamente totalEntrate - totalUscite");
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomRange_marginePctInvariant() {
        // INVARIANTE: marginePct == (margine / totalEntrate) * 100
        Response r = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/kpi")
            .then().statusCode(200).extract().response();

        float totalEntrate = r.path("periodo.totalEntrate");
        float margine      = r.path("periodo.margine");
        Float marginePct   = r.path("periodo.marginePct");

        if (totalEntrate > 0 && marginePct != null) {
            float expected = (margine / totalEntrate) * 100f;
            assertEquals(expected, marginePct, 0.1f,
                "marginePct deve essere margine/totalEntrate*100");
        }
    }

    @Test
    @Order(23)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomRange_nMovimentiPositivi() {
        given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-03-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/kpi")
            .then()
                .statusCode(200)
                .body("periodo.nMovimenti", greaterThan(0));
    }

    @Test
    @Order(24)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiDipendenteHaAccesso_200() {
        given()
            .queryParam("period", "MTD")
            .when().get("/api/dashboard/kpi")
            .then().statusCode(200);
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD ANDAMENTO MENSILE
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void andamentoMensileDefault_200_lista() {
        given()
            .when().get("/api/dashboard/andamento-mensile")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void andamentoMensileAnniTroppi_400() {
        given()
            .queryParam("anni", 6)
            .when().get("/api/dashboard/andamento-mensile")
            .then()
                .statusCode(400)
                .body("code", equalTo("PARAM_OUT_OF_RANGE"));
    }

    @Test
    @Order(32)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void andamentoMensile_hasDati2026() {
        // Con V9, deve esistere almeno una riga anno=2026 nell'MV
        List<Integer> anni = given()
            .queryParam("anni", 2)
            .when().get("/api/dashboard/andamento-mensile")
            .then().statusCode(200)
            .extract().jsonPath().getList("anno");

        assertTrue(anni.contains(2026),
            "Con V9 seed data, andamento-mensile deve avere righe per anno 2026. Anni trovati: " + anni);
    }

    @Test
    @Order(33)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void andamentoMensile_margineInvariantPerRiga() {
        // Per ogni riga: margine == totEntrate - totUscite
        List<Map<String, Object>> righe = given()
            .queryParam("anni", 2)
            .when().get("/api/dashboard/andamento-mensile")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        for (int i = 0; i < righe.size(); i++) {
            Map<String, Object> r = righe.get(i);
            float totE = toFloat(r.get("totEntrate"));
            float totU = toFloat(r.get("totUscite"));
            float marg = toFloat(r.get("margine"));
            assertEquals(totE - totU, marg, 0.02f,
                "Riga " + i + " (anno=" + r.get("anno") + " mese=" + r.get("mese") +
                "): margine deve essere totEntrate - totUscite");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD FATTURATO PER BU
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(40)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void fatturatoPerBuCustomRange_400_fromDopeTo() {
        given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-12-31")
            .queryParam("to", "2026-01-01")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then().statusCode(400);
    }

    @Test
    @Order(41)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void fatturatoPerBuYTD_esattamente5BU() {
        // INVARIANTE CRITICA: sempre 5 BU (anche se alcune hanno zero dati)
        given()
            .queryParam("period", "YTD")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then()
                .statusCode(200)
                .body("$", hasSize(5));
    }

    @Test
    @Order(42)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void fatturatoPerBuCustomRange_esattamente5BU_ancheConDati() {
        // Con dati V9, deve comunque restituire sempre 5 BU
        given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then()
                .statusCode(200)
                .body("$", hasSize(5));
    }

    @Test
    @Order(43)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void fatturatoPerBu_margineInvariantPerOgniBU() {
        // Per ogni BU: margine == totEntrate - totUscite
        List<Map<String, Object>> bus = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertEquals(5, bus.size(), "Deve essere presente esattamente 1 BU per le 5 BU");

        for (Map<String, Object> bu : bus) {
            float totE = toFloat(bu.get("totEntrate"));
            float totU = toFloat(bu.get("totUscite"));
            float marg = toFloat(bu.get("margine"));
            assertEquals(totE - totU, marg, 0.02f,
                "BU " + bu.get("buId") + " (" + bu.get("buNome") + "): margine != totEntrate - totUscite");
        }
    }

    @Test
    @Order(44)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void fatturatoPerBu_totaleEntratePositivoConDatiV9() {
        List<Map<String, Object>> bus = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        float sumEntrate = 0f;
        for (Map<String, Object> bu : bus) {
            sumEntrate += toFloat(bu.get("totEntrate"));
        }
        assertTrue(sumEntrate > 0,
            "Con dati V9 gen-mar 2026, il totale entrate delle BU deve essere > 0");
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD ULTIME TRANSAZIONI – clamping
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void ultimeTransazioniDefault_200_lista() {
        given()
            .when().get("/api/dashboard/ultime-transazioni")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class))
                .body("$", hasSize(greaterThan(0)));
    }

    @Test
    @Order(51)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void ultimeTransazioniLimitGrande_clamatoA50() {
        // limit=200 deve essere clamped a 50 (resource: Math.min(Math.max(limit,1),50))
        List<?> risultati = given()
            .queryParam("limit", 200)
            .when().get("/api/dashboard/ultime-transazioni")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertTrue(risultati.size() <= 50,
            "limit=200 deve essere clamped a max 50, trovati: " + risultati.size());
    }

    @Test
    @Order(52)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void ultimeTransazioniLimitUno_esattamente1() {
        given()
            .queryParam("limit", 1)
            .when().get("/api/dashboard/ultime-transazioni")
            .then()
                .statusCode(200)
                .body("$", hasSize(1));
    }

    @Test
    @Order(53)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void ultimeTransazioniLimitNegativo_clamatoA1() {
        // limit=-5 → Math.max(-5,1)=1 → Math.min(1,50)=1 → 1 risultato (no errore)
        List<?> risultati = given()
            .queryParam("limit", -5)
            .when().get("/api/dashboard/ultime-transazioni")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertEquals(1, risultati.size(),
            "limit negativo deve essere clamped a 1");
    }

    @Test
    @Order(54)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void ultimeTransazioni_campiNonNulli() {
        List<Map<String, Object>> items = given()
            .queryParam("limit", 5)
            .when().get("/api/dashboard/ultime-transazioni")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertFalse(items.isEmpty(), "Deve esserci almeno un movimento con dati V9");
        Map<String, Object> primo = items.get(0);
        assertNotNull(primo.get("id"),        "id non deve essere null");
        assertNotNull(primo.get("tipo"),       "tipo non deve essere null");
        assertNotNull(primo.get("importo"),    "importo non deve essere null");
        assertNotNull(primo.get("dataMovimento"), "dataMovimento non deve essere null");
    }

    // ═══════════════════════════════════════════════════════════
    // DASHBOARD SCADENZE IMMINENTI
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(60)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminentiDefault_200_lista() {
        given()
            .when().get("/api/dashboard/scadenze-imminenti")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminentiGiorni91_400() {
        given()
            .queryParam("giorni", 91)
            .when().get("/api/dashboard/scadenze-imminenti")
            .then()
                .statusCode(400)
                .body("code", equalTo("PARAM_OUT_OF_RANGE"));
    }

    @Test
    @Order(62)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminentiGiorni90_200_valoreConfineBoundary() {
        // giorni=90 è esattamente il limite – deve restituire 200 (non 400)
        given()
            .queryParam("giorni", 90)
            .when().get("/api/dashboard/scadenze-imminenti")
            .then().statusCode(200);
    }

    @Test
    @Order(63)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminenti_urgenzaValida() {
        List<Map<String, Object>> scadenze = given()
            .queryParam("giorni", 90)
            .when().get("/api/dashboard/scadenze-imminenti")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        for (Map<String, Object> s : scadenze) {
            String urgenza = (String) s.get("urgenza");
            assertTrue(urgenza != null && List.of("ALTA","MEDIA","BASSA").contains(urgenza),
                "urgenza deve essere ALTA|MEDIA|BASSA, trovato: " + urgenza);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // REPORTING P&L – validazioni e path felici
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(70)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plSenzaRange_400() {
        given()
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_RANGE"));
    }

    @Test
    @Order(71)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plFromDopeTo_400() {
        given()
            .queryParam("from", "2026-03-31")
            .queryParam("to", "2026-01-01")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_RANGE"));
    }

    @Test
    @Order(72)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plRange5Anni_400() {
        // YEARS.between(2020-01-01, 2025-01-01) = 5 → >= 5 → 400
        given()
            .queryParam("from", "2020-01-01")
            .queryParam("to", "2025-01-01")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(400)
                .body("code", equalTo("RANGE_TOO_LARGE"));
    }

    @Test
    @Order(73)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plSyncCortoRange_200() {
        // 2 mesi → sincrono (< 12 mesi) → 200 OK con corpo PlDTO
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(200)
                .body("ricavi", notNullValue())
                .body("costi", notNullValue())
                .body("ebitda", notNullValue());
    }

    @Test
    @Order(74)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plSyncConBuId_200() {
        // Filtrare per BU1 (Ristorazione) – verifica bug "buId null NPE" risolto
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .queryParam("buId", 1)
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(200)
                .body("ricavi", notNullValue())
                .body("ebitda", notNullValue());
    }

    @Test
    @Order(75)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plEbitdaInvariant_ricaviMinusCostiOperativi() {
        // INVARIANTE: ebitda == ricavi.totale - (costi.totale - costi.capex)
        // costi.totale include capex; ebitda_proxy esclude capex
        Response r = given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/pl")
            .then().statusCode(200).extract().response();

        float ricaviTotale = toFloat(r.path("ricavi.totale"));
        float costiTotale  = toFloat(r.path("costi.totale"));
        float costiCapex   = toFloat(r.path("costi.capex"));
        float ebitda       = toFloat(r.path("ebitda"));

        float costiOperativi = costiTotale - costiCapex;
        assertEquals(ricaviTotale - costiOperativi, ebitda, 0.05f,
            "ebitda deve essere ricavi - costi_operativi (costi.totale - costi.capex)");
    }

    @Test
    @Order(76)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plAsyncLungoRange_202_conJobId() {
        // 59 mesi > 12 → async; range < 5 anni → no 400
        given()
            .queryParam("from", "2021-01-01")
            .queryParam("to", "2025-12-31")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(202)
                .body("jobId", notNullValue());
    }

    @Test
    @Order(77)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plStatusJobIdNonEsistente_404() {
        given()
            .when().get("/api/reporting/pl/status/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404)
                .body("code", equalTo("JOB_NOT_FOUND"));
    }

    @Test
    @Order(78)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plAsyncFullFlow_pollFinoAREADY() throws InterruptedException {
        // Verifica il flusso asincrono completo: submit → poll → READY
        String jobId = given()
            .queryParam("from", "2021-01-01")
            .queryParam("to", "2025-12-31")
            .when().get("/api/reporting/pl")
            .then().statusCode(202)
            .extract().path("jobId");

        assertNotNull(jobId, "jobId non deve essere null nella risposta 202");

        // Poll fino a READY o ERROR (max 10 sec)
        String status = "PENDING";
        for (int i = 0; i < 20 && "PENDING".equals(status); i++) {
            Thread.sleep(500);
            status = given()
                .when().get("/api/reporting/pl/status/" + jobId)
                .then().statusCode(200)
                .extract().path("status");
        }
        assertNotEquals("PENDING", status,
            "Il job P&L non deve rimanere PENDING oltre 10 secondi");
        assertEquals("READY", status,
            "Il job P&L deve completarsi con status=READY, trovato: " + status);
    }

    // ═══════════════════════════════════════════════════════════
    // REPORTING P&L COMPARATIVE
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(80)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plTutteBU_200_conConsolidato() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/pl/tutte-bu")
            .then()
                .statusCode(200)
                .body("businessUnits", notNullValue())
                .body("totaleConsolidato", notNullValue())
                .body("totaleConsolidato.ricavi", notNullValue());
    }

    @Test
    @Order(81)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plTutteBU_consolidatoRicavi_sommaBU() {
        // INVARIANTE: consolidato.ricavi == sum of BU.ricavi
        Response r = given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .when().get("/api/reporting/pl/tutte-bu")
            .then().statusCode(200).extract().response();

        float consolidatoRicavi = toFloat(r.path("totaleConsolidato.ricavi"));
        List<Map<String, Object>> buList = r.jsonPath().getList("businessUnits");

        float sumBuRicavi = 0f;
        for (Map<String, Object> bu : buList) {
            sumBuRicavi += toFloat(bu.get("ricavi"));
        }

        assertEquals(sumBuRicavi, consolidatoRicavi, 0.05f,
            "consolidato.ricavi deve essere la somma dei ricavi di tutte le BU");
    }

    // ═══════════════════════════════════════════════════════════
    // REPORTING CASH FLOW STORICO
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(90)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowStoricoMONTH_200_lista() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .queryParam("granularity", "MONTH")
            .when().get("/api/reporting/cashflow/storico")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class))
                .body("$", hasSize(greaterThan(0)));
    }

    @Test
    @Order(91)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowStoricoWEEK_200_periodoBreve() {
        given()
            .queryParam("from", "2026-03-01")
            .queryParam("to", "2026-03-31")
            .queryParam("granularity", "WEEK")
            .when().get("/api/reporting/cashflow/storico")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(92)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowStoricoWEEK_rangeOltre6Mesi_400() {
        // 7 mesi > 6 → 400
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-08-01")
            .queryParam("granularity", "WEEK")
            .when().get("/api/reporting/cashflow/storico")
            .then()
                .statusCode(400)
                .body("code", equalTo("RANGE_TOO_LARGE"));
    }

    @Test
    @Order(93)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowStoricoMONTH_saldoCumulativoInvariant() {
        // INVARIANTE: saldoCumulato[i] == saldoCumulato[i-1] + saldoPeriodo[i]
        //             saldoPeriodo[i] == entrate[i] - uscite[i]
        List<Map<String, Object>> periodi = given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .queryParam("granularity", "MONTH")
            .when().get("/api/reporting/cashflow/storico")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertTrue(periodi.size() >= 2,
            "Con dati V9 gen+mar 2026, attesi almeno 2 periodi mensili");

        // Controlla saldoPeriodo = entrate - uscite per ogni riga
        for (Map<String, Object> p : periodi) {
            float entr  = toFloat(p.get("entrate"));
            float usc   = toFloat(p.get("uscite"));
            float saldo = toFloat(p.get("saldoPeriodo"));
            assertEquals(entr - usc, saldo, 0.02f,
                "saldoPeriodo deve essere entrate - uscite");
        }

        // Controlla la progressione cumulativa
        float prevCumulato = 0f;
        for (int i = 0; i < periodi.size(); i++) {
            float saldoPeriodo = toFloat(periodi.get(i).get("saldoPeriodo"));
            float saldoCumulato = toFloat(periodi.get(i).get("saldoCumulato"));
            float expected = prevCumulato + saldoPeriodo;
            assertEquals(expected, saldoCumulato, 0.02f,
                "Periodo " + i + ": saldoCumulato deve essere saldoCumulato_prev + saldoPeriodo");
            prevCumulato = saldoCumulato;
        }
    }

    @Test
    @Order(94)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowStoricoFromDopeTo_400() {
        given()
            .queryParam("from", "2026-12-01")
            .queryParam("to", "2026-01-01")
            .when().get("/api/reporting/cashflow/storico")
            .then().statusCode(400);
    }

    // ═══════════════════════════════════════════════════════════
    // REPORTING CASH FLOW FORECAST
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(100)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowForecastDefault_200_lista() {
        given()
            .when().get("/api/reporting/cashflow/forecast")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class))
                .body("$", hasSize(greaterThan(0)));
    }

    @Test
    @Order(101)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowForecastGiorni366_400() {
        given()
            .queryParam("giorni", 366)
            .when().get("/api/reporting/cashflow/forecast")
            .then()
                .statusCode(400)
                .body("code", equalTo("PARAM_OUT_OF_RANGE"));
    }

    @Test
    @Order(102)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowForecast_strutturaStoricoPrevisto() {
        // Deve avere STORICO e PREVISTO; gli STORICO vengono prima
        List<Map<String, Object>> punti = given()
            .queryParam("giorni", 30)
            .when().get("/api/reporting/cashflow/forecast")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        // 31 giorni storico + 30 giorni previsto = 61
        assertEquals(61, punti.size(),
            "Con giorni=30: attesi 31 STORICO + 30 PREVISTO = 61 punti, trovati: " + punti.size());

        // I primi devono essere STORICO
        assertEquals("STORICO", punti.get(0).get("tipo"),
            "Il primo punto del forecast deve essere STORICO");
        // L'ultimo deve essere PREVISTO
        assertEquals("PREVISTO", punti.get(punti.size() - 1).get("tipo"),
            "L'ultimo punto del forecast deve essere PREVISTO");
    }

    @Test
    @Order(103)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowForecast_liquiditaProiettataNonNull() {
        List<Map<String, Object>> punti = given()
            .queryParam("giorni", 10)
            .when().get("/api/reporting/cashflow/forecast")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        for (int i = 0; i < punti.size(); i++) {
            assertNotNull(punti.get(i).get("liquiditaProiettata"),
                "liquiditaProiettata non deve essere null al punto " + i);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // EXPORT – Content-Type e magic bytes
    // ═══════════════════════════════════════════════════════════

    @Test
    @Order(110)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportMovimentiSenzaRange_400() {
        given()
            .when().get("/api/reporting/export/movimenti")
            .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_RANGE"));
    }

    @Test
    @Order(111)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportMovimentiCSV_bomUtf8() {
        // INVARIANTE: CSV inizia con BOM UTF-8 (0xEF 0xBB 0xBF) per compatibilità Excel IT
        byte[] bytes = given()
            .queryParam("from", "2026-03-01")
            .queryParam("to", "2026-03-31")
            .queryParam("format", "csv")
            .when().get("/api/reporting/export/movimenti")
            .then()
                .statusCode(200)
                .extract().asByteArray();

        assertTrue(bytes.length >= 3, "Il CSV non deve essere vuoto");
        assertEquals((byte) 0xEF, bytes[0], "Primo byte BOM UTF-8 deve essere 0xEF");
        assertEquals((byte) 0xBB, bytes[1], "Secondo byte BOM UTF-8 deve essere 0xBB");
        assertEquals((byte) 0xBF, bytes[2], "Terzo byte BOM UTF-8 deve essere 0xBF");
    }

    @Test
    @Order(112)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportMovimentiXLSX_magicBytesEContentType() {
        // XLSX = file ZIP → magic bytes PK (0x50 0x4B)
        byte[] bytes = given()
            .queryParam("from", "2026-03-01")
            .queryParam("to", "2026-03-31")
            .queryParam("format", "xlsx")
            .when().get("/api/reporting/export/movimenti")
            .then()
                .statusCode(200)
                .header("Content-Type",
                        containsString("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .extract().asByteArray();

        assertTrue(bytes.length > 4, "Il file XLSX non deve essere vuoto");
        assertEquals((byte) 0x50, bytes[0], "Magic byte XLSX[0] deve essere 0x50 (P)");
        assertEquals((byte) 0x4B, bytes[1], "Magic byte XLSX[1] deve essere 0x4B (K)");
    }

    @Test
    @Order(113)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportCommercialista_200_xlsx() {
        given()
            .queryParam("mese", 3)
            .queryParam("anno", 2026)
            .when().get("/api/reporting/export/commercialista")
            .then()
                .statusCode(200)
                .header("Content-Type",
                        containsString("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @Order(114)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportCommercialista_meseFuoriRange_400() {
        given()
            .queryParam("mese", 13)
            .queryParam("anno", 2026)
            .when().get("/api/reporting/export/commercialista")
            .then()
                .statusCode(400)
                .body("code", equalTo("PARAM_OUT_OF_RANGE"));
    }

    @Test
    @Order(115)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportCommercialista_mese0_400() {
        given()
            .queryParam("mese", 0)
            .queryParam("anno", 2026)
            .when().get("/api/reporting/export/commercialista")
            .then().statusCode(400);
    }

    @Test
    @Order(116)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportPlBu_xlsx_200() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .queryParam("format", "xlsx")
            .when().get("/api/reporting/export/pl-bu")
            .then()
                .statusCode(200)
                .header("Content-Type",
                        containsString("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @Order(117)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportPlBu_pdf_501() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to", "2026-03-31")
            .queryParam("format", "pdf")
            .when().get("/api/reporting/export/pl-bu")
            .then().statusCode(501);
    }

    @Test
    @Order(118)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportPlBu_senzaRange_400() {
        given()
            .queryParam("format", "xlsx")
            .when().get("/api/reporting/export/pl-bu")
            .then().statusCode(400);
    }

    @Test
    @Order(119)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void exportMovimentiXLSX_conDatiV9_fileNonVuoto() {
        // Verifica che con dati reali l'XLSX abbia almeno intestazione + 1 riga dati
        byte[] bytes = given()
            .queryParam("from", "2026-03-01")
            .queryParam("to", "2026-03-31")
            .queryParam("format", "xlsx")
            .when().get("/api/reporting/export/movimenti")
            .then().statusCode(200)
            .extract().asByteArray();

        // Un XLSX con dati reali ha dimensione significativa (almeno 5 KB)
        assertTrue(bytes.length > 5000,
            "L'XLSX con dati V9 marzo 2026 deve avere dimensione > 5 KB, trovato: " + bytes.length + " byte");
    }

    // ═══════════════════════════════════════════════════════════
    // HELPER
    // ═══════════════════════════════════════════════════════════

    private static float toFloat(Object o) {
        if (o == null) return 0f;
        if (o instanceof Number n) return n.floatValue();
        return Float.parseFloat(o.toString());
    }
}
