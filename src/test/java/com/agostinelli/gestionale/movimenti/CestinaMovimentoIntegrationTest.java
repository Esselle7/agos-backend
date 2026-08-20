package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec movimento-cestina-fisica (20/08/2026) — percorso-soldi.
 *
 * <p>Cancellare fisicamente una scrittura contabile è irreversibile in tabella, quindi qui si
 * difendono le tre cose che la rendono ammissibile: si cestina <b>solo il già annullato</b> (e
 * allora il saldo non si muove — è l'oracolo R5), <b>solo se nessuno lo referenzia</b> (movimenti
 * è partizionata e non ha FK: il database non protegge niente), e la riga <b>resta in audit_log</b>.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CestinaMovimentoIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final short CONTO_CA = 2;
    private static final UUID IMPORT_LOG = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @Inject EntityManager em;

    private UUID vivo;           // REGISTRATO → non si cestina (R2)
    private UUID annullato;      // ANNULLATO e libero → si cestina (R1)
    private UUID conEvento;      // ANNULLATO ma legato a un evento (R4)
    private UUID referenziato;   // ANNULLATO ma puntato dalla coda ricorrenti (R3)

    @BeforeAll
    @Transactional
    void seed() {
        pulisci();
        vivo         = movimento("ZZCest vivo", "REGISTRATO", null);
        annullato    = movimento("ZZCest annullato", "ANNULLATO", null);
        conEvento    = movimento("ZZCest con evento", "ANNULLATO", eventoSegnaposto());
        referenziato = movimento("ZZCest referenziato", "ANNULLATO", null);
        codaRicorrente(referenziato);
    }

    @AfterAll
    @Transactional
    void cleanup() { pulisci(); }

    // ── R2: due passi obbligati — prima si annulla, poi si cestina ────────────────

    @Test
    @Order(1)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r2_unMovimentoVivoNonSiCestina() {
        given().when().delete("/api/movimenti/" + vivo + "/cestina")
                .then().statusCode(409)
                .body("code", equalTo("MOVIMENTO_NON_ANNULLATO"))
                .body("message", containsString("REGISTRATO"));
        assertEquals(1, esiste(vivo), "la riga deve restare");
        assertEquals("REGISTRATO", statoDi(vivo), "e non deve essere stata annullata di straforo");
    }

    // ── R3/R4: chi è ancora collegato non si cancella ─────────────────────────────

    @Test
    @Order(2)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r4_unMovimentoLegatoAUnEventoNonSiCestina() {
        given().when().delete("/api/movimenti/" + conEvento + "/cestina")
                .then().statusCode(409)
                .body("code", equalTo("MOVIMENTO_REFERENZIATO"))
                .body("message", containsString("un evento"));
        assertEquals(1, esiste(conEvento));
    }

    @Test
    @Order(3)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r3_unMovimentoPuntatoDaUnAltraTabellaNonSiCestina() {
        given().when().delete("/api/movimenti/" + referenziato + "/cestina")
                .then().statusCode(409)
                .body("code", equalTo("MOVIMENTO_REFERENZIATO"))
                .body("message", containsString("coda ricorrenti"));
        assertEquals(1, esiste(referenziato), "niente orfani: la riga resta finché resta il puntatore");
    }

    // ── R1 + R5 + R6: il caso buono ──────────────────────────────────────────────

    @Test
    @Order(4)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r1_r5_r6_laRigaSpariscePeroIlSaldoNonSiMuoveELaStoriaResta() {
        refreshMv();
        BigDecimal saldoPrima = saldoConto(CONTO_CA);

        given().when().delete("/api/movimenti/" + annullato + "/cestina")
                .then().statusCode(204);

        assertEquals(0, esiste(annullato), "R1: la riga non è più in movimenti");

        refreshMv();
        assertEquals(0, saldoPrima.compareTo(saldoConto(CONTO_CA)),
                "R5: il saldo del conto si è mosso — una cestina non deve spostare denaro");

        assertEquals(1, auditDelete(annullato),
                "R6: audit_log deve conservare la riga cancellata (dati_precedenti)");
    }

    @Test
    @Order(5)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void unIdInesistenteE404() {
        given().when().delete("/api/movimenti/" + UUID.randomUUID() + "/cestina")
                .then().statusCode(404);
    }

    // ── I4: solo ADMIN ───────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @TestSecurity(user = USER, roles = {"DIPENDENTE"})
    void unDipendenteNonCestina() {
        given().when().delete("/api/movimenti/" + vivo + "/cestina").then().statusCode(403);
        assertEquals(1, esiste(vivo));
    }

    @Test
    @Order(7)
    void senzaTokenNonSiCestina() {
        given().when().delete("/api/movimenti/" + vivo + "/cestina").then().statusCode(401);
        assertEquals(1, esiste(vivo));
    }

    // ── helper ───────────────────────────────────────────────────────────────────

    @Transactional
    void pulisci() {
        em.createNativeQuery("DELETE FROM ricorrenti_da_riconciliare WHERE descrizione_norm LIKE 'ZZCest%'").executeUpdate();
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZCest%'").executeUpdate();
        em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE 'ZZCest%'").executeUpdate();
    }

    @Transactional
    UUID movimento(String descrizione, String stato, UUID eventoId) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, evento_id, created_by, created_at)
            VALUES (:id, DATE '2026-07-31', DATE '2026-07-31', DATE '2026-07-31', DATE '2026-07-31',
                'USCITA', 100.00, 0,
                (SELECT id FROM piano_dei_conti_coge WHERE codice = '40.02.002'), 2,
                5, :stato, 'MANUALE', :descr, :evento, CAST(:u AS uuid), now())
            """)
            .setParameter("id", id).setParameter("stato", stato).setParameter("descr", descrizione)
            .setParameter("evento", eventoId).setParameter("u", UUID.fromString(USER))
            .executeUpdate();
        return id;
    }

    @Transactional
    UUID eventoSegnaposto() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, 'ZZCest evento', 'BANCHETTO_PRIVATO', DATE '2026-07-31', 'CONFERMATO',
                1000, 0, 0, 0, 2, now())
            """).setParameter("id", id).executeUpdate();
        return id;
    }

    /** Una riga della coda ricorrenti che dice «l'ho già messo a libro come questo movimento». */
    @Transactional
    void codaRicorrente(UUID movimentoId) {
        Long n = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM import_log WHERE id = :id")
                .setParameter("id", IMPORT_LOG).getSingleResult()).longValue();
        if (n == 0) {
            em.createNativeQuery("INSERT INTO import_log (id, fonte, stato) VALUES (:id, 'IMPORT_BANCA', 'IN_CORSO')")
                    .setParameter("id", IMPORT_LOG).executeUpdate();
        }
        em.createNativeQuery("""
            INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, importo,
                tipo, conto_bancario_id, descrizione_norm, tipo_presunto, stato, raw_data, movimento_id)
            VALUES (:id, :log, 'IMPORT_BANCA', DATE '2026-07-31', 100.00, 'USCITA', 2,
                'ZZCest riga di coda', 'ALTRO', 'RICONCILIATA', CAST('{}' AS jsonb), :mov)
            """)
            .setParameter("id", UUID.randomUUID()).setParameter("log", IMPORT_LOG)
            .setParameter("mov", movimentoId).executeUpdate();
    }

    @Transactional
    void refreshMv() {
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_saldi_conti").executeUpdate();
    }

    BigDecimal saldoConto(short contoId) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT saldo_calcolato FROM mv_saldi_conti WHERE conto_id = :c")
                .setParameter("c", contoId).getSingleResult();
    }

    int esiste(UUID id) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE id = CAST(:id AS uuid)")
                .setParameter("id", id.toString()).getSingleResult()).intValue();
    }

    String statoDi(UUID id) {
        return (String) em.createNativeQuery("SELECT stato FROM movimenti WHERE id = CAST(:id AS uuid)")
                .setParameter("id", id.toString()).getSingleResult();
    }

    int auditDelete(UUID id) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM audit_log WHERE record_id = :id AND operazione = 'DELETE' "
                + "AND dati_precedenti IS NOT NULL")
                .setParameter("id", id.toString()).getSingleResult()).intValue();
    }
}
