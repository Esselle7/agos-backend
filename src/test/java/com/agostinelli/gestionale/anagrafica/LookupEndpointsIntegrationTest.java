package com.agostinelli.gestionale.anagrafica;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test per i nuovi endpoint lookup:
 *   GET /api/piano-dei-conti
 *   GET /api/metodi-pagamento
 *   GET /api/aliquote-iva
 *
 * Copre: happy path, sicurezza, DTO contract, gerarchia/livello, ordinamento,
 * filtri, regression del bug null-cache-key su piano-dei-conti.
 * Non duplica copertura già presente in AnagraficaIntegrationTest.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LookupEndpointsIntegrationTest {

    // ═══════════════════════════════════════════════════════════════════════
    // PIANO DEI CONTI — GET /api/piano-dei-conti
    // ═══════════════════════════════════════════════════════════════════════

    /** Regressione: senza @DefaultValue il parametro tipo=null provocava NPE nella cache. */
    @Test
    @Order(10)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiSenzaParametroNonProvocaNullCacheKey() {
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(greaterThan(50)));
    }

    @Test
    @Order(11)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiDtoContieneCampiAggioranti() {
        // nome (non più descrizione), livello presenti; isCapex non deve comparire
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("[0].id",          notNullValue())
                .body("[0].codice",      notNullValue())
                .body("[0].nome",        notNullValue())
                .body("[0].tipo",        notNullValue())
                .body("[0].livello",     notNullValue())
                .body("[0].isCapex",     nullValue())
                .body("[0].descrizione", nullValue());
    }

    @Test
    @Order(12)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiLivelloCalcolatoSuCodice() {
        // Livello = numero di dot nel codice + 1
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("find { it.codice == '10' }.livello",        equalTo(1))
                .body("find { it.codice == '10.01' }.livello",     equalTo(2))
                .body("find { it.codice == '10.01.001' }.livello", equalTo(3));
    }

    @Test
    @Order(13)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiHierarchiaPadreFiglio() {
        // Mastro radice: parentId null; conto e sottoconto: parentId non null
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("find { it.codice == '10' }.parentId",         nullValue())
                .body("find { it.codice == '10.01' }.parentId",      notNullValue())
                .body("find { it.codice == '10.01.001' }.parentId",  notNullValue());
    }

    @Test
    @Order(14)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiOrdinatiPerCodice() {
        List<String> codici = given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("codice", String.class);

        for (int i = 0; i < codici.size() - 1; i++) {
            Assertions.assertTrue(
                codici.get(i).compareTo(codici.get(i + 1)) <= 0,
                "Codici non ordinati: " + codici.get(i) + " > " + codici.get(i + 1));
        }
    }

    @Test
    @Order(15)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiFiltroTipoCostoRestituisceOnlyCosto() {
        given()
            .queryParam("tipo", "COSTO")
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("tipo", everyItem(equalTo("COSTO")));
    }

    @Test
    @Order(16)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiFiltroTipoPassivita() {
        given()
            .queryParam("tipo", "PASSIVITA")
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)))
                .body("tipo", everyItem(equalTo("PASSIVITA")));
    }

    @Test
    @Order(17)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiFiltroTipoCaseInsensitive() {
        // Il backend fa toUpperCase: ?tipo=costo deve dare lo stesso risultato di ?tipo=COSTO
        int countUpper = given()
            .queryParam("tipo", "COSTO")
            .when().get("/api/piano-dei-conti")
            .then().statusCode(200)
            .extract().jsonPath().getList("$").size();

        int countLower = given()
            .queryParam("tipo", "costo")
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("tipo", everyItem(equalTo("COSTO")))
                .extract().jsonPath().getList("$").size();

        Assertions.assertEquals(countUpper, countLower,
            "Filtro tipo deve essere case-insensitive");
    }

    @Test
    @Order(18)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiFiltroTipoInesistenteRestituisceVuoto() {
        given()
            .queryParam("tipo", "TIPO_INESISTENTE_XYZ")
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    @Order(19)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testPianoContiAccessibileComeDipendente() {
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("$", hasSize(greaterThan(0)));
    }

    @Test
    @Order(20)
    void testPianoContiRifiutaSenzaToken() {
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(401);
    }

    @Test
    @Order(21)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiNomeMappaDaDescrizione() {
        // Il DB ha la colonna "descrizione"; il DTO espone come "nome"
        // Verifica che il campo nome contenga un valore reale proveniente dal DB
        given()
            .when().get("/api/piano-dei-conti")
            .then()
                .statusCode(200)
                .body("find { it.codice == '10.01.001' }.nome", containsString("BPM"))
                .body("find { it.codice == '10.01.003' }.nome", containsString("contanti"));
    }

    @Test
    @Order(22)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testPianoContiConCacheKeyDiversaPerFiltriDiversi() {
        // Tutti → array grande; solo RICAVO → sottoinsieme; entrambe risposte 200
        // Verifica che il caching con key "all" e "RICAVO" funzioni separatamente
        int total = given().when().get("/api/piano-dei-conti")
            .then().statusCode(200).extract().jsonPath().getList("$").size();

        int ricavi = given().queryParam("tipo", "RICAVO")
            .when().get("/api/piano-dei-conti")
            .then().statusCode(200)
            .body("tipo", everyItem(equalTo("RICAVO")))
            .extract().jsonPath().getList("$").size();

        Assertions.assertTrue(ricavi < total,
            "I RICAVO devono essere un sottoinsieme del totale");
        Assertions.assertTrue(ricavi > 0,
            "Devono esserci conti di tipo RICAVO nel piano dei conti");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // METODI DI PAGAMENTO — GET /api/metodi-pagamento
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(30)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoListaCompleta() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(12));
    }

    @Test
    @Order(31)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoDtoHaCampiCorretti() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .body("[0].id",          notNullValue())
                .body("[0].codice",      notNullValue())
                .body("[0].descrizione", notNullValue());
    }

    @Test
    @Order(32)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoContieneMetodiBase() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .body("codice", hasItems("CONTANTI", "SATISPAY", "BONIFICO", "POS_BPM", "POS_CA_NEXI"));
    }

    @Test
    @Order(33)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoContieneMetodiMancantiFrontendHardcoded() {
        // Questi tre mancavano dalla versione hardcoded del frontend
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .body("codice", hasItems("F24", "ASSEGNO", "RID_SDDMANDAT"));
    }

    @Test
    @Order(34)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoOrdinatiPerId() {
        List<Integer> ids = given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("id", Integer.class);

        for (int i = 0; i < ids.size() - 1; i++) {
            Assertions.assertTrue(ids.get(i) < ids.get(i + 1),
                "Metodi non ordinati per id: " + ids.get(i) + " >= " + ids.get(i + 1));
        }
    }

    @Test
    @Order(35)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoDescrizioneNonVuota() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .body("descrizione", everyItem(not(emptyOrNullString())));
    }

    @Test
    @Order(36)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testMetodiPagamentoCodiceUnico() {
        List<String> codici = given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("codice", String.class);

        long distinti = codici.stream().distinct().count();
        Assertions.assertEquals(codici.size(), distinti,
            "I codici dei metodi di pagamento devono essere univoci");
    }

    @Test
    @Order(37)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testMetodiPagamentoAccessibileComeDipendente() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(200)
                .body("$", hasSize(12));
    }

    @Test
    @Order(38)
    void testMetodiPagamentoRifiutaSenzaToken() {
        given()
            .when().get("/api/metodi-pagamento")
            .then()
                .statusCode(401);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ALIQUOTE IVA — GET /api/aliquote-iva
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @Order(50)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaListaCompleta() {
        // 4 dal seed V5 + 5% aggiunto da migrazione V14
        given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", hasSize(5));
    }

    @Test
    @Order(51)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaDtoHaCampiCorretti() {
        given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .body("[0].id",          notNullValue())
                .body("[0].aliquota",    notNullValue())
                .body("[0].descrizione", notNullValue());
    }

    @Test
    @Order(52)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaValoriSonoDecimaliNonPercentuali() {
        // Il DB archivia 10.0 (percentuale); l'endpoint restituisce 0.10 (moltiplicatore)
        // Nessun valore deve essere >= 1.0
        List<Float> aliquote = given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("aliquota", Float.class);

        Assertions.assertFalse(aliquote.isEmpty(), "La lista aliquote non deve essere vuota");
        for (Float a : aliquote) {
            Assertions.assertTrue(a >= 0.0f && a < 1.0f,
                "Aliquota non convertita: " + a + " (atteso valore in [0.0, 1.0))");
        }
    }

    @Test
    @Order(53)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaOrdinatePerValoreAscendente() {
        List<Float> aliquote = given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("aliquota", Float.class);

        for (int i = 0; i < aliquote.size() - 1; i++) {
            Assertions.assertTrue(aliquote.get(i) <= aliquote.get(i + 1),
                "Aliquote non ordinate: " + aliquote.get(i) + " > " + aliquote.get(i + 1));
        }
    }

    @Test
    @Order(54)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaContieneZeroPercentoEsente() {
        // Primo elemento = 0% esente; descrizione deve menzionare esenzione
        given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .body("[0].descrizione", containsString("Esente"));
    }

    @Test
    @Order(55)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaCinquePercentoAggiuntoDaMigrazioneV14() {
        // V14 inserisce 5%; nell'array ordinato per valore è alla posizione 2
        List<Float> aliquote = given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("aliquota", Float.class);

        boolean cinquePresente = aliquote.stream().anyMatch(a -> Math.abs(a - 0.05f) < 0.001f);
        Assertions.assertTrue(cinquePresente,
            "Aliquota 5% (0.05) deve essere presente dopo la migrazione V14");
    }

    @Test
    @Order(56)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaDieciPercentoRestituisceDecimaleCorretto() {
        // 10% nel DB (10.0) deve diventare 0.10 nella risposta
        List<Float> aliquote = given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("aliquota", Float.class);

        boolean dieciPresente = aliquote.stream().anyMatch(a -> Math.abs(a - 0.10f) < 0.001f);
        Assertions.assertTrue(dieciPresente,
            "Aliquota 10% deve restituire 0.10 (non 10.0 — la divisione per 100 deve avvenire)");
    }

    @Test
    @Order(57)
    @TestSecurity(user = "test-admin", roles = {"ADMIN"})
    void testAliquoteIvaTuttiIValoriAttesi() {
        List<Float> aliquote = given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .extract().jsonPath().getList("aliquota", Float.class);

        float[] attesi = {0.0f, 0.04f, 0.05f, 0.10f, 0.22f};
        for (float atteso : attesi) {
            boolean trovato = aliquote.stream().anyMatch(a -> Math.abs(a - atteso) < 0.001f);
            Assertions.assertTrue(trovato,
                "Aliquota attesa " + atteso + " non trovata nella risposta");
        }
    }

    @Test
    @Order(58)
    @TestSecurity(user = "test-dipendente", roles = {"DIPENDENTE"})
    void testAliquoteIvaAccessibileComeDipendente() {
        given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(200)
                .body("$", hasSize(5));
    }

    @Test
    @Order(59)
    void testAliquoteIvaRifiutaSenzaToken() {
        given()
            .when().get("/api/aliquote-iva")
            .then()
                .statusCode(401);
    }
}
