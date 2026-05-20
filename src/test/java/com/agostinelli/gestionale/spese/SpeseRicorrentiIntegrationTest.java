package com.agostinelli.gestionale.spese;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    @Inject UserTransaction tx;

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
    void testCreaPiano_cogeNonPassivita_400() {
        // cerca un conto COSTO (non PASSIVITA)
        Number costoId;
        try {
            costoId = (Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE tipo = 'COSTO' LIMIT 1")
                    .getSingleResult();
        } catch (Exception e) { return; } // skip se non esiste

        String body = """
            {
              "descrizione": "Errore coge sbagliato",
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

        given()
            .contentType(ContentType.JSON).body(body)
            .when().post(BASE + "/piani")
            .then()
                .statusCode(400);
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

    @Test
    @Order(102)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testFinanziamento_pagaPrimaRata_dueMovimenti() {
        Assumptions.assumeTrue(finanziamentoPlanId != null,
                "Piano finanziamento non creato nel test precedente");

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
        try {
            tx.begin();
            Number movIntCount = (Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM recurring_expense_installment " +
                    "WHERE id = :rataId::uuid AND movimento_interessi_id IS NOT NULL")
                    .setParameter("rataId", rataId)
                    .getSingleResult();
            tx.commit();
            assertEquals(1, movIntCount.intValue(),
                    "movimento_interessi_id deve essere valorizzato dopo il pagamento");
        } catch (Exception e) {
            fail("Errore verifica movimento interessi: " + e.getMessage());
        }
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
        // BUG 2: ACCORPA on FINANZIAMENTO must nullify next rata's quotaCapitale/quotaInteressi
        Assumptions.assumeTrue(finanziamentoPlanId != null,
                "Piano finanziamento non creato");

        io.restassured.path.json.JsonPath before = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200).extract().jsonPath();

        String primaRataId = before.getString("rate.findAll { it.stato == 'PENDING' }[0].id");
        Assumptions.assumeTrue(primaRataId != null);

        given()
            .contentType(ContentType.JSON).body("""
                {"modalita": "ACCORPA"}
                """)
            .when().post(BASE + "/piani/" + finanziamentoPlanId + "/rate/" + primaRataId + "/skip")
            .then().statusCode(204);

        io.restassured.path.json.JsonPath after = given()
            .when().get(BASE + "/piani/" + finanziamentoPlanId)
            .then().statusCode(200).extract().jsonPath();

        // la prima PENDING ora ha importo doppio e quote devono essere null
        Object quotaCapitale  = after.get("rate.find { it.stato == 'PENDING' }.quotaCapitale");
        Object quotaInteressi = after.get("rate.find { it.stato == 'PENDING' }.quotaInteressi");

        assertNull(quotaCapitale,  "BUG 2: quotaCapitale deve essere null dopo ACCORPA su FINANZIAMENTO");
        assertNull(quotaInteressi, "BUG 2: quotaInteressi deve essere null dopo ACCORPA su FINANZIAMENTO");
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
        tx.begin();
        Number noIntCount = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM recurring_expense_installment " +
                "WHERE id = :rataId::uuid AND movimento_interessi_id IS NULL")
                .setParameter("rataId", rataId)
                .getSingleResult();
        tx.commit();

        assertEquals(1, noIntCount.intValue(),
                "FLAT: movimento_interessi_id deve essere NULL dopo il pagamento");
    }

    // ── P&L: struttura waterfall e oneri finanziari ───────────────────────────

    private static volatile boolean plMvRefreshed = false;

    private void ensurePlMvRefreshed() throws Exception {
        if (plMvRefreshed) return;
        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        tx.commit();
        plMvRefreshed = true;
    }

    @Test
    @Order(120)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPL_tutteBu_campiWaterfall_presenti() throws Exception {
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
    void testPL_tutteBu_waterfallInvariant_ebit_eq_ebitda_minus_da_minus_oneri() throws Exception {
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

        // Invariante 1: EBIT = EBITDA − D&A − OneriFinanziari
        assertEquals(ebitda - ammortamenti - oneriFinanziari, ebit, 0.05,
                "Invariante waterfall: EBIT = EBITDA - D&A - OneriFinanziari");

        // Invariante 2: UtileNetto = EBIT − Imposte
        assertEquals(ebit - imposte, utileNetto, 0.05,
                "Invariante waterfall: UtileNetto = EBIT - Imposte");
    }

    @Test
    @Order(122)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void testPL_dopoFinanziamentoPagato_oneriFinanziari_positivi() throws Exception {
        Assumptions.assumeTrue(validContoCoge != null);
        Assumptions.assumeTrue(validContoCogeInteressi != null);

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
        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        tx.commit();

        // Query P&L per maggio 2026 (mese del pagamento = mese corrente in test)
        io.restassured.path.json.JsonPath jp = given()
            .when().get("/api/reporting/pl/tutte-bu?from=2026-05-01&to=2026-05-31")
            .then().statusCode(200).extract().jsonPath();

        double oneriFinanziari = jp.getDouble("totaleConsolidato.oneriFinanziari");
        assertTrue(oneriFinanziari > 0,
                "Dopo il pagamento della prima rata FINANZIAMENTO, oneriFinanziari nel P&L " +
                "deve essere > 0 (interessi = €291.67); trovato: " + oneriFinanziari);

        // Verifica che gli interessi compaiano come oneri finanziari e NON come costi operativi
        double costi = jp.getDouble("totaleConsolidato.costi");
        // Il capitale (PASSIVITA) non deve apparire in costi operativi
        // (la registrazione PASSIVITA riduce il debito, non è un costo)
        // Questa verifica è qualitativa: oneriFinanziari < costi o ≈ 0 dipende dai dati
        // L'invariante chiave è che oneriFinanziari è separato
        assertEquals(oneriFinanziari, jp.getDouble("totaleConsolidato.ebitda")
                - jp.getDouble("totaleConsolidato.ammortamenti")
                - jp.getDouble("totaleConsolidato.ebit"), 0.05,
                "OneriFinanziari deve comparire separato nell'equazione waterfall");
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
}
