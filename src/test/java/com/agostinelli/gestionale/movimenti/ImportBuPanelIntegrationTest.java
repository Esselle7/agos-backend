package com.agostinelli.gestionale.movimenti;

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
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pannello BU dell'import (docs/adr/006): raggruppamento per Business Unit dei movimenti di UN
 * import, coda "da assegnare" derivata dal conto transitorio + BU di fallback, e cambio BU.
 *
 * INVARIANTE DIFESO QUI: cambiare la BU di un movimento NON muove nessun saldo. Il test rileva
 * sia una regressione nelle viste (se un domani mv_saldi_conti leggesse la BU) sia un endpoint
 * che facesse più del dovuto (toccare importo/stato/date/conto mentre riclassifica).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)  // 1 legge · 2 sposta · 3 verifica la persistenza
class ImportBuPanelIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    static final String NIL = "00000000-0000-0000-0000-000000000000";

    @Inject EntityManager em;

    private final UUID importId = UUID.randomUUID();
    private final UUID altroImportId = UUID.randomUUID();
    private final UUID movTransitorio = UUID.randomUUID();   // 39.99.999 + BU5 → coda "da assegnare"
    private final UUID movSpeseBanca = UUID.randomUUID();    // 40.02.002 + BU5 → BU5 legittima
    private final UUID movSpaccio = UUID.randomUUID();       // 30.03.001 + BU3
    private final UUID movAnnullato = UUID.randomUUID();     // escluso dal pannello
    private final UUID movAltroImport = UUID.randomUUID();   // non appartiene a questo import

    @BeforeAll
    @Transactional
    void seed() {
        importLog(importId, "ZZBU-import.csv");
        importLog(altroImportId, "ZZBU-altro.csv");
        movimento(movTransitorio, importId, "ENTRATA", "1000.00", "39.99.999", 5, "ATTIVO", "ZZBU incasso da classificare");
        movimento(movSpeseBanca, importId, "USCITA", "12.50", "40.02.002", 5, "ATTIVO", "ZZBU spese conto");
        movimento(movSpaccio, importId, "ENTRATA", "300.00", "30.03.001", 3, "ATTIVO", "ZZBU vendita carne");
        movimento(movAnnullato, importId, "USCITA", "99.00", "49.99.999", 5, "ANNULLATO", "ZZBU annullato");
        movimento(movAltroImport, altroImportId, "ENTRATA", "50.00", "30.03.001", 3, "ATTIVO", "ZZBU altro import");
    }

    // ── 1. Vista raggruppata: gruppi + coda incerti, e nessun movimento perso ────────

    @Test
    @Order(1)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void panel_raggruppaPerBu_conCodaIncertiSeparata_eSommaChiude() {
        var risposta = given().when().get("/api/movimenti/import/" + importId + "/bu")
            .then().statusCode(200)
                .body("filename", equalTo("ZZBU-import.csv"))
                // l'annullato non entra: 3 movimenti su 4
                .body("totaleMovimenti", equalTo(3))
                // coda "da assegnare": solo il transitorio ancora su BU5
                .body("incerti.numero", equalTo(1))
                .body("incerti.entrate", comparesEqualTo(1000.00f))
                .body("incerti.movimenti[0].id", equalTo(movTransitorio.toString()))
                .body("incerti.movimenti[0].transitorio", equalTo(true))
                // le spese di conto sono BU5 VOLUTA (coge reale): restano nel gruppo BU5
                .body("gruppi.find { it.buId == 5 }.numero", equalTo(1))
                .body("gruppi.find { it.buId == 5 }.uscite", comparesEqualTo(12.50f))
                .body("gruppi.find { it.buId == 3 }.numero", equalTo(1))
                .body("gruppi.find { it.buId == 3 }.entrate", comparesEqualTo(300.00f))
            .extract().jsonPath();

        // invariante di lettura: Σ gruppi + incerti = totale (nessun movimento perso per strada)
        int sommaGruppi = risposta.getList("gruppi.numero", Integer.class).stream().mapToInt(Integer::intValue).sum();
        assertEquals(risposta.getInt("totaleMovimenti"), sommaGruppi + risposta.getInt("incerti.numero"),
                "la somma dei gruppi + incerti non torna col totale dell'import");
    }

    // ── 2. L'INVARIANTE: cambiare BU non muove nessun saldo ─────────────────────────

    @Test
    @Order(2)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_nonMuoveIlSaldoDelConto_neIlContoEconomicoTotale() {
        refreshMv();   // fotografia iniziale delle MV col seed già committato
        BigDecimal saldoPrima = saldoConto(1);
        BigDecimal ricaviPrima = ricaviTotaliTutteLeBu();
        Object[] datiPrima = datiContabili(movTransitorio);

        given().contentType(ContentType.JSON).body("{\"businessUnitId\":1}")
            .when().put("/api/movimenti/import/" + importId + "/bu/" + movTransitorio)
            .then().statusCode(204);

        refreshMv();

        // (a) il saldo del conto è identico al centesimo
        assertEquals(0, saldoPrima.compareTo(saldoConto(1)),
                "cambiare BU ha mosso il saldo del conto: " + saldoPrima + " → " + saldoConto(1));
        // (b) il conto economico complessivo è identico (cambia solo su QUALE BU si appoggia)
        assertEquals(0, ricaviPrima.compareTo(ricaviTotaliTutteLeBu()),
                "cambiare BU ha alterato i ricavi totali");
        // (c) niente di contabile è stato toccato: importo, stato, date, conto
        Assertions.assertArrayEquals(datiPrima, datiContabili(movTransitorio),
                "il cambio BU ha modificato dati contabili del movimento");
        // (d) prova che il test NON è vacuo: la riclassificazione è davvero avvenuta
        assertEquals((short) 1, buDelMovimento(movTransitorio));
    }

    @Test
    @Order(3)
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_persisteEspostaNelGruppoScelto_efuoriDallaCoda() {
        given().when().get("/api/movimenti/import/" + importId + "/bu")
            .then().statusCode(200)
                .body("incerti.numero", equalTo(0))
                .body("gruppi.find { it.buId == 1 }.numero", equalTo(1))
                .body("gruppi.find { it.buId == 1 }.movimenti[0].id", equalTo(movTransitorio.toString()))
                // resta marcata transitoria: la CoGe si sistema in "Da catalogare"
                .body("gruppi.find { it.buId == 1 }.movimenti[0].transitorio", equalTo(true))
                .body("totaleMovimenti", equalTo(3));
    }

    // ── 3. Boundary dell'endpoint: errori chiari, mai 500 ───────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_buInesistente_404() {
        given().contentType(ContentType.JSON).body("{\"businessUnitId\":99}")
            .when().put("/api/movimenti/import/" + importId + "/bu/" + movSpaccio)
            .then().statusCode(404).body("code", equalTo("BU_NON_TROVATA"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_movimentoInesistente_404() {
        given().contentType(ContentType.JSON).body("{\"businessUnitId\":1}")
            .when().put("/api/movimenti/import/" + importId + "/bu/" + NIL)
            .then().statusCode(404).body("code", equalTo("MOVIMENTO_NON_TROVATO"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_movimentoDiUnAltroImport_404() {
        given().contentType(ContentType.JSON).body("{\"businessUnitId\":1}")
            .when().put("/api/movimenti/import/" + importId + "/bu/" + movAltroImport)
            .then().statusCode(404).body("code", equalTo("MOVIMENTO_NON_DI_QUESTO_IMPORT"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void cambioBu_movimentoAnnullato_409() {
        given().contentType(ContentType.JSON).body("{\"businessUnitId\":1}")
            .when().put("/api/movimenti/import/" + importId + "/bu/" + movAnnullato)
            .then().statusCode(409).body("code", equalTo("MOVIMENTO_ANNULLATO"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void panel_importInesistente_404() {
        given().when().get("/api/movimenti/import/" + NIL + "/bu")
            .then().statusCode(404).body("code", equalTo("IMPORT_NON_TROVATO"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"DIPENDENTE"})
    void panel_nonAdmin_403() {
        given().when().get("/api/movimenti/import/" + importId + "/bu").then().statusCode(403);
    }

    // ── helper ──────────────────────────────────────────────────────────────────────

    @Transactional
    void importLog(UUID id, String filename) {
        em.createNativeQuery("""
            INSERT INTO import_log (id, fonte, filename, data_import, righe_totali, righe_importate, stato)
            VALUES (:id, 'IMPORT_BANCA', :f, now(), 4, 4, 'COMPLETATO')
            """).setParameter("id", id).setParameter("f", filename).executeUpdate();
    }

    @Transactional
    void movimento(UUID id, UUID importLogId, String tipo, String importo, String cogeCodice,
                   int bu, String stato, String descrizione) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, fonte_importazione_id, descrizione, created_by, created_at)
            VALUES (:id, DATE '2026-07-15', DATE '2026-07-15', DATE '2026-07-15', DATE '2026-07-15',
                :tipo, CAST(:imp AS numeric), 0,
                (SELECT id FROM piano_dei_conti_coge WHERE codice = :coge), 1,
                :bu, :stato, 'IMPORT_BANCA', :log, :descr, CAST(:u AS uuid), now())
            """)
            .setParameter("id", id).setParameter("tipo", tipo).setParameter("imp", importo)
            .setParameter("coge", cogeCodice).setParameter("bu", (short) bu)
            .setParameter("stato", stato).setParameter("log", importLogId)
            .setParameter("descr", descrizione).setParameter("u", USER)
            .executeUpdate();
    }

    @Transactional
    void refreshMv() {
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_saldi_conti").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
    }

    BigDecimal saldoConto(int contoId) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT saldo_calcolato FROM mv_saldi_conti WHERE conto_id = :c")
                .setParameter("c", (short) contoId).getSingleResult();
    }

    BigDecimal ricaviTotaliTutteLeBu() {
        return (BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(SUM(ricavi),0) FROM mv_conto_economico_mensile WHERE anno = 2026 AND mese = 7")
                .getSingleResult();
    }

    /** Fotografia dei campi CONTABILI del movimento: devono restare identici al cambio BU. */
    Object[] datiContabili(UUID id) {
        return (Object[]) em.createNativeQuery(
                "SELECT importo_lordo, stato, data_movimento, data_finanziaria, conto_bancario_id, conto_coge_id " +
                "FROM movimenti WHERE id = :id").setParameter("id", id).getSingleResult();
    }

    short buDelMovimento(UUID id) {
        return ((Number) em.createNativeQuery("SELECT business_unit_id FROM movimenti WHERE id = :id")
                .setParameter("id", id).getSingleResult()).shortValue();
    }

    @AfterAll
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZBU %'").executeUpdate();
        em.createNativeQuery("DELETE FROM import_log WHERE filename LIKE 'ZZBU-%'").executeUpdate();
    }
}
