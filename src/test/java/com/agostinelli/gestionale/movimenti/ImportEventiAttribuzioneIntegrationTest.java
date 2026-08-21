package com.agostinelli.gestionale.movimenti;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * SPEC docs/specs/import-eventi-attribuzione.md — attribuzione degli incassi-evento parcheggiati.
 *
 * <p>Il difetto che questi test difendono: prima, "Riconcilia" marcava la riga come risolta e non
 * creava alcun movimento; la coda si svuotava e il denaro non entrava mai nei saldi (18.924,00 €
 * invisibili dopo l'import di luglio 2026).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportEventiAttribuzioneIntegrationTest {

    private static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final String MARKER = "[TEST-ATTRIBUZIONE]";

    @Inject EntityManager em;

    // ── R1 + I1: attribuire crea il movimento, e lo crea nel modulo Eventi ──────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_suEventoReale_creaIlMovimentoEPortaISoldiNelSaldo() {
        UUID evento = eventoReale("Matrimonio Test", "2026-07-19", "2000.00");
        UUID park   = parcheggiato("MOLTENI ELENA", "2026-07-14", "1000.00", "2026-07-19", "ACCONTO");

        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"eventoId\":\"" + evento + "\",\"tipo\":\"ACCONTO\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(204);

        Assertions.assertEquals(1, contaMovimenti(evento), "deve nascere esattamente 1 movimento");
        Assertions.assertEquals(0, new BigDecimal("1000.00").compareTo(incassato(evento)),
                "l'incassato dell'evento deve salire dell'importo attribuito");
        Assertions.assertEquals("RICONCILIATO", statoPark(park));
    }

    // ── R6 + il motivo per cui il segnaposto è uno per pagamento ────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_senzaEvento_creaUnSegnapostoPerPagamento() {
        UUID p1 = parcheggiato("VEZZONI MANUEL", "2026-07-14", "1800.00", "2026-07-04", "SALDO");
        UUID p2 = parcheggiato("MONTALDI MATTEO", "2026-07-28", "1550.00", "2026-07-19", "SALDO");

        for (UUID p : new UUID[]{p1, p2}) {
            given().contentType(ContentType.JSON)
                .body("{\"azione\":\"RICONCILIA\",\"creaSegnaposto\":true,\"tipo\":\"SALDO\"}")
                .when().put("/api/movimenti/import/eventi/" + p + "/risolvi")
                .then().statusCode(204);
        }

        // Due SALDO distinti restano su due segnaposto distinti anche ora che l'unicità
        // per tipo è caduta: il motivo non è più il 409, sono gli altri tre vincoli del
        // percorso-soldi (residuo esatto, auto-chiusura a SALDATO, competenza economica
        // sulla data evento) — vedi docs/specs/import-eventi-attribuzione.md.
        Assertions.assertEquals(2, contaSegnaposto(), "un segnaposto per pagamento, non condiviso");
        Assertions.assertEquals(0, new BigDecimal("3350.00").compareTo(incassatoSegnaposto()),
                "entrambi gli incassi devono essere contabilizzati");
        // Anche il segnaposto rispetta "preventivato = somma voci": senza la sua voce, la
        // prima mutazione di voce azzererebbe il preventivato e incassato lo supererebbe.
        Assertions.assertEquals(0, segnapostoConPreventivatoIncoerente(),
                "un segnaposto deve avere una voce di preventivo pari all'importo");
    }

    /** R7: i segnaposto non inquinano la lista eventi né il calendario. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void segnaposto_nonCompareNellaListaEventi() {
        UUID park = parcheggiato("TABBOUCH JANA", "2026-07-20", "750.00", "2026-08-16", "CAPARRA");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"creaSegnaposto\":true,\"tipo\":\"CAPARRA\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(204);

        given().when().get("/api/eventi?page=0&size=100")
            .then().statusCode(200)
                .body("content.findAll { it.nome.startsWith('[DA ATTRIBUIRE]') }", hasSize(0));
    }

    // ── R2: solo i 5 tipi di lk_tipi_evento_mov ────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_tipoNonValido_400() {
        UUID park = parcheggiato("BARBARA CAPPELLI", "2026-07-27", "440.00", null, "AFFITTO_SALA");
        // AFFITTO_SALA è ciò che l'ETL suggerisce, ma non esiste in lk_tipi_evento_mov:
        // accettarlo esploderebbe più a valle sulla FK, con un errore illeggibile.
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"creaSegnaposto\":true,\"tipo\":\"AFFITTO_SALA\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(400).body("code", equalTo("TIPO_EVENTO_NON_VALIDO"));
    }

    /**
     * R3 del wizard §7.2 (docs/specs/wizard-incassi-evento.md): il ricavo evento nasce SOLO dal
     * modulo Eventi. Se un giorno qualcuno sbloccasse CLASSIFICA per «fare prima», l'incasso
     * diventerebbe un movimento generico senza evento collegato — invisibile in ogni bilancio
     * evento. Questo test è la guardia che fallirebbe.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void classifica_restaVietata_ilRicavoEventoNasceSoloDalModuloEventi() {
        UUID park = parcheggiato("LETO SALVATORE", "2026-07-11", "1750.00", null, "CAPARRA");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"CLASSIFICA\",\"tipo\":\"CAPARRA\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(409).body("code", equalTo("EVENTO_NON_CONTABILIZZABILE"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_senzaEventoNeSegnaposto_400() {
        UUID park = parcheggiato("DOTTI FABIO", "2026-07-14", "430.00", null, "SALDO");
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"tipo\":\"SALDO\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(400).body("code", equalTo("EVENTO_RICHIESTO"));
    }

    // ── I2: l'attribuzione non si ripete ───────────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_dueVolte_409_eNessunSecondoMovimento() {
        UUID park = parcheggiato("CORTI ALESSIA", "2026-07-20", "580.00", null, "SALDO");
        String body = "{\"azione\":\"RICONCILIA\",\"creaSegnaposto\":true,\"tipo\":\"SALDO\"}";

        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi").then().statusCode(204);
        given().contentType(ContentType.JSON).body(body)
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi").then().statusCode(409);

        Assertions.assertEquals(1, contaSegnaposto(), "il secondo tentativo non deve creare nulla");
    }

    // ── I3 (rivisto 2026-08-08): due tranche dello stesso tipo sullo stesso evento ──

    /**
     * Caso reale CELLA ERIKA (evento 18/09/2026): la caparra è arrivata in due bonifici
     * distinti — "CAPARRA 3 EVENTO 18.09.26" 320,00 e "CAPARRA 3 BIS EVENTO 18.09 .26" 20,00.
     * Con il vecchio vincolo di unicità il secondo moriva su 409 PAGAMENTO_GIA_PRESENTE e
     * l'operatore era costretto a inventare un tipo sbagliato o un segnaposto.
     *
     * <p>Il doppio inserimento dello STESSO bonifico non è protetto da qui, ma da I2
     * (una riga parcheggiata si risolve una volta sola) — vedi
     * {@link #riconcilia_dueVolte_409_eNessunSecondoMovimento()}.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_dueCaparreSulloStessoEvento_ammesse_eSiSommano() {
        UUID evento = eventoReale("Festa ospedale Erika cella", "2026-09-18", "3000.00");
        UUID p1 = parcheggiato("CELLA ERIKA CAPARRA 3 EVENTO 18.09.26",
                "2026-07-14", "320.00", "2026-09-18", "CAPARRA");
        UUID p2 = parcheggiato("CELLA ERIKA CAPARRA 3 BIS EVENTO 18.09 .26",
                "2026-07-14", "20.00", null, "CAPARRA");

        for (UUID p : new UUID[]{p1, p2}) {
            given().contentType(ContentType.JSON)
                .body("{\"azione\":\"RICONCILIA\",\"eventoId\":\"" + evento + "\",\"tipo\":\"CAPARRA\"}")
                .when().put("/api/movimenti/import/eventi/" + p + "/risolvi")
                .then().statusCode(204);
        }

        Assertions.assertEquals(2, contaMovimenti(evento), "due bonifici = due movimenti distinti");
        Assertions.assertEquals(0, new BigDecimal("340.00").compareTo(incassato(evento)),
                "l'incassato è la somma delle due tranche, non l'ultima che vince");
    }

    // ── R3: si propone solo su nome E data, mai sul solo nome ──────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void suggerimento_soloSeNomeEDataCoincidono() {
        eventoReale("Elena molteni", "2026-07-19", "4365.00");
        UUID buono = parcheggiato("MOLTENI ELENA", "2026-07-14", "1000.00", "2026-07-19", "ACCONTO");
        // Stesso cognome, data diversa: è il falso positivo "Battesimo Laura Molteni" misurato.
        UUID falso = parcheggiato("MOLTENI LAURA", "2026-07-14", "500.00", "2026-05-03", "ACCONTO");

        var res = given().when().get("/api/movimenti/import/eventi?size=100").then().statusCode(200).extract();
        Assertions.assertNotNull(res.path("content.find { it.id == '" + buono + "' }.eventoSuggeritoId"),
                "nome + data coincidono → il sistema propone");
        Assertions.assertNull(res.path("content.find { it.id == '" + falso + "' }.eventoSuggeritoId"),
                "solo il nome coincide → nessuna proposta");
    }

    /**
     * Un evento SALDATO non è un candidato proponibile: {@code registraPagamento} lo rifiuta
     * (EventiService:252), quindi la proposta ha un solo esito possibile, il 409.
     *
     * <p>Misurato sulla copia di produzione del 07/08/2026: delle 26 righe parcheggiate il
     * sistema produceva UNA sola proposta — MOLTENI ELENA 1.000,00 → "Elena molteni" — e
     * quell'evento era SALDATO con residuo 0,00. Cioè: l'unico suggerimento esistente era
     * un suggerimento che non si poteva accettare.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void suggerimento_escludeEventoSaldato() {
        UUID evento = eventoReale("Elena molteni", "2026-07-19", "4365.00");
        saldato(evento);
        UUID park = parcheggiato("MOLTENI ELENA", "2026-07-14", "1000.00", "2026-07-19", "ACCONTO");

        var res = given().when().get("/api/movimenti/import/eventi?size=100").then().statusCode(200).extract();
        Assertions.assertNull(res.path("content.find { it.id == '" + park + "' }.eventoSuggeritoId"),
                "evento SALDATO: non va proposto, non può accettare il pagamento");

        // La prova che la proposta sarebbe stata inservibile: confermarla dà 409.
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"eventoId\":\"" + evento + "\",\"tipo\":\"ACCONTO\"}")
            .when().put("/api/movimenti/import/eventi/" + park + "/risolvi")
            .then().statusCode(409).body("code", equalTo("EVENTO_SALDATO"));
    }

    /**
     * L'incasso attribuito deve atterrare sulla foglia di ricavo eventi, non sul mastro
     * di un'altra business unit.
     *
     * <p>Il difetto: {@code lookupCogeRicavi()} prendeva {@code LIKE '30.%' ORDER BY codice
     * LIMIT 1} = <b>30.01 "Ricavi Ristorazione e Agriturismo"</b>, che è un mastro. Misurato
     * il 07/08/2026 sulla copia di produzione: attribuendo tutte e 26 le righe parcheggiate,
     * 18.924,00 € finivano su 30.01 — cioè fuori dalla riga "Cerimonie ed Eventi" del conto
     * economico, e su un conto diverso da quello dello storico (V15 usa 30.02.002).
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void riconcilia_contabilizzaSullaFogliaRicaviEventi() {
        UUID evento = eventoReale("Compleanno Alessia Corti", "2026-07-18", "1080.00");
        UUID saldo   = parcheggiato("CORTI ALESSIA",  "2026-07-20", "580.00", "2026-07-18", "SALDO");
        UUID caparra = parcheggiato("CORTI ALESSIA 2", "2026-07-20", "100.00", "2026-07-18", "CAPARRA");

        for (var p : new String[][]{{saldo.toString(), "SALDO"}, {caparra.toString(), "CAPARRA"}}) {
            given().contentType(ContentType.JSON)
                .body("{\"azione\":\"RICONCILIA\",\"eventoId\":\"" + evento + "\",\"tipo\":\"" + p[1] + "\"}")
                .when().put("/api/movimenti/import/eventi/" + p[0] + "/risolvi")
                .then().statusCode(204);
        }

        Assertions.assertEquals("30.02.002", cogeDelPagamento(evento, "SALDO"), "il saldo va su Saldi eventi");
        Assertions.assertEquals("30.02.001", cogeDelPagamento(evento, "CAPARRA"), "la caparra ha il suo conto");
    }

    // ── C2/C3: il segnale «già a libro» sulla coda ─────────────────────────────────

    /**
     * C2 — la lista dice, <b>al caricamento</b>, quali righe hanno già un gemello a libro
     * (stesso importo, stessa data finanziaria, stesso conto), con il giorno di inserimento e
     * l'evento su cui è registrato. Prima di questo, il doppione si scopriva solo quando il
     * server rifiutava, cioè dopo aver già scelto l'evento.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void codaEventi_segnalaLeRigheCheHannoGiaUnGemelloALibro() {
        UUID evento = eventoReale("Cena C2", "2026-07-19", "3000.00");
        UUID gia    = parcheggiato("ROSSI MARIO", "2026-07-14", "500.00", "2026-07-19", "ACCONTO");
        risolvi(gia, evento, "ACCONTO", 204);

        UUID sospetta = parcheggiato("ROSSI MARIO", "2026-07-14", "500.00", "2026-07-19", "ACCONTO");
        UUID pulita   = parcheggiato("BIANCHI ANNA", "2026-07-14", "700.00", "2026-07-19", "ACCONTO");

        var res = given().when().get("/api/movimenti/import/eventi?size=100")
                .then().statusCode(200).extract();

        Assertions.assertNotNull(res.path("content.find { it.id == '" + sospetta + "' }.gemelloInseritoIl"),
                "stesso importo, stessa data, stesso conto: la riga va segnalata");
        Assertions.assertEquals(MARKER + "Cena C2",
                res.path("content.find { it.id == '" + sospetta + "' }.gemelloEventoNome"),
                "il segnale dice anche SU QUALE evento il gemello è già registrato");
        Assertions.assertNull(res.path("content.find { it.id == '" + pulita + "' }.gemelloInseritoIl"),
                "importo diverso: nessun gemello, nessun segnale");
    }

    /**
     * C3 — il segnale è <b>informazione, non permesso</b>, in tutte e due le direzioni.
     *
     * <p>(a) una riga segnalata resta confermabile: lo stesso importo su un altro evento è una
     * cosa diversa, e potrebbe essere una seconda tranche vera (ADR 003).
     * <p>(b) una riga NON segnalata può comunque essere respinta: qui il gemello nasce dopo il
     * caricamento della lista, e la conferma prende 409 lo stesso — perché la guardia che conta
     * legge nella stessa transazione che scrive, mentre la lista è una fotografia.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void ilSegnale_nonEUnPermesso_neLaSuaAssenzaUnVialibera() {
        UUID eventoX = eventoReale("Festa X C3", "2026-07-19", "3000.00");
        UUID eventoY = eventoReale("Festa Y C3", "2026-07-26", "3000.00");
        UUID primo   = parcheggiato("VERDI LUCA", "2026-07-15", "400.00", "2026-07-19", "ACCONTO");
        UUID secondo = parcheggiato("VERDI LUCA", "2026-07-15", "400.00", "2026-07-19", "ACCONTO");
        UUID terzo   = parcheggiato("VERDI LUCA", "2026-07-15", "400.00", "2026-07-19", "ACCONTO");

        // La fotografia: nessuna delle tre righe ha un gemello, perché a libro non c'è nulla.
        var prima = given().when().get("/api/movimenti/import/eventi?size=100")
                .then().statusCode(200).extract();
        Assertions.assertNull(prima.path("content.find { it.id == '" + secondo + "' }.gemelloInseritoIl"),
                "prima che il gemello esista, la riga non è segnalata");

        risolvi(primo, eventoX, "ACCONTO", 204);

        // (b) il gemello è nato DOPO la lettura della lista: la conferma viene respinta lo stesso.
        risolvi(secondo, eventoX, "ACCONTO", 409);

        // (a) la stessa riga, ora segnalata, resta confermabile su un evento diverso.
        var dopo = given().when().get("/api/movimenti/import/eventi?size=100")
                .then().statusCode(200).extract();
        Assertions.assertNotNull(dopo.path("content.find { it.id == '" + terzo + "' }.gemelloInseritoIl"),
                "ora il gemello c'è: la riga è segnalata");
        risolvi(terzo, eventoY, "ACCONTO", 204);

        Assertions.assertEquals(1, contaMovimenti(eventoX), "sull'evento X un solo incasso");
        Assertions.assertEquals(1, contaMovimenti(eventoY), "il segnale non ha impedito l'attribuzione vera");
    }

    private void risolvi(UUID parkId, UUID eventoId, String tipo, int atteso) {
        given().contentType(ContentType.JSON)
            .body("{\"azione\":\"RICONCILIA\",\"eventoId\":\"" + eventoId + "\",\"tipo\":\"" + tipo + "\"}")
            .when().put("/api/movimenti/import/eventi/" + parkId + "/risolvi")
            .then().statusCode(atteso);
    }

    // ── helper ────────────────────────────────────────────────────────────────────

    private String cogeDelPagamento(UUID eventoId, String tipo) {
        return (String) em.createNativeQuery(
                "SELECT p.codice FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE m.evento_id = CAST(:e AS uuid) AND m.tipo_evento_movimento = :t AND m.stato <> 'ANNULLATO'")
                .setParameter("e", eventoId.toString()).setParameter("t", tipo).getSingleResult();
    }

    /** Porta l'evento nello stato terminale, come lo storico seminato da V15/V26. */
    private void saldato(UUID eventoId) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE eventi SET stato = 'SALDATO', importo_incassato = importo_totale_preventivato " +
                "WHERE id = CAST(:e AS uuid)")
                .setParameter("e", eventoId.toString()).executeUpdate());
    }

    private UUID eventoReale(String nome, String data, String totale) {
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO eventi (id, nome, tipo, data_evento, importo_totale_preventivato, " +
                "importo_incassato, caparre_incassate, costi_diretti_imputati, stato, business_unit_id, " +
                "contatto_nome, numero_totale_partecipanti, is_segnaposto, created_by, created_at) " +
                "VALUES (CAST(:id AS uuid), :nome, 'ALTRO', CAST(:d AS date), CAST(:tot AS numeric), " +
                "0, 0, 0, 'PREVENTIVATO', 2, :nome, 0, false, CAST(:u AS uuid), now())")
                .setParameter("id", id.toString()).setParameter("nome", MARKER + nome)
                .setParameter("d", data).setParameter("tot", totale).setParameter("u", USER)
                .executeUpdate());
        return id;
    }

    /** eventi_da_riconciliare.import_log_id è NOT NULL: serve un log a cui agganciare le righe. */
    private UUID importLogId;

    private UUID importLog() {
        if (importLogId != null) return importLogId;
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO import_log (id, fonte, filename, data_import, stato, righe_ambigue, " +
                "righe_ambigue_classificate, righe_scartate, righe_parcheggiate, righe_ricorrenti, " +
                "righe_matching_differiti) " +
                "VALUES (CAST(:id AS uuid), 'IMPORT_CONGIUNTO', :f, now(), 'COMPLETATO', 0,0,0,0,0,0)")
                .setParameter("id", id.toString()).setParameter("f", MARKER)
                .executeUpdate());
        importLogId = id;
        return id;
    }

    private UUID parcheggiato(String controparte, String dataMov, String importo,
                              String dataEvento, String tipoPresunto) {
        UUID id = UUID.randomUUID();
        UUID log = importLog();
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, tipo, " +
                "conto_bancario_id, descrizione_norm, tipo_evento_presunto, controparte_nome, " +
                "data_evento_estratta, stato, raw_data, created_at) " +
                "VALUES (CAST(:id AS uuid), CAST(:log AS uuid), 'IMPORT_BANCA', CAST(:dm AS date), CAST(:imp AS numeric), " +
                "'ENTRATA', 2, :descr, :tp, :cp, CAST(:de AS date), 'DA_RICONCILIARE', '{}'::jsonb, now())")
                .setParameter("log", log.toString())
                .setParameter("id", id.toString()).setParameter("dm", dataMov)
                .setParameter("imp", importo).setParameter("descr", MARKER + " ORD:" + controparte)
                .setParameter("tp", tipoPresunto).setParameter("cp", controparte)
                .setParameter("de", dataEvento)
                .executeUpdate());
        return id;
    }

    /**
     * Conta gli INCASSI dell'evento — che e' cio' che questi test verificano ("un solo incasso",
     * "due bonifici = due movimenti distinti").
     *
     * Esclude la riga di competenza della Fase 4 (`tipo_evento_movimento = 'COMPETENZA'`,
     * SPEC docs/specs/competenza-ricavo-evento.md): non e' un incasso, e' il ricavo maturato e non
     * ancora riscosso, e su un evento gia' celebrato ce n'e' sempre una finche' resta un residuo.
     * L'oracolo non e' stato indebolito: e' stato riportato a cio' che diceva di misurare.
     */
    private long contaMovimenti(UUID eventoId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE evento_id = CAST(:e AS uuid) " +
                "AND stato <> 'ANNULLATO' AND COALESCE(tipo_evento_movimento,'') <> 'COMPETENZA'")
                .setParameter("e", eventoId.toString()).getSingleResult()).longValue();
    }

    private BigDecimal incassato(UUID eventoId) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT importo_incassato FROM eventi WHERE id = CAST(:e AS uuid)")
                .setParameter("e", eventoId.toString()).getSingleResult();
    }

    private long contaSegnaposto() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM eventi WHERE is_segnaposto").getSingleResult()).longValue();
    }

    private BigDecimal incassatoSegnaposto() {
        return (BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(sum(importo_incassato),0) FROM eventi WHERE is_segnaposto").getSingleResult();
    }

    /** Segnaposto il cui preventivato non coincide con la somma delle sue voci. */
    private long segnapostoConPreventivatoIncoerente() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM eventi e WHERE e.is_segnaposto AND e.importo_totale_preventivato " +
                "IS DISTINCT FROM (SELECT COALESCE(sum(v.importo_preventivo),0) FROM evento_voce v " +
                "WHERE v.evento_id = e.id)").getSingleResult()).longValue();
    }

    private String statoPark(UUID id) {
        return (String) em.createNativeQuery(
                "SELECT stato FROM eventi_da_riconciliare WHERE id = CAST(:i AS uuid)")
                .setParameter("i", id.toString()).getSingleResult();
    }

    @BeforeEach
    void pulisci() { reset(); }

    @AfterAll
    void cleanup() { reset(); }

    private void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE evento_id IN "
                    + "(SELECT id FROM eventi WHERE is_segnaposto OR nome LIKE :m)")
                    .setParameter("m", MARKER + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM eventi_da_riconciliare WHERE descrizione_norm LIKE :m")
                    .setParameter("m", MARKER + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM eventi WHERE is_segnaposto OR nome LIKE :m")
                    .setParameter("m", MARKER + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE filename = :m")
                    .setParameter("m", MARKER).executeUpdate();
        });
        importLogId = null;
    }
}
