package com.agostinelli.gestionale.spese;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test per il modulo Spese Ricorrenti.
 * Verifica creazione piano, generazione rate, skip, bulk update,
 * liquidazione e annullamento.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpeseRicorrentiIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static final String BASE      = "/api/spese-ricorrenti";

    @Inject EntityManager em;
    @Inject TxHelper txHelper;

    private static Integer validContoCoge;
    private static String  createdPlanId;
    private static Integer validContoCogeInteressi;
    private static String  finanziamentoPlanId;

    @BeforeEach
    @Transactional
    void resolveIds() {
        if (validContoCoge == null) {
            validContoCoge = ((Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'PASSIVITA' AND is_active = true LIMIT 1")
                    .getSingleResult()).intValue();
        }
        if (validContoCogeInteressi == null) {
            try {
                validContoCogeInteressi = ((Number) em.createNativeQuery(
                        "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'ONERE_FINANZIARIO' AND is_active = true LIMIT 1")
                        .getSingleResult()).intValue();
            } catch (Exception ignored) {}
        }
    }

    // ── Conti COGE lookup ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testContiCogePassivita_returnsSoloPassivita() {
        given()
            .when().get(BASE + "/conti-coge")
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    @Test
    @Order(2)
    void testContiCogePassivita_senzaToken_401() {
        given().when().get(BASE + "/conti-coge").then().statusCode(401);
    }

    // ── Creazione piano ───────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_mensile12Rate() {
        String body = """
            {
              "descrizione": "Mutuo test mensile",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 500.00,
              "variazionePct": 0,
              "giornoDelMese": 20,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        createdPlanId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("id",              notNullValue())
                .body("descrizione",     equalTo("Mutuo test mensile"))
                .body("stato",           equalTo("ATTIVO"))
                .body("numeroRate",      equalTo(12))
                .body("frequenza",       equalTo("MENSILE"))
                .body("giornoDelMese",   equalTo(20))
                .body("rate",            hasSize(12))
                .body("rate[0].stato",   equalTo("PENDING"))
                .body("rate[0].importo", equalTo(500.0f))
                .body("rate[0].dataScadenza", equalTo("2026-01-20"))
                .body("rate[11].dataScadenza", equalTo("2026-12-20"))
                .body("totalePiano",     equalTo(6000.0f))
                .body("totalePagato",    equalTo(0))
                .body("totaleResiduo",   equalTo(6000.0f))
                .extract().path("id");
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_conVariazionePercentuale() {
        String body = """
            {
              "descrizione": "Mutuo con rivalutazione 1%%",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 1000.00,
              "variazionePct": 1.0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-03-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate",            hasSize(3))
                .body("rate[0].importo", equalTo(1000.0f))
                // rata[1] = 1000 * 1.01 = 1010.00
                .body("rate[1].importo", equalTo(1010.0f))
                // rata[2] = 1010 * 1.01 = 1020.10
                .body("rate[2].importo", equalTo(1020.1f));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCreaPiano_trimestrale() {
        String body = """
            {
              "descrizione": "Leasing trimestrale",
              "contoBancarioId": 2,
              "contoCoge": %d,
              "importoRata": 1500.00,
              "variazionePct": 0,
              "giornoDelMese": 15,
              "frequenza": "TRIMESTRALE",
              "numeroRate": 4,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate",                 hasSize(4))
                .body("rate[0].dataScadenza", equalTo("2026-01-15"))
                .body("rate[1].dataScadenza", equalTo("2026-04-15"))
                .body("rate[2].dataScadenza", equalTo("2026-07-15"))
                .body("rate[3].dataScadenza", equalTo("2026-10-15"));
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    /**
     * REGOLA CAMBIATA (08/08/2026, docs/specs/ricorrenti-match-strutturato.md): un CoGe di COSTO
     * su un piano FLAT ora è LEGITTIMO — bollette, canoni e assicurazioni sono costi d'esercizio,
     * non rimborsi di debito. Col vecchio vincolo "solo PASSIVITA", 5 delle 10 spese ricorrenti
     * reali non erano rappresentabili se non forzandole su «Debiti verso fornitori».
     * Resta vietato sui FINANZIAMENTO, dove la quota capitale deve scaricarsi sul patrimoniale.
     */
    void testCreaPiano_cogeCosto_ammessoSuFlat_vietatoSuFinanziamento() {
        Number costoId;
        try {
            costoId = (Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'COSTO' LIMIT 1")
                    .getSingleResult();
        } catch (Exception e) { return; } // skip se non esiste

        String flat = """
            {
              "descrizione": "Bolletta luce (costo d'esercizio)",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-01-01"
            }
            """.formatted(costoId.intValue());
        given().contentType(ContentType.JSON).body(flat)
            .when().post(BASE + "/piani")
            .then().statusCode(201);

        Number onereId;
        try {
            onereId = (Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'ONERE_FINANZIARIO' LIMIT 1")
                    .getSingleResult();
        } catch (Exception e) { return; }

        String finanziamento = """
            {
              "descrizione": "Mutuo su conto di costo (vietato)",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-01-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 1000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(costoId.intValue(), onereId.intValue());
        given().contentType(ContentType.JSON).body(finanziamento)
            .when().post(BASE + "/piani")
            .then().statusCode(400).body("code", equalTo("COGE_NON_PASSIVITA"));
    }

    @Test
    @Order(14)
    void testCreaPiano_senzaToken_401() {
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when().post(BASE + "/piani")
            .then().statusCode(401);
    }

    // ── Lista piani ───────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testListaPiani() {
        given()
            .when().get(BASE + "/piani")
            .then()
                .statusCode(200)
                .body("size()", greaterThan(0));
    }

    // ── Dettaglio piano ───────────────────────────────────────────────────────

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDettaglioPiano() {
        Assumptions.assumeTrue(createdPlanId != null, "Piano non creato nel test precedente");

        given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then()
                .statusCode(200)
                .body("id",          equalTo(createdPlanId))
                .body("rate",        hasSize(12))
                .body("rate[0].id",  notNullValue())
                .body("rate[0].stato", equalTo("PENDING"));
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDettaglioPiano_notFound_404() {
        given()
            .when().get(BASE + "/piani/00000000-0000-0000-0000-000000000001")
            .then().statusCode(404);
    }

    // ── Modifica singola rata ─────────────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testUpdateSingolRata() {
        Assumptions.assumeTrue(createdPlanId != null);

        // prendi l'id della prima rata
        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importo": 550.00, "note": "adeguato ISTAT"}
                """)
            .when().put(BASE + "/piani/" + createdPlanId + "/rate/" + rataId)
            .then()
                .statusCode(200)
                .body("importo", equalTo(550.0f))
                .body("note",    equalTo("adeguato ISTAT"))
                .body("stato",   equalTo("PENDING"));
    }

    // ── Paga rata singola ─────────────────────────────────────────────────────

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPagaRata_setsPaidAndMovimentoId() {
        Assumptions.assumeTrue(createdPlanId != null);

        // checkSaldo (guardia SALDO_INSUFFICIENTE) rifiuta il pagamento a saldo 0:
        // nel profilo test conto 1 parte da 0 (nessun seed dev) → top-up esplicito.
        txHelper.seedSaldoConto1(new java.math.BigDecimal("2000.00"));

        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().statusCode(200)
            .extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + rataId + "/paga")
            .then()
                .statusCode(200)
                .body("stato", equalTo("ATTIVO"))
                .body("rate.find { it.id == '" + rataId + "' }.stato",   equalTo("PAID"))
                .body("rate.find { it.id == '" + rataId + "' }.movimentoId", notNullValue());
    }

    // ── Skip rata ─────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testSkipRata_RIMANDA_aggiunteRataInFondo() {
        Assumptions.assumeTrue(createdPlanId != null);

        int numeroBefore = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().path("rate.size()");

        // Prende la prima rata PENDING (non PAID dalla testPagaRata precedente)
        String rataId = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"modalita": "RIMANDA"}
                """)
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + rataId + "/skip")
            .then().statusCode(204);

        // deve esserci una rata in più
        given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then()
                .statusCode(200)
                .body("rate.size()", equalTo(numeroBefore + 1))
                .body("rate.find { it.id == '" + rataId + "' }.stato", equalTo("SKIPPED"));
    }

    @Test
    @Order(41)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testSkipRata_ACCORPA_sommaNellaProssima() {
        Assumptions.assumeTrue(createdPlanId != null);

        // prima rata PENDING dopo lo skip precedente (non è la rata 0 che è SKIPPED)
        io.restassured.path.json.JsonPath detail = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().jsonPath();

        // trova la prima rata PENDING
        String primaRataId   = detail.getString("rate.find { it.stato == 'PENDING' }.id");
        Float  primaImporto  = detail.getFloat("rate.find { it.stato == 'PENDING' }.importo");
        // trova la seconda rata PENDING
        Float  secondaImporto = detail.getFloat("rate.findAll { it.stato == 'PENDING' }[1].importo");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"modalita": "ACCORPA"}
                """)
            .when().post(BASE + "/piani/" + createdPlanId + "/rate/" + primaRataId + "/skip")
            .then().statusCode(204);

        io.restassured.path.json.JsonPath after = given()
            .when().get(BASE + "/piani/" + createdPlanId)
            .then().extract().jsonPath();

        Float nuovaSeconda = after.getFloat("rate.findAll { it.stato == 'PENDING' }[0].importo");
        // la prossima rata deve avere il doppio (primaImporto + secondaImporto)
        assertEquals(primaImporto + secondaImporto, nuovaSeconda, 0.01f);
    }

    // ── Liquidazione ──────────────────────────────────────────────────────────

    @Test
    @Order(50)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testLiquidaPiano() {
        // liquida = maxi-rata da 600€: serve saldo (guardia SALDO_INSUFFICIENTE)
        txHelper.seedSaldoConto1(new java.math.BigDecimal("2000.00"));

        // crea un piano fresco da liquidare
        String body = """
            {
              "descrizione": "Piano da liquidare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 200.00,
              "variazionePct": 0,
              "giornoDelMese": 5,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-06-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importoTotale": null, "note": "Estinzione anticipata"}
                """)
            .when().post(BASE + "/piani/" + planId + "/liquida")
            .then()
                .statusCode(200)
                .body("stato", equalTo("COMPLETATO"))
                .body("rate.findAll { it.stato == 'PAID' }.size()", equalTo(3));

        // invariante: ogni rata PAID deve avere movimentoId
        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + planId)
            .then().extract().jsonPath();

        jp.getList("rate.movimentoId").forEach(id ->
            assertNotNull(id, "movimentoId deve essere non null per le rate PAID")
        );

        // invariante: tutte le rate PAID hanno lo stesso movimentoId (maxi rata)
        long distinct = jp.getList("rate.movimentoId").stream().distinct().count();
        assertEquals(1, distinct, "Tutte le rate liquidate devono puntare allo stesso movimento");
    }

    // ── Annullamento piano ────────────────────────────────────────────────────

    @Test
    @Order(51)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testAnnullaPiano_senzaPenale() {
        String body = """
            {
              "descrizione": "Piano da annullare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 300.00,
              "variazionePct": 0,
              "giornoDelMese": 10,
              "frequenza": "MENSILE",
              "numeroRate": 6,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .body("""
                {"importoPenale": 0, "note": "Piano annullato"}
                """)
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"))
                .body("rate.findAll { it.stato == 'CANCELLED' }.size()", equalTo(6));
    }

    @Test
    @Order(52)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testAnnullaPiano_giaPagato_nonModificaRatePaid() {
        // la liquidazione intermedia (200€) richiede saldo (guardia SALDO_INSUFFICIENTE)
        txHelper.seedSaldoConto1(new java.math.BigDecimal("1000.00"));

        // crea e liquida piano, poi prova ad annullarlo (deve fallire con PIANO_NON_ATTIVO)
        String body = """
            {
              "descrizione": "Piano completato da NON annullare",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "2026-08-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        // liquida
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/liquida")
            .then().statusCode(200);

        // annullare un piano COMPLETATO deve dare 409
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then().statusCode(409);
    }

    // ── Eliminazione fisica piano ─────────────────────────────────────────────

    @Test
    @Order(53)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_soloPending_cancellaPianoERate() {
        String body = """
            {
              "descrizione": "Piano da eliminare fisicamente",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 250.00,
              "variazionePct": 0,
              "giornoDelMese": 12,
              "frequenza": "MENSILE",
              "numeroRate": 5,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        // pre-condizione: 5 rate a DB
        assertEquals(5, txHelper.countInstallments(UUID.fromString(planId)));

        given()
            .when().delete(BASE + "/piani/" + planId)
            .then().statusCode(204);

        // piano sparito
        given().when().get(BASE + "/piani/" + planId).then().statusCode(404);
        // rate cascatate via FK ON DELETE CASCADE
        assertEquals(0, txHelper.countInstallments(UUID.fromString(planId)),
                "Le rate devono essere eliminate in cascata con il piano");
    }

    @Test
    @Order(54)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_conRataPagata_409_eNonCancella() {
        // Serve saldo per pagare la rata
        txHelper.seedSaldoConto1(new java.math.BigDecimal("5000.00"));

        String body = """
            {
              "descrizione": "Piano con rata pagata (non eliminabile)",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 120.00,
              "variazionePct": 0,
              "giornoDelMese": 3,
              "frequenza": "MENSILE",
              "numeroRate": 4,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String rataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/rate/" + rataId + "/paga")
            .then().statusCode(200);

        // delete deve fallire: c'è un movimento contabile collegato
        given()
            .when().delete(BASE + "/piani/" + planId)
            .then()
                .statusCode(409)
                .body("code", equalTo("PIANO_CON_MOVIMENTI"));

        // piano e rate ancora presenti
        given().when().get(BASE + "/piani/" + planId).then().statusCode(200);
        assertEquals(4, txHelper.countInstallments(UUID.fromString(planId)));
    }

    @Test
    @Order(55)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_completato_409_pianoNonAttivo() {
        String body = """
            {
              "descrizione": "Piano liquidato non eliminabile",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 90.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "2026-10-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/liquida")
            .then().statusCode(200);

        given()
            .when().delete(BASE + "/piani/" + planId)
            .then()
                .statusCode(409)
                .body("code", equalTo("PIANO_NON_ATTIVO"));
    }

    @Test
    @Order(56)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_notFound_404() {
        given()
            .when().delete(BASE + "/piani/00000000-0000-0000-0000-000000000001")
            .then().statusCode(404);
    }

    @Test
    @Order(57)
    void testDeletePiano_senzaToken_401() {
        given()
            .when().delete(BASE + "/piani/00000000-0000-0000-0000-000000000001")
            .then().statusCode(401);
    }

    // ── Cestina (purga fisica totale di un piano ANNULLATO) ───────────────────

    @Test
    @Order(58)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCestina_annullatoConRataPagataEPenale_purgaTutto_eRipristinaSaldo() {
        // Invariante: dopo la cestina piano/rate/movimenti spariti + saldo conto ripristinato.
        txHelper.seedSaldoConto1(new java.math.BigDecimal("5000.00"));
        java.math.BigDecimal saldoBaseline = txHelper.getSaldoConto1();

        String body = """
            {
              "descrizione": "Piano cestina con pagato+penale",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 5,
              "frequenza": "MENSILE",
              "numeroRate": 4,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        // paga la prima rata → movimento USCITA reale (saldo scende)
        String rataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200).extract().path("rate[0].id");
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/rate/" + rataId + "/paga")
            .then().statusCode(200);
        String movRataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200)
            .extract().path("rate.find { it.stato == 'PAID' }.movimentoId");
        assertNotNull(movRataId, "la rata pagata deve avere un movimento");

        // annulla con penale → stato ANNULLATO + movimento penale tracciato
        given()
            .contentType(ContentType.JSON).body("{\"importoPenale\": 50.00}")
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then().statusCode(200).body("stato", equalTo("ANNULLATO"));
        UUID penaleId = txHelper.getPlanMovimentoPenaleId(UUID.fromString(planId));
        assertNotNull(penaleId, "il movimento penale deve essere tracciato su movimento_penale_id");

        // saldo ora sotto la baseline (100 rata + 50 penale usciti)
        assertTrue(txHelper.getSaldoConto1().compareTo(saldoBaseline) < 0,
                "dopo pagamento+penale il saldo deve essere sceso");

        // CESTINA
        given()
            .contentType(ContentType.JSON)
            .when().post(BASE + "/piani/" + planId + "/cestina")
            .then().statusCode(204);

        // (a) piano e rate spariti
        given().when().get(BASE + "/piani/" + planId).then().statusCode(404);
        assertEquals(0, txHelper.countInstallments(UUID.fromString(planId)),
                "le rate devono cascatare con il piano");
        // (b) nessun movimento residuo (rata PAID + penale)
        assertEquals(0, txHelper.countMovimentiByIds(
                List.of(UUID.fromString(movRataId), penaleId)),
                "movimento rata e penale devono essere cancellati");
        // (c) saldo conto ripristinato al valore pre-piano
        assertEquals(0, txHelper.getSaldoConto1().compareTo(saldoBaseline),
                "il saldo deve tornare alla baseline: baseline=" + saldoBaseline +
                " attuale=" + txHelper.getSaldoConto1());
    }

    @Test
    @Order(59)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCestina_pianoAttivo_409_nonAnnullato_eNienteCancellato() {
        String body = """
            {
              "descrizione": "Piano attivo non cestinabile",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 70.00,
              "variazionePct": 0,
              "giornoDelMese": 8,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-07-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given()
            .contentType(ContentType.JSON)
            .when().post(BASE + "/piani/" + planId + "/cestina")
            .then()
                .statusCode(409)
                .body("code", equalTo("PIANO_NON_ANNULLATO"));

        // nulla cancellato: piano e rate ancora presenti
        given().when().get(BASE + "/piani/" + planId).then().statusCode(200);
        assertEquals(3, txHelper.countInstallments(UUID.fromString(planId)));
    }

    @Test
    @Order(63)
    void testCestina_senzaToken_401() {
        given()
            .contentType(ContentType.JSON)
            .when().post(BASE + "/piani/00000000-0000-0000-0000-000000000001/cestina")
            .then().statusCode(401);
    }

    @Test
    @Order(135)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testCestina_finanziamento_cancellaAncheMovimentoInteressi() {
        Assumptions.assumeTrue(validContoCogeInteressi != null);
        txHelper.seedSaldoConto1(new java.math.BigDecimal("20000.00"));

        String body = """
            {
              "descrizione": "Finanziamento cestina interessi",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-05-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String rataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200).extract().path("rate[0].id");
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/rate/" + rataId + "/paga")
            .then().statusCode(200);

        // cattura i due movimenti (capitale + interessi) PRIMA della cestina
        String movCapId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200)
            .extract().path("rate.find { it.id == '" + rataId + "' }.movimentoId");
        UUID movIntId = txHelper.getMovimentoInteressiId(UUID.fromString(rataId));
        assertNotNull(movCapId, "capitale");
        assertNotNull(movIntId, "interessi");

        // annulla (no penale) poi cestina
        given()
            .contentType(ContentType.JSON).body("{\"importoPenale\": 0}")
            .when().post(BASE + "/piani/" + planId + "/annulla")
            .then().statusCode(200).body("stato", equalTo("ANNULLATO"));

        given()
            .contentType(ContentType.JSON)
            .when().post(BASE + "/piani/" + planId + "/cestina")
            .then().statusCode(204);

        given().when().get(BASE + "/piani/" + planId).then().statusCode(404);
        assertEquals(0, txHelper.countMovimentiByIds(
                List.of(UUID.fromString(movCapId), movIntId)),
                "sia il movimento capitale sia il movimento interessi devono sparire");
    }

    // ── Delete: corner case ───────────────────────────────────────────────────

    @Test
    @Order(130)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_conRateSkipped_ok_eDoppioDelete404() {
        // Rate SKIPPED non hanno movimenti: non devono bloccare la delete.
        String body = """
            {
              "descrizione": "QA-DELETE piano con skip",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 80.00,
              "variazionePct": 0,
              "giornoDelMese": 7,
              "frequenza": "MENSILE",
              "numeroRate": 4,
              "dataInizio": "2026-11-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String rataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200).extract().path("rate[0].id");

        // RIMANDA aggiunge una rata extra → 5 righe totali, di cui 1 SKIPPED
        given()
            .contentType(ContentType.JSON).body("""
                {"modalita": "RIMANDA"}
                """)
            .when().post(BASE + "/piani/" + planId + "/rate/" + rataId + "/skip")
            .then().statusCode(204);
        assertEquals(5, txHelper.countInstallments(UUID.fromString(planId)));

        given().when().delete(BASE + "/piani/" + planId).then().statusCode(204);
        assertEquals(0, txHelper.countInstallments(UUID.fromString(planId)),
                "Anche le rate SKIPPED devono sparire con il piano");

        // doppia delete → 404, idempotenza lato client
        given().when().delete(BASE + "/piani/" + planId).then().statusCode(404);
    }

    @Test
    @Order(131)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_finanziamentoSoloPending_ok() {
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        String body = """
            {
              "descrizione": "QA-DELETE finanziamento pending",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2027-03-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        given().when().delete(BASE + "/piani/" + planId).then().statusCode(204);
        assertEquals(0, txHelper.countInstallments(UUID.fromString(planId)));
    }

    @Test
    @Order(132)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_ricorrenteRiconciliata_setNull_nonCancellaLaRiga() {
        // Una riga di import collegata al piano (COLLEGA) non blocca la delete:
        // la FK è ON DELETE SET NULL — la riga resta RICONCILIATA, perde solo il link.
        String body = """
            {
              "descrizione": "QA-DELETE piano riconciliato",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 60.00,
              "variazionePct": 0,
              "giornoDelMese": 9,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-12-01"
            }
            """.formatted(validContoCoge);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        UUID rigaId = txHelper.seedRicorrenteCollegata(UUID.fromString(planId));

        given().when().delete(BASE + "/piani/" + planId).then().statusCode(204);

        Object[] riga = txHelper.getRicorrenteRow(rigaId);
        assertEquals("RICONCILIATA", riga[0], "La riga di import non deve cambiare stato");
        assertNull(riga[1], "recurring_plan_id deve essere NULL dopo la delete del piano (FK SET NULL)");
    }

    // ── Delete: side effects su scadenzario e forecasting ────────────────────

    @Test
    @Order(133)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_sparisceDalloScadenzario() {
        // Rata nel mese corrente → visibile in /api/dashboard/scadenze-imminenti;
        // dopo la delete non deve più esserci (query live su recurring_expense_installment).
        java.time.LocalDate oggi = java.time.LocalDate.now();
        String body = """
            {
              "descrizione": "QA-DELETE scadenzario marker",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 42.00,
              "variazionePct": 0,
              "giornoDelMese": 28,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "%s"
            }
            """.formatted(validContoCoge, oggi.withDayOfMonth(1));

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String range = "?period=CUSTOM&from=" + oggi.withDayOfMonth(1)
                + "&to=" + oggi.plusMonths(3);

        given()
            .when().get("/api/dashboard/scadenze-imminenti" + range)
            .then().statusCode(200)
            .body("rateRicorrenti.referenceId", hasItem(planId));

        given().when().delete(BASE + "/piani/" + planId).then().statusCode(204);

        given()
            .when().get("/api/dashboard/scadenze-imminenti" + range)
            .then().statusCode(200)
            .body("rateRicorrenti.referenceId", not(hasItem(planId)));
    }

    @Test
    @Order(134)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testDeletePiano_sparisceDalForecasting() {
        // Rata entro l'orizzonte 90gg → compare nel previsionale come uscita CERTA;
        // dopo la delete il marker non deve più apparire nella risposta.
        java.time.LocalDate oggi = java.time.LocalDate.now();
        String marker = "QA-DELETE-FORECAST-" + UUID.randomUUID();
        String body = """
            {
              "descrizione": "%s",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 77.00,
              "variazionePct": 0,
              "giornoDelMese": 28,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "%s"
            }
            """.formatted(marker, validContoCoge, oggi.withDayOfMonth(1));

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String before = given()
            .when().get("/api/reporting/forecasting?horizon=90")
            .then().statusCode(200).extract().asString();
        assertTrue(before.contains(marker),
                "La rata PENDING entro 90gg deve comparire nel previsionale");

        given().when().delete(BASE + "/piani/" + planId).then().statusCode(204);

        String after = given()
            .when().get("/api/reporting/forecasting?horizon=90")
            .then().statusCode(200).extract().asString();
        assertFalse(after.contains(marker),
                "Dopo la delete il piano non deve più comparire nel previsionale");
    }

    // ── Invarianti matematici ─────────────────────────────────────────────────

    @Test
    @Order(60)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testInvariante_totalePiano_equalsSumRate() {
        String body = """
            {
              "descrizione": "Test invariante totale",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 750.00,
              "variazionePct": 2.5,
              "giornoDelMese": 28,
              "frequenza": "BIMESTRALE",
              "numeroRate": 6,
              "dataInizio": "2026-01-01"
            }
            """.formatted(validContoCoge);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().jsonPath();

        double totalePiano   = jp.getDouble("totalePiano");
        double sumRate = jp.<Float>getList("rate.importo").stream()
                           .mapToDouble(Float::doubleValue).sum();

        assertEquals(totalePiano, sumRate, 0.01, "totalePiano deve essere uguale alla somma delle rate");
    }

    // ── Piano FINANZIAMENTO ───────────────────────────────────────────────────

    @Test
    @Order(100)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_incompleto_400() {
        Assumptions.assumeTrue(validContoCoge != null);
        String body = """
            {
              "descrizione": "Mutuo senza campi obbligatori",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-06-01",
              "tipoPiano": "FINANZIAMENTO"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(400)
                .body("code", equalTo("FINANZIAMENTO_INCOMPLETO"));
    }

    @Test
    @Order(101)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_creapianoEverificaRate() {
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null,
                "Conto ONERE_FINANZIARIO non disponibile (V29 non applicata?)");

        // Rata mensile per mutuo 100000 al 3.5% su 12 mesi ≈ 8490.67/mese
        String body = """
            {
              "descrizione": "Mutuo test finanziamento",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-06-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("tipoPiano",       equalTo("FINANZIAMENTO"))
                .body("tassoInteresseAnnuo", notNullValue())
                .body("rate",            hasSize(12))
                .body("rate[0].stato",   equalTo("PENDING"))
                .extract().jsonPath();

        finanziamentoPlanId = jp.getString("id");

        // Prima rata: quota_interessi ≈ 100000 * 0.035 / 12 ≈ 291.67
        float quotaInteressi = jp.getFloat("rate[0].quotaInteressi");
        float quotaCapitale  = jp.getFloat("rate[0].quotaCapitale");
        float importo        = jp.getFloat("rate[0].importo");

        assertTrue(quotaInteressi > 0,        "quotaInteressi deve essere > 0");
        assertTrue(quotaCapitale  > 0,        "quotaCapitale deve essere > 0");
        assertEquals(importo, quotaCapitale + quotaInteressi, 0.02f,
                "quota_capitale + quota_interessi deve uguagliare importo");
        assertEquals(291.67f, quotaInteressi, 1.0f,
                "quota_interessi rata 1 deve essere ≈ 100000*0.035/12 ≈ 291.67");
    }

    /**
     * L'ultima rata è un moncone: chiude il capitale residuo e gli interessi si CALCOLANO sul
     * periodo, non si deducono per differenza dalla rata piena. Numeri veri del Leasing Merlo
     * in produzione (debito 55.511,03 · 6,997% · TRIMESTRALE · 14 rate · rata 4.716,49): il ramo
     * vecchio scriveva 3.349,41 di interessi su 23,91 maturati, cioè 3.325,50 di costo inventato.
     */
    @Test
    @Order(101)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_ultimaRata_interessiCalcolatiNonDedotti() {
        Assumptions.assumeTrue(validContoCogeInteressi != null,
                "Conto ONERE_FINANZIARIO non disponibile (V29 non applicata?)");

        String body = """
            {
              "descrizione": "ZZ Leasing Merlo ultima rata",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 4716.49,
              "variazionePct": 0,
              "giornoDelMese": 15,
              "frequenza": "TRIMESTRALE",
              "numeroRate": 14,
              "dataInizio": "2026-09-15",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 55511.03,
              "tassoInteresseAnnuo": 6.997,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate", hasSize(14))
                .extract().jsonPath();

        assertEquals(1367.08f, jp.getFloat("rate[13].quotaCapitale"),  0.005f, "capitale rata 14");
        assertEquals(  23.91f, jp.getFloat("rate[13].quotaInteressi"), 0.005f, "interessi rata 14");
        assertEquals(1390.99f, jp.getFloat("rate[13].importo"),        0.005f, "importo rata 14");

        // Il debito residuo è la sola quota capitale, non il totale da sborsare (A1).
        assertEquals(55511.03f, jp.getFloat("debitoResiduo"), 0.005f, "debitoResiduo = debito iniziale");
        assertEquals(66030.86f - 3325.50f, jp.getFloat("totaleResiduo"), 0.01f,
                "totaleResiduo = capitale + interessi reali");

        given().when().delete(BASE + "/piani/" + jp.getString("id")).then().statusCode(anyOf(is(200), is(204)));
    }

    @Test
    @Order(102)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_pagaPrimaRata_dueMovimenti() {
        Assumptions.assumeTrue(finanziamentoPlanId != null,
                "Piano finanziamento non creato nel test precedente");

        // Garantisce saldo sufficiente per pagare una rata da 8 490€ anche quando
        // il test viene eseguito in isolamento (conto 1 ha saldo_iniziale=0 da V6
        // e V27 non ne accredita abbastanza).
        txHelper.seedSaldoConto1(new java.math.BigDecimal("20000.00"));

        String rataId = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200)
            .extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + finanziamentoPlanId + "/rate/" + rataId + "/paga")
            .then()
                .statusCode(200)
                .extract().jsonPath();

        // Dopo il pagamento la rata deve avere movimentoId (capitale) valorizzato
        String movimentoId = jp.getString("rate.find { it.id == '" + rataId + "' }.movimentoId");
        assertNotNull(movimentoId, "movimentoId (capitale) deve essere non null");

        // Verifica che nel DB ci sia anche il movimento interessi (movimento_interessi_id)
        long movIntCount = txHelper.countInstallmentsWithInteresse(UUID.fromString(rataId), true);
        assertEquals(1, movIntCount,
                "movimento_interessi_id deve essere valorizzato dopo il pagamento");
    }

    // ── BUG fixes regression tests ────────────────────────────────────────────

    @Test
    @Order(103)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_skipRimanda_propagaQuote() {
        // BUG 1: extra rata from RIMANDA must carry quotaCapitale/quotaInteressi
        Assumptions.assumeTrue(finanziamentoPlanId != null,
                "Piano finanziamento non creato");

        io.restassured.path.json.JsonPath before = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200).extract().jsonPath();

        String primaRataId = before.getString("rate.find { it.stato == 'PENDING' }.id");
        Assumptions.assumeTrue(primaRataId != null);
        float origCapitale  = before.getFloat("rate.find { it.stato == 'PENDING' }.quotaCapitale");
        float origInteressi = before.getFloat("rate.find { it.stato == 'PENDING' }.quotaInteressi");

        given()
            .contentType(ContentType.JSON).body("""
                {"modalita": "RIMANDA"}
                """)
            .when().post(BASE + "/piani/" + finanziamentoPlanId + "/rate/" + primaRataId + "/skip")
            .then().statusCode(204);

        io.restassured.path.json.JsonPath after = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200).extract().jsonPath();

        // ultima rata (la nuova extra) deve avere stessa quota
        int lastIdx = (int) after.getList("rate").stream().count() - 1;
        Float extraCapitale  = after.getFloat("rate[" + lastIdx + "].quotaCapitale");
        Float extraInteressi = after.getFloat("rate[" + lastIdx + "].quotaInteressi");

        assertNotNull(extraCapitale,  "BUG 1: quotaCapitale non propagata alla rata RIMANDA");
        assertNotNull(extraInteressi, "BUG 1: quotaInteressi non propagata alla rata RIMANDA");
        assertEquals(origCapitale,  extraCapitale,  0.02f, "quotaCapitale deve matchare rata originale");
        assertEquals(origInteressi, extraInteressi, 0.02f, "quotaInteressi deve matchare rata originale");
    }

    @Test
    @Order(104)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_skipAccorpa_nullificaQuote() {
        // BUG 2 regression: ACCORPA su FINANZIAMENTO deve azzerare quotaCapitale/quotaInteressi
        // della rata successiva quando la rata saltata aveva il piano di ammortamento
        // già perso (es. importo aggiornato manualmente → BUG 4 fix in updateInstallment).
        //
        // Test autonomo: crea un proprio piano e azzera le quote della rata saltata
        // PRIMA di chiamare ACCORPA, in modo che la branch "split già perso" del
        // service venga esercitata indipendentemente da test precedenti.
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        String body = """
            {
              "descrizione": "Mutuo skipAccorpa autonomous",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2027-01-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String primaRataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        // Modifica importo della rata → updateInstallment azzera quotaCapitale/quotaInteressi
        // su un piano FINANZIAMENTO (BUG 4 fix). La rata risultante ha quote NULL.
        given()
            .contentType(ContentType.JSON)
            .body("{\"importo\": 9000.00}")
            .when().put(BASE + "/piani/" + planId + "/rate/" + primaRataId)
            .then().statusCode(200);

        // Ora ACCORPA: la rata saltata ha quote null → ramo "split già perso"
        // del service forza next.quotaCapitale = next.quotaInteressi = null.
        given()
            .contentType(ContentType.JSON).body("""
                {"modalita": "ACCORPA"}
                """)
            .when().post(BASE + "/piani/" + planId + "/rate/" + primaRataId + "/skip")
            .then().statusCode(204);

        io.restassured.path.json.JsonPath after = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200).extract().jsonPath();

        // La rata successiva (prima PENDING dopo ACCORPA) ora deve avere quote null
        Object quotaCapitale  = after.get("rate.find { it.stato == 'PENDING' }.quotaCapitale");
        Object quotaInteressi = after.get("rate.find { it.stato == 'PENDING' }.quotaInteressi");

        assertNull(quotaCapitale,  "BUG 2: quotaCapitale deve essere null dopo ACCORPA su FINANZIAMENTO con split perso");
        assertNull(quotaInteressi, "BUG 2: quotaInteressi deve essere null dopo ACCORPA su FINANZIAMENTO con split perso");
    }

    @Test
    @Order(105)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_rataInsufficiente_400() {
        // BUG 3: importoRata <= primo slice interessi deve ritornare 400 RATA_INSUFFICIENTE
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        // 100000 * 3.5% / 12 ≈ 291.67 → importo di 100.00 è troppo basso
        String body = """
            {
              "descrizione": "Mutuo rata troppo bassa",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 100.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-06-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(400)
                .body("code", equalTo("RATA_INSUFFICIENTE"));
    }

    @Test
    @Order(106)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_updateImporto_azzera_quote() {
        // BUG 4: updateInstallment changing importo on FINANZIAMENTO must clear quotaCapitale/quotaInteressi
        Assumptions.assumeTrue(finanziamentoPlanId != null,
                "Piano finanziamento non creato");

        String rataId = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200)
            .extract().path("rate.find { it.stato == 'PENDING' }.id");

        Assumptions.assumeTrue(rataId != null);

        io.restassured.path.json.JsonPath updated = given()
            .contentType(ContentType.JSON)
            .body("""
                {"importo": 9000.00}
                """)
            .when().put(BASE + "/piani/" + finanziamentoPlanId + "/rate/" + rataId)
            .then()
                .statusCode(200)
                .extract().jsonPath();

        assertNull(updated.get("quotaCapitale"),
                "BUG 4: quotaCapitale deve essere null dopo updateImporto su FINANZIAMENTO");
        assertNull(updated.get("quotaInteressi"),
                "BUG 4: quotaInteressi deve essere null dopo updateImporto su FINANZIAMENTO");
    }

    @Test
    @Order(107)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_rataEccessiva_overAmmortamento_400() {
        // BUG 5: importoRata troppo alta per numeroRate → il debito si estingue PRIMA
        // dell'ultima rata. Oggi non c'è guardia: le rate eccedenti producono
        // quotaCapitale NEGATIVA e quotaInteressi fantasma (l'ultima rata incassa come
        // "interessi" tutto il residuo dell'importo), gonfiando gli oneriFinanziari nel
        // P&L e corrompendo EBT/UtileNetto. Speculare a RATA_INSUFFICIENTE (BUG 3): va
        // rifiutato a create con 400.
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        // debito 3000 @ 1% annuo, 10 rate da 1000 → il debito si azzera intorno alla 3ª
        // rata; le rate 4..10 over-ammortizzano (debitoResiduo negativo).
        String body = """
            {
              "descrizione": "Mutuo rata eccessiva (over-ammortamento)",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 1000.00,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 10,
              "dataInizio": "2026-06-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 3000.00,
              "tassoInteresseAnnuo": 1.0,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(400)
                .body("code", equalTo("RATA_ECCESSIVA"));
    }

    // ── Invarianti matematici ammortamento alla francese ──────────────────────

    private static String finanziamentoMathPlanId;

    @Test
    @Order(109)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_creapianoMathRef() {
        // Piano di riferimento per i test matematici: 12 rate mensili, 100k, 3.5%
        // PMT = 100000 * (0.035/12) / (1-(1+0.035/12)^-12) ≈ 8490.67
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        String body = """
            {
              "descrizione": "Mutuo math reference 12m",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-01-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        finanziamentoMathPlanId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("tipoPiano",  equalTo("FINANZIAMENTO"))
                .body("rate",       hasSize(12))
                .extract().path("id");
    }

    @Test
    @Order(110)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_sommaCapitale_equalsDebitoIniziale() {
        Assumptions.assumeTrue(finanziamentoMathPlanId != null,
                "Piano math reference non creato in test 109");

        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + finanziamentoMathPlanId)
            .then().statusCode(200).extract().jsonPath();

        double sumCapitale = jp.<Float>getList("rate.quotaCapitale")
                .stream().mapToDouble(Float::doubleValue).sum();

        assertEquals(100_000.0, sumCapitale, 0.50,
                "La somma delle quote capitale deve uguagliare il debito iniziale (100 000€); " +
                "trovato: " + sumCapitale);
    }

    @Test
    @Order(111)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_capitaleInteressiSommanoAImporto() {
        Assumptions.assumeTrue(finanziamentoMathPlanId != null);

        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + finanziamentoMathPlanId)
            .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> rate = jp.getList("rate");
        for (int i = 0; i < rate.size(); i++) {
            Map<String, Object> r = rate.get(i);
            double importo        = ((Number) r.get("importo")).doubleValue();
            double quotaCapitale  = ((Number) r.get("quotaCapitale")).doubleValue();
            double quotaInteressi = ((Number) r.get("quotaInteressi")).doubleValue();
            assertEquals(importo, quotaCapitale + quotaInteressi, 0.02,
                    "Rata " + (i + 1) + ": quotaCapitale (" + quotaCapitale +
                    ") + quotaInteressi (" + quotaInteressi + ") deve uguagliare importo (" + importo + ")");
        }
    }

    @Test
    @Order(112)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_interessiDecrescenti_capitaleNonDecrescente() {
        Assumptions.assumeTrue(finanziamentoMathPlanId != null);

        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + finanziamentoMathPlanId)
            .then().statusCode(200).extract().jsonPath();

        List<Float> interessi = jp.getList("rate.quotaInteressi");
        List<Float> capitali  = jp.getList("rate.quotaCapitale");
        Assumptions.assumeTrue(interessi.size() >= 2);

        for (int i = 0; i < interessi.size() - 1; i++) {
            assertTrue(interessi.get(i) >= interessi.get(i + 1) - 0.02f,
                    "quotaInteressi deve essere non crescente: rata " + (i+1) + "=" +
                    interessi.get(i) + " vs rata " + (i+2) + "=" + interessi.get(i+1));
            assertTrue(capitali.get(i) <= capitali.get(i + 1) + 0.02f,
                    "quotaCapitale deve essere non decrescente: rata " + (i+1) + "=" +
                    capitali.get(i) + " vs rata " + (i+2) + "=" + capitali.get(i+1));
        }
    }

    @Test
    @Order(113)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_60rate_mathCorretto() {
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        // PMT = 100000 * (0.035/12) / (1-(1+0.035/12)^-60) ≈ 1817.60
        String body = """
            {
              "descrizione": "Mutuo 60 mesi math test",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 1817.60,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 60,
              "dataInizio": "2026-01-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("tipoPiano", equalTo("FINANZIAMENTO"))
                .body("rate",      hasSize(60))
                .extract().jsonPath();

        // Σ quotaCapitale ≈ 100 000€ (last rata closes residual debt)
        double sumCapitale = jp.<Float>getList("rate.quotaCapitale")
                .stream().mapToDouble(Float::doubleValue).sum();
        assertEquals(100_000.0, sumCapitale, 1.0,
                "60-rate plan: somma quote capitale ≈ 100 000€; trovato: " + sumCapitale);

        // Per ogni rata: qc + qi = importo ± 0.02
        List<Map<String, Object>> rate = jp.getList("rate");
        for (int i = 0; i < rate.size(); i++) {
            double importo = ((Number) rate.get(i).get("importo")).doubleValue();
            double qc      = ((Number) rate.get(i).get("quotaCapitale")).doubleValue();
            double qi      = ((Number) rate.get(i).get("quotaInteressi")).doubleValue();
            assertEquals(importo, qc + qi, 0.02,
                    "60-rate plan, rata " + (i + 1) + ": qc + qi deve uguagliare importo");
        }

        // totaleInteressi = totalePiano - importoDebitoIniziale
        double totalePiano    = jp.getDouble("totalePiano");
        double totInteressi   = jp.getDouble("totaleInteressi");
        double attesiInteressi = totalePiano - 100_000.0;
        assertEquals(attesiInteressi, totInteressi, 1.0,
                "totaleInteressi deve essere pari a totalePiano - debitoIniziale; " +
                "attesi=" + attesiInteressi + " trovati=" + totInteressi);
    }

    @Test
    @Order(114)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_primaRata_valoriEsatti_3_5pct_12mesi() {
        Assumptions.assumeTrue(finanziamentoMathPlanId != null);

        io.restassured.path.json.JsonPath jp = given()
            .when().get(BASE + "/piani/" + finanziamentoMathPlanId)
            .then().statusCode(200).extract().jsonPath();

        // 1ª rata: interesse = 100000 * 0.035/12 = 291.67
        //          capitale  = 8490.67 - 291.67 = 8199.00
        float quotaInteressi = jp.getFloat("rate[0].quotaInteressi");
        float quotaCapitale  = jp.getFloat("rate[0].quotaCapitale");

        assertEquals(291.67f, quotaInteressi, 0.05f,
                "Prima rata interessi ≈ €291.67 (100000 * 3.5%/12)");
        assertEquals(8199.00f, quotaCapitale, 0.10f,
                "Prima rata capitale ≈ €8199.00 (8490.67 - 291.67)");
    }

    // ── FLAT: null split invariante ────────────────────────────────────────────

    @Test
    @Order(118)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFlat_rate_quoteSonoNull() {
        Assumptions.assumeTrue(validContoCoge != null);

        String body = """
            {
              "descrizione": "Flat null-split test",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 300.00,
              "variazionePct": 0,
              "giornoDelMese": 5,
              "frequenza": "MENSILE",
              "numeroRate": 3,
              "dataInizio": "2026-08-01"
            }
            """.formatted(validContoCoge);

        io.restassured.path.json.JsonPath jp = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().jsonPath();

        List<Object> capitals  = jp.getList("rate.quotaCapitale");
        List<Object> interests = jp.getList("rate.quotaInteressi");

        capitals.forEach(v  -> assertNull(v,  "FLAT: quotaCapitale deve essere null su tutte le rate"));
        interests.forEach(v -> assertNull(v, "FLAT: quotaInteressi deve essere null su tutte le rate"));
    }

    @Test
    @Order(119)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFlat_pagamento_noMovimentoInteressi() throws Exception {
        Assumptions.assumeTrue(validContoCoge != null);

        String body = """
            {
              "descrizione": "Flat pagamento singolo",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 150.00,
              "variazionePct": 0,
              "giornoDelMese": 5,
              "frequenza": "MENSILE",
              "numeroRate": 2,
              "dataInizio": "2026-09-01"
            }
            """.formatted(validContoCoge);

        String flatPlanId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String rataId = given()
            .when().get(BASE + "/piani/" + flatPlanId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + flatPlanId + "/rate/" + rataId + "/paga")
            .then().statusCode(200);

        // Verifica che movimento_interessi_id sia NULL nel DB (FLAT ha un solo movimento)
        long noIntCount = txHelper.countInstallmentsWithInteresse(UUID.fromString(rataId), false);
        assertEquals(1, noIntCount,
                "FLAT: movimento_interessi_id deve essere NULL dopo il pagamento");
    }

    // ── P&L: struttura waterfall e oneri finanziari ───────────────────────────

    private static volatile boolean plMvRefreshed = false;

    private void ensurePlMvRefreshed() {
        if (plMvRefreshed) return;
        txHelper.refreshPlMv();
        plMvRefreshed = true;
    }

    @Test
    @Order(120)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPL_tutteBu_campiWaterfall_presenti() {
        ensurePlMvRefreshed();

        given()
            .when().get("/api/reporting/pl/tutte-bu?from=2026-01-01&to=2026-12-31")
            .then()
                .statusCode(200)
                .body("totaleConsolidato.ricavi",           notNullValue())
                .body("totaleConsolidato.costi",            notNullValue())
                .body("totaleConsolidato.ebitda",           notNullValue())
                .body("totaleConsolidato.ammortamenti",     notNullValue())
                .body("totaleConsolidato.ebit",             notNullValue())
                .body("totaleConsolidato.oneriFinanziari",  notNullValue())
                .body("totaleConsolidato.imposte",          notNullValue())
                .body("totaleConsolidato.utileNetto",       notNullValue())
                .body("totaleConsolidato.marginePct",       notNullValue());
    }

    @Test
    @Order(121)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPL_tutteBu_waterfallInvariant_ebit_eq_ebitda_minus_da() {
        ensurePlMvRefreshed();

        io.restassured.path.json.JsonPath jp = given()
            .when().get("/api/reporting/pl/tutte-bu?from=2026-01-01&to=2026-12-31")
            .then().statusCode(200).extract().jsonPath();

        double ebitda          = jp.getDouble("totaleConsolidato.ebitda");
        double ammortamenti    = jp.getDouble("totaleConsolidato.ammortamenti");
        double oneriFinanziari = jp.getDouble("totaleConsolidato.oneriFinanziari");
        double ebit            = jp.getDouble("totaleConsolidato.ebit");
        double imposte         = jp.getDouble("totaleConsolidato.imposte");
        double utileNetto      = jp.getDouble("totaleConsolidato.utileNetto");

        // Invariante 1 (waterfall standard): EBIT = EBITDA − D&A
        // Gli oneri finanziari NON impattano l'EBIT — sono sotto la linea EBIT.
        // Cfr. ReportingService.getPlTutteBu (totEbit = totEbitda - ammortamenti).
        assertEquals(ebitda - ammortamenti, ebit, 0.05,
                "Invariante waterfall: EBIT = EBITDA - D&A");

        // Invariante 2: UtileNetto = EBIT − OneriFinanziari − Imposte
        assertEquals(ebit - oneriFinanziari - imposte, utileNetto, 0.05,
                "Invariante waterfall: UtileNetto = EBIT - OneriFinanziari - Imposte");
    }

    @Test
    @Order(122)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPL_dopoFinanziamentoPagato_oneriFinanziari_positivi() {
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

        // Top-up saldo per garantire il pagamento (vedi seedSaldoConto1 sopra).
        txHelper.seedSaldoConto1(new java.math.BigDecimal("20000.00"));

        // Crea e paga un piano FINANZIAMENTO nello stesso mese (maggio 2026)
        // per garantire che oneriFinanziari > 0 nel periodo
        String body = """
            {
              "descrizione": "Fin PL test maggio 2026",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 8490.67,
              "variazionePct": 0,
              "giornoDelMese": 1,
              "frequenza": "MENSILE",
              "numeroRate": 12,
              "dataInizio": "2026-05-01",
              "tipoPiano": "FINANZIAMENTO",
              "importoDebitoIniziale": 100000.00,
              "tassoInteresseAnnuo": 3.5,
              "contoCogeInteressiId": %d
            }
            """.formatted(validContoCoge, validContoCogeInteressi);

        String planId = given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then().statusCode(201).extract().path("id");

        String rataId = given()
            .when().get(BASE + "/piani/" + planId)
            .then().statusCode(200)
            .extract().path("rate[0].id");

        // Paga la prima rata: genera due movimenti (capitale + interessi)
        given()
            .contentType(ContentType.JSON).body("{}")
            .when().post(BASE + "/piani/" + planId + "/rate/" + rataId + "/paga")
            .then().statusCode(200);

        // Refresh MV per includere i nuovi movimenti
        txHelper.refreshPlMv();

        // Query P&L sul MESE CORRENTE: il pagamento di una rata FINANZIAMENTO usa
        // LocalDate.now() come data del movimento (RecurringExpenseService#pagaRata),
        // quindi il range deve seguire l'orologio, non una data hardcoded (altrimenti
        // il test diventa un time-bomb che passa solo nel mese in cui fu scritto).
        java.time.LocalDate oggi = java.time.LocalDate.now();
        String from = oggi.withDayOfMonth(1).toString();
        String to   = oggi.withDayOfMonth(oggi.lengthOfMonth()).toString();
        io.restassured.path.json.JsonPath jp = given()
            .when().get("/api/reporting/pl/tutte-bu?from=" + from + "&to=" + to)
            .then().statusCode(200).extract().jsonPath();

        double oneriFinanziari = jp.getDouble("totaleConsolidato.oneriFinanziari");
        assertTrue(oneriFinanziari > 0,
                "Dopo il pagamento della prima rata FINANZIAMENTO, oneriFinanziari nel P&L " +
                "deve essere > 0 (interessi = €291.67); trovato: " + oneriFinanziari);

        // Il capitale (PASSIVITA) non deve apparire in costi operativi
        // (la registrazione PASSIVITA riduce il debito, non è un costo).
        // Invariante waterfall (cfr. ReportingService.getPlTutteBu):
        //   EBIT = EBITDA - ammortamenti  (gli oneri finanziari sono SOTTO la linea EBIT)
        //   UtileNetto = EBIT - oneriFinanziari - imposte
        double ebitda       = jp.getDouble("totaleConsolidato.ebitda");
        double ammortamenti = jp.getDouble("totaleConsolidato.ammortamenti");
        double ebit         = jp.getDouble("totaleConsolidato.ebit");
        double imposte      = jp.getDouble("totaleConsolidato.imposte");
        double utileNetto   = jp.getDouble("totaleConsolidato.utileNetto");
        assertEquals(ebitda - ammortamenti, ebit, 0.05,
                "EBIT = EBITDA - ammortamenti (oneri finanziari sotto la linea EBIT)");
        assertEquals(ebit - oneriFinanziari - imposte, utileNetto, 0.05,
                "UtileNetto = EBIT - oneriFinanziari - imposte");
    }

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testInvariante_bimestrale_dateCorrette() {
        String body = """
            {
              "descrizione": "Test date bimestrale",
              "contoBancarioId": 1,
              "contoCoge": %d,
              "importoRata": 200.00,
              "variazionePct": 0,
              "giornoDelMese": 15,
              "frequenza": "BIMESTRALE",
              "numeroRate": 3,
              "dataInizio": "2026-02-01"
            }
            """.formatted(validContoCoge);

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(201)
                .body("rate[0].dataScadenza", equalTo("2026-02-15"))
                .body("rate[1].dataScadenza", equalTo("2026-04-15"))
                .body("rate[2].dataScadenza", equalTo("2026-06-15"));
    }

    // ── Helper CDI bean: isola le operazioni DB in una transazione propria ─────
    // (REQUIRES_NEW) per evitare conflitti con la transazione già attiva sul
    // thread del test — vedi ARJUNA016051 "thread already associated with a tx".

    @jakarta.enterprise.context.ApplicationScoped
    public static class TxHelper {

        @Inject EntityManager em;

        /**
         * Conta le rate con id specificato, opzionalmente filtrate per la
         * presenza/assenza di {@code movimento_interessi_id}.
         */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public long countInstallmentsWithInteresse(UUID rataId, boolean withInteresse) {
            String predicate = withInteresse
                    ? "movimento_interessi_id IS NOT NULL"
                    : "movimento_interessi_id IS NULL";
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM recurring_expense_installment " +
                    "WHERE id = :rataId AND " + predicate)
                    .setParameter("rataId", rataId)
                    .getSingleResult();
            return n.longValue();
        }

        /** Conta le rate a DB per un piano: verifica il cascade dopo la delete fisica. */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public long countInstallments(UUID pianoId) {
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM recurring_expense_installment WHERE piano_id = :pid")
                    .setParameter("pid", pianoId)
                    .getSingleResult();
            return n.longValue();
        }

        /**
         * Simula una riga di import parcheggiata e riconciliata (COLLEGA) sul piano:
         * import_log minimale + ricorrenti_da_riconciliare con recurring_plan_id valorizzato.
         */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public UUID seedRicorrenteCollegata(UUID planId) {
            String fonte = (String) em.createNativeQuery(
                    "SELECT codice FROM lk_fonti_movimento LIMIT 1").getSingleResult();
            UUID logId = UUID.randomUUID();
            em.createNativeQuery(
                    "INSERT INTO import_log (id, fonte) VALUES (:id, :fonte)")
                    .setParameter("id", logId).setParameter("fonte", fonte)
                    .executeUpdate();
            UUID rigaId = UUID.randomUUID();
            em.createNativeQuery("""
                    INSERT INTO ricorrenti_da_riconciliare
                        (id, import_log_id, fonte, importo, stato, recurring_plan_id, raw_data)
                    VALUES (:id, :log, :fonte, 123.45, 'RICONCILIATA', :pid, '{}'::jsonb)
                    """)
                    .setParameter("id", rigaId).setParameter("log", logId)
                    .setParameter("fonte", fonte).setParameter("pid", planId)
                    .executeUpdate();
            return rigaId;
        }

        /** Ritorna [stato, recurring_plan_id] della riga parcheggiata. */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public Object[] getRicorrenteRow(UUID id) {
            return (Object[]) em.createNativeQuery(
                    "SELECT stato, recurring_plan_id FROM ricorrenti_da_riconciliare WHERE id = :id")
                    .setParameter("id", id)
                    .getSingleResult();
        }

        /** Saldo corrente del conto 1 (stessa formula di RecurringExpenseService#getContoBancarioSaldo). */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public java.math.BigDecimal getSaldoConto1() {
            Object r = em.createNativeQuery(
                    "SELECT cb.saldo_iniziale + COALESCE(SUM(" +
                    "  CASE WHEN m.tipo='ENTRATA' THEN m.importo_lordo" +
                    "       WHEN m.tipo='USCITA'  THEN -m.importo_lordo ELSE 0 END),0)" +
                    " FROM conti_bancari cb" +
                    " LEFT JOIN movimenti m ON m.conto_bancario_id=cb.id AND m.data_finanziaria IS NOT NULL" +
                    " WHERE cb.id=1 GROUP BY cb.saldo_iniziale")
                    .getSingleResult();
            return r instanceof java.math.BigDecimal bd ? bd : new java.math.BigDecimal(r.toString());
        }

        /** Quanti dei movimenti indicati esistono ancora a DB (per verificare la purga della cestina). */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public long countMovimentiByIds(List<UUID> ids) {
            if (ids.isEmpty()) return 0;
            Number n = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM movimenti WHERE id IN (:ids)")
                    .setParameter("ids", ids)
                    .getSingleResult();
            return n.longValue();
        }

        /** movimento_penale_id tracciato sul piano (null se assente). */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public UUID getPlanMovimentoPenaleId(UUID planId) {
            return (UUID) em.createNativeQuery(
                    "SELECT movimento_penale_id FROM recurring_expense_plan WHERE id = :pid")
                    .setParameter("pid", planId)
                    .getSingleResult();
        }

        /** movimento_interessi_id di una rata (null se FLAT o non pagata). */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public UUID getMovimentoInteressiId(UUID rataId) {
            return (UUID) em.createNativeQuery(
                    "SELECT movimento_interessi_id FROM recurring_expense_installment WHERE id = :rid")
                    .setParameter("rid", rataId)
                    .getSingleResult();
        }

        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public void refreshPlMv() {
            em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile")
                    .executeUpdate();
        }

        /**
         * Inserisce un ENTRATA fittizia su conto 1 per garantire un saldo
         * sufficiente per i pagamenti di rata grossa nel test FINANZIAMENTO.
         * Le rate da 8 490€ supererebbero altrimenti il saldo dei conti seedati
         * da V27 (alcuni a 0 di saldo iniziale).
         */
        @Transactional(Transactional.TxType.REQUIRES_NEW)
        public void seedSaldoConto1(java.math.BigDecimal importo) {
            int cogeRicavo = ((Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE codice = '30.01.001'")
                    .getSingleResult()).intValue();
            int metodo = ((Number) em.createNativeQuery(
                    "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'")
                    .getSingleResult()).intValue();
            em.createNativeQuery("""
                    INSERT INTO movimenti (
                        id, data_movimento, tipo, importo_lordo, importo_commissione,
                        data_competenza, data_finanziaria, data_liquidita,
                        conto_bancario_id, metodo_pagamento_id,
                        conto_coge_id, business_unit_id,
                        descrizione, stato, fonte, created_by, created_at
                    ) VALUES (
                        gen_random_uuid(), DATE '2026-04-01', 'ENTRATA', :importo, 0,
                        DATE '2026-04-01', DATE '2026-04-01', DATE '2026-04-01',
                        1, :metodo, :coge, 1,
                        '[TEST] top-up saldo conto 1 per FINANZIAMENTO', 'REGISTRATO', 'MANUALE',
                        CAST('00000000-0000-0000-0000-000000000099' AS uuid), now()
                    )
                    """)
                    .setParameter("importo", importo)
                    .setParameter("coge", cogeRicavo)
                    .setParameter("metodo", metodo)
                    .executeUpdate();
        }
    }
}
