package com.agostinelli.gestionale.anagrafica;

import com.agostinelli.gestionale.reporting.scheduler.MvRefreshRunner;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Acquisto operativo cespite (SPEC cespiti-acquisto-ammortamento): il flusso crea il cespite E
 * il movimento di acquisto CAPEX collegato, con parte finanziaria ed economica coerenti.
 * Conto CAPEX seed: 50.01.001 (id 72).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CespitiAcquistoIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    static final int CONTO_CAPEX = 72;

    @Inject EntityManager em;
    @Inject MvRefreshRunner mvRunner;

    /** I test "pagato" richiedono fondi: dà capienza al primo conto (guardia FONDI_INSUFFICIENTI). */
    @BeforeAll
    @Transactional
    void fundAccount() {
        em.createNativeQuery(
                "UPDATE conti_bancari SET saldo_iniziale = 1000000 " +
                "WHERE id = (SELECT id FROM conti_bancari ORDER BY id LIMIT 1)").executeUpdate();
    }

    /** Pulisce cespiti di test e i loro movimenti (marker ZZAQ; gestisce la FK cespite_id). */
    @AfterAll
    void cleanup() { cleanupTx(); }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM movimenti WHERE cespite_id IN " +
                "(SELECT id FROM cespiti WHERE descrizione LIKE 'ZZAQ%')").executeUpdate();
        em.createNativeQuery("DELETE FROM cespiti WHERE descrizione LIKE 'ZZAQ%'").executeUpdate();
    }

    private static String today() { return LocalDate.now().toString(); }

    private static <T> T tx(java.util.function.Supplier<T> s) {
        try {
            return QuarkusTransaction.requiringNew().call(s::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private int firstId(String sql) {
        return tx(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).intValue());
    }

    // ── I1 + I4: acquisto pagato → cespite + movimento USCITA capex REGISTRATO collegato ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_pagato_creaCespiteEMovimentoCapex() {
        int metodo = firstId("SELECT id FROM metodi_pagamento ORDER BY id LIMIT 1");
        int banca  = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");

        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Lavastoviglie 10k","contoCogeId":%d,"costoStorico":10000,"vitaAnni":2,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d,"metodoPagamentoId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca, metodo))
                .when().post("/api/cespiti/acquisto")
                .then().statusCode(201)
                    .body("aliquotaAmmortamento", comparesEqualTo(50.0f))   // 100/2 anni
                    .body("ammortamentoMensile",  comparesEqualTo(416.67f)) // 10000*50/1200
                    .body("statoPagamentoAcquisto", equalTo("REGISTRATO"))
                    .body("movimentoAcquistoId", notNullValue())
                    .extract().path("id");

        // Il movimento collegato è USCITA, importo 10.000, su conto CAPEX (is_capex=true)
        Object[] r = tx(() -> (Object[]) em.createNativeQuery("""
                SELECT m.tipo, m.importo_lordo, pc.is_capex
                FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id
                WHERE m.cespite_id = CAST(:cid AS uuid)
                """).setParameter("cid", id).getSingleResult());
        assertEquals("USCITA", r[0]);
        assertEquals(0, new BigDecimal("10000.00").compareTo((BigDecimal) r[1]));
        assertEquals(Boolean.TRUE, r[2], "l'acquisto deve capitalizzarsi su conto CAPEX");
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_nonPagato_daLiquidare() {
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Forno da liquidare","contoCogeId":%d,"costoStorico":6000,"vitaAnni":5,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(CONTO_CAPEX, today()))
                .when().post("/api/cespiti/acquisto")
                .then().statusCode(201)
                    .body("aliquotaAmmortamento", comparesEqualTo(20.0f))  // 100/5
                    .body("statoPagamentoAcquisto", equalTo("DA_LIQUIDARE"));
    }

    // ── I1: conto non-CAPEX rifiutato (previene il booking come costo operativo) ──────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_contoNonCapex_400() {
        int contoCosto = firstId(
                "SELECT id FROM piano_dei_conti_coge WHERE is_capex = false AND tipo='COSTO' AND is_active=true ORDER BY id LIMIT 1");
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Errato","contoCogeId":%d,"costoStorico":1000,"vitaAnni":2,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(contoCosto, today()))
                .when().post("/api/cespiti/acquisto")
                .then().statusCode(400);
    }

    // ── I2: l'acquisto è capex → NON tocca l'EBITDA (nessun doppio conteggio con l'ammortamento) ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_capex_nonToccaEbitda_maAlzaCapex() {
        int y = LocalDate.now().getYear();
        String pl = "/api/reporting/pl?from=%d-01-01&to=%d-12-31".formatted(y, y);

        mvRunner.refresh();
        double ebitdaPre = plNum(pl, "ebitda");
        double capexPre  = plNum(pl, "costi.capex");
        double costiPre  = plNum(pl, "costi.totale");

        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Capex EBITDA","contoCogeId":%d,"costoStorico":8000,"vitaAnni":4,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca))
                .when().post("/api/cespiti/acquisto").then().statusCode(201);

        mvRunner.refresh();
        double ebitdaPost = plNum(pl, "ebitda");
        double capexPost  = plNum(pl, "costi.capex");
        double costiPost  = plNum(pl, "costi.totale");

        assertEquals(ebitdaPre, ebitdaPost, 0.001, "il capex non deve entrare nell'EBITDA");
        assertEquals(capexPre + 8000, capexPost, 0.001, "l'acquisto è capitalizzato tra gli investimenti");
        // Regressione: il capex NON deve gonfiare i costi economici (bug: 10k mostrati come costo)
        assertEquals(costiPre, costiPost, 0.001, "il capex non deve entrare nei costi operativi del P&L");
    }

    // ── Guardia fondi: saldo insufficiente → 409; pagato senza conto → 400 ────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_fondiInsufficienti_409() {
        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1"); // fondato a 1M
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Troppo caro","contoCogeId":%d,"costoStorico":99000000,"vitaAnni":10,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca))
                .when().post("/api/cespiti/acquisto")
                .then().statusCode(409)
                    .body("code", equalTo("FONDI_INSUFFICIENTI"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_pagatoSenzaConto_400() {
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Senza conto","contoCogeId":%d,"costoStorico":1000,"vitaAnni":2,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s"}
                """.formatted(CONTO_CAPEX, today(), today()))
                .when().post("/api/cespiti/acquisto")
                .then().statusCode(400);
    }

    // ── I5: delete con acquisto liquidato → 409; con acquisto da liquidare → 204 ──────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void delete_acquistoLiquidato_409() {
        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Pagato non elim","contoCogeId":%d,"costoStorico":3000,"vitaAnni":3,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca))
                .when().post("/api/cespiti/acquisto").then().statusCode(201).extract().path("id");

        given().when().delete("/api/cespiti/" + id).then().statusCode(409);
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void delete_acquistoDaLiquidare_ok() {
        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Da liquidare elim","contoCogeId":%d,"costoStorico":2000,"vitaAnni":2,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(CONTO_CAPEX, today()))
                .when().post("/api/cespiti/acquisto").then().statusCode(201).extract().path("id");

        given().when().delete("/api/cespiti/" + id).then().statusCode(204);
        // il movimento pendente è stato annullato e sganciato (nessun blocco FK)
        long attivi = tx(() -> ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE cespite_id = CAST(:cid AS uuid) AND stato <> 'ANNULLATO'")
                .setParameter("cid", id).getSingleResult()).longValue());
        assertEquals(0, attivi);
    }

    // ── Forecasting: la quota ammortamento compare nel Dettaglio Voci Previsionali come UNA riga
    //    per mese (convenzione P&L: quota mensile fissa a fine mese) e FUORI dai costi operativi ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_compareInForecastingDettaglio_quotaMensile() {
        // horizon 60: finestra ≥ 60 gg contiene sempre ≥ 1 fine-mese → il "find" sotto è sicuro.
        String fc = "/api/reporting/forecasting?horizon=60";
        double ammPre   = plNum(fc, "economico.ammortamentiPrevisti");
        double costiPre = plNum(fc, "economico.costiPrevisti");

        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        // 7300 € su 2 anni → aliquota 50% → quota mensile = 7300*50/1200 = 304.17
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Forecast quota","contoCogeId":%d,"costoStorico":7300,"vitaAnni":2,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca))
                .when().post("/api/cespiti/acquisto").then().statusCode(201);

        // Quante fine-mese cadono nella finestra [oggi+1, oggi+60] dentro la vita del cespite (24 mesi)?
        // Robusto rispetto al giorno di esecuzione: calcolo qui il delta atteso.
        LocalDate oggi = LocalDate.now();
        LocalDate start = oggi.plusDays(1);
        LocalDate end   = oggi.plusDays(60);
        YearMonth inizio   = YearMonth.from(oggi);
        YearMonth fineEscl = inizio.plusMonths(24);
        BigDecimal quota = new BigDecimal("304.17");
        int nQuote = 0;
        for (YearMonth ym = YearMonth.from(start); !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
            if (ym.isBefore(inizio) || !ym.isBefore(fineEscl)) continue;
            LocalDate last = ym.atEndOfMonth();
            if (!last.isBefore(start) && !last.isAfter(end)) nQuote++;
        }
        double delta = quota.multiply(BigDecimal.valueOf(nQuote)).doubleValue();

        given().when().get(fc).then().statusCode(200)
                .body("economico.dettaglio.find { it.categoria == 'AMMORTAMENTO' " +
                      "&& it.descrizione.contains('ZZAQ Forecast quota') }.importoUscita",
                      comparesEqualTo(304.17f));
        assertEquals(ammPre + delta, plNum(fc, "economico.ammortamentiPrevisti"), 0.01,
                "la quota mensile del nuovo cespite entra negli ammortamenti previsti (1 riga/mese)");
        assertEquals(costiPre, plNum(fc, "economico.costiPrevisti"), 0.01,
                "l'ammortamento sta tra EBITDA ed EBIT, non nei costi operativi previsti");
    }

    // ── R13: liquidazione differita di un acquisto DA_LIQUIDARE ───────────────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void liquidazione_daLiquidare_diventaRegistrato() {
        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Liquida felice","contoCogeId":%d,"costoStorico":4000,"vitaAnni":4,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(CONTO_CAPEX, today()))
                .when().post("/api/cespiti/acquisto").then().statusCode(201)
                    .body("statoPagamentoAcquisto", equalTo("DA_LIQUIDARE"))
                    .extract().path("id");

        given().contentType(ContentType.JSON).body("""
                {"contoBancarioId":%d}
                """.formatted(banca))
                .when().post("/api/cespiti/" + id + "/liquidazione")
                .then().statusCode(200)
                    .body("statoPagamentoAcquisto", equalTo("REGISTRATO"));

        // Il movimento è REGISTRATO con date finanziaria/liquidità e conto valorizzati
        Object[] r = tx(() -> (Object[]) em.createNativeQuery("""
                SELECT stato, data_finanziaria, data_liquidita, conto_bancario_id
                FROM movimenti WHERE cespite_id = CAST(:cid AS uuid) AND stato <> 'ANNULLATO'
                """).setParameter("cid", id).getSingleResult());
        assertEquals("REGISTRATO", r[0]);
        assertNotNull(r[1], "data_finanziaria valorizzata alla liquidazione");
        assertNotNull(r[2], "data_liquidita valorizzata alla liquidazione");
        assertNotNull(r[3], "conto_bancario_id valorizzato alla liquidazione");
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void liquidazione_doppia_409() {
        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Liquida doppia","contoCogeId":%d,"costoStorico":1500,"vitaAnni":3,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(CONTO_CAPEX, today()))
                .when().post("/api/cespiti/acquisto").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("{\"contoBancarioId\":%d}".formatted(banca))
                .when().post("/api/cespiti/" + id + "/liquidazione").then().statusCode(200);

        given().contentType(ContentType.JSON).body("{\"contoBancarioId\":%d}".formatted(banca))
                .when().post("/api/cespiti/" + id + "/liquidazione")
                .then().statusCode(409)
                    .body("code", equalTo("ACQUISTO_GIA_LIQUIDATO"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void liquidazione_fondiInsufficienti_409() {
        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1"); // fondato a 1M
        // Costo sopra il saldo (→ FONDI_INSUFFICIENTI) ma vita lunga: la quota ammortamento resta
        // piccola per non gonfiare ammortamentiPrevisti degli altri test (precisione JSON).
        String id = given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Liquida troppo caro","contoCogeId":%d,"costoStorico":2000000,"vitaAnni":50,
                 "dataAcquisto":"%s","businessUnitId":2}
                """.formatted(CONTO_CAPEX, today()))
                .when().post("/api/cespiti/acquisto").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("{\"contoBancarioId\":%d}".formatted(banca))
                .when().post("/api/cespiti/" + id + "/liquidazione")
                .then().statusCode(409)
                    .body("code", equalTo("FONDI_INSUFFICIENTI"));
    }

    // ── Dashboard: il capex non entra nei KPI economici (Costi/Margine); la cache si invalida ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void acquisto_capex_nonEntraNeiCostiDashboard() {
        String kpi = "/api/dashboard/kpi?from=%s&to=%s&period=MTD".formatted(today(), today());
        double uscitePre = plNum(kpi, "periodo.totalUscite");
        double margPre   = plNum(kpi, "periodo.margine");
        double nMovPre   = plNum(kpi, "periodo.nMovimenti");

        int banca = firstId("SELECT id FROM conti_bancari ORDER BY id LIMIT 1");
        given().contentType(ContentType.JSON).body("""
                {"descrizione":"ZZAQ Dash capex","contoCogeId":%d,"costoStorico":5000,"vitaAnni":5,
                 "dataAcquisto":"%s","businessUnitId":2,"dataPagamento":"%s","contoBancarioId":%d}
                """.formatted(CONTO_CAPEX, today(), today(), banca))
                .when().post("/api/cespiti/acquisto").then().statusCode(201);

        assertEquals(uscitePre, plNum(kpi, "periodo.totalUscite"), 0.001,
                "l'acquisto cespite non deve comparire nei Costi della dashboard (solo ammortamento)");
        assertEquals(margPre, plNum(kpi, "periodo.margine"), 0.001,
                "il margine economico non cambia all'acquisto di un cespite");
        // nMovimenti +1 dimostra che la cache dashboard-kpi è stata invalidata (no risposta stale)
        assertEquals(nMovPre + 1, plNum(kpi, "periodo.nMovimenti"), 0.001,
                "la cache dashboard-kpi deve essere invalidata dall'acquisto cespite");
    }

    private double plNum(String url, String path) {
        return ((Number) given().when().get(url).then().statusCode(200).extract().path(path)).doubleValue();
    }
}
