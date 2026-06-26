package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per il modulo Movimenti.
 * Usa @TestSecurity con UUID fisso (V11) per soddisfare il FK created_by.
 * Richiede PostgreSQL attivo su localhost:5432 (agosdb_test).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MovimentiIntegrationTest {

    /** UUID dell'utente di test – seeded in V11 con UUID fisso. */
    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";

    @Inject EntityManager em;

    // Dati validi risolti dal DB (IDs SERIAL, non hardcoded)
    private static Integer validContoCoge;
    private static Integer validMetodoPagamento;

    @BeforeEach
    void resolveIds() {
        if (validContoCoge == null) {
            validContoCoge = ((Number) em
                    .createNativeQuery("SELECT id FROM piano_dei_conti_coge LIMIT 1")
                    .getSingleResult()).intValue();
        }
        if (validMetodoPagamento == null) {
            validMetodoPagamento = ((Number) em
                    .createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'")
                    .getSingleResult()).intValue();
        }
    }

    // ── Lista e filtri ─────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiDefaultPaginata() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content", notNullValue())
                .body("page", equalTo(0))
                .body("size", greaterThan(0))
                .body("totalElements", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(2)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testListaMovimentiSizeMax() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    @Test
    @Order(3)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testListaDipendente() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(4)
    void testListaSenzaToken() {
        given()
            .when().get("/api/movimenti")
            .then()
                .statusCode(401);
    }

    @Test
    @Order(5)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testFiltraPerTipo() {
        given()
            .queryParam("tipo", "ENTRATA")
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content.tipo", everyItem(equalTo("ENTRATA")));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimento() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "150.00", "Pagamento test", null))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("id", notNullValue())
                .body("tipo", equalTo("ENTRATA"))
                .body("importo", equalTo(150.0f))
                .body("stato", equalTo("REGISTRATO"))
                .body("fonte", equalTo("MANUALE"));
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoConCommissione() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequestConCommissione("ENTRATA", "97.00", "100.00"))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("importo", equalTo(97.0f))
                .body("importoCommissione", equalTo(3.0f));
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaERecuperaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("USCITA", "250.00", "Spesa test", null))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .extract().path("id");

        given()
            .when().get("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("tipo", equalTo("USCITA"))
                .body("importo", equalTo(250.0f));
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoNonEsistente() {
        given()
            .when().get("/api/movimenti/00000000-0000-0000-0000-000000000000")
            .then()
                .statusCode(404);
    }

    @Test
    @Order(14)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAggiornaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "100.00", "Da aggiornare", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        // PUT con semantica full-overwrite: il client invia lo stato completo del
        // movimento (come fa il form FE), non un patch parziale.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":200.00,"dataMovimento":"2025-06-01",
                     "dataFinanziaria":"2025-06-01",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,
                     "descrizione":"Descrizione aggiornata"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().put("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("descrizione", equalTo("Descrizione aggiornata"))
                .body("importo", equalTo(200.0f));
    }

    @Test
    @Order(15)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAnnullaMovimento() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("USCITA", "50.00", "Da annullare", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        given()
            .when().delete("/api/movimenti/" + id)
            .then()
                .statusCode(204);

        given()
            .when().get("/api/movimenti/" + id)
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"));
    }

    @Test
    @Order(16)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testAnnullaDipendenteForbidden() {
        given()
            .when().delete("/api/movimenti/00000000-0000-0000-0000-000000000001")
            .then()
                .statusCode(403);
    }

    // ── Validazione ────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoSenzaImporto() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","dataMovimento":"2025-01-01",
                     "contoBancarioId":1,"metodoPagamentoId":1,
                     "businessUnitId":1,"contoCoge":1,"descrizione":"test"}
                    """)
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCreaMovimentoTipoEventoSenzaEventoId() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "100.00", "Test", "CAPARRA"))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    // ── Bulk import ────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImport() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"movimenti":[
                      %s,
                      %s
                    ]}
                    """.formatted(
                        buildCreateRequest("ENTRATA", "100.00", "Bulk 1", null),
                        buildCreateRequest("USCITA",  "200.00", "Bulk 2", null)
                    ))
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(200)
                .body("importati", equalTo(2))
                .body("duplicati", equalTo(0))
                .body("errori", equalTo(0));
    }

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImportDeduplication() {
        String rif = "REF-DEDUP-" + System.currentTimeMillis();
        String mov = buildCreateRequestConRif("ENTRATA", "300.00", "Dedup test", rif);

        // Prima importazione: ok
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[" + mov + "]}")
            .when().post("/api/movimenti/bulk")
            .then().statusCode(200).body("importati", equalTo(1));

        // Seconda importazione con stesso riferimento: duplicato
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[" + mov + "]}")
            .when().post("/api/movimenti/bulk")
            .then().statusCode(200)
            .body("importati", equalTo(0))
            .body("duplicati", equalTo(1));
    }

    @Test
    @Order(32)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void testBulkImportDipendenteForbidden() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[]}")
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(403);
    }

    // ── Importi edge case ──────────────────────────────────────────────────────

    @Test
    @Order(51)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testImportoZeroRifiutato() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":0.00,"dataMovimento":"2026-12-01",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,"descrizione":"Importo zero"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(52)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testImportoNegativoRifiutato() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":-50.00,"dataMovimento":"2026-12-01",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,"descrizione":"Importo negativo"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(53)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testImportoMassimoAccettato() {
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "9999999.99", "Importo massimo", null))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("importo", greaterThan(9000000.0f));
    }

    @Test
    @Order(54)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testCommissioneDecimaleNonArrotondata() {
        // 100.00 - 97.37 = 2.63 — verifica che BigDecimal non arrotondi
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequestConCommissione("ENTRATA", "97.37", "100.00"))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("importo", equalTo(97.37f))
                .body("importoCommissione", equalTo(2.63f));
    }

    // ── Date edge case ─────────────────────────────────────────────────────────

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testDataPassatoRemotoAccettata() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":1.00,"dataMovimento":"2000-01-01",
                     "dataFinanziaria":"2000-01-01",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,"descrizione":"Data passato remoto"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("dataMovimento", equalTo("2000-01-01"));
    }

    @Test
    @Order(62)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testDataFuturaAccettata() {
        // dataFinanziaria futura è rifiutata dal servizio; usiamo DA_LIQUIDARE (senza contoBancarioId)
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":1.00,"dataMovimento":"2099-12-31",
                     "dataLiquidita":"2099-12-31",
                     "businessUnitId":1,"contoCoge":%d,"descrizione":"Data futura"}
                    """.formatted(validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("dataMovimento", equalTo("2099-12-31"))
                .body("stato", equalTo("DA_LIQUIDARE"));
    }

    // ── Stato machine ──────────────────────────────────────────────────────────

    @Test
    @Order(71)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testAnnullaMovimentoGiaAnnullatoConflict() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("USCITA", "30.00", "Doppio annullo test", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        given().when().delete("/api/movimenti/" + id).then().statusCode(204);
        // Seconda annullazione sullo stesso movimento → CONFLICT
        given().when().delete("/api/movimenti/" + id).then().statusCode(409);
    }

    @Test
    @Order(72)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testUpdateMovimentoGiaAnnullatoConflict() {
        String id = given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("ENTRATA", "40.00", "Update su annullato", null))
            .when().post("/api/movimenti")
            .then().statusCode(201).extract().path("id");

        given().when().delete("/api/movimenti/" + id).then().statusCode(204);

        given()
            .contentType(ContentType.JSON)
            .body("{\"descrizione\":\"Tentativo update su annullato\"}")
            .when().put("/api/movimenti/" + id)
            .then()
                .statusCode(409);
    }

    // ── Bulk edge case ─────────────────────────────────────────────────────────

    @Test
    @Order(81)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImportOltreLimite500() {
        StringBuilder sb = new StringBuilder("{\"movimenti\":[");
        for (int i = 0; i < 501; i++) {
            if (i > 0) sb.append(",");
            sb.append(buildCreateRequest("ENTRATA", "1.00", "Limite " + i, null));
        }
        sb.append("]}");

        given()
            .contentType(ContentType.JSON)
            .body(sb.toString())
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(82)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testBulkImportMistoValidiEInvalidi() {
        String valido1 = buildCreateRequest("ENTRATA", "10.00", "Bulk valido A", null);
        String invalido = """
                {"tipo":"ENTRATA","importo":0.00,"dataMovimento":"2026-12-01",
                 "contoBancarioId":1,"metodoPagamentoId":%d,
                 "businessUnitId":1,"contoCoge":%d,"descrizione":"Importo zero"}
                """.formatted(validMetodoPagamento, validContoCoge);
        String valido2 = buildCreateRequest("USCITA", "20.00", "Bulk valido B", null);

        given()
            .contentType(ContentType.JSON)
            .body("{\"movimenti\":[" + valido1 + "," + invalido + "," + valido2 + "]}")
            .when().post("/api/movimenti/bulk")
            .then()
                .statusCode(200)
                .body("importati", equalTo(2))
                .body("errori",    equalTo(1))
                .body("duplicati", equalTo(0));
    }

    // ── Filtri avanzati ────────────────────────────────────────────────────────

    @Test
    @Order(91)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testFiltraPerDateRange() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":5.00,"dataMovimento":"2026-11-15",
                     "dataLiquidita":"2026-11-15",
                     "businessUnitId":1,"contoCoge":%d,"descrizione":"Filtro date range"}
                    """.formatted(validContoCoge))
            .when().post("/api/movimenti")
            .then().statusCode(201);

        given()
            .queryParam("from", "2026-11-15")
            .queryParam("to", "2026-11-15")
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content", hasSize(greaterThan(0)))
                .body("content.dataMovimento", everyItem(equalTo("2026-11-15")));
    }

    @Test
    @Order(92)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testFiltraPerBuId() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":1.00,"dataMovimento":"2026-10-01",
                     "dataLiquidita":"2026-10-01",
                     "businessUnitId":2,"contoCoge":%d,"descrizione":"Filtro buId 2"}
                    """.formatted(validContoCoge))
            .when().post("/api/movimenti")
            .then().statusCode(201);

        given()
            .queryParam("buId", 2)
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content", hasSize(greaterThan(0)))
                .body("content.businessUnitId", everyItem(equalTo(2)));
    }

    @Test
    @Order(93)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testFiltraPerStatoAnnullato() {
        // Order 15 ha già creato e annullato un movimento — almeno uno ANNULLATO esiste
        given()
            .queryParam("stato", "ANNULLATO")
            .when().get("/api/movimenti")
            .then()
                .statusCode(200)
                .body("content.stato", everyItem(equalTo("ANNULLATO")));
    }

    // ── Liquidità immediata vs futura ──────────────────────────────────────────

    @Test
    @Order(110)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoLiquiditaFuturaSenzaContoOk() {
        // dataLiquidita > dataMovimento: conto e metodo NON richiesti → 201
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"USCITA","importo":300.00,
                     "dataMovimento":"2026-05-03","dataCompetenza":"2026-05-03",
                     "dataLiquidita":"2026-08-01",
                     "businessUnitId":1,"contoCoge":%d,
                     "descrizione":"Fattura a 90 giorni"}
                    """.formatted(validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("stato", equalTo("DA_LIQUIDARE"))
                .body("contoBancarioId", nullValue())
                .body("metodoPagamentoId", nullValue());
    }

    @Test
    @Order(111)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoLiquidatoSenzaContoBancarioRifiutato() {
        // dataFinanziaria presente ma contoBancarioId assente → 400
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"USCITA","importo":300.00,
                     "dataMovimento":"2026-05-03","dataFinanziaria":"2026-05-03",
                     "businessUnitId":1,"contoCoge":%d,
                     "descrizione":"Liquidato senza conto bancario"}
                    """.formatted(validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(112)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoLiquiditaFuturaConContoRifiutato() {
        // dataLiquidita futura ma conto presente: inconsistente → 400
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"USCITA","importo":300.00,
                     "dataMovimento":"2026-05-03","dataLiquidita":"2026-08-01",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,
                     "descrizione":"Futuro con conto specificato"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(113)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testMovimentoSenzaDataLiquiditaConContoOk() {
        // dataFinanziaria presente con conto e metodo → 201 REGISTRATO
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"ENTRATA","importo":150.00,
                     "dataMovimento":"2026-05-03","dataFinanziaria":"2026-05-03",
                     "contoBancarioId":1,"metodoPagamentoId":%d,
                     "businessUnitId":1,"contoCoge":%d,
                     "descrizione":"Pagamento immediato completo"}
                    """.formatted(validMetodoPagamento, validContoCoge))
            .when().post("/api/movimenti")
            .then()
                .statusCode(201)
                .body("stato", equalTo("REGISTRATO"));
    }

    // ── Sommario ──────────────────────────────────────────────────────────────

    @Test
    @Order(120)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testSommarioRestituisceRiepilogo() {
        given()
            .when().get("/api/movimenti/sommario")
            .then()
                .statusCode(200)
                .body("perStato", notNullValue())
                .body("totaleEntrate", greaterThanOrEqualTo(0f))
                .body("totaleUscite", greaterThanOrEqualTo(0f))
                .body("totaleCount", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(121)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testSommarioConFiltroData() {
        given()
            .queryParam("from", "2025-01-01")
            .queryParam("to", "2025-12-31")
            .when().get("/api/movimenti/sommario")
            .then()
                .statusCode(200)
                .body("perStato", notNullValue());
    }

    @Test
    @Order(122)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void testSommarioConFiltroStato() {
        given()
            .queryParam("stato", "REGISTRATO")
            .when().get("/api/movimenti/sommario")
            .then()
                .statusCode(200)
                .body("perStato.stato", everyItem(equalTo("REGISTRATO")));
    }

    @Test
    @Order(123)
    void testSommarioSenzaToken() {
        given()
            .when().get("/api/movimenti/sommario")
            .then()
                .statusCode(401);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String buildCreateRequest(String tipo, String importo, String descrizione, String tipoEvento) {
        String tipoEventoJson = tipoEvento != null
                ? "\"tipoEventoMovimento\":\"" + tipoEvento + "\","
                : "";
        return """
                {"tipo":"%s","importo":%s,"dataMovimento":"2025-06-01",
                 "dataFinanziaria":"2025-06-01",
                 "contoBancarioId":1,"metodoPagamentoId":%d,
                 "businessUnitId":1,"contoCoge":%d,
                 %s"descrizione":"%s"}
                """.formatted(tipo, importo, validMetodoPagamento, validContoCoge,
                tipoEventoJson, descrizione);
    }

    private String buildCreateRequestConCommissione(String tipo, String importo, String importoLordo) {
        return """
                {"tipo":"%s","importo":%s,"importoLordo":%s,
                 "dataMovimento":"2025-06-01","dataFinanziaria":"2025-06-01",
                 "contoBancarioId":1,
                 "metodoPagamentoId":%d,"businessUnitId":1,"contoCoge":%d,
                 "descrizione":"Test commissione"}
                """.formatted(tipo, importo, importoLordo,
                validMetodoPagamento, validContoCoge);
    }

    private String buildCreateRequestConRif(String tipo, String importo, String descrizione, String rif) {
        return """
                {"tipo":"%s","importo":%s,"dataMovimento":"2025-06-01",
                 "dataFinanziaria":"2025-06-01",
                 "contoBancarioId":1,"metodoPagamentoId":%d,
                 "businessUnitId":1,"contoCoge":%d,
                 "descrizione":"%s","fonte":"IMPORT_BILLY","riferimentoEsterno":"%s"}
                """.formatted(tipo, importo, validMetodoPagamento, validContoCoge,
                descrizione, rif);
    }
}
