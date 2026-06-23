package com.agostinelli.gestionale.reporting;

import com.agostinelli.gestionale.reporting.dto.ForecastingDettaglioDTO;
import com.agostinelli.gestionale.reporting.dto.ForecastingRispostaDTO;
import com.agostinelli.gestionale.reporting.dto.ForecastingTimelineDTO;
import com.agostinelli.gestionale.reporting.service.ForecastingService;
import com.agostinelli.gestionale.reporting.scheduler.ForecastBaselineService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test del layer STIMATO del previsionale: ricalcolo {@code forecast_baseline} (media mobile per
 * giorno-della-settimana sui ricavi cash) e sua proiezione in {@link ForecastingService}.
 *
 * <p>Isolamento: i conti ricavo cash (30/34/35) sono condivisi con altri test → questa classe
 * marca i propri movimenti con {@link #MARKER}, li ripulisce in {@link #cleanup()} e resetta la
 * baseline. Le date sono ancorate a "oggi" così da cadere sempre nella finestra mobile.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForecastBaselineIntegrationTest {

    static final String MARKER = "ZZTEST_BASELINE";
    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";

    static final int CONTO_RIST = 30;   // 30.01.001 allowlist
    static final int CONTO_ALVEARE = 36; // 30.03.003 FUORI allowlist

    @Inject EntityManager em;
    @Inject ForecastBaselineService baselineService;
    @Inject ForecastingService forecastingService;

    // Date di riferimento (martedì e domeniche passate, dentro la finestra 8 settimane)
    static final LocalDate OGGI = LocalDate.now();
    static final LocalDate TUE0 = OGGI.with(TemporalAdjusters.previous(DayOfWeek.TUESDAY));
    static final LocalDate TUE1 = TUE0.minusWeeks(1);
    static final LocalDate TUE2 = TUE0.minusWeeks(2);
    static final LocalDate TUE3 = TUE0.minusWeeks(3);
    static final LocalDate SUN0 = OGGI.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
    static final LocalDate SUN1 = SUN0.minusWeeks(1);

    static volatile boolean seeded = false;

    @BeforeEach
    void seed() {
        if (seeded) return;
        purge();           // rimuove eventuale rumore di altri test sui conti allowlist nella finestra
        insertScenario();
        baselineService.recompute();
        seeded = true;
    }

    // ── Scenario ────────────────────────────────────────────────────────────

    @Transactional
    void purge() {
        em.createNativeQuery(
                "DELETE FROM movimenti WHERE conto_coge_id IN (30,34,35,36) " +
                "AND data_movimento >= current_date - INTERVAL '200 days'").executeUpdate();
    }

    @Transactional
    void insertScenario() {
        // CONTO_RIST, martedì (dow=2): 4 giorni distinti; TUE0 ha 2 scontrini (100+100) → testa che
        // la media sia per-GIORNO (SUM/giorni distinti) e non per-riga. SUM=500, giorni=4 → media 125.
        mov(CONTO_RIST, "ENTRATA", "100.00", TUE0, "REGISTRATO", null);
        mov(CONTO_RIST, "ENTRATA", "100.00", TUE0, "REGISTRATO", null);
        mov(CONTO_RIST, "ENTRATA", "100.00", TUE1, "REGISTRATO", null);
        mov(CONTO_RIST, "ENTRATA", "100.00", TUE2, "REGISTRATO", null);
        mov(CONTO_RIST, "ENTRATA", "100.00", TUE3, "REGISTRATO", null);

        // CONTO_RIST, domenica (dow=0): solo 2 giorni → sotto soglia, escluso dalla proiezione.
        mov(CONTO_RIST, "ENTRATA", "90.00", SUN0, "REGISTRATO", null);
        mov(CONTO_RIST, "ENTRATA", "90.00", SUN1, "REGISTRATO", null);

        // Esclusioni che NON devono entrare nella media del martedì conto 30:
        mov(CONTO_RIST, "ENTRATA", "9999.00", TUE0, "ANNULLATO", null);     // stato ANNULLATO
        mov(CONTO_RIST, "USCITA",  "9999.00", TUE0, "REGISTRATO", null);     // tipo USCITA
        UUID evento = insertEvento();
        mov(CONTO_RIST, "ENTRATA", "9999.00", TUE0, "REGISTRATO", evento);   // legato a evento

        // Conto fuori allowlist: non deve generare nessuna riga baseline.
        mov(CONTO_ALVEARE, "ENTRATA", "500.00", TUE1, "REGISTRATO", null);
    }

    @Transactional
    void mov(int coge, String tipo, String importo, LocalDate data, String stato, UUID eventoId) {
        em.createNativeQuery(
                "INSERT INTO movimenti (id, data_movimento, tipo, importo_lordo, importo_commissione, " +
                "data_competenza, conto_coge_id, business_unit_id, evento_id, descrizione, stato, fonte, created_by, created_at) " +
                "VALUES (gen_random_uuid(), :d, :t, CAST(:imp AS numeric), 0, :d, :coge, 1, :ev, :descr, :st, 'MANUALE', CAST(:u AS uuid), now())")
                .setParameter("d", data)
                .setParameter("t", tipo)
                .setParameter("imp", importo)
                .setParameter("coge", coge)
                .setParameter("ev", eventoId)
                .setParameter("descr", MARKER + " " + tipo)
                .setParameter("st", stato)
                .setParameter("u", TEST_USER)
                .executeUpdate();
    }

    @Transactional
    UUID insertEvento() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO eventi (id, nome, tipo, data_evento, stato) " +
                "VALUES (CAST(:id AS uuid), :nome, 'MATRIMONIO', :d, 'CONFERMATO')")
                .setParameter("id", id.toString())
                .setParameter("nome", MARKER + " evento")
                .setParameter("d", TUE0)
                .executeUpdate();
        return id;
    }

    // ── Asserzioni baseline ──────────────────────────────────────────────────

    @Test @Order(1)
    void baseline_mediaPerGiorno_eNGiorni() {
        Object[] tue = (Object[]) em.createNativeQuery(
                "SELECT media_attesa, n_giorni FROM forecast_baseline WHERE conto_coge_id=:c AND dow=2")
                .setParameter("c", CONTO_RIST).getSingleResult();
        assertEquals(0, new BigDecimal("125.00").compareTo((BigDecimal) tue[0]),
                "media martedì = SUM(500)/giorni distinti(4) = 125, non AVG per-riga (100)");
        assertEquals(4, ((Number) tue[1]).intValue(), "4 martedì distinti nella finestra");
    }

    @Test @Order(2)
    void baseline_segmentoSottoSoglia_presenteMaConPocaStoria() {
        Object[] sun = (Object[]) em.createNativeQuery(
                "SELECT media_attesa, n_giorni FROM forecast_baseline WHERE conto_coge_id=:c AND dow=0")
                .setParameter("c", CONTO_RIST).getSingleResult();
        assertEquals(0, new BigDecimal("90.00").compareTo((BigDecimal) sun[0]));
        assertEquals(2, ((Number) sun[1]).intValue(), "2 domeniche → sotto soglia min-giorni=4");
    }

    @Test @Order(3)
    void baseline_escludeContoFuoriAllowlist() {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM forecast_baseline WHERE conto_coge_id=:c")
                .setParameter("c", CONTO_ALVEARE).getSingleResult();
        assertEquals(0L, n.longValue(), "Alveare 30.03.003 è fuori allowlist → niente baseline");
    }

    @Test @Order(4)
    void baseline_esclusioniNonInquinanoLaMedia() {
        // Se ANNULLATO/USCITA/evento fossero contati, n_giorni o media del martedì sarebbero diversi.
        Object[] tue = (Object[]) em.createNativeQuery(
                "SELECT media_attesa, n_giorni FROM forecast_baseline WHERE conto_coge_id=:c AND dow=2")
                .setParameter("c", CONTO_RIST).getSingleResult();
        assertEquals(0, new BigDecimal("125.00").compareTo((BigDecimal) tue[0]),
                "le righe 9999 (annullato/uscita/evento) NON devono entrare nella media");
        assertEquals(4, ((Number) tue[1]).intValue());
    }

    // ── Asserzioni proiezione ─────────────────────────────────────────────────

    @Test @Order(10)
    void proiezione_soloMartedi_perOrizzonte90() {
        ForecastingRispostaDTO r = forecastingService.computeForecasting("90");
        List<ForecastingDettaglioDTO> stimate = r.economico().dettaglio().stream()
                .filter(d -> "STIMATO".equals(d.affidabilita())).toList();
        assertEquals(1, stimate.size(), "una riga stima aggregata per conto (solo conto 30 qualificato)");
        ForecastingDettaglioDTO s = stimate.get(0);
        assertEquals("MOVIMENTO", s.categoria());
        assertTrue(s.descrizione().startsWith("Stima"), "descrizione: " + s.descrizione());

        BigDecimal atteso = BigDecimal.valueOf(125L * contaMartedi(90));
        assertEquals(0, atteso.compareTo(s.importoEntrata()),
                "stima = #martedì in [domani, oggi+90] × 125 (le domeniche sotto soglia non contano). " +
                "atteso=" + atteso + " trovato=" + s.importoEntrata());
    }

    @Test @Order(11)
    void proiezione_nonInquinaIRicaviCerti() {
        ForecastingRispostaDTO r = forecastingService.computeForecasting("90");
        BigDecimal certiDaDettaglio = r.economico().dettaglio().stream()
                .filter(d -> "CERTO".equals(d.affidabilita()))
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, certiDaDettaglio.compareTo(r.economico().ricaviPrevisti()),
                "ricaviPrevisti deve restare il solo CERTO, la stima è esclusa dal subtotale");
    }

    @Test @Order(12)
    void proiezione_troncataA90Giorni() {
        BigDecimal stima90 = sommaStimata(forecastingService.computeForecasting("90"));
        BigDecimal stima180 = sommaStimata(forecastingService.computeForecasting("180"));
        assertEquals(0, stima90.compareTo(stima180),
                "oltre 90 giorni niente stima → 180 e 90 hanno lo stesso totale stimato");
    }

    @Test @Order(13)
    void proiezione_timelineEntrateStimate_coerenteColDettaglio() {
        ForecastingRispostaDTO r = forecastingService.computeForecasting("90");
        BigDecimal dettaglio = sommaStimata(r);
        BigDecimal timeline = r.finanziario().timeline().stream()
                .map(ForecastingTimelineDTO::entrateStimate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, dettaglio.compareTo(timeline),
                "somma entrateStimate dei bucket == totale stima del dettaglio");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private BigDecimal sommaStimata(ForecastingRispostaDTO r) {
        return r.economico().dettaglio().stream()
                .filter(d -> "STIMATO".equals(d.affidabilita()))
                .map(ForecastingDettaglioDTO::importoEntrata)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Numero di martedì nell'intervallo (oggi+1 .. oggi+giorni), coerente con la proiezione. */
    private long contaMartedi(int giorni) {
        long n = 0;
        for (LocalDate d = OGGI.plusDays(1); !d.isAfter(OGGI.plusDays(giorni)); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.TUESDAY) n++;
        }
        return n;
    }

    @AfterAll
    void cleanup() {
        cleanupTx();
        seeded = false;
    }

    @Transactional
    void cleanupTx() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE :m")
                .setParameter("m", MARKER + "%").executeUpdate();
        em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE :m")
                .setParameter("m", MARKER + "%").executeUpdate();
        // baseline ricalcolata sui dati residui (i nostri sono spariti) → torna pulita per altri test
        baselineService.recompute();
    }
}
