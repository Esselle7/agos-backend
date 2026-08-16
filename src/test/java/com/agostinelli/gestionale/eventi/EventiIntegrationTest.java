package com.agostinelli.gestionale.eventi;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test per il modulo Eventi – ciclo economico cerimonie.
 *
 * Copre: CRUD eventi, macchina a stati (happy path + edge case),
 * registrazione pagamenti con verifica del trigger DB su importoIncassato,
 * rimborsi, calendario, dashboard, partecipanti.
 *
 * Richiede PostgreSQL su localhost:5432/agosdb_test (test profile).
 * L'utente 00000000-0000-0000-0000-000000000099 è seeded in V11.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventiIntegrationTest {

    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";
    static final String NIL_UUID       = "00000000-0000-0000-0000-000000000000";

    @Inject EntityManager em;

    // IDs risolti dal DB (non hardcoded – seguono le sequenze SERIAL del seed)
    private static Integer metodoPagamentoId;
    private static Short   contoBancarioId;
    private static Integer contoCoge;
    private static String  personaleId;        // UUID stringa, MENSILE, inserito nel @BeforeEach
    private static String  personaleOrariaId;  // UUID stringa, ORARIA con paga oraria (per allocaOre)

    @BeforeEach
    @Transactional
    void resolveIds() {
        if (metodoPagamentoId == null) {
            metodoPagamentoId = ((Number) em
                    .createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'")
                    .getSingleResult()).intValue();
        }
        if (contoBancarioId == null) {
            contoBancarioId = ((Number) em
                    .createNativeQuery("SELECT id FROM conti_bancari LIMIT 1")
                    .getSingleResult()).shortValue();
        }
        if (contoCoge == null) {
            contoCoge = ((Number) em
                    .createNativeQuery("SELECT id FROM piano_dei_conti_coge LIMIT 1")
                    .getSingleResult()).intValue();
        }
        if (personaleId == null) {
            em.createNativeQuery(
                    "INSERT INTO personale (nome, cognome, is_active) " +
                    "VALUES ('Test', 'Personale', true) " +
                    "ON CONFLICT DO NOTHING")
                    .executeUpdate();
            personaleId = em
                    .createNativeQuery("SELECT id FROM personale WHERE nome='Test' AND cognome='Personale'")
                    .getSingleResult()
                    .toString();
        }
        if (personaleOrariaId == null) {
            em.createNativeQuery(
                    "INSERT INTO personale (nome, cognome, is_active, tipo_retribuzione, paga_oraria) " +
                    "VALUES ('Test', 'Oraria', true, 'ORARIA', 20.00) " +
                    "ON CONFLICT DO NOTHING")
                    .executeUpdate();
            personaleOrariaId = em
                    .createNativeQuery("SELECT id FROM personale WHERE nome='Test' AND cognome='Oraria'")
                    .getSingleResult()
                    .toString();
        }
    }

    // ── 1. Autenticazione ──────────────────────────────────────────────────────

    @Test
    @Order(1)
    void listaEventi_senzaToken_401() {
        given().when().get("/api/eventi").then().statusCode(401);
    }

    @Test
    @Order(2)
    void getEvento_senzaToken_401() {
        given().when().get("/api/eventi/" + NIL_UUID).then().statusCode(401);
    }

    @Test
    @Order(3)
    void creaEvento_senzaToken_401() {
        given().contentType(ContentType.JSON)
                .body(buildCreateRequest("Evento senza token", "1000"))
                .when().post("/api/eventi")
                .then().statusCode(401);
    }

    // ── 2. CRUD – Creazione ────────────────────────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void creaEvento_campiCompleti_201() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Matrimonio Bianchi-Verdi",
                     "tipo":"MATRIMONIO",
                     "dataEvento":"2026-09-15",
                     "dataPreventivo":"2026-03-01",
                     "importoTotalePreviventivato":8000.00,
                     "contattoNome":"Luca Bianchi",
                     "contattoTelefono":"+39 333 1111111",
                     "contattoEmail":"luca@bianchi.it",
                     "numeroTotalePartecipanti":70,
                     "note":"Menu 5 portate",
                     "businessUnitId":2}
                    """)
            .when().post("/api/eventi")
            .then()
                .statusCode(201)
                .body("id",                          notNullValue())
                .body("nome",                        equalTo("Matrimonio Bianchi-Verdi"))
                .body("tipo",                        equalTo("MATRIMONIO"))
                .body("stato",                       equalTo("PREVENTIVATO"))
                .body("importoTotalePreviventivato", equalTo(8000.0f))
                .body("importoIncassato",            equalTo(0))
                .body("importoResiduo",              equalTo(8000.0f))
                .body("percentualeIncassata",        equalTo(0.0f))
                .body("businessUnitId",              equalTo(2))
                .body("pagamenti",                   hasSize(0))
                .body("profitto",                    equalTo(0));
    }

    @Test
    @Order(11)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void creaEvento_campiBareMinimo_201() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Evento Minimo","tipo":"ALTRO",
                     "dataEvento":"2026-10-01","contattoNome":"Mario Rossi",
                     "numeroTotalePartecipanti":1}
                    """)
            .when().post("/api/eventi")
            .then()
                .statusCode(201)
                .body("stato", equalTo("PREVENTIVATO"))
                .body("businessUnitId", equalTo(2));  // default BU2
    }

    @Test
    @Order(12)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void creaEvento_dipendente_403() {
        // POST /api/eventi è ADMIN-only: il DIPENDENTE non può creare eventi.
        given()
            .contentType(ContentType.JSON)
            .body(buildCreateRequest("Evento da Dipendente", "500"))
            .when().post("/api/eventi")
            .then()
                .statusCode(403);
    }

    @Test
    @Order(13)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void creaEvento_dataPassata_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Evento nel passato","tipo":"ALTRO",
                     "dataEvento":"2020-01-01","contattoNome":"Test",
                     "numeroTotalePartecipanti":1}
                    """)
            .when().post("/api/eventi")
            .then()
                .statusCode(400)
                .body("code", equalTo("DATA_NEL_PASSATO"));
    }

    @Test
    @Order(14)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void creaEvento_nomeBlank_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"","tipo":"ALTRO","dataEvento":"2026-12-01",
                     "contattoNome":"Test","numeroTotalePartecipanti":1}
                    """)
            .when().post("/api/eventi")
            .then()
                .statusCode(400);
    }

    @Test
    @Order(15)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void creaEvento_tipoMancante_400() {
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Evento senza tipo","dataEvento":"2026-12-01",
                     "contattoNome":"Test","numeroTotalePartecipanti":1}
                    """)
            .when().post("/api/eventi")
            .then()
                .statusCode(400);
    }

    // ── 3. CRUD – Lettura ──────────────────────────────────────────────────────

    @Test
    @Order(20)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getEventoById_esistente_200() {
        String id = creaEventoTest("Get By Id Test", "1000");

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("id",    equalTo(id))
                .body("nome",  equalTo("Get By Id Test"))
                .body("stato", equalTo("PREVENTIVATO"));
    }

    @Test
    @Order(21)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getEventoById_nonEsistente_404() {
        given()
            .when().get("/api/eventi/" + NIL_UUID)
            .then()
                .statusCode(404);
    }

    @Test
    @Order(22)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void listaEventi_paginata_200() {
        given()
            .when().get("/api/eventi")
            .then()
                .statusCode(200)
                .body("content",       notNullValue())
                .body("page",          equalTo(0))
                .body("size",          greaterThan(0))
                .body("totalElements", greaterThan(0));
    }

    @Test
    @Order(23)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void listaEventi_sizeMaxCapato_200() {
        given()
            .queryParam("size", 9999)
            .when().get("/api/eventi")
            .then()
                .statusCode(200)
                .body("size", lessThanOrEqualTo(100));
    }

    @Test
    @Order(24)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void listaEventi_filtroStato_soloPreventivati() {
        given()
            .queryParam("stato", "PREVENTIVATO")
            .when().get("/api/eventi")
            .then()
                .statusCode(200)
                .body("content.stato", everyItem(equalTo("PREVENTIVATO")));
    }

    // ── 4. CRUD – Aggiornamento campo ──────────────────────────────────────────

    @Test
    @Order(30)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void updateEvento_aggiornaNome_200() {
        String id = creaEventoTest("Nome Originale", "1000");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Nome Aggiornato","numeroTotalePartecipanti":50}
                    """)
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("nome",                      equalTo("Nome Aggiornato"))
                .body("numeroTotalePartecipanti",   equalTo(50))
                .body("stato",                     equalTo("PREVENTIVATO"));
    }

    @Test
    @Order(31)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void updateEvento_nonEsistente_404() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"nome\":\"X\"}")
            .when().put("/api/eventi/" + NIL_UUID)
            .then()
                .statusCode(404);
    }

    // ── 5. Macchina a stati – PREVENTIVATO → CONFERMATO ──────────────────────

    @Test
    @Order(40)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void confermazione_conImporto_200() {
        String id = creaEventoTest("Evento da Confermare", "3000");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"stato":"CONFERMATO","importoTotalePreviventivato":3000.00}
                    """)
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("stato", equalTo("CONFERMATO"));
    }

    @Test
    @Order(41)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void confermazione_senzaImporto_400() {
        String id = creaEventoTest("Evento senza importo", null);

        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"CONFERMATO\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(400)
                .body("code", equalTo("IMPORTO_PREVENTIVATO_MANCANTE"));
    }

    @Test
    @Order(42)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void update_dipendente_403() {
        // PUT /api/eventi/{id} è ADMIN-only: il DIPENDENTE non può modificare gli eventi.
        String id = creaEventoTest_comeAdmin("Evento per DIPENDENTE update", "500");

        given()
            .contentType(ContentType.JSON)
            .body("{\"nome\":\"Tentativo modifica DIPENDENTE\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(403);
    }

    // ── 6. Macchina a stati – ANNULLAMENTO ────────────────────────────────────

    @Test
    @Order(50)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void annullamento_admin_conNote_200() {
        String id = creaEventoTest("Evento da Annullare", "1000");

        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Cliente ha disdetto\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("stato", equalTo("ANNULLATO"));
    }

    @Test
    @Order(51)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void annullamento_senzaNote_400() {
        String id = creaEventoTest("Evento annullo senza note", "1000");

        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"ANNULLATO\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(400)
                .body("code", equalTo("NOTE_ANNULLAMENTO_OBBLIGATORIE"));
    }

    @Test
    @Order(52)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void annullamento_dipendente_403() {
        String id = creaEventoTest_comeAdmin("Evento per annullo DIPENDENTE", "1000");

        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Tentativo dipendente\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(403);
    }

    @Test
    @Order(53)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void noteAnnullamento_visibilePerAdmin() {
        String id = creaEventoTest("Evento per visibility test", "1000");

        given().contentType(ContentType.JSON)
                .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Nota riservata ADMIN\"}")
                .when().put("/api/eventi/" + id)
                .then().statusCode(200);

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("noteAnnullamento", equalTo("Nota riservata ADMIN"));
    }

    @Test
    @Order(54)
    @TestSecurity(user = TEST_USER_UUID, roles = {"DIPENDENTE"})
    void noteAnnullamento_nullPerDipendente() {
        String id = given()
                .queryParam("stato", "ANNULLATO")
                .when().get("/api/eventi")
                .then().statusCode(200)
                .extract().path("content[0].id");

        if (id == null) return;

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("noteAnnullamento", nullValue());
    }

    // ── 7. CRUD – Delete ──────────────────────────────────────────────────────

    @Test
    @Order(60)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void deleteEvento_preventivo_senzaMovimenti_204() {
        String id = creaEventoTest("Evento da Eliminare", null);

        given().when().delete("/api/eventi/" + id).then().statusCode(204);

        given().when().get("/api/eventi/" + id).then().statusCode(404);
    }

    @Test
    @Order(61)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void deleteEvento_nonPreventivo_403() {
        String id = creaEventoTest("Evento Confermato da non eliminare", "2000");
        given().contentType(ContentType.JSON)
                .body("{\"stato\":\"CONFERMATO\"}")
                .when().put("/api/eventi/" + id)
                .then().statusCode(200);

        given().when().delete("/api/eventi/" + id).then().statusCode(403);
    }

    @Test
    @Order(62)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void deleteEvento_nonEsistente_404() {
        given().when().delete("/api/eventi/" + NIL_UUID).then().statusCode(404);
    }

    // ── 8. Pagamenti – CAPARRA con verifica trigger DB ─────────────────────────

    @Test
    @Order(70)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void registraCaparra_verificaTriggerImportoIncassato() {
        String id = creaEventoConfermato("Evento Caparra Trigger", "5000");

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(0.0f))
                .body("caparreIncassate", equalTo(0.0f));

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("CAPARRA", "1500.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .body("tipo",    equalTo("CAPARRA"))
                .body("importo", equalTo(1500.0f))
                .body("stato",   equalTo("ATTIVO"));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .statusCode(200)
                .body("importoIncassato", equalTo(1500.0f))
                .body("caparreIncassate", equalTo(1500.0f))
                .body("importoResiduo",   equalTo(3500.0f))
                .body("pagamenti",        hasSize(1));
    }

    @Test
    @Order(71)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void registraSaldo_headerXSuggestCompletamento() {
        String id = creaEventoConfermato("Evento Saldo Pieno", "1000");

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("SALDO", "1000.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .header("X-Suggest-Completamento", equalTo("true"));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(1000.0f))
                .body("importoResiduo",   equalTo(0.0f));
    }

    @Test
    @Order(72)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void registraAcconto_headerNonPresente() {
        String id = creaEventoConfermato("Evento Acconto Parziale", "2000");

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "500.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .header("X-Suggest-Completamento", nullValue());
    }

    @Test
    @Order(73)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void registraPagamento_importoSuperaResiduo_409() {
        String id = creaEventoConfermato("Evento Importo Supera", "500");

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "999.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("IMPORTO_SUPERA_RESIDUO"));
    }

    @Test
    @Order(74)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void registraPagamento_suEventoAnnullato_409() {
        String id = creaEventoTest("Evento da Annullare per pagamento", "1000");
        given().contentType(ContentType.JSON)
                .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Test\"}")
                .when().put("/api/eventi/" + id)
                .then().statusCode(200);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("CAPARRA", "100.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("EVENTO_ANNULLATO"));
    }

    @Test
    @Order(75)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void saldoPienoSuEventoPreventivato_portaASaldato() {
        // Pagamento in un'unica soluzione (SALDO) su un evento ancora PREVENTIVATO:
        // un evento incassato al 100% NON può restare PREVENTIVATO.
        String id = creaEventoTest("Evento Saldo da Preventivato", "1000");
        given().when().get("/api/eventi/" + id).then().body("stato", equalTo("PREVENTIVATO"));

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("SALDO", "1000.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .header("X-Suggest-Completamento", equalTo("true"));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoResiduo", equalTo(0.0f))
                .body("stato",          equalTo("SALDATO"));
    }

    @Test
    @Order(76)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void pagamentoSuEventoSaldato_409() {
        String id = creaEventoConfermato("Evento Saldato Poi Pagamento", "500");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("SALDO", "500.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "100.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("EVENTO_SALDATO"));
    }

    /**
     * I3 (rivisto 2026-08-08): una caparra pagata in due tranche è legittima — il vincolo
     * "max 1 CAPARRA/ACCONTO/SALDO per evento" è stato rimosso. Il test difende sia
     * l'accettazione sia la somma: due CAPARRA devono sommarsi, non sovrascriversi.
     */
    @Test
    @Order(77)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void doppiaCaparra_ammessa_eSiSomma() {
        String id = creaEventoConfermato("Evento Doppia Caparra", "2000");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "500.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("CAPARRA", "300.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .body("tipo", equalTo("CAPARRA"));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(800.0f))
                .body("importoResiduo",   equalTo(1200.0f))
                .body("stato",            equalTo("CONFERMATO"));

        // Terza tranche che chiude il residuo: l'auto-transizione a SALDATO resta viva
        // anche con più pagamenti dello stesso tipo.
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "1200.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(2000.0f))
                .body("stato",            equalTo("SALDATO"));
    }

    /**
     * L'unicità è caduta, il tetto sul residuo no: la quarta tranche che sfonda il
     * preventivato deve continuare a essere rifiutata (guardia percorso-soldi).
     */
    @Test
    @Order(78)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void tranceMultipleOltreIlResiduo_409() {
        String id = creaEventoConfermato("Evento Tranche Oltre Residuo", "1000");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "600.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "500.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("IMPORTO_SUPERA_RESIDUO"));
    }

    /**
     * Doppione vero: stesso evento, stesso tipo, stesso importo, stessa data, stesso conto.
     * È il caso reale di «Greg» (14/08/2026) — lo stesso bonifico da 1.500,00 registrato prima
     * a mano dalla scheda evento e poi di nuovo dal wizard incassi-evento, che aveva portato
     * l'incassato a 3.000,00 con un solo bonifico realmente arrivato.
     *
     * <p>Da distinguere da {@link #doppiaCaparra_ammessa_eSiSomma}: due tranche vere differiscono
     * almeno per importo o per data, e restano ammesse (ADR 003).
     */
    @Test
    @Order(79)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void stessoPagamentoDueVolte_409_eNonAlteraLIncassato() {
        String id = creaEventoConfermato("Evento Doppione Identico", "5000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "1500.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        // Il secondo, identico in tutto, viene rifiutato: il messaggio NOMINA il gemello.
        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "1500.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code",    equalTo("PAGAMENTO_DUPLICATO"))
                // Il messaggio NOMINA il gemello, con importi e date leggibili dal titolare.
                .body("message", containsString("acconto"))
                .body("message", containsString("1.500,00 €"))
                .body("message", containsString("01/08/2026"));

        // E soprattutto: il rifiuto non ha lasciato metà scrittura dietro di sé.
        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(1500.0f))
                .body("importoResiduo",   equalTo(3500.0f))
                .body("pagamenti.size()", equalTo(1));

        // Stesso tipo ma importo diverso: è una seconda tranche vera, resta ammessa (ADR 003).
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "200.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);
    }

    /**
     * A1 — su un evento SENZA capienza scattano entrambe le guardie, e quella che parla per prima
     * detta la diagnosi. Deve parlare l'anti-doppione: IMPORTO_SUPERA_RESIDUO manderebbe a
     * correggere il preventivo, cioè a fare spazio a un incasso che non esiste.
     *
     * <p>Caso reale del 14/08/2026: «18esimo Erica balzaretti», 550 € su 1.050 preventivati e già
     * incassati per 550 — rifiutato con la diagnosi sbagliata. Falsificabile: rimettendo la guardia
     * sotto il tetto sul residuo questo test torna rosso (409 IMPORTO_SUPERA_RESIDUO).
     */
    @Test
    @Order(160)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void doppioneSenzaCapienza_diagnosiDuplicato_nonResiduo() {
        String id = creaEventoConfermato("Evento Doppione Senza Capienza", "1050");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "550.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        // Residuo ora 500,00: il doppione da 550,00 lo sfonda. Deve vincere PAGAMENTO_DUPLICATO.
        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "550.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("PAGAMENTO_DUPLICATO"));

        given().when().get("/api/eventi/" + id)
                .then().body("importoIncassato", equalTo(550.0f))
                       .body("pagamenti.size()", equalTo(1));
    }

    /**
     * A2 — il messaggio del tetto sul residuo non manda più a correggere il preventivo: la prima
     * domanda è sul doppione, la seconda via è l'extra a consuntivo (che lascia intatto il
     * pattuito). Seguire il vecchio suggerimento su un doppione produceva preventivo falso
     * <b>e</b> ricavo fantasma.
     */
    @Test
    @Order(161)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void importoSuperaResiduo_messaggioNonMandaACorreggereIlPreventivo() {
        String id = creaEventoConfermato("Evento Messaggio Residuo", "500");

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "999.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code",    equalTo("IMPORTO_SUPERA_RESIDUO"))
                .body("message", startsWith("È lo stesso pagamento già registrato?"))
                .body("message", containsString("extra a consuntivo"))
                .body("message", not(containsStringIgnoringCase("correggi il preventivo")));
    }

    /**
     * A4 — la controparte è il sesto campo del confronto: due bonifici di persone diverse con
     * stesso importo, stesso giorno e stesso evento sono due incassi veri, non un doppione.
     * Caso misurato: 07/07/2026, due caparre da 20,00 € sull'evento del 25/09 (INDUNI RENATA e
     * DE AGOSTINI M RCO, estratto Crédit Agricole righe 55-56).
     *
     * <p>Il confronto resta <b>fail-closed</b> sul NULL: una controparte ignota non distingue
     * nulla, quindi il gemello scatta lo stesso.
     */
    @Test
    @Order(162)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void controparteDiversa_dueIncassiVeri_stessaOAssente_409() {
        String id = creaEventoConfermato("Evento Due Caparre da 20", "1000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "20.00", "2026-07-07", "INDUNI RENATA"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        // Stesso importo, stesso giorno, stesso conto — persona diversa: passa.
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "20.00", "2026-07-07", "DE AGOSTINI M RCO"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        // Stessa persona: doppione.
        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("CAPARRA", "20.00", "2026-07-07", "INDUNI RENATA"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("PAGAMENTO_DUPLICATO"));

        // Controparte assente (registrazione dalla scheda evento): non distingue → si rifiuta.
        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("CAPARRA", "20.00", "2026-07-07", null))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("PAGAMENTO_DUPLICATO"));

        given().when().get("/api/eventi/" + id)
                .then().body("importoIncassato", equalTo(40.0f))
                       .body("pagamenti.size()", equalTo(2));
    }

    /**
     * A4, non-regressione sul caso «Greg»: prima a mano dalla scheda evento (senza controparte),
     * poi dal wizard con la controparte letta dall'estratto. È la coppia che ha prodotto il
     * doppione reale da 1.500,00 €. Con un confronto {@code controparte = ?} secco il secondo
     * inserimento passerebbe — qui deve continuare a essere rifiutato.
     */
    @Test
    @Order(163)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void manualeSenzaControparte_poiImportConControparte_409() {
        String id = creaEventoConfermato("Evento Greg", "5000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "1500.00", "2026-07-02", null))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "1500.00", "2026-07-02", "PASCHETTO"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("PAGAMENTO_DUPLICATO"));

        given().when().get("/api/eventi/" + id)
                .then().body("importoIncassato", equalTo(1500.0f));
    }

    /**
     * A3 — «extra a consuntivo»: chi incassa più del pattuito registra l'eccedenza come voce con
     * preventivo 0 e consuntivo pari all'eccedenza. Il tetto sul pagamento si allarga (I4), il
     * preventivo — che è il pattuito — resta intatto (I2).
     *
     * <p>Copre anche il criterio 1: il 409 non deve creare voci per conto suo. In automatico
     * questa voce avrebbe assorbito in silenzio il doppione da 1.500 € di «Greg».
     */
    @Test
    @Order(164)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void extraAConsuntivo_allargaIlTetto_eLasciaIlPreventivoIntatto() {
        // L'evento creato con un preventivato nasce già con la sua voce: aggiungerne un'altra
        // qui raddoppierebbe il pattuito e il test misurerebbe un'altra cosa.
        String id = creaEventoConfermato("Evento Extra Consuntivo", "1000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "900.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given().when().get("/api/eventi/" + id).then()
                .body("importoTotalePreviventivato", equalTo(1000.0f))
                .body("importoIncassato",            equalTo(900.0f))
                .body("importoResiduo",              equalTo(100.0f));

        // Residuo 100,00: l'eccedenza da 300,00 non entra…
        given().contentType(ContentType.JSON)
            .body(buildPagamentoRequest("ACCONTO", "300.00", "2026-08-02", null))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then().statusCode(409).body("code", equalTo("IMPORTO_SUPERA_RESIDUO"));

        // …e il rifiuto non ha creato nessuna voce di comodo (criterio 1).
        given().when().get("/api/eventi/" + id + "/voci").then().body("size()", equalTo(1));

        // L'extra a consuntivo è un'azione esplicita: la registra l'utente, dalla data evento.
        dataEventoAIeri(id);
        aggiungiVoce(id, "Extra A3", "300.00", "0", "1");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "300.00", "2026-08-02", null))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        given()
            .when().get("/api/eventi/" + id)
            .then()
                // il pattuito NON è stato gonfiato per far entrare il pagamento (I2)
                .body("importoTotalePreviventivato", equalTo(1000.0f))
                .body("importoIncassato",            equalTo(1200.0f));
    }

    /**
     * A3 criterio 2, non-regressione: il tetto usa il MAGGIORE fra preventivo e consuntivo.
     * Con il consuntivo secco, un evento le cui voci sono state consuntivate al ribasso
     * (servizio non erogato) rifiuterebbe un pagamento del tutto legittimo.
     */
    @Test
    @Order(165)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void consuntivoMinoreDelPreventivo_nonStringeIlTetto() {
        String id = creaEventoConfermato("Evento Consuntivo Minore", "1000");
        dataEventoAIeri(id);
        // consuntivo 400,00 su preventivo 1.000,00
        String voceId = given().when().get("/api/eventi/" + id + "/voci")
                .then().statusCode(200).extract().path("[0].id").toString();
        given().contentType(ContentType.JSON)
                .body("{\"quantitaConsuntivo\":0.4}")
                .when().put("/api/eventi/voci/" + voceId).then().statusCode(200);

        // 900,00 > 400,00 di consuntivo, ma ≤ 1.000,00 di preventivo: deve passare.
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "900.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);
    }

    /**
     * A3 criterio 3 — una voce extra a consuntivo NON è un ricavo: i ricavi del P&L nascono dai
     * movimenti (`importoConsuntivo` è letto solo dentro EventiService e dal suo DTO). Senza
     * questo test, «allargare il tetto» potrebbe silenziosamente diventare doppio conteggio.
     */
    @Test
    @Order(166)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void voceExtraAConsuntivo_nonMuoveIlContoEconomico() {
        String id = creaEventoConfermato("Evento Extra No PL", "500");
        dataEventoAIeri(id);

        java.math.BigDecimal prima = ricaviAnno(2026);
        aggiungiVoce(id, "Extra A3 pl", "250.00", "0", "1");
        java.math.BigDecimal dopo = ricaviAnno(2026);

        assertEquals(0, prima.compareTo(dopo),
                "una voce a consuntivo non genera ricavo: il P&L legge i movimenti");
    }

    // ── 9. Pagamenti – RIMBORSO ───────────────────────────────────────────────

    @Test
    @Order(80)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void rimborso_riduceImportoIncassato() {
        String id = creaEventoConfermato("Evento con Rimborso", "2000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "800.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("RIMBORSO", "200.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(201)
                .body("tipo",    equalTo("RIMBORSO"))
                .body("importo", lessThan(0.0f));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(600.0f))
                .body("importoResiduo",   equalTo(1400.0f));
    }

    @Test
    @Order(81)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void rimborsoSuperaIncassato_409() {
        String id = creaEventoConfermato("Evento Rimborso Eccessivo", "2000");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "500.00"))
                .when().post("/api/eventi/" + id + "/pagamenti").then().statusCode(201);

        // Rimborso > incassato: vietato (porterebbe importoIncassato negativo)
        given()
            .contentType(ContentType.JSON)
            .body(buildPagamentoRequest("RIMBORSO", "800.00"))
            .when().post("/api/eventi/" + id + "/pagamenti")
            .then()
                .statusCode(409)
                .body("code", equalTo("RIMBORSO_SUPERA_INCASSATO"));

        given().when().get("/api/eventi/" + id)
            .then().body("importoIncassato", equalTo(500.0f));
    }

    // ── 10. Flusso completo: PREVENTIVATO → CONFERMATO → SALDATO ─────────────

    @Test
    @Order(90)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void flussoPagamentoCompleto_e_transitaASaldato() {
        String id = creaEventoConfermato("Evento Flusso Completo", "1000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "400.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "300.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("SALDO", "300.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then()
                    .statusCode(201)
                    .header("X-Suggest-Completamento", equalTo("true"));

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato",     equalTo(1000.0f))
                .body("importoResiduo",       equalTo(0.0f))
                .body("percentualeIncassata", equalTo(100.0f))
                .body("stato",                equalTo("SALDATO"))  // auto-transitioned on payment
                .body("pagamenti",            hasSize(3));
    }

    @Test
    @Order(91)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void completamento_conResiduoPositivo_409() {
        String id = creaEventoConfermato("Evento Residuo Positivo", "1000");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("ACCONTO", "900.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"SALDATO\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(409)
                .body("code", equalTo("RESIDUO_NON_AZZERATO"));
    }

    @Test
    @Order(92)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void saldato_nonModificabile_403() {
        String id = creaEventoConfermato("Evento Saldato Imm.", "500");
        // SALDO pari all'importo: residuo = 0 → auto-transizione a SALDATO
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("SALDO", "500.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201).header("X-Suggest-Completamento", equalTo("true"));

        // Evento ora SALDATO: qualsiasi modifica deve essere respinta
        given()
            .contentType(ContentType.JSON)
            .body("{\"nome\":\"Tentativo modifica\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(403);
    }

    @Test
    @Order(93)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void transitazioneNonAmmessa_400() {
        String id = creaEventoTest("Evento Trans Illegale", "1000");

        // PREVENTIVATO → SALDATO (salto CONFERMATO) → non ammesso
        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"SALDATO\"}")
            .when().put("/api/eventi/" + id)
            .then()
                .statusCode(400)
                .body("code", equalTo("TRANSIZIONE_NON_AMMESSA"));
    }

    // ── 11. Delete con movimenti collegati ────────────────────────────────────

    @Test
    @Order(100)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void deleteEvento_conMovimentiCollegati_403() {
        String id = creaEventoConfermato("Evento Con Movimenti", "2000");
        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "200.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given().when().delete("/api/eventi/" + id).then().statusCode(403);
    }

    // ── 12. Calendario ────────────────────────────────────────────────────────

    @Test
    @Order(110)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void calendario_default_lista_200() {
        given()
            .when().get("/api/eventi/calendario")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(111)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void calendario_conRange_200() {
        given()
            .queryParam("from", "2026-09-01")
            .queryParam("to", "2026-09-30")
            .when().get("/api/eventi/calendario")
            .then()
                .statusCode(200)
                .body("$", instanceOf(java.util.List.class));
    }

    @Test
    @Order(112)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void calendario_coloriStato_corretti() {
        // Data nel futuro lontano per evitare "DATA_NEL_PASSATO" quando la suite
        // viene eseguita dopo la data che era originariamente hardcodata.
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"nome":"Evento Calendario Colore","tipo":"AZIENDALE",
                     "dataEvento":"2099-05-20","contattoNome":"Test",
                     "numeroTotalePartecipanti":1}
                    """)
            .when().post("/api/eventi")
            .then().statusCode(201);

        given()
            .queryParam("from", "2099-05-01")
            .queryParam("to",   "2099-05-31")
            .when().get("/api/eventi/calendario")
            .then()
                .statusCode(200)
                .body("find { it.nome == 'Evento Calendario Colore' }.coloreStato",
                        equalTo("#FFA500")); // PREVENTIVATO = arancione
    }

    // ── 13. Dashboard ─────────────────────────────────────────────────────────

    @Test
    @Order(120)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void dashboard_campiPresenti_200() {
        given()
            .queryParam("from", "2026-01-01")
            .queryParam("to",   "2026-12-31")
            .when().get("/api/eventi/dashboard")
            .then()
                .statusCode(200)
                .body("totaleEventi",    notNullValue())
                .body("totaleIncassato", notNullValue())
                .body("totaleCosti",     notNullValue())
                .body("profittoTotale",  notNullValue())
                .body("from",            equalTo("2026-01-01"))
                .body("to",              equalTo("2026-12-31"));
    }

    @Test
    @Order(121)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void dashboard_profittoCoerente_conIncassatiECosti() {
        io.restassured.response.ValidatableResponse resp = given()
            .queryParam("from", "2026-01-01")
            .queryParam("to",   "2026-12-31")
            .when().get("/api/eventi/dashboard")
            .then().statusCode(200);

        float incassato = ((Number) resp.extract().path("totaleIncassato")).floatValue();
        float costi     = ((Number) resp.extract().path("totaleCosti")).floatValue();
        float profitto  = ((Number) resp.extract().path("profittoTotale")).floatValue();

        assertEquals(incassato - costi, profitto, 0.01f,
                "profittoTotale deve essere incassato - costi");
    }

    // ── 14. Partecipanti ──────────────────────────────────────────────────────

    @Test
    @Order(130)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void aggiungiPartecipante_ok_201() {
        String eventoId = creaEventoTest("Evento Con Partecipanti", "3000");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"personaleId":"%s","ruolo":"Fotografo","costo":500.00}
                    """.formatted(personaleId))
            .when().post("/api/eventi/" + eventoId + "/partecipanti")
            .then()
                .statusCode(201)
                .body("id",          notNullValue())
                .body("eventoId",    equalTo(eventoId))
                .body("personaleId", equalTo(personaleId))
                .body("ruolo",       equalTo("Fotografo"))
                .body("costo",       equalTo(500.0f));
    }

    @Test
    @Order(131)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void aggiungiPartecipante_duplicato_409() {
        String eventoId = creaEventoTest("Evento Partecipante Dup", "1000");

        String body = """
                {"personaleId":"%s","ruolo":"DJ"}
                """.formatted(personaleId);

        given().contentType(ContentType.JSON).body(body)
                .when().post("/api/eventi/" + eventoId + "/partecipanti")
                .then().statusCode(201);

        given()
            .contentType(ContentType.JSON)
            .body(body)
            .when().post("/api/eventi/" + eventoId + "/partecipanti")
            .then()
                .statusCode(409)
                .body("code", equalTo("PARTECIPANTE_DUPLICATO"));
    }

    @Test
    @Order(132)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void aggiungiPartecipante_personaleNonTrovato_404() {
        String eventoId = creaEventoTest("Evento Partecipante 404", "500");

        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"personaleId":"%s"}
                    """.formatted(NIL_UUID))
            .when().post("/api/eventi/" + eventoId + "/partecipanti")
            .then()
                .statusCode(404)
                .body("code", equalTo("PERSONALE_NOT_FOUND"));
    }

    @Test
    @Order(133)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getPartecipanti_lista_200() {
        String eventoId = creaEventoTest("Evento Lista Partecipanti", "1000");
        given().contentType(ContentType.JSON)
                .body("{\"personaleId\":\"%s\",\"ruolo\":\"Cameriere\"}".formatted(personaleId))
                .when().post("/api/eventi/" + eventoId + "/partecipanti")
                .then().statusCode(201);

        given()
            .when().get("/api/eventi/" + eventoId + "/partecipanti")
            .then()
                .statusCode(200)
                .body("$",            instanceOf(java.util.List.class))
                .body("",             hasSize(1))
                .body("[0].ruolo",    equalTo("Cameriere"))
                .body("[0].eventoId", equalTo(eventoId));
    }

    @Test
    @Order(134)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void rimuoviPartecipante_ok_204() {
        String eventoId = creaEventoTest("Evento Rimuovi Partecipante", "1000");
        int partId = given()
                .contentType(ContentType.JSON)
                .body("{\"personaleId\":\"%s\",\"ruolo\":\"Barman\"}".formatted(personaleId))
                .when().post("/api/eventi/" + eventoId + "/partecipanti")
                .then().statusCode(201)
                .extract().path("id");

        given()
            .when().delete("/api/eventi/partecipanti/" + partId)
            .then()
                .statusCode(204);

        given()
            .when().get("/api/eventi/" + eventoId + "/partecipanti")
            .then()
                .body("", hasSize(0));
    }

    @Test
    @Order(135)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void rimuoviPartecipante_nonTrovato_404() {
        given()
            .when().delete("/api/eventi/partecipanti/99999999")
            .then()
                .statusCode(404);
    }

    // ── 15. PDF Preventivo ────────────────────────────────────────────────────

    @Test
    @Order(140)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void pdfPreventivo_501_notImplemented() {
        String id = creaEventoTest("Evento PDF", "1000");

        given()
            .when().get("/api/eventi/" + id + "/pdf-preventivo")
            .then()
                .statusCode(501)
                .body("message",        containsString("non ancora implementato"))
                .body("plannedRelease", equalTo("v1.1"));
    }

    // ── 16. Profitto (integrazione costi reali da movimenti) ──────────────────

    @Test
    @Order(150)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void profittoEvento_calcolatoDaMovimentiUscita() {
        String id = creaEventoConfermato("Evento Profitto", "3000");

        given().contentType(ContentType.JSON)
                .body(buildPagamentoRequest("CAPARRA", "1000.00"))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);

        given()
            .when().get("/api/eventi/" + id)
            .then()
                .body("importoIncassato", equalTo(1000.0f))
                .body("costiReali",       equalTo(0))
                .body("profitto",         equalTo(1000.0f));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    // ── 11. Costi diretti (DJ/Torta/Custom → movimento USCITA DA_LIQUIDARE) ─────

    @Test
    @Order(100)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void costoDiretto_crud_djCreaMovimentoEPoiRimuovi() {
        String id = creaEventoTest("Evento Costi DJ", "5000");
        int costoId = given().contentType(ContentType.JSON)
            .body("""
                {"tipoCosto":"FISSO","voce":"DJ","importo":300.00}
                """)
            .when().post("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(201)
                .body("voce",        equalTo("DJ"))
                .body("etichetta",   equalTo("DJ e Intrattenimento"))
                .body("importo",     equalTo(300.0f))
                .body("movimentoId", notNullValue())
            .extract().path("id");

        given().when().get("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(200).body("size()", equalTo(1));

        given().when().delete("/api/eventi/" + id + "/costi-diretti/" + costoId)
            .then().statusCode(204);

        given().when().get("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    @Order(101)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void costoDiretto_voceNonValida_400() {
        String id = creaEventoTest("Evento Voce Errata", "1000");
        given().contentType(ContentType.JSON)
            .body("""
                {"tipoCosto":"FISSO","voce":"PIPPO","importo":100.00}
                """)
            .when().post("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(400).body("code", equalTo("VOCE_NON_VALIDA"));
    }

    @Test
    @Order(102)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void costoDiretto_customSenzaEtichetta_400() {
        String id = creaEventoTest("Evento Custom No Label", "1000");
        given().contentType(ContentType.JSON)
            .body("""
                {"tipoCosto":"VARIABILE","voce":"CUSTOM","importo":100.00}
                """)
            .when().post("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(400).body("code", equalTo("ETICHETTA_MANCANTE"));
    }

    @Test
    @Order(103)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void costoDiretto_eventoAnnullato_409() {
        String id = creaEventoTest("Evento Annullato Costo", "1000");
        given().contentType(ContentType.JSON)
                .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Test\"}")
                .when().put("/api/eventi/" + id).then().statusCode(200);
        given().contentType(ContentType.JSON)
            .body("""
                {"tipoCosto":"FISSO","voce":"DJ","importo":100.00}
                """)
            .when().post("/api/eventi/" + id + "/costi-diretti")
            .then().statusCode(409).body("code", equalTo("EVENTO_ANNULLATO"));
    }

    @Test
    @Order(104)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void getCostiDiretti_eventoInesistente_404() {
        given().when().get("/api/eventi/" + NIL_UUID + "/costi-diretti")
            .then().statusCode(404);
    }

    // ── 12. Ore su partecipante (paga oraria → movimento costo) ─────────────────

    @Test
    @Order(110)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void allocaOre_oraria_creaCostoEPoiRimuovi() {
        String id = creaEventoTest("Evento Ore Oraria", "5000");
        int pid = given().contentType(ContentType.JSON)
            .body("""
                {"personaleId":"%s","ruolo":"Cameriere"}
                """.formatted(personaleOrariaId))
            .when().post("/api/eventi/" + id + "/partecipanti")
            .then().statusCode(201).extract().path("id");

        // 20 EUR/h × 5h = 100.00
        given().contentType(ContentType.JSON)
            .body("""
                {"ore":5.0}
                """)
            .when().post("/api/eventi/partecipanti/" + pid + "/ore")
            .then().statusCode(200)
                .body("ore",   equalTo(5.0f))
                .body("costo", equalTo(100.0f));

        given().when().delete("/api/eventi/partecipanti/" + pid + "/ore")
            .then().statusCode(200).body("costo", nullValue());
    }

    @Test
    @Order(111)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void allocaOre_dipendenteNonOrario_400() {
        String id = creaEventoTest("Evento Ore Mensile", "5000");
        int pid = given().contentType(ContentType.JSON)
            .body("""
                {"personaleId":"%s","ruolo":"Cuoco"}
                """.formatted(personaleId))
            .when().post("/api/eventi/" + id + "/partecipanti")
            .then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
            .body("""
                {"ore":3.0}
                """)
            .when().post("/api/eventi/partecipanti/" + pid + "/ore")
            .then().statusCode(400).body("code", equalTo("NON_ORARIA"));
    }

    @Test
    @Order(112)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void allocaOre_partecipanteInesistente_404() {
        given().contentType(ContentType.JSON)
            .body("""
                {"ore":2.0}
                """)
            .when().post("/api/eventi/partecipanti/999999/ore")
            .then().statusCode(404);
    }

    // ── 13. Monitoring preventivato (AFFITTO/CATERING, no contabilità) ──────────

    @Test
    @Order(120)
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void preventivoTracking_upsertGetRimuovi() {
        String id = creaEventoTest("Evento Preventivo Tracking", "5000");
        int tid = given().contentType(ContentType.JSON)
            .body("""
                {"tipo":"AFFITTO","importoIncasso":1200.00}
                """)
            .when().post("/api/eventi/" + id + "/preventivo-tracking")
            .then().statusCode(200).body("tipo", equalTo("AFFITTO"))
            .extract().path("id");

        given().when().get("/api/eventi/" + id + "/preventivo-tracking")
            .then().statusCode(200).body("size()", equalTo(1));

        given().when().delete("/api/eventi/" + id + "/preventivo-tracking/" + tid)
            .then().statusCode(204);

        given().when().get("/api/eventi/" + id + "/preventivo-tracking")
            .then().statusCode(200).body("size()", equalTo(0));
    }

    private String creaEventoTest(String nome, String importo) {
        String importoJson = importo != null ? "\"importoTotalePreviventivato\":" + importo + "," : "";
        return given()
                .auth().none()
                .contentType(ContentType.JSON)
                .body("""
                        {"nome":"%s","tipo":"BANCHETTO_PRIVATO","dataEvento":"2026-12-15",
                         "contattoNome":"Test Contatto","numeroTotalePartecipanti":50,
                         %s"businessUnitId":2}
                        """.formatted(nome, importoJson))
                .when().post("/api/eventi")
                .then().statusCode(201)
                .extract().path("id");
    }

    /**
     * Inserisce un evento direttamente via EntityManager, bypassando il controllo
     * di ruolo. Necessario per i test che eseguono assertion da contesto
     * DIPENDENTE (ad esempio: verifica che il DIPENDENTE riceva 403 su PUT)
     * ma hanno bisogno di un evento già esistente come fixture: la creazione
     * via REST sarebbe respinta dal nuovo regime di permessi ADMIN-only.
     */
    @Transactional
    String creaEventoTest_comeAdmin(String nome, String importo) {
        String id = java.util.UUID.randomUUID().toString();
        java.math.BigDecimal importoBd = importo != null ? new java.math.BigDecimal(importo) : null;
        em.createNativeQuery("""
                INSERT INTO eventi (
                    id, nome, tipo, data_evento, importo_totale_preventivato,
                    importo_incassato, caparre_incassate, costi_diretti_imputati,
                    stato, business_unit_id, contatto_nome,
                    numero_totale_partecipanti, created_at)
                VALUES (
                    CAST(:id AS uuid), :nome, 'BANCHETTO_PRIVATO', DATE '2026-12-15', :importo,
                    0, 0, 0,
                    'PREVENTIVATO', 2, 'Test Contatto',
                    50, now())
                """)
                .setParameter("id", id)
                .setParameter("nome", nome)
                .setParameter("importo", importoBd)
                .executeUpdate();
        return id;
    }

    private String creaEventoConfermato(String nome, String importo) {
        String id = creaEventoTest(nome, importo);
        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"CONFERMATO\"}")
            .when().put("/api/eventi/" + id)
            .then().statusCode(200);
        return id;
    }

    private String buildCreateRequest(String nome, String importo) {
        return """
                {"nome":"%s","tipo":"BANCHETTO_PRIVATO","dataEvento":"2026-12-10",
                 "contattoNome":"Test","importoTotalePreviventivato":%s,
                 "numeroTotalePartecipanti":50,"businessUnitId":2}
                """.formatted(nome, importo);
    }

    private String buildPagamentoRequest(String tipo, String importo) {
        return """
                {"tipo":"%s","importo":%s,"data":"2026-08-01",
                 "metodoPagamentoId":%d,"contoBancarioId":%d}
                """.formatted(tipo, importo, metodoPagamentoId, contoBancarioId);
    }

    /** Voce di preventivo (e, se {@code qtaConsuntivo} != null, anche di consuntivo). */
    private void aggiungiVoce(String eventoId, String label, String prezzo,
                              String qtaPreventivo, String qtaConsuntivo) {
        String consuntivo = qtaConsuntivo == null ? "" : ",\"quantitaConsuntivo\":" + qtaConsuntivo;
        given().contentType(ContentType.JSON)
                .body("{\"label\":\"%s\",\"prezzoUnitario\":%s,\"quantitaPreventivo\":%s%s}"
                        .formatted(label, prezzo, qtaPreventivo, consuntivo))
                .when().post("/api/eventi/" + eventoId + "/voci")
                .then().statusCode(201);
    }

    /**
     * Il consuntivo è compilabile solo dalla data evento (`assertConsuntivabile`), e la creazione
     * di un evento rifiuta le date passate: l'unico modo di avere un evento consuntivabile è
     * spostargli la data da sotto. È setup, non comportamento sotto test.
     */
    private void dataEventoAIeri(String eventoId) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE eventi SET data_evento = current_date - 1 WHERE id = CAST(:i AS uuid)")
                .setParameter("i", eventoId).executeUpdate());
    }

    private java.math.BigDecimal ricaviAnno(int anno) {
        em.createNativeQuery("SELECT fn_refresh_all_mv()").getSingleResult();
        return (java.math.BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(sum(ricavi),0) FROM mv_conto_economico_mensile WHERE anno = :a")
                .setParameter("a", anno).getSingleResult();
    }

    /** Variante A4: data del bonifico e controparte espliciti (null = registrazione manuale). */
    private String buildPagamentoRequest(String tipo, String importo, String data, String controparte) {
        return """
                {"tipo":"%s","importo":%s,"data":"%s",
                 "metodoPagamentoId":%d,"contoBancarioId":%d,"controparte":%s}
                """.formatted(tipo, importo, data, metodoPagamentoId, contoBancarioId,
                              controparte == null ? "null" : "\"" + controparte + "\"");
    }
}
