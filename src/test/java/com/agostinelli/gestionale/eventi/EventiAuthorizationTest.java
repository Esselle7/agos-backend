package com.agostinelli.gestionale.eventi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Suite di test focalizzata su autorizzazione e visibility policy del modulo
 * Eventi dopo l'introduzione del link {@code users.personale_id} (V28) e della
 * separazione ADMIN/DIPENDENTE.
 *
 * Verifica in particolare:
 *   - POST/PUT/DELETE eventi e POST pagamenti sono ADMIN-only (403 per DIPENDENTE)
 *   - GET list/detail/calendario/dashboard accessibili a entrambi i ruoli
 *   - I campi finanziari (importi, profitto, residuo, costo personale,
 *     KPI dashboard) sono {@code null} per i DIPENDENTE
 *   - Le date di journey (dataConferma, dataSaldo) sono visibili a entrambi
 *   - {@code /api/eventi/miei} ritorna solo gli eventi assegnati al
 *     dipendente collegato, lista vuota se {@code personale_id} è null
 *     (account non collegato), 403 per ADMIN
 *   - Lista pagamenti è restituita anche a DIPENDENTE ma con importo/note nulli
 *
 * Tutti i dati di fixture sono inseriti via {@link EntityManager} per non
 * dipendere dal contesto di sicurezza dei singoli test (un test DIPENDENTE
 * non potrebbe creare un evento via REST, essendo POST ADMIN-only).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventiAuthorizationTest {

    static final String USER_ADMIN_UUID      = "00000000-0000-0000-0000-000000000099";
    /** Dipendente collegato a un record personale (via users.personale_id). */
    static final String USER_LINKED_UUID     = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    /** Dipendente senza link (personale_id IS NULL). */
    static final String USER_UNLINKED_UUID   = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    /** Dipendente collegato a un altro personale (per verificare isolamento). */
    static final String USER_OTHER_UUID      = "cccccccc-cccc-cccc-cccc-cccccccccccc";

    @Inject EntityManager em;
    @Inject FixtureSetup fixtures;

    private static FixtureIds ids;

    /** Container immutabile per gli id risolti una sola volta nel ciclo dei test. */
    record FixtureIds(
            String linkedPersonaleId,
            String otherPersonaleId,
            String eventoAssegnatoLinkedId,
            String eventoAssegnatoOtherId,
            String eventoMaiAssegnatoId,
            Integer metodoPagamentoId,
            Short   contoBancarioId
    ) {}

    @BeforeEach
    void setUp() {
        if (ids == null) {
            ids = fixtures.bootstrap(
                    USER_LINKED_UUID, USER_UNLINKED_UUID, USER_OTHER_UUID);
        }
    }

    // ── 1. POST / PUT / DELETE eventi: ADMIN-only ─────────────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void post_evento_dipendente_403() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"nome":"Tentativo DIPENDENTE","tipo":"ALTRO",
                         "dataEvento":"2026-12-31","contattoNome":"X",
                         "numeroTotalePartecipanti":1}
                        """)
                .when().post("/api/eventi")
                .then().statusCode(403);
    }

    @Test
    @Order(2)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void put_evento_dipendente_403() {
        given().contentType(ContentType.JSON)
                .body("{\"nome\":\"hack\"}")
                .when().put("/api/eventi/" + ids.eventoAssegnatoLinkedId())
                .then().statusCode(403);
    }

    @Test
    @Order(3)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void delete_evento_dipendente_403() {
        given().when().delete("/api/eventi/" + ids.eventoAssegnatoLinkedId())
                .then().statusCode(403);
    }

    @Test
    @Order(4)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void post_pagamento_dipendente_403() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"tipo":"CAPARRA","importo":100,"data":"2026-08-01",
                         "metodoPagamentoId":%d,"contoBancarioId":%d}
                        """.formatted(ids.metodoPagamentoId(), ids.contoBancarioId()))
                .when().post("/api/eventi/" + ids.eventoAssegnatoLinkedId() + "/pagamenti")
                .then().statusCode(403);
    }

    @Test
    @Order(5)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void post_partecipante_dipendente_403() {
        given().contentType(ContentType.JSON)
                .body("{\"personaleId\":\"%s\"}".formatted(ids.otherPersonaleId()))
                .when().post("/api/eventi/" + ids.eventoAssegnatoLinkedId() + "/partecipanti")
                .then().statusCode(403);
    }

    @Test
    @Order(6)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void delete_partecipante_dipendente_403() {
        given().when().delete("/api/eventi/partecipanti/1")
                .then().statusCode(403);
    }

    // ── 2. Dashboard: KPI finanziari ADMIN-only ────────────────────────────────

    @Test
    @Order(10)
    @TestSecurity(user = USER_ADMIN_UUID, roles = {"ADMIN"})
    void dashboard_admin_vede_campi_finanziari() {
        given().queryParam("from", "2026-01-01")
                .queryParam("to",   "2026-12-31")
                .when().get("/api/eventi/dashboard")
                .then()
                    .statusCode(200)
                    .body("totaleEventi",    notNullValue())
                    .body("totaleIncassato", notNullValue())
                    .body("totaleCosti",     notNullValue())
                    .body("profittoTotale",  notNullValue());
    }

    @Test
    @Order(11)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void dashboard_dipendente_solo_totale_eventi() {
        given().queryParam("from", "2026-01-01")
                .queryParam("to",   "2026-12-31")
                .when().get("/api/eventi/dashboard")
                .then()
                    .statusCode(200)
                    .body("totaleEventi",    notNullValue())
                    .body("totaleIncassato", nullValue())
                    .body("totaleCosti",     nullValue())
                    .body("profittoTotale",  nullValue());
    }

    // ── 3. Event detail e list: financial fields ADMIN-only ──────────────────

    @Test
    @Order(20)
    @TestSecurity(user = USER_ADMIN_UUID, roles = {"ADMIN"})
    void detail_admin_vede_tutti_i_campi() {
        given().when().get("/api/eventi/" + ids.eventoAssegnatoLinkedId())
                .then()
                    .statusCode(200)
                    .body("importoTotalePreviventivato", notNullValue())
                    .body("importoIncassato",            notNullValue())
                    .body("caparreIncassate",            notNullValue())
                    .body("costiDirettiImputati",        notNullValue())
                    .body("importoResiduo",              notNullValue())
                    .body("costiReali",                  notNullValue())
                    .body("profitto",                    notNullValue());
    }

    @Test
    @Order(21)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void detail_dipendente_nessun_campo_finanziario() {
        given().when().get("/api/eventi/" + ids.eventoAssegnatoLinkedId())
                .then()
                    .statusCode(200)
                    .body("importoTotalePreviventivato", nullValue())
                    .body("importoIncassato",            nullValue())
                    .body("caparreIncassate",            nullValue())
                    .body("costiDirettiImputati",        nullValue())
                    .body("importoResiduo",              nullValue())
                    .body("percentualeIncassata",        nullValue())
                    .body("costiReali",                  nullValue())
                    .body("profitto",                    nullValue())
                    .body("noteAnnullamento",            nullValue());
    }

    @Test
    @Order(22)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void detail_dipendente_pagamenti_con_importo_nascosto() {
        // Inserisce un movimento CAPARRA direttamente in DB: bypassa il
        // controllo di ruolo per costruire la fixture necessaria al test.
        fixtures.registraCaparraDirettamente(
                ids.eventoAssegnatoLinkedId(),
                new BigDecimal("300.00"),
                ids.metodoPagamentoId(),
                ids.contoBancarioId());

        given().when().get("/api/eventi/" + ids.eventoAssegnatoLinkedId())
                .then()
                    .statusCode(200)
                    .body("pagamenti.size()",         greaterThanOrEqualTo(1))
                    .body("pagamenti[0].tipo",        equalTo("CAPARRA"))
                    .body("pagamenti[0].importo",     nullValue())  // ADMIN-only
                    .body("pagamenti[0].note",        nullValue())  // ADMIN-only
                    .body("pagamenti[0].stato",       equalTo("ATTIVO"))
                    .body("pagamenti[0].dataFinanziaria", notNullValue())
                    .body("dataConferma",             notNullValue()); // visibile a tutti
    }

    @Test
    @Order(23)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void list_dipendente_nessun_campo_finanziario() {
        given().when().get("/api/eventi")
                .then()
                    .statusCode(200)
                    .body("content.findAll { it != null }.importoTotalePreviventivato",
                            everyItem(nullValue()))
                    .body("content.findAll { it != null }.profitto", everyItem(nullValue()));
    }

    // ── 4. Calendario: importoTotale/importoResiduo ADMIN-only ───────────────

    @Test
    @Order(30)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void calendario_dipendente_nessun_importo() {
        given().queryParam("from", "2026-01-01")
                .queryParam("to",   "2026-12-31")
                .when().get("/api/eventi/calendario")
                .then()
                    .statusCode(200)
                    .body("findAll { it != null }.importoTotale",  everyItem(nullValue()))
                    .body("findAll { it != null }.importoResiduo", everyItem(nullValue()));
    }

    // ── 5. Partecipanti: costo ADMIN-only ─────────────────────────────────────

    @Test
    @Order(40)
    @TestSecurity(user = USER_ADMIN_UUID, roles = {"ADMIN"})
    void partecipanti_admin_vede_costo() {
        given().when().get("/api/eventi/" + ids.eventoAssegnatoLinkedId() + "/partecipanti")
                .then()
                    .statusCode(200)
                    .body("size()",     greaterThanOrEqualTo(1))
                    .body("[0].costo",  notNullValue())
                    .body("[0].costo",  equalTo(250.0f));
    }

    @Test
    @Order(41)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void partecipanti_dipendente_costo_nascosto() {
        given().when().get("/api/eventi/" + ids.eventoAssegnatoLinkedId() + "/partecipanti")
                .then()
                    .statusCode(200)
                    .body("size()",    greaterThanOrEqualTo(1))
                    .body("[0].costo", nullValue())
                    .body("[0].nome",  notNullValue());  // dati anagrafici visibili
    }

    // ── 6. /api/eventi/miei: lista per personale collegato ────────────────────

    @Test
    @Order(50)
    @TestSecurity(user = USER_LINKED_UUID, roles = {"DIPENDENTE"})
    void miei_dipendente_collegato_solo_eventi_propri() {
        var resp = given().when().get("/api/eventi/miei").then().statusCode(200);

        // Deve contenere l'evento dove è assegnato linkedPersonaleId
        resp.body("content.id", hasItem(ids.eventoAssegnatoLinkedId()));
        // Non deve contenere eventi di altri dipendenti
        resp.body("content.id", not(hasItem(ids.eventoAssegnatoOtherId())));
        // Non deve contenere eventi senza assegnazioni
        resp.body("content.id", not(hasItem(ids.eventoMaiAssegnatoId())));
        // Visibility policy applicata: niente campi finanziari
        resp.body("content.findAll { it != null }.importoTotalePreviventivato",
                everyItem(nullValue()));
    }

    @Test
    @Order(51)
    @TestSecurity(user = USER_UNLINKED_UUID, roles = {"DIPENDENTE"})
    void miei_dipendente_non_collegato_lista_vuota() {
        given().when().get("/api/eventi/miei")
                .then()
                    .statusCode(200)
                    .body("content.size()", equalTo(0))
                    .body("totalElements",  equalTo(0));
    }

    @Test
    @Order(52)
    @TestSecurity(user = USER_OTHER_UUID, roles = {"DIPENDENTE"})
    void miei_dipendente_altro_isolato_da_eventi_di_terzi() {
        var resp = given().when().get("/api/eventi/miei").then().statusCode(200);
        resp.body("content.id", hasItem(ids.eventoAssegnatoOtherId()));
        resp.body("content.id", not(hasItem(ids.eventoAssegnatoLinkedId())));
    }

    @Test
    @Order(53)
    @TestSecurity(user = USER_ADMIN_UUID, roles = {"ADMIN"})
    void miei_admin_403() {
        // L'endpoint /miei è riservato al DIPENDENTE: gli ADMIN non hanno un
        // record personale e usano gli endpoint standard.
        given().when().get("/api/eventi/miei").then().statusCode(403);
    }

    // ── 7. Senza autenticazione: filtro/role guard respingono ────────────────

    @Test
    @Order(60)
    void miei_senza_securityContext_respinta() {
        // In test profile il JwtAuthFilter è disabilitato; il @RolesAllowed
        // applicato dal SecurityContext vuoto rifiuta comunque la richiesta.
        given().when().get("/api/eventi/miei")
                .then().statusCode(anyOf(is(401), is(403)));
    }

    // ── Fixture setup CDI bean (mantiene transazioni e isolamento) ───────────

    @jakarta.enterprise.context.ApplicationScoped
    public static class FixtureSetup {

        @Inject EntityManager em;

        @Transactional
        public FixtureIds bootstrap(String userLinkedId, String userUnlinkedId, String userOtherId) {
            int metodoPagamentoId = ((Number) em.createNativeQuery(
                    "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'")
                    .getSingleResult()).intValue();
            short contoBancarioId = ((Number) em.createNativeQuery(
                    "SELECT id FROM conti_bancari LIMIT 1")
                    .getSingleResult()).shortValue();

            String linkedPersonaleId = upsertPersonale("AuthTest", "Linked");
            String otherPersonaleId  = upsertPersonale("AuthTest", "Other");

            upsertUser(userLinkedId,   "linked-auth@test.local",   "sub-linked-auth",   linkedPersonaleId);
            upsertUser(userUnlinkedId, "unlinked-auth@test.local", "sub-unlinked-auth", null);
            upsertUser(userOtherId,    "other-auth@test.local",    "sub-other-auth",    otherPersonaleId);

            String eventoLinked = insertEvento("Evento Auth Linked", new BigDecimal("1000.00"));
            assegnaPersonale(eventoLinked, linkedPersonaleId, "Fotografo", new BigDecimal("250.00"));

            String eventoOther = insertEvento("Evento Auth Other", new BigDecimal("2000.00"));
            assegnaPersonale(eventoOther, otherPersonaleId, "DJ", new BigDecimal("400.00"));

            String eventoSolo = insertEvento("Evento Auth Solo", new BigDecimal("500.00"));

            return new FixtureIds(linkedPersonaleId, otherPersonaleId,
                    eventoLinked, eventoOther, eventoSolo,
                    metodoPagamentoId, contoBancarioId);
        }

        /**
         * Registra una caparra inserendo direttamente il movimento e
         * aggiornando i totali dell'evento. Bypassa il controllo di ruolo
         * REST: utile quando il test corrente è in contesto DIPENDENTE.
         */
        @Transactional
        public void registraCaparraDirettamente(
                String eventoId, BigDecimal importo,
                int metodoPagamentoId, short contoBancarioId) {
            // Risolvi un coge ricavi
            int cogeId = ((Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '30.%' ORDER BY codice LIMIT 1")
                    .getSingleResult()).intValue();

            em.createNativeQuery("""
                    INSERT INTO movimenti (
                        id, tipo, importo, importo_commissione,
                        data_movimento, data_finanziaria, data_liquidita,
                        stato, evento_id, tipo_evento_movimento,
                        conto_bancario_id, metodo_pagamento_id, business_unit_id, conto_coge,
                        descrizione, fonte, created_at)
                    VALUES (
                        gen_random_uuid(), 'ENTRATA', :importo, 0,
                        (SELECT data_evento FROM eventi WHERE id = CAST(:eid AS uuid)),
                        DATE '2026-08-01', DATE '2026-08-01',
                        'ATTIVO', CAST(:eid AS uuid), 'CAPARRA',
                        :conto, :metodo, 2, :coge,
                        '[EVENTO TEST] caparra fixture', 'MANUALE', now())
                    """)
                    .setParameter("importo", importo)
                    .setParameter("eid", eventoId)
                    .setParameter("conto", contoBancarioId)
                    .setParameter("metodo", metodoPagamentoId)
                    .setParameter("coge", cogeId)
                    .executeUpdate();

            // Aggiorna i totali aggregati: importoIncassato e caparreIncassate
            em.createNativeQuery("""
                    UPDATE eventi SET
                        importo_incassato = importo_incassato + :importo,
                        caparre_incassate = caparre_incassate + :importo,
                        stato = CASE WHEN stato = 'PREVENTIVATO' THEN 'CONFERMATO' ELSE stato END
                    WHERE id = CAST(:eid AS uuid)
                    """)
                    .setParameter("importo", importo)
                    .setParameter("eid", eventoId)
                    .executeUpdate();
        }

        private String upsertPersonale(String nome, String cognome) {
            em.createNativeQuery("""
                    INSERT INTO personale (nome, cognome, is_active)
                    VALUES (:n, :c, true)
                    ON CONFLICT DO NOTHING
                    """)
                    .setParameter("n", nome)
                    .setParameter("c", cognome)
                    .executeUpdate();
            return em.createNativeQuery(
                    "SELECT id FROM personale WHERE nome = :n AND cognome = :c")
                    .setParameter("n", nome)
                    .setParameter("c", cognome)
                    .getSingleResult().toString();
        }

        private void upsertUser(String userId, String email, String googleSub, String personaleId) {
            em.createNativeQuery("""
                    INSERT INTO users (id, email, google_sub, full_name, role, is_active, personale_id, created_at)
                    VALUES (CAST(:id AS uuid), :email, :sub, :name, 'DIPENDENTE', true,
                            CASE WHEN :pid IS NULL THEN NULL ELSE CAST(:pid AS uuid) END,
                            now())
                    ON CONFLICT (id) DO UPDATE SET
                        personale_id = CASE WHEN :pid IS NULL THEN NULL ELSE CAST(:pid AS uuid) END
                    """)
                    .setParameter("id", userId)
                    .setParameter("email", email)
                    .setParameter("sub", googleSub)
                    .setParameter("name", email)
                    .setParameter("pid", personaleId)
                    .executeUpdate();
        }

        private String insertEvento(String nome, BigDecimal importo) {
            String id = UUID.randomUUID().toString();
            em.createNativeQuery("""
                    INSERT INTO eventi (
                        id, nome, tipo, data_evento, importo_totale_preventivato,
                        importo_incassato, caparre_incassate, costi_diretti_imputati,
                        stato, business_unit_id, contatto_nome,
                        numero_totale_partecipanti, created_at)
                    VALUES (
                        CAST(:id AS uuid), :nome, 'BANCHETTO_PRIVATO', DATE '2026-11-30', :importo,
                        0, 0, 0,
                        'PREVENTIVATO', 2, 'Test',
                        20, now())
                    """)
                    .setParameter("id", id)
                    .setParameter("nome", nome)
                    .setParameter("importo", importo)
                    .executeUpdate();
            return id;
        }

        private void assegnaPersonale(String eventoId, String personaleId, String ruolo, BigDecimal costo) {
            em.createNativeQuery("""
                    INSERT INTO evento_partecipanti (evento_id, personale_id, ruolo, costo)
                    VALUES (CAST(:eid AS uuid), CAST(:pid AS uuid), :ruolo, :costo)
                    ON CONFLICT (evento_id, personale_id) DO NOTHING
                    """)
                    .setParameter("eid", eventoId)
                    .setParameter("pid", personaleId)
                    .setParameter("ruolo", ruolo)
                    .setParameter("costo", costo)
                    .executeUpdate();
        }
    }
}
