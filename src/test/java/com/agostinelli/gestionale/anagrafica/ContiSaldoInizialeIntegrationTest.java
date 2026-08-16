package com.agostinelli.gestionale.anagrafica;

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

/**
 * Saldo di apertura dei conti (PUT /api/conti/{id}/saldo-iniziale). Usa il conto 3 (Cassa) e lo
 * ripristina a 0 a fine classe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContiSaldoInizialeIntegrationTest {

    @Inject EntityManager em;

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_impostaSaldo_eCompareInLista() {
        given().contentType(ContentType.JSON)
            .body("""
                {"saldoIniziale":1234.56,"dataSaldoIniziale":"2025-12-31"}
                """)
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(200)
                .body("id", equalTo(3))
                .body("saldoIniziale", comparesEqualTo(1234.56f))
                .body("dataSaldoIniziale", equalTo("2025-12-31"));

        given().when().get("/api/conti")
            .then().statusCode(200)
                .body("find { it.id == 3 }.saldoIniziale", comparesEqualTo(1234.56f));
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_contoInesistente_404() {
        given().contentType(ContentType.JSON)
            .body("{\"saldoIniziale\":100,\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/99/saldo-iniziale")
            .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void put_senzaSaldo_400() {
        given().contentType(ContentType.JSON)
            .body("{\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"DIPENDENTE"})
    void put_nonAdmin_403() {
        given().contentType(ContentType.JSON)
            .body("{\"saldoIniziale\":1,\"dataSaldoIniziale\":\"2025-12-31\"}")
            .when().put("/api/conti/3/saldo-iniziale")
            .then().statusCode(403);
    }

    /**
     * V24 — "Saldo AL giorno X" conta solo i movimenti POSTERIORI a X. Senza il filtro sulla data,
     * un movimento anteriore viene sottratto da un saldo che lo contiene già (doppio conteggio:
     * è ciò che ha portato la liquidità a -28.929,02 il 2026-08-07).
     */
    @Test
    @TestSecurity(user = "00000000-0000-0000-0000-000000000099", roles = {"ADMIN"})
    void saldo_ignoraMovimentiAnterioriAllaDataApertura() {
        given().contentType(ContentType.JSON)
            .body("{\"saldoIniziale\":1000.00,\"dataSaldoIniziale\":\"2026-06-30\"}")
            .when().put("/api/conti/3/saldo-iniziale").then().statusCode(200);

        mov("2026-06-15", "100.00");   // anteriore  → NON deve contare
        mov("2026-06-30", "50.00");    // il giorno stesso: già dentro il saldo → NON deve contare
        refreshMv();
        Assertions.assertEquals(0, new java.math.BigDecimal("1000.00").compareTo(saldoConto3()),
                "i movimenti anteriori alla data di apertura non devono essere sottratti");

        mov("2026-07-01", "40.00");    // posteriore → deve contare
        refreshMv();
        Assertions.assertEquals(0, new java.math.BigDecimal("960.00").compareTo(saldoConto3()),
                "i movimenti posteriori alla data di apertura devono contare");
    }

    /**
     * Legge la MV, non {@code GET /api/conti}: quell'endpoint è @CacheResult su "conti-list" e
     * servirebbe la prima risposta a entrambe le letture, rendendo il test cieco.
     */
    private java.math.BigDecimal saldoConto3() {
        return (java.math.BigDecimal) em.createNativeQuery(
                "SELECT saldo_calcolato FROM mv_saldi_conti WHERE conto_id = 3").getSingleResult();
    }

    private static final String MARKER = "[TEST-V24-SALDO-APERTURA]";

    // Transazione esplicita e non @Transactional: chiamati da un metodo della stessa classe, gli
    // interceptor non scattano e la scrittura non risulterebbe committata alla chiamata HTTP,
    // che gira su un'altra connessione e vedrebbe la MV vecchia.
    void mov(String data, String importo) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO movimenti (id, data_movimento, data_finanziaria, tipo, importo_lordo, " +
                "importo_commissione, data_competenza, conto_bancario_id, conto_coge_id, business_unit_id, " +
                "descrizione, stato, fonte, created_by, created_at) " +
                "VALUES (gen_random_uuid(), CAST(:d AS date), CAST(:d AS date), 'USCITA', CAST(:imp AS numeric), " +
                "0, CAST(:d AS date), 3, 1, 1, :descr, 'REGISTRATO', 'MANUALE', " +
                "CAST('00000000-0000-0000-0000-000000000099' AS uuid), now())")
                .setParameter("d", data).setParameter("imp", importo).setParameter("descr", MARKER)
                .executeUpdate());
    }

    void refreshMv() {
        QuarkusTransaction.requiringNew().run(
                () -> em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_saldi_conti").executeUpdate());
    }

    @AfterAll
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione = :m").setParameter("m", MARKER).executeUpdate();
            em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = 0, data_saldo_iniziale = NULL WHERE id = 3")
                    .executeUpdate();
            em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_saldi_conti").executeUpdate();
        });
    }
}
