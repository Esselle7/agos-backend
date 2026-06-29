package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * GET /api/movimenti/senza-banca + attribuzione a un conto via PUT {contoBancarioId}.
 * Inserisce un movimento ATTIVO con conto_bancario_id NULL (marker "ZZ ...") e verifica che:
 * compaia nella lista → dopo l'assegnazione del conto 3 sparisca.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovimentiSenzaBancaIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    @Inject EntityManager em;

    private final UUID movId = UUID.randomUUID();

    @BeforeAll
    @Transactional
    void seed() {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (:id, DATE '2026-03-01', DATE '2026-03-01', DATE '2026-03-01', DATE '2026-03-01',
                'ENTRATA', 500, 0, 30, NULL, 1, 'ATTIVO', 'MANUALE', 'ZZ senza banca test',
                CAST(:u AS uuid), now())
            """)
            .setParameter("id", movId)
            .setParameter("u", USER)
            .executeUpdate();
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void senzaBanca_listaContieneIlMovimento_poiSpariscoDopoAssegnazione() {
        // compare nella lista senza-banca
        given().when().get("/api/movimenti/senza-banca")
            .then().statusCode(200)
                .body("descrizione", hasItem("ZZ senza banca test"))
                .body("find { it.descrizione == 'ZZ senza banca test' }.id", equalTo(movId.toString()));

        // assegno il conto 3 (Cassa) col PATCH mirato — NON deve de-liquidare il movimento
        given().contentType(ContentType.JSON)
            .body("{\"contoBancarioId\":3}")
            .when().patch("/api/movimenti/" + movId + "/conto-bancario")
            .then().statusCode(200)
                .body("stato", equalTo("ATTIVO"))          // resta liquidato (non DA_LIQUIDARE)
                .body("descrizione", equalTo("ZZ senza banca test"));

        // non è più senza banca
        given().when().get("/api/movimenti/senza-banca")
            .then().statusCode(200)
                .body("descrizione", not(hasItem("ZZ senza banca test")));
    }

    @Test
    @TestSecurity(user = USER, roles = {"DIPENDENTE"})
    void senzaBanca_dipendentePuoLeggere() {
        given().when().get("/api/movimenti/senza-banca").then().statusCode(200);
    }

    @AfterAll
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZ %'").executeUpdate();
    }
}
