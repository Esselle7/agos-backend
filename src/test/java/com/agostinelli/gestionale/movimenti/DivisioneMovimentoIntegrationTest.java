package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec riba-split-importo (lotto 19/08/2026, punto 2) — percorso-soldi.
 *
 * Il caso è quello vero: la RiBa del 31/07/2026 da 2.049,17 € su Crédit Agricole, la cui causale
 * porta il dettaglio «E. 287,49 E. 663,68 E. 1.098,00». L'oracolo che conta è UNO:
 * **il saldo del conto non si muove di un centesimo**. Se la divisione perdesse o creasse denaro,
 * è questo test a cadere, non un conteggio di righe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DivisioneMovimentoIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final short CONTO_CA = 2;

    @Inject EntityManager em;

    private UUID riba;          // la riga reale, divisibile
    private UUID ribaGemella;   // per il test di doppia divisione
    private UUID ribaEvento;    // legata a un evento → vietata

    @BeforeAll
    @Transactional
    void seed() {
        pulisci();
        riba = movimento("ZZSplit PAGAMENTO EFFETTI/RIBA DETTAGLIO: E. 287,49 E. 663,68 E. 1.098,00",
                "2049.17", null);
        ribaGemella = movimento("ZZSplit RiBa gemella", "300.00", null);
        UUID evento = eventoSegnaposto();
        ribaEvento = movimento("ZZSplit RiBa legata a evento", "100.00", evento);
    }

    @AfterAll
    @Transactional
    void cleanup() {
        pulisci();
    }

    // ── 1. Il caso reale: 2.049,17 → 287,49 + 663,68 + 1.098,00 ────────────────────

    @Test
    @Order(1)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r5_ilSaldoDelContoNonSiMuoveDiUnCentesimo() {
        refreshMv();
        BigDecimal saldoPrima = saldoConto(CONTO_CA);

        List<String> figli = given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":287.49,"contoCogeId":%d,"businessUnitId":5,"descrizione":"ZZSplit effetto 1"},
                        {"importo":663.68,"contoCogeId":%d,"businessUnitId":5,"descrizione":"ZZSplit effetto 2"},
                        {"importo":1098.00,"contoCogeId":%d,"businessUnitId":5,"descrizione":"ZZSplit effetto 3"}]}
                      """.formatted(cogeCosto(), cogeCosto(), cogeCosto()))
                .when().post("/api/movimenti/" + riba + "/dividi")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .extract().path("id");

        refreshMv();
        assertEquals(0, saldoPrima.compareTo(saldoConto(CONTO_CA)),
                "il saldo del conto è cambiato: la divisione ha creato o perso denaro");

        // R4 — il padre è annullato e conserva il riferimento esterno (scudo della dedup, I3)
        Object[] padre = (Object[]) em.createNativeQuery(
                "SELECT stato, riferimento_esterno, note FROM movimenti WHERE id = CAST(:id AS uuid)")
                .setParameter("id", riba.toString()).getSingleResult();
        assertEquals("ANNULLATO", padre[0]);
        assertEquals("ZZSPLIT-RIF-2049", padre[1], "il riferimento esterno resta sul padre");
        assertTrue(((String) padre[2]).contains("Diviso in 3 quote"), "R7: il padre traccia i figli");

        // I1 — la somma dei figli è il padre, al centesimo
        BigDecimal somma = (BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(sum(importo_lordo),0) FROM movimenti WHERE id IN (:ids)")
                .setParameter("ids", figli.stream().map(UUID::fromString).toList()).getSingleResult();
        assertEquals(0, new BigDecimal("2049.17").compareTo(somma));

        // R4/I4 — coordinate finanziarie identiche al padre, riferimento esterno NULL sui figli
        for (String f : figli) {
            Object[] r = (Object[]) em.createNativeQuery("""
                    SELECT data_movimento, data_finanziaria, data_liquidita, conto_bancario_id,
                           tipo, metodo_pagamento_id, fonte, riferimento_esterno, stato, note
                    FROM movimenti WHERE id = CAST(:id AS uuid)
                    """).setParameter("id", f).getSingleResult();
            assertEquals("2026-07-31", r[0].toString());
            assertEquals("2026-07-31", r[1].toString(), "I4: data_finanziaria propagata");
            assertEquals("2026-07-31", r[2].toString());
            assertEquals((short) CONTO_CA, r[3]);
            assertEquals("USCITA", r[4]);
            assertEquals("IMPORT_BANCA", r[6]);
            assertNull(r[7], "I3: il riferimento esterno non si duplica sui figli");
            assertEquals("REGISTRATO", r[8]);
            assertTrue(((String) r[9]).contains(riba.toString()), "R7: il figlio nomina il padre");
        }

        // R7 — l'audit finisce nella PARTIZIONE, non nella tabella madre
        Number audit = (Number) em.createNativeQuery("""
                SELECT count(*) FROM audit_log
                WHERE tabella = 'movimenti_2026' AND record_id IN (:ids)
                """).setParameter("ids", figli).getSingleResult();
        assertEquals(3, audit.intValue(), "un INSERT per figlio in audit_log");
    }

    // ── 2. Le guardie ─────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r6_dividerloDueVolteNonRaddoppiaLeRighe() {
        String quote = """
                {"quote":[
                  {"importo":100.00,"contoCogeId":%d,"businessUnitId":5},
                  {"importo":200.00,"contoCogeId":%d,"businessUnitId":5}]}
                """.formatted(cogeCosto(), cogeCosto());

        given().contentType(ContentType.JSON).body(quote)
                .when().post("/api/movimenti/" + ribaGemella + "/dividi")
                .then().statusCode(200).body("$", hasSize(2));

        given().contentType(ContentType.JSON).body(quote)
                .when().post("/api/movimenti/" + ribaGemella + "/dividi")
                .then().statusCode(409).body("code", equalTo("MOVIMENTO_GIA_DIVISO"));

        Number figli = (Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE note LIKE :p AND stato <> 'ANNULLATO'")
                .setParameter("p", "%della divisione del movimento " + ribaGemella + "%")
                .getSingleResult();
        assertEquals(2, figli.intValue(), "la seconda chiamata non deve creare altri figli");
    }

    @Test
    @Order(3)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r3_unoScartoNonSiAggiustaDaSolo() {
        // 100 + 50 su un movimento da 2.049,17: si rifiuta e si dice di quanto, non si arrotonda.
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":100.00,"contoCogeId":%d,"businessUnitId":5},
                        {"importo":50.00,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeCosto(), cogeCosto()))
                .when().post("/api/movimenti/" + ribaEvento + "/dividi")
                .then().statusCode(anyOf(is(400), is(409)));   // qui vince la guardia evento (R10)

        UUID solo = movimentoScarto();
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":100.00,"contoCogeId":%d,"businessUnitId":5},
                        {"importo":50.00,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeCosto(), cogeCosto()))
                .when().post("/api/movimenti/" + solo + "/dividi")
                .then().statusCode(400)
                .body("code", equalTo("SPLIT_NON_QUADRA"))
                .body("message", containsString("50"));

        assertEquals("REGISTRATO", statoDi(solo), "I5: quote rifiutate → il padre resta attivo");
        assertEquals(0, figliDi(solo), "nessun figlio scritto a metà strada");
    }

    @Test
    @Order(4)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r3_unaSolaQuotaNonEUnaDivisione() {
        UUID solo = movimentoScarto();
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[{"importo":150.00,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeCosto()))
                .when().post("/api/movimenti/" + solo + "/dividi")
                .then().statusCode(400);
        assertEquals(0, figliDi(solo));
    }

    @Test
    @Order(5)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r10_unMovimentoLegatoAUnEventoNonSiDivide() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":40.00,"contoCogeId":%d,"businessUnitId":5},
                        {"importo":60.00,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeCosto(), cogeCosto()))
                .when().post("/api/movimenti/" + ribaEvento + "/dividi")
                .then().statusCode(409)
                .body("code", equalTo("SPLIT_SU_EVENTO_NON_SUPPORTATO"));
        assertEquals("REGISTRATO", statoDi(ribaEvento));
    }

    @Test
    @Order(6)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void unaQuotaSulTransitorioNonEUnaClassificazione() {
        // Le quote QUADRANO (2.000 + 49,17 = 2.049,17): a fermare la chiamata deve essere la
        // destinazione, non la somma — altrimenti il test passerebbe per il motivo sbagliato.
        UUID solo = movimentoScarto();
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":2000.00,"contoCogeId":%d,"businessUnitId":5},
                        {"importo":49.17,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeIdDi("49.99.999"), cogeCosto()))
                .when().post("/api/movimenti/" + solo + "/dividi")
                .then().statusCode(409)
                .body("code", equalTo("COGE_TRANSITORIO"));
        assertEquals(0, figliDi(solo));
    }

    @Test
    @Order(7)
    void senzaTokenNonSiDivide() {
        given().contentType(ContentType.JSON).body("{\"quote\":[]}")
                .when().post("/api/movimenti/" + riba + "/dividi")
                .then().statusCode(401);
    }

    @Test
    @Order(8)
    @TestSecurity(user = USER, roles = {"DIPENDENTE"})
    void unDipendenteNonDivide() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"quote":[
                        {"importo":100.00,"contoCogeId":%d,"businessUnitId":5},
                        {"importo":50.00,"contoCogeId":%d,"businessUnitId":5}]}
                      """.formatted(cogeCosto(), cogeCosto()))
                .when().post("/api/movimenti/" + riba + "/dividi")
                .then().statusCode(403);
    }

    // ── helper ────────────────────────────────────────────────────────────────────

    @Transactional
    void pulisci() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZSplit%'").executeUpdate();
        em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE 'ZZSplit%'").executeUpdate();
    }

    @Transactional
    UUID movimento(String descrizione, String importo, UUID eventoId) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, riferimento_esterno, evento_id,
                metodo_pagamento_id, created_by, created_at)
            VALUES (:id, DATE '2026-07-31', DATE '2026-07-31', DATE '2026-07-31', DATE '2026-07-31',
                'USCITA', CAST(:imp AS numeric), 0,
                (SELECT id FROM piano_dei_conti_coge WHERE codice = '49.99.999'), 2,
                5, 'REGISTRATO', 'IMPORT_BANCA', :descr, :rif, :evento,
                (SELECT id FROM metodi_pagamento WHERE codice = 'RIBA'), CAST(:u AS uuid), now())
            """)
            .setParameter("id", id).setParameter("imp", importo).setParameter("descr", descrizione)
            // Solo la riga «reale» porta un riferimento esterno: idx_movimenti_dedup_import è
            // UNIQUE su (fonte, riferimento_esterno, data_movimento) e i cloni lo violerebbero.
            .setParameter("rif", descrizione.contains("DETTAGLIO") ? "ZZSPLIT-RIF-2049" : null)
            .setParameter("evento", eventoId)
            .setParameter("u", UUID.fromString(USER))
            .executeUpdate();
        return id;
    }

    /** Movimento usa-e-getta da 2.049,17 per i test delle guardie (ognuno il suo, niente stato condiviso). */
    UUID movimentoScarto() {
        return movimento("ZZSplit scarto " + UUID.randomUUID(), "2049.17", null);
    }

    @Transactional
    UUID eventoSegnaposto() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("""
            INSERT INTO eventi (id, nome, tipo, data_evento, stato, importo_totale_preventivato,
                importo_incassato, caparre_incassate, costi_diretti_imputati, business_unit_id, created_at)
            VALUES (:id, 'ZZSplit evento', 'BANCHETTO_PRIVATO', DATE '2026-07-31', 'CONFERMATO',
                1000, 0, 0, 0, 2, now())
            """).setParameter("id", id).executeUpdate();
        return id;
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

    int cogeCosto() { return cogeIdDi("40.02.002"); }

    int cogeIdDi(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }

    String statoDi(UUID id) {
        return (String) em.createNativeQuery("SELECT stato FROM movimenti WHERE id = CAST(:id AS uuid)")
                .setParameter("id", id.toString()).getSingleResult();
    }

    int figliDi(UUID padre) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE note LIKE :p")
                .setParameter("p", "%della divisione del movimento " + padre + "%")
                .getSingleResult()).intValue();
    }
}
