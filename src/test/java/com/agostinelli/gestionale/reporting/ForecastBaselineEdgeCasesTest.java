package com.agostinelli.gestionale.reporting;

import com.agostinelli.gestionale.reporting.dto.ForecastingDettaglioDTO;
import com.agostinelli.gestionale.reporting.dto.ForecastingRispostaDTO;
import com.agostinelli.gestionale.reporting.service.ForecastingService;
import com.agostinelli.gestionale.reporting.scheduler.ForecastBaselineService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Casi limite del layer STIMATO: mappatura giorno-della-settimana, finestra mobile, soglia n_giorni,
 * multi-conto, idempotenza del ricalcolo e contratto JSON dell'endpoint. Ogni test è autonomo:
 * ripulisce i conti allowlist nella finestra, inserisce il proprio scenario e ricalcola.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForecastBaselineEdgeCasesTest {

    static final String MARKER = "ZZEDGE_BASELINE";
    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static final int RIST = 30, CARNE = 34, ORTO = 35;

    @Inject EntityManager em;
    @Inject ForecastBaselineService baselineService;
    @Inject ForecastingService forecastingService;

    static final LocalDate OGGI = LocalDate.now();

    // ── DOW mapping: solo sabati → dow=6, e la proiezione conta i sabati ───────

    @Test
    void dowMapping_sabato_postgres6() {
        reset();
        LocalDate sab = OGGI.with(TemporalAdjusters.previous(DayOfWeek.SATURDAY));
        for (int i = 0; i < 4; i++) mov(RIST, "ENTRATA", "200.00", sab.minusWeeks(i));
        baselineService.recompute();

        Object[] row = (Object[]) em.createNativeQuery(
                "SELECT dow, media_attesa, n_giorni FROM forecast_baseline WHERE conto_coge_id=:c")
                .setParameter("c", RIST).getSingleResult();
        assertEquals(6, ((Number) row[0]).intValue(), "sabato → dow Postgres 6");
        assertEquals(0, new BigDecimal("200.00").compareTo((BigDecimal) row[1]));

        BigDecimal stima = sommaStimata(forecastingService.computeForecasting("90"));
        assertEquals(0, BigDecimal.valueOf(200L * contaDow(DayOfWeek.SATURDAY, 90)).compareTo(stima),
                "la stima proietta 200 su ogni sabato futuro entro 90gg");
    }

    // ── Finestra mobile: movimenti fuori da 8 settimane esclusi ────────────────

    @Test
    void finestraMobile_escludeFuoriOttoSettimane() {
        reset();
        // current_date - make_interval(weeks=>8) = oggi-56. Dentro: oggi-50. Fuori: oggi-70.
        mov(CARNE, "ENTRATA", "100.00", OGGI.minusDays(70));   // fuori finestra
        mov(CARNE, "ENTRATA", "100.00", OGGI.minusDays(50));   // dentro finestra
        baselineService.recompute();

        Number n = (Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(n_giorni),0) FROM forecast_baseline WHERE conto_coge_id=:c")
                .setParameter("c", CARNE).getSingleResult();
        assertEquals(1L, n.longValue(), "solo il movimento dentro la finestra di 8 settimane è contato");
    }

    // ── Soglia n_giorni: segmento con esattamente min-giorni incluso, sotto escluso ──

    @Test
    void soglia_nGiorni_boundary_4incluso_3escluso() {
        reset();
        LocalDate lun = OGGI.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate mer = OGGI.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
        for (int i = 0; i < 4; i++) mov(CARNE, "ENTRATA", "100.00", lun.minusWeeks(i)); // 4 lunedì → soglia ok
        for (int i = 0; i < 3; i++) mov(CARNE, "ENTRATA", "100.00", mer.minusWeeks(i)); // 3 mercoledì → sotto
        baselineService.recompute();

        BigDecimal stima = sommaStimata(forecastingService.computeForecasting("90"));
        BigDecimal atteso = BigDecimal.valueOf(100L * contaDow(DayOfWeek.MONDAY, 90));
        assertEquals(0, atteso.compareTo(stima),
                "min-giorni=4: i lunedì (4gg) contano, i mercoledì (3gg) no");
    }

    // ── Multi-conto: una riga stimata per ciascun conto allowlist ──────────────

    @Test
    void multiConto_unaRigaStimataPerConto() {
        reset();
        LocalDate mar = OGGI.with(TemporalAdjusters.previous(DayOfWeek.TUESDAY));
        for (int i = 0; i < 4; i++) {
            mov(RIST,  "ENTRATA", "100.00", mar.minusWeeks(i));
            mov(CARNE, "ENTRATA", "50.00",  mar.minusWeeks(i));
            mov(ORTO,  "ENTRATA", "30.00",  mar.minusWeeks(i));
        }
        baselineService.recompute();

        List<ForecastingDettaglioDTO> stimate = forecastingService.computeForecasting("90")
                .economico().dettaglio().stream()
                .filter(d -> "STIMATO".equals(d.affidabilita())).toList();
        assertEquals(3, stimate.size(), "tre conti allowlist → tre righe stima aggregate");
        assertTrue(stimate.stream().allMatch(d -> d.descrizione().startsWith("Stima")));
        // ogni riga = media × #martedì futuri entro 90gg
        long martedi = contaDow(DayOfWeek.TUESDAY, 90);
        assertTrue(stimate.stream().anyMatch(d ->
                d.importoEntrata().compareTo(BigDecimal.valueOf(100L * martedi)) == 0), "conto ristorazione 100×");
        assertTrue(stimate.stream().anyMatch(d ->
                d.importoEntrata().compareTo(BigDecimal.valueOf(50L * martedi)) == 0), "conto carne 50×");
        assertTrue(stimate.stream().anyMatch(d ->
                d.importoEntrata().compareTo(BigDecimal.valueOf(30L * martedi)) == 0), "conto ortofrutta 30×");
    }

    // ── Idempotenza + rimozione segmenti spariti (DELETE+INSERT) ───────────────

    @Test
    void recompute_idempotente_eRimuoveSegmentiSpariti() {
        reset();
        LocalDate gio = OGGI.with(TemporalAdjusters.previous(DayOfWeek.THURSDAY));
        for (int i = 0; i < 4; i++) mov(ORTO, "ENTRATA", "70.00", gio.minusWeeks(i));

        int n1 = baselineService.recompute();
        int n2 = baselineService.recompute();
        assertEquals(n1, n2, "ricalcolo idempotente: stesso numero di segmenti");
        assertTrue(countBaseline(ORTO) > 0);

        // I dati spariscono dalla finestra → il segmento non deve restare stantio
        purgeConti();
        baselineService.recompute();
        assertEquals(0L, countBaseline(ORTO), "DELETE+INSERT: segmento senza più dati rimosso");
    }

    // ── Contratto JSON: l'endpoint espone i nuovi campi additivi ───────────────

    @Test
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void endpoint_espone_affidabilita_e_entrateStimate() {
        reset();
        LocalDate ven = OGGI.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
        for (int i = 0; i < 4; i++) mov(RIST, "ENTRATA", "150.00", ven.minusWeeks(i));
        baselineService.recompute();

        given().queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
                .body("economico.dettaglio.affidabilita", hasItem("STIMATO"))
                .body("economico.dettaglio.findAll { it.affidabilita == 'STIMATO' }.categoria", everyItem(equalTo("MOVIMENTO")))
                .body("finanziario.timeline[0]", hasKey("entrateStimate"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private BigDecimal sommaStimata(ForecastingRispostaDTO r) {
        return r.economico().dettaglio().stream()
                .filter(d -> "STIMATO".equals(d.affidabilita()))
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private long contaDow(DayOfWeek dow, int giorni) {
        long n = 0;
        for (LocalDate d = OGGI.plusDays(1); !d.isAfter(OGGI.plusDays(giorni)); d = d.plusDays(1))
            if (d.getDayOfWeek() == dow) n++;
        return n;
    }

    private long countBaseline(int conto) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM forecast_baseline WHERE conto_coge_id=:c")
                .setParameter("c", conto).getSingleResult()).longValue();
    }

    private void reset() { purgeConti(); }

    @Transactional
    void purgeConti() {
        em.createNativeQuery(
                "DELETE FROM movimenti WHERE conto_coge_id IN (30,34,35,36) " +
                "AND data_movimento >= current_date - INTERVAL '200 days'").executeUpdate();
    }

    @Transactional
    void mov(int coge, String tipo, String importo, LocalDate data) {
        em.createNativeQuery(
                "INSERT INTO movimenti (id, data_movimento, tipo, importo_lordo, importo_commissione, " +
                "data_competenza, conto_coge_id, business_unit_id, descrizione, stato, fonte, created_by, created_at) " +
                "VALUES (gen_random_uuid(), :d, :t, CAST(:imp AS numeric), 0, :d, :coge, 1, :descr, 'REGISTRATO', 'MANUALE', CAST(:u AS uuid), now())")
                .setParameter("d", data).setParameter("t", tipo).setParameter("imp", importo)
                .setParameter("coge", coge).setParameter("descr", MARKER)
                .setParameter("u", TEST_USER).executeUpdate();
    }

    @AfterAll
    void cleanup() {
        cleanupTx();
    }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione = :m")
                .setParameter("m", MARKER).executeUpdate();
        baselineService.recompute();
    }
}
