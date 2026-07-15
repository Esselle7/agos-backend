package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CONFERMA/IGNORA di una spesa ricorrente parcheggiata (V22): l'azione CONFERMA crea il movimento
 * contabile reale della rata già addebitata in banca. Copre gli invarianti (Design by Contract):
 * CoGe obbligatorio su USCITA, ENTRATA forzata su 90.01.001, conferma-una-volta-sola, IGNORA senza
 * movimento, azione COLLEGA rimossa. Righe di test marcate {@code ZZTESTRIC} e ripulite a fine classe.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RisolviRicorrenteIntegrationTest {

    private static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final UUID IMPORT_LOG = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String DESCR_PREFIX = "ZZTESTRIC";
    private static final LocalDate DATA = LocalDate.of(2026, 3, 15);

    @Inject EntityManager em;

    // ── (a) CONFERMA uscita: crea il movimento sul CoGe scelto/BU5/conto/importo e marca CONFERMATA ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void confermaUscita_creaMovimentoEmarcaConfermata() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_MUTUO IPOTECARIO", (short) 1);
        int cogeId = cogeId("20.01.001");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeId + "}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        Object[] row = statoEMovimento(ric);
        assertEquals("CONFERMATA", row[0]);
        UUID movId = (UUID) row[1];
        assertNotNull(movId, "movimento_id deve essere valorizzato");

        Object[] mov = movimento(movId);
        assertEquals(cogeId, ((Number) mov[0]).intValue(), "CoGe del movimento");
        assertEquals(5, ((Number) mov[1]).intValue(), "BU Overhead");
        assertEquals(1, ((Number) mov[2]).intValue(), "conto bancario");
        assertEquals(0, ((BigDecimal) mov[3]).compareTo(new BigDecimal("123.45")), "importo");
        assertEquals("USCITA", mov[4]);
    }

    // ── (b) CONFERMA entrata: forza CoGe 90.01.001 anche se il client ne manda un altro ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void confermaEntrata_forza90_01_001() {
        UUID ric = seedRicorrente("ENTRATA", DESCR_PREFIX + "_EROGAZIONE FINANZIAMENTO", (short) 1);
        int cogeSbagliato = cogeId("20.01.001");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeSbagliato + "}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        UUID movId = (UUID) statoEMovimento(ric)[1];
        Object[] mov = movimento(movId);
        assertEquals(cogeId("90.01.001"), ((Number) mov[0]).intValue(), "ENTRATA forza 90.01.001");
        assertEquals("ENTRATA", mov[4]);
    }

    // ── (c) CONFERMA uscita senza cogeId → 400 ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void confermaUscita_senzaCoge_400() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_LEASING", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("COGE_OBBLIGATORIO"));
        assertEquals("DA_RICONCILIARE", statoEMovimento(ric)[0], "resta non risolta");
    }

    // ── (d) azione COLLEGA (rimossa) → 400 ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void azioneCollega_400() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_CANONE", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("AZIONE_NON_VALIDA"));
    }

    // ── (e) doppia conferma → 409 ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void doppiaConferma_409() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_ASSICURAZIONE", (short) 1);
        int cogeId = cogeId("40.05.002");
        String body = "{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeId + "}";

        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi").then().statusCode(204);
        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(409).body("code", equalTo("RICORRENTE_GIA_RISOLTA"));
    }

    // ── (f) IGNORA non crea movimenti ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void ignora_nonCreaMovimenti() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_IGNOREME", (short) 1);
        long prima = movimentiConDescr(DESCR_PREFIX + "_IGNOREME");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"IGNORA\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        Object[] row = statoEMovimento(ric);
        assertEquals("IGNORATA", row[0]);
        assertNull(row[1], "IGNORA non deve creare un movimento");
        assertEquals(prima, movimentiConDescr(DESCR_PREFIX + "_IGNOREME"), "nessun movimento creato");
    }

    // ── (g) CONFERMA con cogeId inesistente → 400 (fail fast al boundary) ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void confermaUscita_cogeInesistente_400() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_COGE_FANTASMA", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":99999999}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("COGE_NON_TROVATO"));
        assertEquals("DA_RICONCILIARE", statoEMovimento(ric)[0], "resta non risolta (rollback del claim)");
    }

    // ── (h) descrizione con SDD → metodo RID_SDDMANDAT; senza → ADDEBITO_CONTO ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void confermaUscita_metodoDaDescrizione() {
        UUID sdd = seedRicorrente("USCITA", DESCR_PREFIX + "_SDD A : CONFIDI LOMBARDIA", (short) 2);
        int cogeId = cogeId("20.01.006");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeId + "}")
            .when().put("/api/movimenti/import/ricorrenti/" + sdd + "/risolvi").then().statusCode(204);
        assertEquals("RID_SDDMANDAT", metodoDiMovimento((UUID) statoEMovimento(sdd)[1]),
                "descrizione con SDD → addebito diretto SEPA");

        UUID bollo = seedRicorrente("USCITA", DESCR_PREFIX + "_IMPOSTA DI BOLLO CC", (short) 2);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeId("40.02.002") + "}")
            .when().put("/api/movimenti/import/ricorrenti/" + bollo + "/risolvi").then().statusCode(204);
        assertEquals("ADDEBITO_CONTO", metodoDiMovimento((UUID) statoEMovimento(bollo)[1]),
                "senza SDD → addebito generico sul conto");
    }

    // ── (i) rollback dell'import cancella anche il movimento confermato e le righe parcheggiate ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void rollbackImport_cancellaMovimentoConfermatoECoda() {
        UUID log = seedImportLogDedicato();
        UUID ric = seedRicorrenteSuLog(log, "USCITA", DESCR_PREFIX + "_ROLLBACK MUTUO", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CONFERMA\",\"cogeId\":" + cogeId("20.01.001") + "}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi").then().statusCode(204);
        UUID movId = (UUID) statoEMovimento(ric)[1];
        assertNotNull(movId);

        given().when().delete("/api/movimenti/import/" + log + "/rollback").then().statusCode(200);

        assertEquals(0L, count("movimenti", "id = '" + movId + "'"),
                "il movimento confermato deve sparire col rollback (fonte_importazione_id)");
        assertEquals(0L, count("ricorrenti_da_riconciliare", "import_log_id = '" + log + "'"),
                "la riga parcheggiata deve sparire in cascata con l'import_log");
    }

    // ── (j) invariante eventi: CLASSIFICA su evento parcheggiato → 409, mai movimenti ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void eventoParcheggiato_classifica_409() {
        UUID ev = seedEventoParcheggiato(DESCR_PREFIX + "_EVENTO BONIFICO SALDO");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CLASSIFICA\",\"cogeId\":" + cogeId("20.01.001") + ",\"businessUnitId\":2}")
            .when().put("/api/movimenti/import/eventi/" + ev + "/risolvi")
            .then().statusCode(409).body("code", equalTo("EVENTO_NON_CONTABILIZZABILE"));
        assertEquals(0L, count("movimenti", "descrizione LIKE '" + DESCR_PREFIX + "_EVENTO%'"),
                "un evento parcheggiato non genera MAI movimenti");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    @Transactional
    UUID seedRicorrente(String tipo, String descr, Short conto) {
        ensureImportLog();
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, tipo, " +
                "conto_bancario_id, descrizione_norm, tipo_presunto, stato, raw_data) " +
                "VALUES (:id, :log, 'IMPORT_BANCA', :data, 123.45, :tipo, :conto, :descr, 'ALTRO', 'DA_RICONCILIARE', CAST('{}' AS jsonb))")
                .setParameter("id", id).setParameter("log", IMPORT_LOG).setParameter("data", DATA)
                .setParameter("tipo", tipo).setParameter("conto", conto).setParameter("descr", descr)
                .executeUpdate();
        return id;
    }

    @Transactional
    void ensureImportLog() {
        Long n = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM import_log WHERE id = :id")
                .setParameter("id", IMPORT_LOG).getSingleResult()).longValue();
        if (n == 0) {
            em.createNativeQuery("INSERT INTO import_log (id, fonte, stato) VALUES (:id, 'IMPORT_BANCA', 'IN_CORSO')")
                    .setParameter("id", IMPORT_LOG).executeUpdate();
        }
    }

    Object[] statoEMovimento(UUID ric) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT stato, movimento_id FROM ricorrenti_da_riconciliare WHERE id = :id")
                .setParameter("id", ric).getResultList();
        return rows.get(0);
    }

    Object[] movimento(UUID movId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT conto_coge_id, business_unit_id, conto_bancario_id, importo_lordo, tipo " +
                "FROM movimenti WHERE id = :id").setParameter("id", movId).getResultList();
        assertTrue(!rows.isEmpty(), "movimento inesistente: " + movId);
        return rows.get(0);
    }

    String metodoDiMovimento(UUID movId) {
        return (String) em.createNativeQuery(
                "SELECT mp.codice FROM movimenti m JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id " +
                "WHERE m.id = :id").setParameter("id", movId).getSingleResult();
    }

    long count(String tabella, String where) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM " + tabella + " WHERE " + where)
                .getSingleResult()).longValue();
    }

    /** Import log dedicato al test di rollback (non quello condiviso: il rollback lo cancella). */
    @Transactional
    UUID seedImportLogDedicato() {
        UUID id = UUID.randomUUID();
        em.createNativeQuery("INSERT INTO import_log (id, fonte, stato) VALUES (:id, 'IMPORT_CONGIUNTO', 'COMPLETATO')")
                .setParameter("id", id).executeUpdate();
        return id;
    }

    @Transactional
    UUID seedRicorrenteSuLog(UUID log, String tipo, String descr, Short conto) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, tipo, " +
                "conto_bancario_id, descrizione_norm, tipo_presunto, stato, raw_data) " +
                "VALUES (:id, :log, 'IMPORT_BANCA', :data, 123.45, :tipo, :conto, :descr, 'ALTRO', 'DA_RICONCILIARE', CAST('{}' AS jsonb))")
                .setParameter("id", id).setParameter("log", log).setParameter("data", DATA)
                .setParameter("tipo", tipo).setParameter("conto", conto).setParameter("descr", descr)
                .executeUpdate();
        return id;
    }

    @Transactional
    UUID seedEventoParcheggiato(String descr) {
        ensureImportLog();
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, tipo, " +
                "conto_bancario_id, descrizione_norm, stato, raw_data) " +
                "VALUES (:id, :log, 'IMPORT_BANCA', :data, 500.00, 'ENTRATA', 2, :descr, 'DA_RICONCILIARE', CAST('{}' AS jsonb))")
                .setParameter("id", id).setParameter("log", IMPORT_LOG).setParameter("data", DATA)
                .setParameter("descr", descr).executeUpdate();
        return id;
    }

    int cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }

    long movimentiConDescr(String descr) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM movimenti WHERE descrizione = :d")
                .setParameter("d", descr).getSingleResult()).longValue();
    }

    @AfterAll
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE '" + DESCR_PREFIX + "%'").executeUpdate();
        em.createNativeQuery("DELETE FROM ricorrenti_da_riconciliare WHERE import_log_id = :log")
                .setParameter("log", IMPORT_LOG).executeUpdate();
        em.createNativeQuery("DELETE FROM eventi_da_riconciliare WHERE import_log_id = :log")
                .setParameter("log", IMPORT_LOG).executeUpdate();
        em.createNativeQuery("DELETE FROM import_log WHERE id = :log").setParameter("log", IMPORT_LOG).executeUpdate();
    }
}
