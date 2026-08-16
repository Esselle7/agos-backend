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
    private static final UUID NIL_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000");

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

    // ── (d) COLLEGA senza piano/rata → 400 (l'azione esiste di nuovo, ma vuole i suoi parametri) ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaSenzaPianoERata_400() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_CANONE", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("PIANO_O_RATA_MANCANTE"));
        assertEquals("DA_RICONCILIARE", statoEMovimento(ric)[0], "resta non risolta");
    }

    // ── (d2) azione inesistente → 400 ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void azioneSconosciuta_400() {
        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_BOLLO_X", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"FONDI\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("AZIONE_NON_VALIDA"));
    }

    // ── (d3) COLLEGA su ENTRATA → 400: un'erogazione non è una rata ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaSuEntrata_400() {
        UUID ric = seedRicorrente("ENTRATA", DESCR_PREFIX + "_EROGAZIONE_C", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + NIL_UUID + "\",\"rataId\":\"" + NIL_UUID + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("COLLEGA_SOLO_USCITE"));
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
            .body("{\"azione\":\"IGNORA\",\"nota\":\"non e' una rata di un nostro piano\"}")
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

    // ── (k) COLLEGA su rata PENDING: paga la rata con la DATA REALE dell'addebito ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaRataPending_pagaConDataAddebito() {
        accreditaConto1("PENDING");
        UUID piano = creaPianoMensile(DESCR_PREFIX + "_PIANO_PENDING", new BigDecimal("150.00"));
        UUID rata  = primaRataPending(piano);
        UUID ric   = seedRicorrente("USCITA", DESCR_PREFIX + "_CANONE PENDING", (short) 1);

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        Object[] row = statoEMovimento(ric);
        assertEquals("RICONCILIATA", row[0]);
        UUID movId = (UUID) row[1];
        assertNotNull(movId, "la riga deve puntare al movimento della rata");

        assertEquals("PAID", statoRata(rata), "la rata collegata risulta pagata");
        assertEquals(movId, movimentoDiRata(rata), "riga e rata puntano allo STESSO movimento");
        // la data del movimento è quella dell'addebito in banca, non la scadenza né oggi
        assertEquals(DATA, dataMovimento(movId), "il movimento usa la data reale dell'addebito");
    }

    // ── SPEC ricorrenti-importo-reale-da-import: l'estratto conto vince sul previsionale ──

    /**
     * R2 — Il piano è il previsionale, l'addebito è la verità: se il canone reale è 520,00 dove il
     * piano prevedeva 500,00, il movimento vale 520,00 e la rata viene riscritta.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaRata_importoRealeSovrascriveIlPrevisto() {
        accreditaConto1("REALE");
        UUID piano = creaPianoMensile(DESCR_PREFIX + "_PIANO_REALE", new BigDecimal("500.00"));
        UUID rata  = primaRataPending(piano);
        UUID ric   = seedRicorrenteImporto("USCITA", DESCR_PREFIX + "_CANONE INDICIZZATO", (short) 1, "520.00");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        UUID movId = movimentoDiRata(rata);
        assertEquals(0, new BigDecimal("520.00").compareTo(importoMovimento(movId)),
                "il movimento vale l'addebito reale, non la stima del piano");
        assertEquals(0, new BigDecimal("520.00").compareTo(importoRata(rata)),
                "la rata registra il fatto: 500,00 previsto -> 520,00 reale");
    }

    /**
     * R3 — FINANZIAMENTO: la quota CAPITALE resta quella del piano, lo scarto va tutto sugli
     * interessi. È ciò che tiene in piedi l'ammortamento: il debito continua a estinguersi come
     * previsto e le rate successive non vanno rigenerate.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaRataFinanziamento_scartoTuttoSugliInteressi() {
        accreditaConto1("FIN");
        UUID piano = creaPianoFinanziamento(DESCR_PREFIX + "_MUTUO", new BigDecimal("900.00"));
        UUID rata  = primaRataPending(piano);
        BigDecimal capitalePrima = quoteRata(rata)[0];

        UUID ric = seedRicorrenteImporto("USCITA", DESCR_PREFIX + "_RATA MUTUO", (short) 1, "918.17");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        BigDecimal[] q = quoteRata(rata);
        assertEquals(0, capitalePrima.compareTo(q[0]),
                "la quota capitale NON si tocca: e' cio' che mantiene valido l'ammortamento");
        assertEquals(0, new BigDecimal("918.17").subtract(capitalePrima).compareTo(q[1]),
                "lo scarto e' assorbito dagli interessi");
        assertEquals(0, new BigDecimal("918.17").compareTo(importoRata(rata)));
        assertEquals(0, q[0].add(q[1]).compareTo(importoRata(rata)),
                "invariante: importo = quota capitale + quota interessi");
    }

    /** R4 — Sotto la quota capitale gli interessi sarebbero negativi: si rifiuta, non si inventa. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaRataFinanziamento_sottoLaQuotaCapitale_400() {
        accreditaConto1("FIN_KO");
        UUID piano = creaPianoFinanziamento(DESCR_PREFIX + "_MUTUO_KO", new BigDecimal("900.00"));
        UUID rata  = primaRataPending(piano);
        UUID ric   = seedRicorrenteImporto("USCITA", DESCR_PREFIX + "_RATA SBAGLIATA", (short) 1, "10.00");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(400).body("code", equalTo("IMPORTO_SOTTO_QUOTA_CAPITALE"));

        assertEquals("PENDING", statoRata(rata), "nessuna scrittura: la rata resta com'era");
    }

    // ── (l) COLLEGA su rata GIÀ PAID: nessun movimento nuovo. È la chiusura del doppio conteggio ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegaRataGiaPagata_nonDuplicaIlMovimento() {
        accreditaConto1("PAID");
        UUID piano = creaPianoMensile(DESCR_PREFIX + "_PIANO_PAID", new BigDecimal("90.00"));
        UUID rata  = primaRataPending(piano);

        // lo scheduler (o l'utente) ha già pagato la rata: esiste un movimento
        given().contentType(ContentType.JSON)
            .when().post("/api/spese-ricorrenti/piani/" + piano + "/rate/" + rata + "/paga")
            .then().statusCode(200);
        UUID movPrima = movimentoDiRata(rata);
        assertNotNull(movPrima);
        long movimentiPrima = count("movimenti", "descrizione LIKE '" + DESCR_PREFIX + "_PIANO_PAID%'");

        UUID ric = seedRicorrente("USCITA", DESCR_PREFIX + "_CANONE GIA PAGATO", (short) 1);
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(204);

        assertEquals(movimentiPrima, count("movimenti", "descrizione LIKE '" + DESCR_PREFIX + "_PIANO_PAID%'"),
                "COLLEGA su rata già pagata NON deve creare un secondo movimento");
        assertEquals(movPrima, movimentoDiRata(rata), "il movimento della rata resta lo stesso");
        assertEquals(movPrima, statoEMovimento(ric)[1], "la riga si aggancia al movimento esistente");
        assertEquals("RICONCILIATA", statoEMovimento(ric)[0]);
    }

    // ── (m) COLLEGA due volte sulla stessa riga → 409 ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void doppioCollega_409() {
        accreditaConto1("DOPPIO");
        UUID piano = creaPianoMensile(DESCR_PREFIX + "_PIANO_DOPPIO", new BigDecimal("70.00"));
        UUID rata  = primaRataPending(piano);
        UUID ric   = seedRicorrente("USCITA", DESCR_PREFIX + "_CANONE DOPPIO", (short) 1);
        String body = "{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}";

        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi").then().statusCode(204);
        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/ricorrenti/" + ric + "/risolvi")
            .then().statusCode(409).body("code", equalTo("RICORRENTE_GIA_RISOLTA"));
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

    /**
     * Il pagamento di una rata passa da checkSaldo (saldo = saldo_iniziale + movimenti live) e nel DB
     * di test tutti i conti sono a 0,00: senza fondi ogni pagamento darebbe 409 SALDO_INSUFFICIENTE.
     * Si accredita il conto 1 con un'entrata marcata col prefisso, che il cleanup rimuove.
     */
    @Transactional
    void accreditaConto1(String tag) {
        // colonne NOT NULL senza default (lette da information_schema, non indovinate):
        // data_movimento, tipo, importo_lordo, conto_coge_id, business_unit_id, fonte, created_by
        em.createNativeQuery(
                "INSERT INTO movimenti (id, tipo, importo_lordo, data_movimento, data_competenza, " +
                "data_finanziaria, conto_bancario_id, conto_coge_id, business_unit_id, descrizione, " +
                "stato, fonte, importo_commissione, created_by) " +
                "VALUES (:id, 'ENTRATA', 10000.00, :d, :d, :d, 1, :coge, 2, :descr, 'REGISTRATO', 'MANUALE', 0, :uid)")
                .setParameter("id", UUID.randomUUID()).setParameter("d", DATA)
                .setParameter("coge", cogeId("30.01.001"))
                .setParameter("descr", DESCR_PREFIX + "_FONDI_" + tag)
                .setParameter("uid", UUID.fromString(USER))
                .executeUpdate();
    }

    /** Piano MENSILE attivo con 3 rate, creato dall'API come lo farebbe l'utente. */
    UUID creaPianoMensile(String descrizione, BigDecimal importoRata) {
        String body = "{\"descrizione\":\"" + descrizione + "\",\"contoBancarioId\":1,"
                // il piano vuole un CoGe di ramo PASSIVITA (la rata rimborsa un debito, non è un costo)
                + "\"contoCoge\":" + cogeId("20.01.001") + ",\"importoRata\":" + importoRata + ","
                + "\"giornoDelMese\":15,\"frequenza\":\"MENSILE\",\"numeroRate\":3,"
                + "\"dataInizio\":\"" + DATA + "\",\"tipoPiano\":\"FLAT\"}";
        return UUID.fromString(given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().log().ifValidationFails().statusCode(201).extract().path("id"));
    }

    UUID primaRataPending(UUID piano) {
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery(
                "SELECT id FROM recurring_expense_installment WHERE piano_id = :p AND stato = 'PENDING' " +
                "ORDER BY numero_rata LIMIT 1").setParameter("p", piano).getResultList();
        assertTrue(!ids.isEmpty(), "il piano deve avere almeno una rata PENDING");
        Object v = ids.get(0);
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    String statoRata(UUID rata) {
        return (String) em.createNativeQuery("SELECT stato FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
    }

    UUID movimentoDiRata(UUID rata) {
        Object v = em.createNativeQuery("SELECT movimento_id FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
        return v == null ? null : (v instanceof UUID u ? u : UUID.fromString(v.toString()));
    }

    LocalDate dataMovimento(UUID movId) {
        Object v = em.createNativeQuery("SELECT data_movimento FROM movimenti WHERE id = :id")
                .setParameter("id", movId).getSingleResult();
        return ((java.sql.Date) v).toLocalDate();
    }

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

    /** Riga parcheggiata con un importo scelto: serve a far divergere il reale dal previsto. */
    @Transactional
    UUID seedRicorrenteImporto(String tipo, String descr, Short conto, String importo) {
        ensureImportLog();
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, tipo, " +
                "conto_bancario_id, descrizione_norm, tipo_presunto, stato, raw_data) " +
                "VALUES (:id, :log, 'IMPORT_BANCA', :data, CAST(:imp AS numeric), :tipo, :conto, :descr, 'ALTRO', " +
                "'DA_RICONCILIARE', CAST('{}' AS jsonb))")
                .setParameter("id", id).setParameter("log", IMPORT_LOG).setParameter("data", DATA)
                .setParameter("imp", importo)
                .setParameter("tipo", tipo).setParameter("conto", conto).setParameter("descr", descr)
                .executeUpdate();
        return id;
    }

    /** Piano FINANZIAMENTO: la rata si divide in quota capitale + quota interessi. */
    UUID creaPianoFinanziamento(String descrizione, BigDecimal importoRata) {
        String body = "{\"descrizione\":\"" + descrizione + "\",\"contoBancarioId\":1,"
                + "\"contoCoge\":" + cogeId("20.01.001") + ",\"importoRata\":" + importoRata + ","
                + "\"giornoDelMese\":15,\"frequenza\":\"MENSILE\",\"numeroRate\":12,"
                + "\"dataInizio\":\"" + DATA + "\",\"tipoPiano\":\"FINANZIAMENTO\","
                + "\"importoDebitoIniziale\":10000.00,\"tassoInteresseAnnuo\":3.5,"
                + "\"contoCogeInteressiId\":" + cogeId("60.01.001") + "}";
        return UUID.fromString(given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().log().ifValidationFails().statusCode(201).extract().path("id"));
    }

    BigDecimal importoRata(UUID rata) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT importo FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
    }

    BigDecimal[] quoteRata(UUID rata) {
        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT quota_capitale, quota_interessi FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
        return new BigDecimal[]{ (BigDecimal) r[0], (BigDecimal) r[1] };
    }

    BigDecimal importoMovimento(UUID movId) {
        return (BigDecimal) em.createNativeQuery("SELECT importo_lordo FROM movimenti WHERE id = :id")
                .setParameter("id", movId).getSingleResult();
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
        // I piani restavano nel DB di test condiviso: quelli FINANZIAMENTO fanno comparire nel
        // previsionale le categorie RATA_RICORRENTE_CAPITALE/INTERESSI e rompevano l'invariante
        // di ForecastingIntegrationTest a seconda dell'ordine di esecuzione.
        em.createNativeQuery(
                "DELETE FROM recurring_expense_installment WHERE piano_id IN "
                + "(SELECT id FROM recurring_expense_plan WHERE descrizione LIKE :p)")
                .setParameter("p", DESCR_PREFIX + "%").executeUpdate();
        em.createNativeQuery("DELETE FROM recurring_expense_plan WHERE descrizione LIKE :p")
                .setParameter("p", DESCR_PREFIX + "%").executeUpdate();
    }
}
