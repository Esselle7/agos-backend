package com.agostinelli.gestionale.eventi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Invarianti del modulo voci di preventivo/consuntivo (SPEC eventi-preventivo-consuntivo):
 * righe quantità × prezzo unitario, le voci NON generano movimenti, il totale
 * preventivato = Σ voci, il consuntivo è compilabile solo dalla data evento, il costo
 * diretto con ricarico crea una voce (non un movimento in più), il listino si auto-popola.
 *
 * Verifica tutto via API: l'assenza di movimenti da voce è provata dal fatto che
 * incassato/costiReali restano invariati dopo l'aggiunta di voci.
 */
@QuarkusTest
class EventoVociIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";

    private String creaEvento(String dataEvento) {
        String body = """
                {"nome":"Voci Test","tipo":"BANCHETTO_PRIVATO","dataEvento":"%s",
                 "contattoNome":"Test","numeroTotalePartecipanti":50,"businessUnitId":2}
                """.formatted(dataEvento);
        return given().contentType(ContentType.JSON).body(body)
                .when().post("/api/eventi")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void aggiungiVoce(String eventoId, String jsonBody, int expectedStatus) {
        given().contentType(ContentType.JSON).body(jsonBody)
                .when().post("/api/eventi/" + eventoId + "/voci")
                .then().statusCode(expectedStatus);
    }

    // ── I1 + I2: righe q×prezzo, no movimenti, totale = Σ preventivo ─────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void voci_componiPreventivato_senzaMovimenti() {
        String id = creaEvento("2026-12-10");

        aggiungiVoce(id, "{\"label\":\"Affitto\",\"prezzoUnitario\":500,\"quantitaPreventivo\":1}", 201);
        aggiungiVoce(id, "{\"label\":\"Catering\",\"prezzoUnitario\":1000,\"quantitaPreventivo\":2}", 201);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("importoTotalePreviventivato", equalTo(2500.0f)) // 500 + 2×1000
                .body("totaleConsuntivato", equalTo(2500.0f))          // consuntivo null → usa preventivo
                .body("voci.size()", is(2))
                .body("importoIncassato", equalTo(0.0f))               // nessun movimento ENTRATA
                .body("costiReali", equalTo(0));                       // nessun movimento USCITA
    }

    // ── I3: consuntivo compilabile solo dalla data evento ───────────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void consuntivo_suEventoFuturo_bloccato() {
        String id = creaEvento("2026-12-10"); // futuro
        aggiungiVoce(id,
                "{\"label\":\"Caraffa Spritz\",\"prezzoUnitario\":100,\"quantitaPreventivo\":5,\"quantitaConsuntivo\":6}", 400);
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void consuntivo_daDataEvento_ammesso_conScostamento() {
        String oggi = LocalDate.now().toString();
        String id = creaEvento(oggi);
        // 5 preventivate → 6 consuntivate a 100 = 500 vs 600
        aggiungiVoce(id,
                "{\"label\":\"Caraffa Spritz\",\"prezzoUnitario\":100,\"quantitaPreventivo\":5,\"quantitaConsuntivo\":6}", 201);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("importoTotalePreviventivato", equalTo(500.0f))
                .body("totaleConsuntivato", equalTo(600.0f))
                .body("scostamentoConsuntivo", equalTo(100.0f))
                .body("voci[0].scostamento", equalTo(100.0f));
    }

    // ── I5 + I1: costo diretto con ricarico → voce (non un movimento in più) ─────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void costoDiretto_conRicarico_creaVoce_nonMovimento() {
        String id = creaEvento("2026-12-10");

        given().contentType(ContentType.JSON)
                .body("{\"tipoCosto\":\"FISSO\",\"voce\":\"DJ\",\"importo\":200,\"importoAddebitoCliente\":250}")
                .when().post("/api/eventi/" + id + "/costi-diretti")
                .then().statusCode(201);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("costiReali", equalTo(200.0f))                  // solo il costo è movimento
                .body("importoTotalePreviventivato", equalTo(250.0f)) // il ricarico è una voce
                .body("voci.size()", is(1))
                .body("voci[0].origine", equalTo("COSTO_DIRETTO"));
    }

    // ── I6: rimuovendo il costo diretto cade la voce di ricarico ─────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void rimuoviCostoDiretto_rimuoveVoceRicarico() {
        String id = creaEvento("2026-12-10");
        int costoId = given().contentType(ContentType.JSON)
                .body("{\"tipoCosto\":\"FISSO\",\"voce\":\"DJ\",\"importo\":200,\"importoAddebitoCliente\":250}")
                .when().post("/api/eventi/" + id + "/costi-diretti")
                .then().statusCode(201).extract().path("id");

        given().when().delete("/api/eventi/" + id + "/costi-diretti/" + costoId)
                .then().statusCode(204);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("voci.size()", is(0))
                .body("importoTotalePreviventivato", equalTo(0.0f));
    }

    // ── E5-bis: una voce COSTO_DIRETTO non è rimovibile direttamente ─────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void voceDaCostoDiretto_nonRimovibileDirettamente() {
        String id = creaEvento("2026-12-10");
        given().contentType(ContentType.JSON)
                .body("{\"tipoCosto\":\"FISSO\",\"voce\":\"DJ\",\"importo\":200,\"importoAddebitoCliente\":250}")
                .when().post("/api/eventi/" + id + "/costi-diretti").then().statusCode(201);
        int voceId = given().when().get("/api/eventi/" + id + "/voci")
                .then().statusCode(200).extract().path("[0].id");

        given().when().delete("/api/eventi/voci/" + voceId).then().statusCode(409);
    }

    // ── Voce SOLO consuntiva (non preventivata): q. preventivo = 0 ───────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void voce_soloConsuntiva_nonPreventivata() {
        String oggi = LocalDate.now().toString();
        String id = creaEvento(oggi);
        // Location non preventivata, comparsa solo a consuntivo: 1 × 500
        aggiungiVoce(id,
                "{\"label\":\"Location in esclusiva\",\"prezzoUnitario\":500,\"quantitaPreventivo\":0,\"quantitaConsuntivo\":1}", 201);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("importoTotalePreviventivato", equalTo(0.0f)) // non preventivata
                .body("totaleConsuntivato", equalTo(500.0f))        // solo consuntivo
                .body("scostamentoConsuntivo", equalTo(500.0f));
    }

    // ── Voce custom salvata nel listino CON prezzo → riutilizzabile ──────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void voceCustom_salvataNelListino_conPrezzo() {
        String id = creaEvento("2026-12-10");
        aggiungiVoce(id, "{\"label\":\"Fotografo QA\",\"prezzoUnitario\":800,\"quantitaPreventivo\":1}", 201);

        given().when().get("/api/eventi/voci-catalogo")
                .then().statusCode(200)
                .body("find { it.label == 'Fotografo QA' }.prezzoDefault", equalTo(800.0f));
    }

    // ── Costo diretto imputato a una BU diversa: il profitto evento lo netta ──────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void costoDiretto_altraBU_nettatoNelProfittoEvento() {
        String id = creaEvento("2026-12-10");
        given().contentType(ContentType.JSON)
                .body("{\"tipoCosto\":\"FISSO\",\"voce\":\"DJ\",\"importo\":200,\"businessUnitId\":1}")
                .when().post("/api/eventi/" + id + "/costi-diretti")
                .then().statusCode(201);

        given().when().get("/api/eventi/" + id)
                .then().statusCode(200)
                .body("costiReali", equalTo(200.0f)); // costo nettato a prescindere dalla BU
    }

    // ── Listino: default guidati con prezzo + auto-popolamento con label nuove ───
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void listino_defaultGuidati_conPrezzo_eAutoPopolamento() {
        given().when().get("/api/eventi/voci-catalogo")
                .then().statusCode(200)
                .body("findAll { it.isDefault == true }.label", hasItems("Menù adulti", "Menù bambini"))
                .body("find { it.label == 'Menù adulti' }.prezzoDefault", equalTo(65.0f));

        String id = creaEvento("2026-12-10");
        aggiungiVoce(id, "{\"label\":\"Prosecco QA\",\"prezzoUnitario\":70,\"quantitaPreventivo\":1}", 201);

        given().when().get("/api/eventi/voci-catalogo")
                .then().statusCode(200)
                .body("label", hasItem("Prosecco QA"));
    }
}
