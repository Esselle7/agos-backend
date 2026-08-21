package com.agostinelli.gestionale.reporting;

import io.restassured.http.ContentType;
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
 * Copre DashboardResource, ReportingResource, ReportJobService.
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
        //
        // Il test si crea il proprio ricavo e il proprio costo. Prima si appoggiava ai
        // movimenti presenti nel range, ma il profilo %test carica solo db/migration
        // (i seed stanno in db/seed/common): in mag-lug 2026 restavano due movimenti
        // lasciati da altre classi di test, entrambi su un conto ATTIVITA. Il
        // "totalEntrate > 0" era quindi verde solo finché la dashboard sommava anche i
        // conti non economici — cioè grazie al difetto che questa suite ora impedisce.
        String data = "2026-05-15";
        int cogeRicavo = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'RICAVO' AND is_active ORDER BY id LIMIT 1")
                .getSingleResult()).intValue();
        int cogeCosto = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'COSTO' AND NOT is_capex AND is_active ORDER BY id LIMIT 1")
                .getSingleResult()).intValue();
        int metodo = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'").getSingleResult()).intValue();

        creaMovimento("ENTRATA", "1000.00", 1, cogeRicavo, metodo, data, "ZZ ricavo invariante margine");
        creaMovimento("USCITA",   "400.00", 1, cogeCosto,  metodo, data, "ZZ costo invariante margine");

        Response r = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-07-31")
            .when().get("/api/dashboard/kpi")
            .then().statusCode(200).extract().response();

        float totalEntrate = ((Number) r.path("periodo.totalEntrate")).floatValue();
        float totalUscite  = ((Number) r.path("periodo.totalUscite")).floatValue();
        float margine      = ((Number) r.path("periodo.margine")).floatValue();

        assertTrue(totalEntrate > 0,
            "il ricavo appena creato deve comparire nelle entrate: totalEntrate=" + totalEntrate);
        assertEquals(totalEntrate - totalUscite, margine, 0.02f,
            "margine deve essere esattamente totalEntrate - totalUscite");
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomRange_marginePctInvariant() {
        // INVARIANTE: marginePct == (margine / totalEntrate) * 100
        // Periodo aggiornato a maggio 2026 per garantire totalEntrate > 0 (V26 ha
        // cancellato i seed V9 di gen-mar; V27 inserisce movimenti da maggio).
        Response r = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-05-31")
            .when().get("/api/dashboard/kpi")
            .then().statusCode(200).extract().response();

        float totalEntrate = toFloat(r.path("periodo.totalEntrate"));
        float margine      = toFloat(r.path("periodo.margine"));
        Object marginePctRaw = r.path("periodo.marginePct");

        if (totalEntrate > 0 && marginePctRaw != null) {
            float marginePct = toFloat(marginePctRaw);
            float expected = (margine / totalEntrate) * 100f;
            assertEquals(expected, marginePct, 0.1f,
                "marginePct deve essere margine/totalEntrate*100");
        }
    }

    @Test
    @Order(23)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiCustomRange_nMovimentiPositivi() {
        // Periodo aggiornato a maggio 2026: V26 ha cancellato i seed V9 di marzo;
        // V27 inserisce movimenti a partire da maggio 2026.
        given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-05-31")
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
    void fatturatoPerBu_totaleEntratePositivoConDatiV27() {
        // Periodo aggiornato a maggio 2026: V26 ha cancellato i seed V9; V27 inserisce
        // movimenti a partire da maggio 2026.
        List<Map<String, Object>> bus = given()
            .queryParam("period", "CUSTOM")
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-05-31")
            .when().get("/api/dashboard/fatturato-per-bu")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        float sumEntrate = 0f;
        for (Map<String, Object> bu : bus) {
            sumEntrate += toFloat(bu.get("totEntrate"));
        }
        assertTrue(sumEntrate > 0,
            "Con dati V27 maggio 2026, il totale entrate delle BU deve essere > 0");
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
    void scadenzeImminentiDefault_200_splitDTO() {
        given()
            .when().get("/api/dashboard/scadenze-imminenti")
            .then()
                .statusCode(200)
                .body("eventi", instanceOf(java.util.List.class))
                .body("rateRicorrenti", instanceOf(java.util.List.class));
    }

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminentiPeriodoInvalido_400() {
        given()
            .queryParam("period", "INVALID")
            .when().get("/api/dashboard/scadenze-imminenti")
            .then()
                .statusCode(400)
                .body("code", equalTo("INVALID_PERIOD"));
    }

    @Test
    @Order(62)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminentiCustomSenzaDate_400() {
        given()
            .queryParam("period", "CUSTOM")
            .when().get("/api/dashboard/scadenze-imminenti")
            .then()
                .statusCode(400)
                .body("code", equalTo("MISSING_RANGE"));
    }

    @Test
    @Order(63)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void scadenzeImminenti_urgenzaValida() {
        io.restassured.path.json.JsonPath jp = given()
            .when().get("/api/dashboard/scadenze-imminenti")
            .then().statusCode(200)
            .extract().jsonPath();

        List<Map<String, Object>> eventi       = jp.getList("eventi");
        List<Map<String, Object>> rateRicorr   = jp.getList("rateRicorrenti");

        List<Map<String, Object>> tutti = new java.util.ArrayList<>();
        tutti.addAll(eventi);
        tutti.addAll(rateRicorr);

        for (Map<String, Object> s : tutti) {
            String urgenza = (String) s.get("urgenza");
            assertTrue(urgenza != null && List.of("ALTA","MEDIA","BASSA").contains(urgenza),
                "urgenza deve essere ALTA|MEDIA|BASSA, trovato: " + urgenza);
            String stato = (String) s.get("stato");
            assertTrue(stato != null && List.of("PENDING","PAID").contains(stato),
                "stato deve essere PENDING|PAID, trovato: " + stato);
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

    /**
     * Fase 5 / decisione C — il conto economico e' mensile e lo dice.
     *
     * Prima di questo controllo, {@code from=2026-07-10&to=2026-07-20} restituiva 200 con TUTTO
     * luglio: {@code mv_conto_economico_mensile} aggrega per {@code anno*100+mese} e la parte
     * giorno del range cadeva in silenzio. Misurato sul dump di produzione del 21/08/2026:
     * sulla finestra 01/07 -> 20/08 il P&L rispondeva 19.691,75 contro i 20.191,75 della
     * dashboard, che filtra per giorno esatto — 500,00 EUR di scarto fra due schermate della
     * stessa app.
     *
     * CONTROPROVA ESEGUITA: togliendo la chiamata a validateRangeMensile questo test fallisce
     * con "expected 400 but was 200".
     */
    /**
     * REGRESSIONE Fase 4 — i grafici «Andamento Mensile» e «Fatturato per BU» leggono la BANCA
     * (data_movimento, importo lordo, nessun filtro su pc.tipo): una riga senza data_finanziaria
     * non e' denaro transitato e non deve comparirci.
     *
     * MISURATO IN PRODUZIONE il 21/08/2026: appena nate le righe di ricavo maturato-non-incassato,
     * le entrate di luglio del grafico passavano da 32.439,14 a 48.005,14 — 15.566,00 € di soldi
     * mai arrivati in conto, mostrati come incassi.
     *
     * Non risolve il difetto di fondo (restano grafici di banca sotto un header economico, misura
     * del 20/08 §8): impedisce che peggiori.
     *
     * ⚠️ Il movimento si crea e si annulla dalla REST API, NON con SQL diretto: l'endpoint e'
     * `@CacheResult` e una scrittura fuori dal service non invalida la cache — la prima versione
     * di questo test passava anche SENZA il fix, perche' rileggeva il valore in cache. Scoperto
     * eseguendo la controprova.
     * CONTROPROVA ESEGUITA: togliendo il filtro `data_finanziaria IS NOT NULL` dalle due query di
     * DashboardService questo test va rosso (4.321,00 di differenza).
     */
    @Test
    @Order(78)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void grafichiDiBanca_ignoranoLeRigheSenzaDataFinanziaria() {
        java.math.BigDecimal prima = entrateFatturatoBu();

        int cogeRicaviEventi = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02.002'")
                .getSingleResult()).intValue();

        String movId = given().contentType(ContentType.JSON)
                .body("""
                      {"tipo":"ENTRATA","importo":4321.00,"dataMovimento":"2026-07-15",
                       "dataCompetenza":"2026-07-15","dataLiquidita":"2026-07-15",
                       "businessUnitId":2,"contoCoge":%d,
                       "descrizione":"[TEST] credito senza banca"}
                      """.formatted(cogeRicaviEventi))
                .when().post("/api/movimenti")
                .then().statusCode(201).extract().path("id");
        try {
            assertEquals(0, prima.compareTo(entrateFatturatoBu()),
                    "una riga senza data_finanziaria non e' denaro entrato: il grafico di banca non deve vederla");
        } finally {
            given().when().delete("/api/movimenti/" + movId).then().statusCode(204);
        }
    }

    /**
     * REGRESSIONE Fase 4, secondo caso — il ramo WEEK di /cashflow/storico aggrega per
     * `data_movimento` e non filtrava la data finanziaria: le righe di ricavo maturato-non-incassato
     * ci entravano come se fossero soldi arrivati in conto.
     *
     * MISURATO IN PRODUZIONE il 21/08/2026: stessa chiamata, stesso periodo, entrate di luglio
     * 42.059,14 con granularity=MONTH e 48.005,14 con WEEK.
     *
     * Il test verifica che le righe senza data finanziaria restino fuori. La divergenza di fondo
     * fra i due rami — 9.620,00 € di scarto strutturale su luglio — è stata chiusa il 21/08/2026
     * portando anche WEEK su `data_finanziaria`: la copre
     * {@link #cashFlowWEEK_coincideConMONTH_sullaStessaFinestra()}.
     * CONTROPROVA ESEGUITA: togliendo il filtro questo test va rosso.
     */
    @Test
    @Order(79)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowWEEK_ignoraLeRigheSenzaDataFinanziaria() {
        java.math.BigDecimal prima = entrateCashFlowWeek();

        int cogeRicaviEventi = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02.002'")
                .getSingleResult()).intValue();

        String movId = given().contentType(ContentType.JSON)
                .body("""
                      {"tipo":"ENTRATA","importo":1234.00,"dataMovimento":"2026-07-15",
                       "dataCompetenza":"2026-07-15","dataLiquidita":"2026-07-15",
                       "businessUnitId":2,"contoCoge":%d,
                       "descrizione":"[TEST] credito senza banca, cash flow"}
                      """.formatted(cogeRicaviEventi))
                .when().post("/api/movimenti")
                .then().statusCode(201).extract().path("id");
        try {
            assertEquals(0, prima.compareTo(entrateCashFlowWeek()),
                    "il cash flow conta i soldi transitati, non i crediti aperti");
        } finally {
            given().when().delete("/api/movimenti/" + movId).then().statusCode(204);
        }
    }

    /**
     * Stessa domanda, stessa finestra, stessa risposta: su un periodo di mesi interi la somma dei
     * secchi WEEK deve coincidere con quella dei secchi MONTH, entrate e uscite.
     *
     * Prima del 21/08/2026 non coincidevano: MONTH aggrega {@code mv_cash_flow_statement} per
     * {@code data_finanziaria}, WEEK aggregava {@code movimenti} per {@code data_movimento}. Due
     * basi diverse per la stessa domanda, misurate su luglio 2026 in produzione: 42.059,14 contro
     * 32.439,14, 9.620,00 € di scarto strutturale. Decisione dell'utente: WEEK si allinea a MONTH.
     *
     * Non basta cambiare colonna. La MV somma tre secchi (operativo + investimento + finanziario)
     * e una riga {@code ATTIVITA} non-capex non cade in nessuno: su luglio è il giroconto da 300,00
     * fra i due conti del 06/07 (COGE 10.03.001), che con la sola colonna cambiata teneva WEEK a
     * 42.359,14 / 49.336,83. Per questo la query replica anche i JOIN e il predicato della MV.
     *
     * CONTROPROVA ESEGUITA: riportando {@code getCashFlowSettimanale} su {@code data_movimento}
     * questo test va rosso.
     */
    @Test
    @Order(80)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowWEEK_coincideConMONTH_sullaStessaFinestra() {
        var week  = cashFlowStorico("WEEK");
        var month = cashFlowStorico("MONTH");

        assertEquals(0, week[0].compareTo(month[0]),
                "entrate di luglio: WEEK " + week[0] + " contro MONTH " + month[0]);
        assertEquals(0, week[1].compareTo(month[1]),
                "uscite di luglio: WEEK " + week[1] + " contro MONTH " + month[1]);
    }

    /**
     * L'altra metà del fix del 21/08/2026: un giroconto non è un flusso di cassa, ed è la ragione
     * per cui portare WEEK su {@code data_finanziaria} non basta a farlo coincidere con MONTH.
     *
     * {@code mv_cash_flow_statement} somma tre secchi (operativo, investimento, finanziario) e una
     * riga {@code ATTIVITA} non-capex non cade in nessuno: sparisce, correttamente. La query WEEK
     * replica quel predicato. Senza, su luglio 2026 in produzione il giroconto da 300,00 del 06/07
     * (COGE 10.03.001) teneva WEEK a 42.359,14 / 49.336,83 contro i 42.059,14 / 49.036,83 di MONTH.
     *
     * Serve un test suo perché {@link #cashFlowWEEK_coincideConMONTH_sullaStessaFinestra()} non lo
     * copre: nel DB di test non esiste una riga ATTIVITA non-capex a luglio, e togliendo il
     * predicato quel test resta verde (CONTROPROVA ESEGUITA il 21/08). Qui la riga la creo io.
     *
     * CONTROPROVA ESEGUITA: togliendo il predicato dalla query questo test va rosso.
     */
    @Test
    @Order(81)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void cashFlowWEEK_escludeIGiroconti_comeLaMV() {
        var prima = cashFlowStorico("WEEK");

        int cogeGiroconto = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03.001'")
                .getSingleResult()).intValue();
        int metodo = ((Number) em.createNativeQuery(
                "SELECT MIN(id) FROM metodi_pagamento").getSingleResult()).intValue();

        String movId = given().contentType(ContentType.JSON)
                .body("""
                      {"tipo":"USCITA","importo":777.00,"dataMovimento":"2026-07-15",
                       "dataCompetenza":"2026-07-15","dataFinanziaria":"2026-07-15",
                       "dataLiquidita":"2026-07-15","contoBancarioId":1,"metodoPagamentoId":%d,
                       "businessUnitId":2,"contoCoge":%d,
                       "descrizione":"[TEST] giroconto, non e' un flusso di cassa"}
                      """.formatted(metodo, cogeGiroconto))
                .when().post("/api/movimenti")
                .then().statusCode(201).extract().path("id");
        try {
            var dopo = cashFlowStorico("WEEK");
            assertEquals(0, prima[1].compareTo(dopo[1]),
                    "un giroconto non è un'uscita di cassa: uscite " + prima[1] + " → " + dopo[1]);
            assertEquals(0, prima[0].compareTo(dopo[0]),
                    "il giroconto non deve toccare nemmeno le entrate");
        } finally {
            given().when().delete("/api/movimenti/" + movId).then().statusCode(204);
        }
    }

    /** Totali {entrate, uscite} di luglio 2026 da /cashflow/storico alla granularità data. */
    private java.math.BigDecimal[] cashFlowStorico(String granularity) {
        java.util.List<java.util.Map<String, Object>> righe = given()
                .queryParam("from", "2026-07-01").queryParam("to", "2026-07-31")
                .queryParam("granularity", granularity)
                .when().get("/api/reporting/cashflow/storico")
                .then().statusCode(200).extract().jsonPath().getList("$");
        java.math.BigDecimal entrate = java.math.BigDecimal.ZERO;
        java.math.BigDecimal uscite  = java.math.BigDecimal.ZERO;
        for (var r : righe) {
            entrate = entrate.add(new java.math.BigDecimal(r.get("entrate").toString()));
            uscite  = uscite.add(new java.math.BigDecimal(r.get("uscite").toString()));
        }
        return new java.math.BigDecimal[] { entrate, uscite };
    }

    /** Somma delle entrate di luglio 2026 secondo /cashflow/storico?granularity=WEEK. */
    private java.math.BigDecimal entrateCashFlowWeek() {
        java.util.List<java.util.Map<String, Object>> righe = given()
                .queryParam("from", "2026-07-01").queryParam("to", "2026-07-31")
                .queryParam("granularity", "WEEK")
                .when().get("/api/reporting/cashflow/storico")
                .then().statusCode(200).extract().jsonPath().getList("$");
        java.math.BigDecimal tot = java.math.BigDecimal.ZERO;
        for (var r : righe) tot = tot.add(new java.math.BigDecimal(r.get("entrate").toString()));
        return tot;
    }

    /** Somma delle entrate di luglio 2026 secondo /api/dashboard/fatturato-per-bu. */
    private java.math.BigDecimal entrateFatturatoBu() {
        java.util.List<java.util.Map<String, Object>> righe = given()
                .queryParam("from", "2026-07-01").queryParam("to", "2026-07-31")
                .when().get("/api/dashboard/fatturato-per-bu")
                .then().statusCode(200).extract().jsonPath().getList("$");
        java.math.BigDecimal tot = java.math.BigDecimal.ZERO;
        for (var r : righe) tot = tot.add(new java.math.BigDecimal(r.get("totEntrate").toString()));
        return tot;
    }

    @Test
    @Order(74)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plRangeParzialeDentroUnMese_400() {
        given()
            .queryParam("from", "2026-07-10")
            .queryParam("to", "2026-07-20")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(400)
                .body("code", equalTo("RANGE_NON_MENSILE"));
    }

    @Test
    @Order(75)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plRangeCheFinisceAMetaMese_400() {
        // Il caso vero dei preset MTD/QTD/YTD: "dal primo del mese a oggi".
        given()
            .queryParam("from", "2026-07-01")
            .queryParam("to", "2026-08-20")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(400)
                .body("code", equalTo("RANGE_NON_MENSILE"));
    }

    @Test
    @Order(76)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plTutteBuRangeParziale_400() {
        given()
            .queryParam("from", "2026-07-10")
            .queryParam("to", "2026-07-20")
            .when().get("/api/reporting/pl/tutte-bu")
            .then()
                .statusCode(400)
                .body("code", equalTo("RANGE_NON_MENSILE"));
    }

    @Test
    @Order(77)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void plMeseInteroPassa_200() {
        // Il mese intero resta l'unica forma accettata, e continua a rispondere 200.
        given()
            .queryParam("from", "2026-07-01")
            .queryParam("to", "2026-07-31")
            .when().get("/api/reporting/pl")
            .then()
                .statusCode(200)
                .body("ricavi", notNullValue());
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
        // Periodo aggiornato a maggio 2026: V26 ha cancellato i seed V9; V27 inserisce
        // movimenti a partire da maggio 2026.
        given()
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-05-31")
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
        // Periodo aggiornato a maggio-luglio 2026: V26 ha cancellato i seed V9
        // di gen-mar; V27 inserisce movimenti a partire da maggio 2026.
        List<Map<String, Object>> periodi = given()
            .queryParam("from", "2026-05-01")
            .queryParam("to", "2026-07-31")
            .queryParam("granularity", "MONTH")
            .when().get("/api/reporting/cashflow/storico")
            .then().statusCode(200)
            .extract().jsonPath().getList("$");

        assertTrue(periodi.size() >= 1,
            "Con dati V27 mag-lug 2026, atteso almeno 1 periodo mensile (la MV CF " +
            "raggruppa per data_finanziaria — i movimenti DA_LIQUIDARE non compaiono)");

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
    // HELPER
    // ═══════════════════════════════════════════════════════════

    private static float toFloat(Object o) {
        if (o == null) return 0f;
        if (o instanceof Number n) return n.floatValue();
        return Float.parseFloat(o.toString());
    }

    // ═══════════════════════════════════════════════════════════
    // KPI economici: solo conti RICAVO/COSTO (invariante DRY col P&L)
    // ═══════════════════════════════════════════════════════════

    /**
     * Un giroconto e' denaro che cambia tasca, non un ricavo ne' un costo: il P&L lo
     * esclude via "pc.tipo IN ('RICAVO','COSTO')" (V3), la sezione "Performance
     * Economica" della dashboard deve fare lo stesso. Misurato su agosdb prima del fix:
     * dashboard entrate 32.631,14 contro ricavi P&L 32.159,41 nello stesso luglio 2026,
     * di cui 300,00 erano UN SOLO giroconto CA->BPM contato sia come entrata sia come uscita.
     * CoGe 10.03.001 = 'Giroconto da Credit Agricole a Banco BPM', tipo ATTIVITA (seed V4).
     */
    @Test
    @Order(70)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiEconomici_giroconto_nonEntraNeEntrateNeUscite() {
        String oggi = java.time.LocalDate.now().toString();
        String kpi = "/api/dashboard/kpi?from=%s&to=%s&period=CUSTOM".formatted(oggi, oggi);

        double entratePre = kpiNum(kpi, "periodo.totalEntrate");
        double uscitePre  = kpiNum(kpi, "periodo.totalUscite");

        int cogeGiroconto = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03.001'")
                .getSingleResult()).intValue();
        int metodo = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'")
                .getSingleResult()).intValue();

        creaMovimento("USCITA",  "300.00", 2, cogeGiroconto, metodo, oggi, "ZZ giroconto uscita");
        creaMovimento("ENTRATA", "300.00", 1, cogeGiroconto, metodo, oggi, "ZZ giroconto entrata");

        assertEquals(entratePre, kpiNum(kpi, "periodo.totalEntrate"), 0.001,
                "un giroconto ATTIVITA non e' un ricavo: non deve alzare le Entrate della dashboard");
        assertEquals(uscitePre, kpiNum(kpi, "periodo.totalUscite"), 0.001,
                "un giroconto ATTIVITA non e' un costo: non deve alzare le Uscite della dashboard");
    }

    /**
     * Stessa grandezza, una sola verita': le Entrate/Uscite della sezione economica
     * devono coincidere con ricavi/costi del Conto Economico sullo stesso periodo.
     */
    @Test
    @Order(71)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiEconomici_coincidonoColContoEconomico() throws Exception {
        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        tx.commit();

        java.time.LocalDate primo = java.time.LocalDate.now().withDayOfMonth(1);
        java.time.LocalDate ultimo = primo.plusMonths(1).minusDays(1);
        String kpi = "/api/dashboard/kpi?from=%s&to=%s&period=CUSTOM".formatted(primo, ultimo);

        double ricaviPl = ((Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(ricavi),0) FROM mv_conto_economico_mensile WHERE anno = :a AND mese = :m")
                .setParameter("a", primo.getYear()).setParameter("m", primo.getMonthValue())
                .getSingleResult()).doubleValue();
        double costiPl = ((Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(costi_operativi),0) FROM mv_conto_economico_mensile WHERE anno = :a AND mese = :m")
                .setParameter("a", primo.getYear()).setParameter("m", primo.getMonthValue())
                .getSingleResult()).doubleValue();

        assertEquals(ricaviPl, kpiNum(kpi, "periodo.totalEntrate"), 0.011,
                "Entrate dashboard e Ricavi del Conto Economico misurano la stessa cosa");
        assertEquals(costiPl, kpiNum(kpi, "periodo.totalUscite"), 0.011,
                "Uscite dashboard e Costi del Conto Economico misurano la stessa cosa");
    }

    /**
     * V39 — storno di ricavo (USCITA su conto RICAVO): il Conto Economico lo sottrae dai ricavi,
     * e la sezione "Performance Economica" deve fare lo stesso. Prima dell'allineamento di
     * queryKpiDirect la dashboard ignorava la riga e restava piu' alta del P&amp;L esattamente
     * dell'importo stornato (misurato in prod: 500,00 EUR «carne» sul conto 30.03.001).
     */
    @Test
    @Order(72)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void kpiEconomici_stornoRicavo_restaAllineatoAlContoEconomico() throws Exception {
        java.time.LocalDate oggi = java.time.LocalDate.now();
        java.time.LocalDate primo = oggi.withDayOfMonth(1);
        java.time.LocalDate ultimo = primo.plusMonths(1).minusDays(1);
        String kpi = "/api/dashboard/kpi?from=%s&to=%s&period=CUSTOM".formatted(primo, ultimo);

        double entratePre = kpiNum(kpi, "periodo.totalEntrate");

        int cogeRicavo = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = '30.01.001'")
                .getSingleResult()).intValue();
        int metodo = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'")
                .getSingleResult()).intValue();

        creaMovimento("ENTRATA", "1000.00", 1, cogeRicavo, metodo, oggi.toString(), "ZZ ricavo base");
        creaMovimento("USCITA",   "400.00", 1, cogeRicavo, metodo, oggi.toString(), "ZZ storno ricavo (USCITA su conto RICAVO)");

        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        tx.commit();

        double ricaviPl = ((Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(ricavi),0) FROM mv_conto_economico_mensile WHERE anno = :a AND mese = :m")
                .setParameter("a", primo.getYear()).setParameter("m", primo.getMonthValue())
                .getSingleResult()).doubleValue();

        assertEquals(entratePre + 600.00, kpiNum(kpi, "periodo.totalEntrate"), 0.011,
                "V39: lo storno deve ridurre le Entrate della dashboard (1000 - 400), non essere ignorato");
        assertEquals(ricaviPl, kpiNum(kpi, "periodo.totalEntrate"), 0.011,
                "V39: dashboard e Conto Economico restano la stessa grandezza anche con uno storno di ricavo");
    }

    private double kpiNum(String url, String path) {
        return ((Number) given().when().get(url).then().statusCode(200).extract().path(path)).doubleValue();
    }

    private void creaMovimento(String tipo, String importo, int conto, int coge,
                               int metodo, String data, String descrizione) {
        given().contentType("application/json")
            .body("""
                    {"tipo":"%s","importo":%s,"dataMovimento":"%s","dataFinanziaria":"%s",
                     "contoBancarioId":%d,"metodoPagamentoId":%d,"businessUnitId":1,
                     "contoCoge":%d,"descrizione":"%s"}
                    """.formatted(tipo, importo, data, data, conto, metodo, coge, descrizione))
            .when().post("/api/movimenti")
            .then().statusCode(201);
    }
}
