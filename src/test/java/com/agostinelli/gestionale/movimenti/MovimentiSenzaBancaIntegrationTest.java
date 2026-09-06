package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

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

    private final UUID movId        = UUID.randomUUID();
    private final UUID eventoId     = UUID.randomUUID();
    private final UUID compSporcaId = UUID.randomUUID();
    private final UUID compPulitaId = UUID.randomUUID();

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

    /**
     * Invariante I2: una riga COMPETENZA non è denaro entrato, quindi non deve nemmeno essere
     * PROPOSTA nel popup "Senza banca" — e se un conto le è già stato assegnato (4.020 € su CA
     * il 25/08/2026), `allinea-competenza` deve riportarla a NULL.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void competenza_fuoriDalPopup_eIlContoSiRipulisce() {
        seedCompetenzaSporca();

        // 1. non compare fra i "senza banca" nemmeno quando il conto è NULL
        given().when().get("/api/movimenti/senza-banca")
            .then().statusCode(200)
                .body("descrizione", not(hasItem("ZZ competenza pulita")));

        // 2. la guardia in profondità rifiuta l'assegnazione diretta
        given().contentType(ContentType.JSON).body("{\"contoBancarioId\":2}")
            .when().patch("/api/movimenti/" + compPulitaId + "/conto-bancario")
            .then().statusCode(409)
                .body("code", equalTo("COMPETENZA_SENZA_BANCA"));

        // 3. la riga già sporcata torna a conto NULL dopo l'allineamento
        given().contentType(ContentType.JSON)
            .when().post("/api/eventi/allinea-competenza")
            .then().statusCode(200);

        assertNull(contoDi(compSporcaId), "la riga COMPETENZA deve tornare senza conto bancario (I2)");
    }

    @Transactional
    void seedCompetenzaSporca() {
        // evento nel perimetro (celebrato, dal go-live a oggi) con credito aperto
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, 'ZZ evento competenza', 'BANCHETTO_PRIVATO', :d, 'CONFERMATO',
                800, 0, 0, 0, 2, now())
            """)
            .setParameter("id", eventoId)
            .setParameter("d", LocalDate.now().minusDays(1))
            .executeUpdate();

        // la riga sporcata: conto valorizzato, come l'ha lasciata il popup
        insCompetenza(compSporcaId, "ZZ competenza sporca", eventoId, (short) 2);
        // una riga sana: serve solo a provare che il popup non la propone
        insCompetenza(compPulitaId, "ZZ competenza pulita", null, null);
    }

    private void insCompetenza(UUID id, String desc, UUID evento, Short conto) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, evento_id, tipo_evento_movimento,
                created_by, created_at)
            VALUES (:id, :d, :d, NULL, :d, 'ENTRATA', 800, 0, 30, :conto, 2,
                'DA_LIQUIDARE', 'MANUALE', :desc, :evento, 'COMPETENZA', CAST(:u AS uuid), now())
            """)
            .setParameter("id", id)
            .setParameter("d", LocalDate.now().minusDays(1))
            .setParameter("conto", conto)
            .setParameter("desc", desc)
            .setParameter("evento", evento)
            .setParameter("u", USER)
            .executeUpdate();
    }

    @Transactional
    Short contoDi(UUID id) {
        return (Short) em.createNativeQuery("SELECT conto_bancario_id FROM movimenti WHERE id = :id")
                .setParameter("id", id).getSingleResult();
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
        em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE 'ZZ %'").executeUpdate();
    }
}
