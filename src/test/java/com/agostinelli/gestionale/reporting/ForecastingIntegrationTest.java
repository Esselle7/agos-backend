package com.agostinelli.gestionale.reporting;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ForecastingIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static volatile boolean mvRefreshed = false;

    /** Perimetro go-live del gestionale: una sola definizione, in Perimetro (P6 / R6.4). */
    static final LocalDate GO_LIVE = Perimetro.GO_LIVE;

    // ── Dati costruiti per P2 (marcati "ZZP2" per essere riconoscibili nel dettaglio) ──
    static final String EV_NOME      = "ZZP2 evento nell'orizzonte";
    static final String ACCONTO_DESC = "ZZP2 acconto gia' incassato";
    static final String EV_ANTE_NOME = "ZZP2 evento ante go-live";
    static final String ACC_ANTE_DESC = "ZZP2 acconto ante go-live";
    static final String EV_NOPREV_NOME  = "ZZP2 evento senza preventivo";
    static final String ACC_NOPREV_DESC = "ZZP2 acconto senza preventivo";
    static final String COMPETENZA_DESC = "ZZP4 competenza evento celebrato";
    static final java.math.BigDecimal CREDITO_EVENTO     = new java.math.BigDecimal("450.00");
    static final java.math.BigDecimal CREDITO_NON_EVENTO = new java.math.BigDecimal("70.00");
    static final java.math.BigDecimal PREVENTIVATO = new java.math.BigDecimal("1000.00");
    static final java.math.BigDecimal ACCONTO      = new java.math.BigDecimal("300.00");

    final UUID eventoId     = UUID.randomUUID();
    final UUID accontoId    = UUID.randomUUID();
    final UUID eventoAnteId = UUID.randomUUID();
    final UUID accAnteId    = UUID.randomUUID();
    final UUID eventoNoPrevId = UUID.randomUUID();
    final UUID accNoPrevId    = UUID.randomUUID();
    final UUID eventoCelebratoId  = UUID.randomUUID();
    final UUID competenzaId       = UUID.randomUUID();
    final UUID creditoNonEventoId = UUID.randomUUID();
    /** Data dell'evento costruito: dentro tutti gli orizzonti (30 gg è il più stretto). */
    final LocalDate dataEvento = LocalDate.now().plusDays(20);

    @Inject EntityManager em;
    @Inject UserTransaction tx;

    /**
     * P2 — dati costruiti, non numeri veri: `agosdb_test` è vuoto (clean-at-start) e la disciplina
     * della suite è «invarianti strutturali, i numeri stanno nella baseline».
     *
     * Due eventi CONFERMATO, entrambi con un acconto già incassato (data_finanziaria valorizzata,
     * data_competenza = data dell'evento):
     *  - uno dentro l'orizzonte → deve comparire, e la somma delle sue righe deve fare il preventivato;
     *  - uno con data_evento ante go-live → non deve comparire, né come EVENTO né come acconto.
     */
    @BeforeAll
    @Transactional
    void seedP2() {
        int contoRicavo = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'RICAVO' " +
                "AND COALESCE(is_capex, false) = false AND is_active = true ORDER BY id LIMIT 1")
                .getSingleResult()).intValue();

        insEvento(eventoId, EV_NOME, dataEvento);
        insAcconto(accontoId, ACCONTO_DESC, eventoId, dataEvento, contoRicavo);

        // Ante go-live: data_competenza dell'acconto = data dell'evento, come nei dati veri.
        LocalDate dAnte = GO_LIVE.minusDays(16);
        insEvento(eventoAnteId, EV_ANTE_NOME, dAnte);
        insAcconto(accAnteId, ACC_ANTE_DESC, eventoAnteId, dAnte, contoRicavo);

        // Caso limite deciso dall'utente il 06/09/2026 (domanda 1 della spec): evento CONFERMATO
        // con preventivato NULL ma con un acconto incassato a competenza futura.
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, :nome, 'BANCHETTO_PRIVATO', :d, 'CONFERMATO', NULL, :inc, 0, 0, 2, now())
            """)
            .setParameter("id", eventoNoPrevId).setParameter("nome", EV_NOPREV_NOME)
            .setParameter("d", dataEvento).setParameter("inc", ACCONTO)
            .executeUpdate();
        insAcconto(accNoPrevId, ACC_NOPREV_DESC, eventoNoPrevId, dataEvento, contoRicavo);

        // L'evento celebrato: SALDATO e passato, così non produce nessuna riga di previsione.
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, 'ZZP4 evento celebrato', 'BANCHETTO_PRIVATO', :d, 'CONFERMATO', :prev, 0, 0, 0, 2, now())
            """)
            .setParameter("id", eventoCelebratoId).setParameter("d", LocalDate.now().minusDays(10))
            .setParameter("prev", CREDITO_EVENTO)
            .executeUpdate();

        // P4 — credito da evento GIÀ CELEBRATO: riga COMPETENZA (data_finanziaria NULL) su un
        // evento passato, la forma che EventiService crea quando l'evento si celebra.
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, evento_id, tipo_evento_movimento,
                created_by, created_at)
            VALUES (:id, :d, :d, NULL, :d, 'ENTRATA', :imp, 0, :coge, NULL, 2,
                'DA_LIQUIDARE', 'MANUALE', :desc, :evento, 'COMPETENZA', CAST(:u AS uuid), now())
            """)
            .setParameter("id", competenzaId).setParameter("d", LocalDate.now().minusDays(10))
            .setParameter("imp", CREDITO_EVENTO).setParameter("coge", contoRicavo)
            .setParameter("desc", COMPETENZA_DESC).setParameter("evento", eventoCelebratoId)
            .setParameter("u", TEST_USER)
            .executeUpdate();

        // Un credito aperto SENZA evento: serve a provare che creditoEventiCelebrati è un
        // sottoinsieme STRETTO di creditiAperti, non un alias.
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (:id, :d, :d, NULL, :d, 'ENTRATA', :imp, 0, :coge, NULL, 2,
                'DA_LIQUIDARE', 'MANUALE', 'ZZP4 credito senza evento', CAST(:u AS uuid), now())
            """)
            .setParameter("id", creditoNonEventoId).setParameter("d", LocalDate.now().minusDays(10))
            .setParameter("imp", CREDITO_NON_EVENTO).setParameter("coge", contoRicavo)
            .setParameter("u", TEST_USER)
            .executeUpdate();
    }

    private void insEvento(UUID id, String nome, LocalDate data) {
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, :nome, 'BANCHETTO_PRIVATO', :d, 'CONFERMATO', :prev, :inc, 0, 0, 2, now())
            """)
            .setParameter("id", id).setParameter("nome", nome).setParameter("d", data)
            .setParameter("prev", PREVENTIVATO).setParameter("inc", ACCONTO)
            .executeUpdate();
    }

    /** Giorni di ritardo della cassa costruita: oltre la soglia di freschezza di default (14),
     *  così il banner P6 nasce ACCESO come in produzione. */
    static final int CASSA_VECCHIA_GIORNI = 40;

    /** Acconto GIÀ INCASSATO: data_finanziaria valorizzata (quindi fuori da buildMovimentiDaLiquidare),
     *  data_competenza = data dell'evento (quindi futura per l'evento nell'orizzonte). */
    private void insAcconto(UUID id, String desc, UUID evento, LocalDate competenza, int contoCoge) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, evento_id, tipo_evento_movimento,
                created_by, created_at)
            VALUES (:id, :incasso, :comp, :incasso, :incasso, 'ENTRATA', :imp, 0, :coge, 2, 2,
                'ATTIVO', 'MANUALE', :desc, :evento, 'ACCONTO', CAST(:u AS uuid), now())
            """)
            .setParameter("id", id)
            .setParameter("incasso", LocalDate.now().minusDays(CASSA_VECCHIA_GIORNI))
            .setParameter("comp", competenza)
            .setParameter("imp", ACCONTO)
            .setParameter("coge", contoCoge)
            .setParameter("desc", desc)
            .setParameter("evento", evento)
            .setParameter("u", TEST_USER)
            .executeUpdate();
    }

    @BeforeEach
    void refreshMvs() throws Exception {
        if (mvRefreshed) return;
        tx.begin();
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

    /**
     * INVARIANTE: ogni voce dettaglio ha importi non negativi, SALVO gli storni.
     *
     * L'eccezione è stata aperta il 06/09/2026 con P2 (docs/specs/previsionale-correzioni.md) ed è
     * una decisione dell'utente, non un allentamento per far passare la suite.
     *
     * Perché esiste: un RIMBORSO a un cliente è registrato come movimento `tipo='ENTRATA'` con
     * `importo_lordo` NEGATIVO (EventiService:359 — il segno negativo è ciò che fa scendere
     * `importo_incassato` via ricalcolaIncassi). Prima di P2 le entrate evento erano escluse dal
     * dettaglio economico e una riga negativa non poteva esistere. Ora entra, e DEVE entrare:
     * senza di lei la somma delle righe di un evento con rimborso supera il suo preventivato
     * (1.400 + 800 = 2.200 invece di 2.000), cioè salta l'invariante I1, che è l'obiettivo di P2.
     *
     * È la stessa semantica di `mv_conto_economico_mensile` (V39): uno storno su conto RICAVO entra
     * nei ricavi COL SEGNO MENO. Previsionale e conto economico restano d'accordo.
     *
     * Resta vietato il caso che nasconderebbe un errore di mappatura: un importo negativo dalla
     * parte sbagliata (un costo negativo, o una entrata negativa che non viene da uno storno).
     * Occorrenze in produzione al 06/09/2026: 0 rimborsi evento, 0 entrate negative.
     */
    @Test @Order(33)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void invariante_dettaglio_importiNonNegativi_salvoStorni() {
        List<Map<String, Object>> dettaglio = given()
            .queryParam("horizon", "180")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");

        for (int i = 0; i < dettaglio.size(); i++) {
            Map<String, Object> voce = dettaglio.get(i);
            float entrata = toFloat(voce.get("importoEntrata"));
            float uscita  = toFloat(voce.get("importoUscita"));
            String desc   = String.valueOf(voce.get("descrizione"));

            if (entrata < 0) {
                assertTrue(isStorno(desc),
                    "Voce " + i + " (" + desc + "): un importoEntrata negativo è ammesso solo per " +
                    "uno storno (rimborso a cliente). Qui non c'è nessun movimento di storno con " +
                    "questa descrizione: è un errore di mappatura, non un rimborso.");
            }
            assertTrue(uscita >= 0,
                "Voce " + i + " (" + desc + "): importoUscita deve essere >= 0. Un costo negativo " +
                "non è uno storno ammesso: è un segno finito nella colonna sbagliata.");
        }
    }

    /** true se esiste a DB un movimento con quella descrizione, ENTRATA e importo negativo. */
    private boolean isStorno(String descrizione) {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM movimenti WHERE descrizione = :d AND stato <> 'ANNULLATO' " +
                "AND tipo = 'ENTRATA' AND COALESCE(importo_imponibile, importo_lordo) < 0")
                .setParameter("d", descrizione)
                .getSingleResult();
        return n.longValue() > 0;
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

        // I piani FINANZIAMENTO spezzano la rata in due voci (ForecastingService:423): senza
        // queste due l'invariante falliva appena esisteva un piano di quel tipo nel DB di test.
        java.util.Set<String> categorieValide = java.util.Set.of(
            "MOVIMENTO", "EVENTO", "RATA_RICORRENTE", "STIPENDIO",
            "RATA_RICORRENTE_CAPITALE", "RATA_RICORRENTE_INTERESSI");

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
    // P2 — il ricavo dell'evento previsto è il suo PREVENTIVATO
    // (docs/specs/previsionale-correzioni.md)
    // ═══════════════════════════════════════════════════════════

    /**
     * R2.2 — invariante I1 di `docs/specs/competenza-ricavo-evento.md` portato sul previsionale:
     * per un evento CONFERMATO dentro l'orizzonte, riga EVENTO (residuo) + acconti già incassati
     * con competenza futura = importo_totale_preventivato.
     *
     * CONTROPROVA ESEGUITA: rimettendo in `buildMovimentiEconomici` la clausola
     * `AND NOT (evento_id IS NOT NULL AND tipo = 'ENTRATA')` questo test fallisce
     * (trova 700,00 invece di 1.000,00: l'acconto sparisce).
     */
    @Test @Order(70)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p2_evento_nellOrizzonte_sommaRighe_eq_preventivato() {
        List<Map<String, Object>> dettaglio = dettaglio("90");

        float rigaEvento = sommaEntrate(dettaglio, EV_NOME);
        float rigaAcconto = sommaEntrate(dettaglio, ACCONTO_DESC);

        assertTrue(rigaEvento > 0, "manca la riga EVENTO per " + EV_NOME);
        assertTrue(rigaAcconto > 0,
            "manca l'acconto già incassato con competenza futura: è esattamente ciò che P2 rimette dentro");
        assertEquals(PREVENTIVATO.floatValue(), rigaEvento + rigaAcconto, 0.01f,
            "I1: riga EVENTO (" + rigaEvento + ") + acconti (" + rigaAcconto + ") deve fare il preventivato");
        assertEquals(PREVENTIVATO.subtract(ACCONTO).floatValue(), rigaEvento, 0.01f,
            "la riga EVENTO deve restare il RESIDUO, non diventare il preventivato");
    }

    /**
     * Caso limite dell'evento senza preventivo — ASIMMETRIA ACCETTATA E DICHIARATA
     * (decisione dell'utente 06/09/2026, domanda 1 di docs/specs/previsionale-correzioni.md).
     *
     * Un evento CONFERMATO con `importo_totale_preventivato` NULL non produce riga EVENTO
     * (`buildEventiForecasting` filtra `preventivato > incassato`), ma i suoi acconti già incassati
     * con competenza futura SÌ entrano nei ricavi previsti. È asimmetrico e resta così di
     * proposito: `mv_conto_economico_mensile` quegli acconti li conta in quel mese, quindi
     * toglierli farebbe prevedere meno ricavo di quello che il P&L registrerà — cioè romperebbe
     * l'obiettivo 1 della spec proprio sul caso limite.
     *
     * Occorrenze in produzione al 06/09/2026: 0. Questo test fissa la decisione, non un numero.
     */
    @Test @Order(74)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p2_evento_senza_preventivo_nessuna_rigaEvento_ma_acconto_nei_ricavi() {
        List<Map<String, Object>> d = dettaglio("90");

        assertEquals(0f, sommaEntrate(d, EV_NOPREV_NOME), 0.001f,
            "un evento senza preventivato non deve produrre riga EVENTO: non c'è un residuo da prevedere");
        assertEquals(ACCONTO.floatValue(), sommaEntrate(d, ACC_NOPREV_DESC), 0.01f,
            "l'acconto con competenza futura resta nei ricavi previsti anche senza preventivo: " +
            "il conto economico di quel mese lo conterà");
    }

    /**
     * R2.3 — P2 tocca solo la vista ECONOMICA: l'acconto rimesso nei ricavi previsti NON deve
     * entrare nella proiezione di cassa. È già in banca (data_finanziaria valorizzata), contarlo
     * come incasso futuro sarebbe doppio conteggio.
     *
     * La guardia strutturale è la vista: `buildTimeline` scarta le righe ECONOMICA, e
     * `buildFinanziario` non chiama nemmeno `buildMovimentiEconomici`.
     */
    @Test @Order(71)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p2_acconto_resta_fuori_dalla_cassa() {
        for (String h : List.of("30", "60", "90", "180", "FINE_ANNO")) {
            List<Map<String, Object>> righeAcconto = dettaglio(h).stream()
                .filter(v -> ACCONTO_DESC.equals(v.get("descrizione")))
                .toList();
            for (Map<String, Object> v : righeAcconto) {
                assertEquals("ECONOMICA", v.get("vista"),
                    "horizon=" + h + ": l'acconto già incassato deve essere ECONOMICA-only, " +
                    "altrimenti entra nel saldo di cassa proiettato");
            }
        }
    }

    /**
     * R2.4 / G6 — perimetro go-live: un evento con data_evento < 01/07/2026 non produce nessuna
     * riga, né EVENTO né acconto.
     *
     * Cosa lo garantisce davvero (non c'è un filtro esplicito su GO_LIVE, e non va aggiunto):
     * la finestra del previsionale è (oggi, fine], e sia `data_evento` sia la `data_competenza`
     * dell'acconto valgono la data dell'evento — che è nel passato. Il test fissa la conseguenza,
     * così se un giorno la finestra o la data usata cambiassero, il perimetro non scivola in silenzio.
     */
    @Test @Order(72)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p2_evento_ante_golive_non_produce_righe() {
        assertTrue(dataEvento.isAfter(GO_LIVE), "precondizione del test: l'evento di controllo è post go-live");
        for (String h : List.of("30", "90", "180", "FINE_ANNO")) {
            List<Map<String, Object>> d = dettaglio(h);
            assertEquals(0f, sommaEntrate(d, EV_ANTE_NOME), 0.001f,
                "horizon=" + h + ": nessuna riga EVENTO per un evento ante go-live");
            assertEquals(0f, sommaEntrate(d, ACC_ANTE_DESC), 0.001f,
                "horizon=" + h + ": nessun acconto di un evento ante go-live");
        }
    }

    /**
     * R2.5 — nata TRAPPOLA, ora GUARDIA DI REGRESSIONE: la trappola è scattata e il fix è stato
     * applicato (collaudo 06/09/2026).
     *
     * <p>`buildMovimentiEconomici` classificava ricavo/costo sul solo `movimenti.tipo`, senza join
     * su `piano_dei_conti_coge`: un'entrata datata in avanti su un conto patrimoniale contava come
     * ricavo previsto. Occorrenze in produzione: 0 su 11 (B7 della baseline) — ma il controesempio
     * è arrivato come TEST ROSSO, che il CLAUDE.md ammette come misura: due ENTRATA con competenza
     * futura su conti ATTIVITA («Filtro date range», «Filtro buId 2») finivano nei ricavi previsti.
     *
     * <p>Il fix replica il CASE di `mv_conto_economico_mensile` (V39), così il previsionale promette
     * il conto economico che quel mese produrrà davvero. Impatto su agosdb: 0,00 €.
     */
    @Test @Order(73)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p2_trappola_ricaviPrevisti_solo_da_conti_ricavo() {
        // Si verifica L'USCITA DEL SERVIZIO contro la regola ricalcolata a DB, non l'assenza di
        // righe scomode nel DB: quello che deve valere e' «il previsionale non conta come ricavo un
        // movimento su conto non-RICAVO», non «nessuno ha mai creato un movimento simile».
        // agosdb_test e' condiviso fra le classi della stessa JVM: le righe scomode ci SONO e ci
        // devono poter essere — quello che non deve succedere e' che finiscano nel ricavo previsto.
        //
        // Le sole righe categoria=MOVIMENTO e vista=ECONOMICA vengono da buildMovimentiEconomici
        // (l'altro produttore di vista=ECONOMICA e' AMMORTAMENTO, altra categoria; le righe stimate
        // di P7 nascono vista=ENTRAMBE).
        float daServizio = 0f;
        for (Map<String, Object> v : dettaglio("180")) {
            if ("MOVIMENTO".equals(v.get("categoria")) && "ECONOMICA".equals(v.get("vista"))) {
                daServizio += toFloat(v.get("importoEntrata"));
            }
        }

        // Stessa finestra del servizio — start = oggi+1, end = oggi+180 — e stessa classificazione
        // del CASE di mv_conto_economico_mensile (V39).
        Number daDb = (Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(COALESCE(m.importo_imponibile, m.importo_lordo)), 0) " +
                "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato <> 'ANNULLATO' AND m.tipo = 'ENTRATA' " +
                "  AND pc.tipo = 'RICAVO' AND NOT pc.is_capex " +
                "  AND m.data_competenza BETWEEN CURRENT_DATE + 1 AND CURRENT_DATE + 180")
                .getSingleResult();

        assertEquals(daDb.floatValue(), daServizio, 0.01f,
            "il ricavo previsto dai movimenti deve venire SOLO da conti RICAVO non capex. " +
            "Se il servizio conta di piu', sta contando un giroconto o un capex datato in avanti: " +
            "e' il difetto che il JOIN su piano_dei_conti_coge in buildMovimentiEconomici chiude.");
    }

    private List<Map<String, Object>> dettaglio(String horizon) {
        return given()
            .queryParam("horizon", horizon)
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200)
            .extract().jsonPath().getList("economico.dettaglio");
    }

    private float sommaEntrate(List<Map<String, Object>> dettaglio, String descrizione) {
        float tot = 0f;
        for (Map<String, Object> v : dettaglio) {
            if (descrizione.equals(v.get("descrizione"))) tot += toFloat(v.get("importoEntrata"));
        }
        return tot;
    }

    // ═══════════════════════════════════════════════════════════
    // P3 — cascata EBITDA → EBIT → EBT
    // ═══════════════════════════════════════════════════════════

    /**
     * R3.1 — `ebtPrevisto = ebitPrevisto − oneriFinanziariPrevisti`, su tutti gli orizzonti.
     *
     * Non è una formalità: prima di P3 il pannello mostrava la riga «− Oneri finanziari» sopra un
     * EBIT che non li sottraeva. Il livello dove gli oneri vengono davvero tolti ora esiste e ha
     * un nome.
     */
    @Test @Order(80)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p3_invariante_ebt_eq_ebit_meno_oneri() {
        for (String h : List.of("30", "60", "90", "180", "FINE_ANNO")) {
            Response r = given()
                .queryParam("horizon", h)
                .when().get("/api/reporting/forecasting")
                .then().statusCode(200).extract().response();

            Object ebtRaw = r.path("economico.ebtPrevisto");
            assertNotNull(ebtRaw, "horizon=" + h + ": ebtPrevisto non può essere null");

            float ebit  = toFloat(r.path("economico.ebitPrevisto"));
            float oneri = toFloat(r.path("economico.oneriFinanziariPrevisti"));
            assertEquals(ebit - oneri, toFloat(ebtRaw), 0.01f,
                "horizon=" + h + ": ebtPrevisto deve essere ebitPrevisto − oneriFinanziariPrevisti");
        }
    }

    /**
     * R3.4 — P3 AGGIUNGE un livello, non ne sposta uno: `ebitPrevisto` resta
     * `ebitdaPrevisto − ammortamentiPrevisti` e gli oneri continuano a non toccarlo.
     * (Il confronto col valore assoluto della baseline lo fa lo snapshot, non la suite.)
     */
    @Test @Order(81)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p3_ebit_resta_ebitda_meno_ammortamenti() {
        for (String h : List.of("30", "90", "FINE_ANNO")) {
            Response r = given()
                .queryParam("horizon", h)
                .when().get("/api/reporting/forecasting")
                .then().statusCode(200).extract().response();

            float ebitda = toFloat(r.path("economico.ebitdaPrevisto"));
            float amm    = toFloat(r.path("economico.ammortamentiPrevisti"));
            assertEquals(ebitda - amm, toFloat(r.path("economico.ebitPrevisto")), 0.01f,
                "horizon=" + h + ": gli oneri finanziari non devono entrare nell'EBIT");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // P4 — credito da eventi celebrati: visibile, ma fuori dalla cassa
    // ═══════════════════════════════════════════════════════════

    /**
     * R4.1 — `creditoEventiCelebrati` non dipende dall'orizzonte: riguarda eventi già celebrati,
     * non la finestra di previsione. (Il valore vero, 26.616,00 al 06/09/2026, sta nella baseline.)
     */
    @Test @Order(90)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p4_creditoEventiCelebrati_uguale_su_tutti_gli_orizzonti() {
        Float atteso = null;
        for (String h : List.of("30", "60", "90", "180", "FINE_ANNO")) {
            Object v = given()
                .queryParam("horizon", h)
                .when().get("/api/reporting/forecasting")
                .then().statusCode(200).extract().path("asIs.creditoEventiCelebrati");

            assertNotNull(v, "horizon=" + h + ": creditoEventiCelebrati non può essere null");
            if (atteso == null) atteso = toFloat(v);
            else assertEquals(atteso, toFloat(v), 0.001f,
                "horizon=" + h + ": il credito da eventi celebrati non dipende dall'orizzonte");
        }
        // Il valore assoluto NON si asserisce: `agosdb_test` è condiviso con le altre classi della
        // suite, che seminano altri crediti evento (misurato: 1.550,00 invece di 450,00 quando la
        // suite gira intera). Il requisito R4.1 è l'INVARIANZA fra orizzonti; il numero vero
        // (26.616,00 al 06/09/2026) sta nella baseline, non qui.
        assertTrue(atteso >= CREDITO_EVENTO.floatValue() - 0.01f,
            "il credito deve comprendere almeno la riga COMPETENZA costruita da questa classe");

        Number attesoDaDb = (Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(importo_lordo),0) FROM movimenti " +
                "WHERE stato <> 'ANNULLATO' AND tipo = 'ENTRATA' AND evento_id IS NOT NULL " +
                "AND data_finanziaria IS NULL")
                .getSingleResult();
        assertEquals(attesoDaDb.floatValue(), atteso, 0.01f,
            "creditoEventiCelebrati deve essere esattamente la somma delle entrate evento non liquidate");
    }

    /**
     * R4.2 — è un SOTTOINSIEME di `creditiAperti`, e qui stretto: c'è anche un credito senza evento.
     * Strutturalmente garantito (stessa SUM, CASE più stretto), il test lo fissa.
     */
    @Test @Order(91)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p4_creditoEventi_sottoinsieme_di_creditiAperti() {
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float credEventi = toFloat(r.path("asIs.creditoEventiCelebrati"));
        float credTotali = toFloat(r.path("asIs.creditiAperti"));

        assertTrue(credEventi <= credTotali + 0.001f,
            "creditoEventiCelebrati (" + credEventi + ") non può superare creditiAperti (" + credTotali + ")");
        // La differenza si verifica CONTRO IL DB, non contro CREDITO_NON_EVENTO: `creditiAperti`
        // somma tutte le entrate non liquidate di agosdb_test, che e' condiviso fra le classi della
        // stessa JVM. Fissare qui il valore assoluto rendeva il test verde da solo e rosso nella
        // suite (misurato il 06/09/2026: expected <70.0> but was <77.0>, per 7,00 seminati altrove).
        // La regola da difendere e' «il complemento del sottoinsieme e' il credito senza evento»,
        // e quella si legge dal DB.
        Number nonEvento = (Number) em.createNativeQuery(
                "SELECT COALESCE(SUM(importo_lordo), 0) FROM movimenti " +
                "WHERE stato <> 'ANNULLATO' AND data_finanziaria IS NULL " +
                "  AND tipo = 'ENTRATA' AND evento_id IS NULL")
                .getSingleResult();
        assertEquals(nonEvento.floatValue(), credTotali - credEventi, 0.01f,
            "la differenza deve essere il credito aperto che non viene da un evento");
    }

    /**
     * R4.3 — il requisito centrale del passo: si aggiunge INFORMAZIONE, non flusso. Il credito
     * non entra nel saldo finale né negli incassi previsti.
     *
     * Controllo diretto: la riga COMPETENZA non compare in nessuna riga di dettaglio, e
     * saldoFinale continua a valere saldoPartenza + incassi − uscite (G1) senza il credito dentro.
     */
    @Test @Order(92)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p4_credito_non_entra_nella_cassa() {
        for (String h : List.of("30", "60", "90", "180", "FINE_ANNO")) {
            Response r = given()
                .queryParam("horizon", h)
                .when().get("/api/reporting/forecasting")
                .then().statusCode(200).extract().response();

            float saldoPartenza = toFloat(r.path("finanziario.saldoPartenza"));
            float incassi       = toFloat(r.path("finanziario.incassiPrevisti"));
            float uscite        = toFloat(r.path("finanziario.uscitePreviste"));
            float saldoFinale   = toFloat(r.path("finanziario.saldoFinale"));
            float credito       = toFloat(r.path("asIs.creditoEventiCelebrati"));

            assertTrue(credito > 0, "precondizione: il dato costruito ha un credito da contare");
            assertEquals(saldoPartenza + incassi - uscite, saldoFinale, 0.05f,
                "horizon=" + h + ": il credito non deve entrare nel saldo finale");

            List<Map<String, Object>> righe = r.jsonPath().getList("economico.dettaglio");
            for (Map<String, Object> v : righe) {
                assertNotEquals(COMPETENZA_DESC, v.get("descrizione"),
                    "horizon=" + h + ": la riga COMPETENZA non deve comparire nel dettaglio previsionale");
            }
        }
    }

    /**
     * R4.5 — un evento ante go-live non contribuisce al credito: per costruzione non ha riga
     * COMPETENZA (EventiService.residuoDaMaturare torna 0 sotto il go-live). Il test lo fissa
     * così il perimetro non scivola: l'acconto ante go-live seeded qui ha data_finanziaria
     * valorizzata e quindi resta fuori da tutti e tre i numeri di credito.
     */
    @Test @Order(93)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p4_evento_ante_golive_non_contribuisce_al_credito() {
        float credito = toFloat(given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().path("asIs.creditoEventiCelebrati"));

        Number righeAnte = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM movimenti m JOIN eventi e ON e.id = m.evento_id " +
                "WHERE m.stato <> 'ANNULLATO' AND m.tipo = 'ENTRATA' AND m.data_finanziaria IS NULL " +
                "AND e.data_evento < :goLive")
                .setParameter("goLive", GO_LIVE)
                .getSingleResult();

        assertEquals(0L, righeAnte.longValue(),
            "nessun credito aperto può venire da un evento ante go-live");
        assertTrue(credito > 0, "precondizione: c'è del credito da contare");
    }

    // ═══════════════════════════════════════════════════════════
    // P5 — lo YTD si ferma a oggi, non a fine mese
    // ═══════════════════════════════════════════════════════════

    /**
     * R5.2 — invariante di NON-SOVRAPPOSIZIONE: nessun movimento contribuisce sia allo YTD sia al
     * previsionale. Il confine è YTD = [1 gen, oggi] · previsione = (oggi, fine].
     *
     * È un esperimento, non un'asserzione su un numero magico: si misura lo YTD, si inserisce UNA
     * riga per volta e si guarda cosa si muove. Prima di P5 la prima riga (competenza domani)
     * faceva salire lo YTD, perché `mv_conto_economico_mensile` ha grana mensile e il mese corrente
     * entrava intero.
     *
     * Ultimo della classe (@Order 100) perché scrive sul DB e rinfresca le MV.
     *
     * CONTROPROVA ESEGUITA: togliendo la sottrazione della coda in `buildAsIs` questo test
     * fallisce sul primo passo (lo YTD sale di 1.234,56 invece di restare fermo).
     */
    @Test @Order(100)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p5_ytd_e_previsionale_non_si_sovrappongono() throws Exception {
        final java.math.BigDecimal DOMANI = new java.math.BigDecimal("1234.56");
        final java.math.BigDecimal OGGI   = new java.math.BigDecimal("77.77");
        final java.math.BigDecimal INIZIO_ANNO = new java.math.BigDecimal("88.88");
        LocalDate oggi = LocalDate.now();

        float ytdPrima = ricaviYtd();

        // 1) competenza DOMANI: sta nel previsionale, NON nello YTD.
        insRicavo("ZZP5 competenza domani", oggi.plusDays(1), DOMANI);
        assertEquals(ytdPrima, ricaviYtd(), 0.01f,
            "un movimento con competenza domani non deve entrare nello YTD");
        assertEquals(DOMANI.floatValue(), sommaEntrate(dettaglio("30"), "ZZP5 competenza domani"), 0.01f,
            "...e deve invece comparire nel dettaglio previsionale");

        // 2) competenza OGGI: il contrario esatto.
        insRicavo("ZZP5 competenza oggi", oggi, OGGI);
        assertEquals(ytdPrima + OGGI.floatValue(), ricaviYtd(), 0.01f,
            "un movimento con competenza oggi deve entrare nello YTD");
        assertEquals(0f, sommaEntrate(dettaglio("180"), "ZZP5 competenza oggi"), 0.01f,
            "...e non deve comparire nel previsionale: la finestra è (oggi, fine]");

        // 3) R5.4 — una competenza passata resta nello YTD. Nell'anno del go-live (2026) il
        //    1° gennaio è ANTE go-live: quei ricavi sono legittimi (8.251,04 € di eventi di giugno
        //    rimessi a libro il 20/08/2026) e non vanno tolti, si dichiarano in P6.
        insRicavo("ZZP5 competenza inizio anno", oggi.withDayOfYear(1), INIZIO_ANNO);
        assertEquals(ytdPrima + OGGI.floatValue() + INIZIO_ANNO.floatValue(), ricaviYtd(), 0.01f,
            "una competenza passata, anche ante go-live, resta dentro lo YTD");
    }

    /**
     * R5.3 — `ebitdaYtd = ricaviYtd − costiYtd` continua a valere dopo il taglio: la coda si
     * sottrae dall'EBITDA con la stessa differenza con cui si sottrae dai due addendi.
     * (`invariante_asIs_ebitda_eq_ricavi_minus_costi` ha una tolleranza larga; questo è stretto.)
     */
    @Test @Order(101)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p5_ebitdaYtd_resta_ricavi_meno_costi() {
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        assertEquals(toFloat(r.path("asIs.ricaviYtd")) - toFloat(r.path("asIs.costiYtd")),
                     toFloat(r.path("asIs.ebitdaYtd")), 0.01f,
            "il taglio a oggi non deve rompere ebitdaYtd = ricaviYtd − costiYtd");
    }

    private float ricaviYtd() {
        return toFloat(given()
            .queryParam("horizon", "30")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().path("asIs.ricaviYtd"));
    }

    /** Inserisce un ricavo con la competenza data e rinfresca la MV del conto economico. */
    private void insRicavo(String desc, LocalDate competenza, java.math.BigDecimal importo) throws Exception {
        tx.begin();
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (:id, :d, :comp, :d, :d, 'ENTRATA', :imp, 0,
                (SELECT id FROM piano_dei_conti_coge WHERE tipo = 'RICAVO'
                 AND COALESCE(is_capex, false) = false AND is_active = true ORDER BY id LIMIT 1),
                2, 2, 'ATTIVO', 'MANUALE', :desc, CAST(:u AS uuid), now())
            """)
            .setParameter("id", UUID.randomUUID())
            .setParameter("d", LocalDate.now())
            .setParameter("comp", competenza)
            .setParameter("imp", importo)
            .setParameter("desc", desc)
            .setParameter("u", TEST_USER)
            .executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        tx.commit();
    }

    // ═══════════════════════════════════════════════════════════
    // P6 — la pagina dichiara su che dato sta prevedendo
    // ═══════════════════════════════════════════════════════════

    /**
     * R6.1 — `ultimaDataCassa` = MAX(data_finanziaria) sui movimenti non annullati; `datiIncompleti`
     * è vero quando quella data è più vecchia di `forecast.freschezza.giorni-max` (default 14,
     * deciso dall'utente il 06/09/2026 — la soglia di 35 proposta dalla spec avrebbe lasciato il
     * banner spento proprio nel caso che P6 esiste per raccontare).
     *
     * Il test verifica la REGOLA contro il DB, non un esito atteso: `ultimaDataCassa` è un MAX
     * globale e `agosdb_test` è condiviso con le altre classi della suite, che seminano date di
     * cassa proprie (misurato: 2099-11-01 quando la suite gira intera). Così il test resta vero in
     * qualunque composizione, e copre entrambi i rami — acceso e spento — su qualunque dataset.
     *
     * Lo stato acceso reale (produzione al 06/09/2026: ultima cassa 20/08, 17 giorni fa,
     * `datiIncompleti = true`) è misurato nella baseline, che è il posto dei numeri veri.
     */
    @Test @Order(94)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p6_freschezza_segue_il_max_della_cassa() {
        int soglia = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                .getOptionalValue("forecast.freschezza.giorni-max", Integer.class).orElse(14);

        Object maxDb = em.createNativeQuery(
                "SELECT MAX(data_finanziaria) FROM movimenti WHERE stato <> 'ANNULLATO'")
                .getSingleResult();
        LocalDate atteso = maxDb == null ? null : LocalDate.parse(maxDb.toString().substring(0, 10));

        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        assertEquals(atteso == null ? null : atteso.toString(), r.path("asIs.ultimaDataCassa"),
            "ultimaDataCassa deve essere il MAX(data_finanziaria) dei movimenti non annullati");

        boolean attesoIncompleto = atteso == null || atteso.isBefore(LocalDate.now().minusDays(soglia));
        assertEquals(attesoIncompleto, r.path("asIs.datiIncompleti"),
            "datiIncompleti deve seguire la soglia di " + soglia + " giorni su ultimaDataCassa=" + atteso);

        // R6.2 / R6.3: la nota esiste ESATTAMENTE quando il dato è incompleto, e cita la data.
        String nota = r.path("asIs.nota");
        if (attesoIncompleto) {
            assertNotNull(nota, "quando il dato è incompleto la nota deve dirlo");
            if (atteso != null) {
                assertTrue(nota.contains(atteso.format(
                        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))),
                    "R6.2: la nota deve citare la data. Trovato: " + nota);
            }
        } else {
            assertNull(nota,
                "senza dati incompleti non si scrive una nota: stesso pattern di computeQualita");
        }
    }

    /**
     * R6.3 — il caso «dato fresco» reso deterministico: si inserisce una riga di cassa a OGGI, che
     * per definizione è il nuovo MAX se non esistono date future. Se il dataset contiene già una
     * data futura (lo fa un'altra classe della suite), il MAX resta quella e il dato è fresco lo
     * stesso: in entrambi i casi il banner dev'essere SPENTO. È l'unica lettura che regge su
     * qualunque composizione della suite.
     *
     * Scrive sul DB e per questo sta in fondo, dopo l'esperimento di P5.
     */
    @Test @Order(102)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p6_cassa_recente_spegne_il_banner() throws Exception {
        insRicavo("ZZP6 cassa di oggi", LocalDate.now(), new java.math.BigDecimal("10.00"));

        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        String ultima = r.path("asIs.ultimaDataCassa");
        assertNotNull(ultima, "appena inserita una riga di cassa, ultimaDataCassa non può essere null");
        assertFalse(LocalDate.parse(ultima).isBefore(LocalDate.now()),
            "il MAX non può essere anteriore alla riga appena inserita, datata oggi");
        assertEquals(Boolean.FALSE, r.path("asIs.datiIncompleti"),
            "con cassa di oggi il dato è fresco: nessun banner");
        assertNull(r.path("asIs.nota"),
            "senza dati incompleti non si scrive una nota: stesso pattern di computeQualita");
    }

    /**
     * R6.4 — `GO_LIVE` è dichiarato UNA volta sola, in {@link Perimetro}. Prima di P6 era
     * duplicato in ReportingService ed EventiService e stava per diventare tre.
     *
     * Il test guarda i sorgenti perché è lì che il difetto vive: due costanti uguali compilano
     * benissimo, e divergono il giorno in cui qualcuno ne cambia una sola.
     */
    @Test @Order(96)
    void p6_goLive_dichiarato_una_volta_sola() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java");
        assertTrue(java.nio.file.Files.isDirectory(src), "atteso di girare dalla root di agos-backend");

        try (var paths = java.nio.file.Files.walk(src)) {
            List<String> definizioni = paths
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    try { return java.nio.file.Files.readString(p).contains("LocalDate.of(2026, 7, 1)"); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .map(java.nio.file.Path::toString)
                .toList();

            assertEquals(List.of(src.resolve("com/agostinelli/gestionale/reporting/Perimetro.java").toString()),
                definizioni,
                "la data di go-live si dichiara solo in Perimetro. Trovata anche in: " + definizioni);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // P7 — stima costi: il gate fail-closed sui dati REALI
    // ═══════════════════════════════════════════════════════════

    /**
     * R7.1 — con i dati di oggi il gate NON passa: la stima costi non esce, e la pagina dice perché.
     *
     * Non è un ripiego, è il «fatto» del passo su questo dataset: con go-live al 01/07/2026 e la
     * finestra che non può scendere sotto di esso (R7.7), tre mesi interi non esistono ancora. Il
     * motore è provato su dataset costruito da {@code ForecastingStimaCostiIntegrationTest}.
     *
     * Il silenzio non basterebbe: senza la nota, «costi previsti 0,00» si legge come «non spendiamo».
     */
    @Test @Order(97)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p7_gate_nonPassa_eLoDice() {
        for (String h : List.of("30", "90", "FINE_ANNO")) {
            Response r = given()
                .queryParam("horizon", h)
                .when().get("/api/reporting/forecasting")
                .then().statusCode(200).extract().response();

            String nota = r.path("economico.notaStimaCosti");
            assertNotNull(nota, "horizon=" + h + ": se la stima non c'è, la pagina deve dire perché");
            assertTrue(nota.startsWith("Stima costi non disponibile"),
                "la nota deve dichiarare l'indisponibilità. Trovato: " + nota);

            List<Map<String, Object>> stimate = r.jsonPath()
                    .<Map<String, Object>>getList("economico.dettaglio").stream()
                    .filter(v -> "STIMATO".equals(v.get("affidabilita")))
                    .filter(v -> toFloat(v.get("importoUscita")) > 0)
                    .toList();
            assertTrue(stimate.isEmpty(),
                "horizon=" + h + ": gate non passato ⇒ nessuna riga di costo stimata, trovate " + stimate);

            float sommaUsciteStimate = 0f;
            for (Map<String, Object> t : r.jsonPath().<Map<String, Object>>getList("finanziario.timeline")) {
                Object u = t.get("usciteStimate");
                assertNotNull(u, "usciteStimate deve essere valorizzato, non null");
                sommaUsciteStimate += toFloat(u);
            }
            assertEquals(0f, sommaUsciteStimate, 0.001f,
                "horizon=" + h + ": Σ usciteStimate deve essere 0,00 col gate chiuso");
        }
    }

    /**
     * G3 — il saldo progressivo si muove SOLO col certo: né entrate né uscite stimate lo toccano.
     * Vale sempre, gate aperto o chiuso; il caso a gate aperto è provato su dataset costruito.
     */
    @Test @Order(98)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void p7_saldoProgressivo_soloCerto() {
        Response r = given()
            .queryParam("horizon", "90")
            .when().get("/api/reporting/forecasting")
            .then().statusCode(200).extract().response();

        float saldo = toFloat(r.path("finanziario.saldoPartenza"));
        for (Map<String, Object> t : r.jsonPath().<Map<String, Object>>getList("finanziario.timeline")) {
            saldo += toFloat(t.get("entratePreviste")) - toFloat(t.get("uscitePreviste"));
            assertEquals(saldo, toFloat(t.get("saldoLiquiditaFine")), 0.05f,
                "bucket " + t.get("bucket") + ": entrateStimate/usciteStimate non devono muovere il saldo");
        }
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
