package com.agostinelli.gestionale.eventi;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec eventi-storico-e-ordinamento (lotto 19/08/2026, punto 5).
 *
 * Le due schede sono una PARTIZIONE: `SALDATO` ⟺ Storico, tutto il resto ⟺ Lista, e nessun evento
 * può stare in entrambe o in nessuna. L'oracolo non è un numero scritto a mano — è il totale della
 * stessa query senza `vista`, così il test non invecchia quando il DB di test cambia.
 *
 * Ogni chiamata filtra su un prefisso unico (`search`): il DB di test è condiviso con le altre
 * classi, che creano eventi per conto loro.
 */
@QuarkusTest
class EventiVistaPartizioneIntegrationTest {

    static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";
    private static final String MARCA = "ZZVista";
    private static final ZoneId ITALY = ZoneId.of("Europe/Rome");

    @Inject EntityManager em;

    private LocalDate oggi;

    @BeforeEach
    void setUp() {
        oggi = LocalDate.now(ITALY);
    }

    /** Ripulisce e ricrea i 4 eventi di prova: 2 passati (back-dati a mano) e 2 futuri. */
    @Transactional
    void seed() {
        // Prima i movimenti: saldare un evento ne crea uno che lo referenzia (FK
        // movimenti_evento_id_fkey), e senza questo passo la pulizia del test precedente esplode.
        em.createNativeQuery(
                "DELETE FROM movimenti WHERE evento_id IN "
                + "(SELECT id FROM eventi WHERE nome LIKE '" + MARCA + "%')").executeUpdate();
        em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE '" + MARCA + "%'").executeUpdate();
    }

    private void seedEventi() {
        seed();
        // La creazione rifiuta le date passate (EventiService:90): i due passati nascono futuri e
        // vengono retrodatati a DB — è l'unico modo di avere il blocco «da chiudere» in un test.
        String p30 = crea(MARCA + " passato lontano", oggi.plusDays(90));
        String p10 = crea(MARCA + " passato vicino",  oggi.plusDays(91));
        crea(MARCA + " futuro vicino",  oggi.plusDays(5));
        crea(MARCA + " futuro lontano", oggi.plusDays(40));
        retrodata(p30, oggi.minusDays(30));
        retrodata(p10, oggi.minusDays(10));
    }

    @Transactional
    void retrodata(String id, LocalDate data) {
        em.createNativeQuery("UPDATE eventi SET data_evento = :data WHERE id = CAST(:id AS uuid)")
                .setParameter("data", data).setParameter("id", id).executeUpdate();
    }

    private String crea(String nome, LocalDate data) {
        return given().contentType(ContentType.JSON)
                .body("""
                      {"nome":"%s","tipo":"BANCHETTO_PRIVATO","dataEvento":"%s",
                       "contattoNome":"Test","importoTotalePreviventivato":100.00,
                       "numeroTotalePartecipanti":10,"businessUnitId":2}
                      """.formatted(nome, data))
                .when().post("/api/eventi")
                .then().statusCode(201)
                .extract().path("id");
    }

    private List<String> nomi(String vista) {
        return given().queryParam("search", MARCA).queryParam("size", 100)
                .queryParam("vista", vista)
                .when().get("/api/eventi")
                .then().statusCode(200)
                .extract().path("content.nome");
    }

    private long totale(String vista) {
        var req = given().queryParam("search", MARCA).queryParam("size", 100);
        if (vista != null) req = req.queryParam("vista", vista);
        return ((Number) req.when().get("/api/eventi")
                .then().statusCode(200)
                .extract().path("totalElements")).longValue();
    }

    private void saldaPerIntero(String nome) {
        String id = given().queryParam("search", nome).queryParam("size", 5)
                .when().get("/api/eventi").then().statusCode(200)
                .extract().path("content[0].id");
        given().contentType(ContentType.JSON)
                .body("{\"stato\":\"CONFERMATO\"}")
                .when().put("/api/eventi/" + id).then().statusCode(200);
        Integer metodo = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'").getSingleResult()).intValue();
        Short conto = ((Number) em.createNativeQuery(
                "SELECT id FROM conti_bancari LIMIT 1").getSingleResult()).shortValue();
        given().contentType(ContentType.JSON)
                .body("""
                      {"tipo":"SALDO","importo":100.00,"data":"%s","metodoPagamentoId":%d,"contoBancarioId":%d}
                      """.formatted(oggi, metodo, conto))
                .when().post("/api/eventi/" + id + "/pagamenti")
                .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void r1_listaApreSulProssimoEventoEIPassatiChiudonoLaLista() {
        seedEventi();
        assertEquals(List.of(
                MARCA + " futuro vicino",     // +5gg  ← la prima riga: il prossimo evento
                MARCA + " futuro lontano",    // +40gg
                MARCA + " passato lontano",   // -30gg, il più in ritardo dei «da chiudere»
                MARCA + " passato vicino"),   // -10gg
                nomi("LISTA"));
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void r2_r6_unEventoSaldatoLasciaLaListaEComparNelloStorico() {
        seedEventi();
        assertEquals(4, nomi("LISTA").size());
        assertEquals(List.of(), nomi("STORICO"));

        saldaPerIntero(MARCA + " passato vicino");

        assertEquals(List.of(
                MARCA + " futuro vicino",
                MARCA + " futuro lontano",
                MARCA + " passato lontano"),
                nomi("LISTA"));
        assertEquals(List.of(MARCA + " passato vicino"), nomi("STORICO"));
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void r3_partizione_listaPiuStoricoUgualeTotale() {
        seedEventi();
        saldaPerIntero(MARCA + " futuro lontano");

        long lista = totale("LISTA"), storico = totale("STORICO"), tutti = totale(null);
        assertEquals(tutti, lista + storico,
                "un evento è finito in due schede o in nessuna: lista=" + lista
                + " storico=" + storico + " tutti=" + tutti);
        assertEquals(3, lista);
        assertEquals(1, storico);
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void r5_ilFiltroStatoContinuaAFunzionareDentroLaScheda() {
        seedEventi();
        // Dentro la Lista il filtro per stato resta vivo…
        assertEquals(4, (int) given().queryParam("search", MARCA).queryParam("vista", "LISTA")
                .queryParam("stato", "PREVENTIVATO").queryParam("size", 100)
                .when().get("/api/eventi").then().statusCode(200)
                .extract().path("content.size()"));
        // …e chiedere i SALDATI nella Lista dà zero righe: è il caso che l'interfaccia intercetta
        // rimandando allo Storico invece di mostrare una lista vuota senza spiegazione.
        assertEquals(0, (int) given().queryParam("search", MARCA).queryParam("vista", "LISTA")
                .queryParam("stato", "SALDATO").queryParam("size", 100)
                .when().get("/api/eventi").then().statusCode(200)
                .extract().path("content.size()"));
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void senzaVistaIlComportamentoRestaQuelloDiPrima() {
        seedEventi();
        // Le altre maschere (filtri movimenti, wizard) chiedono l'elenco intero: il default non
        // deve restringersi, o si svuotano senza che nessuno se ne accorga.
        assertEquals(4, totale(null));
    }

    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void vistaSconosciuta_400() {
        given().queryParam("vista", "ARCHIVIO")
                .when().get("/api/eventi")
                .then().statusCode(400)
                .body("code", org.hamcrest.Matchers.equalTo("VISTA_NON_VALIDA"));
    }
}
