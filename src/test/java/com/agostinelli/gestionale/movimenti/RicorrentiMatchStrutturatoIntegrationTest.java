package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.RicorrenteParcheggiataDTO;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SPEC docs/specs/ricorrenti-match-strutturato.md — R1..R8.
 *
 * <p>Il riconoscimento di una rata non è più solo lessicale: l'import confronta la riga bancaria
 * con i piani ricorrenti ATTIVI e la parcheggia con piano e rata già proposti. Qui si verifica
 * che (a) la proposta esca giusta o non esca affatto, (b) nessuna riga riconosciuta finisca in
 * contabilità da sola, (c) senza piani il comportamento resti identico a prima.
 *
 * <p>Le descrizioni bancarie sono quelle reali della copia di produzione (SDD Telepass/Enel: nessuna
 * parola chiave della rete attuale). Tutto ciò che il test crea è marcato {@code ZZMATCH} e
 * ripulito dopo ogni test: i piani FINANZIAMENTO lasciati in giro rompono ForecastingIntegrationTest.
 */
@QuarkusTest
class RicorrentiMatchStrutturatoIntegrationTest {

    private static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final UUID TEST_USER = UUID.fromString(USER);
    private static final String TAG = "ZZMATCH";
    /** Le rate dei piani di prova scadono il 15; gli addebiti reali arrivano il 16 (scarto +1). */
    private static final LocalDate SCADENZA = LocalDate.of(2026, 4, 15);
    private static final LocalDate ADDEBITO = LocalDate.of(2026, 4, 16);

    // Righe reali dell'estratto conto: nessuna contiene una parola chiave della rete attuale.
    private static final String DESCR_TELEPASS =
            "SDD A : TELEPASS S.P.A. 9105578 SALDO DOCUM.018062456 DEL 30.06.2026 ADDEBITO SDD NUM";
    private static final String DESCR_ENEL =
            "ADDEBITO DIRETTO SDD - SDD CORE: 2C1071113500569T ENEL ENERGIA";

    @Inject EntityManager em;
    @Inject ImportTriageService triageService;
    @Inject MovimentoImportService importService;

    @AfterEach
    void pulisci() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE '" + TAG + "%'").executeUpdate();
            // le code figlie sono in ON DELETE CASCADE su import_log; i movimenti no (partizionata)
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IN "
                    + "(SELECT id FROM import_log WHERE filename LIKE '" + TAG + "%')").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE filename LIKE '" + TAG + "%'").executeUpdate();
            em.createNativeQuery("DELETE FROM recurring_expense_installment WHERE piano_id IN "
                    + "(SELECT id FROM recurring_expense_plan WHERE descrizione LIKE '" + TAG + "%')")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recurring_expense_plan WHERE descrizione LIKE '" + TAG + "%'")
                    .executeUpdate();
        });
    }

    // ── R1 — una sola rata compatibile → proposta a un click ─────────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r1_unaSolaRataCompatibile_proponePianoERata() {
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID rata = primaRataPending(piano);
        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "194.76", ADDEBITO);

        RicorrenteParcheggiataDTO dto = leggi(riga);
        assertEquals(rata, dto.propostaRataId(), "la rata del piano Telepass deve essere proposta");
        assertEquals(1, dto.candidati().size());
        var c = dto.candidati().get(0);
        assertEquals(piano, c.pianoId());
        assertEquals(1L, c.scartoGiorni(), "addebito il 16, scadenza il 15");
        assertTrue(c.motivo().contains("TELEPASS"),
                "il motivo dice perché: " + c.motivo());
    }

    /** R1-bis — il riferimento in estratto conto riconosce un piano dal nome non pronunciabile. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r1bis_riferimentoEstrattoConto_riconosceIlPianoQuandoIlNomeNonBasta() {
        UUID piano = creaPiano(TAG + " Luce", "600.00", (short) 1, "2C1071113500569T");
        UUID rata = primaRataPending(piano);
        UUID riga = seedRiga(DESCR_ENEL, (short) 1, "1449.04", ADDEBITO);

        RicorrenteParcheggiataDTO dto = leggi(riga);
        assertEquals(rata, dto.propostaRataId(), "il mandato SDD identifica il piano anche se il nome non c'entra");
        assertTrue(dto.candidati().get(0).motivo().contains("2C1071113500569T"));
    }

    // ── R2 — più candidati → nessuna proposta, la lista ordinata ─────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r2_piuCandidatiSenzaSpareggio_nessunaProposta() {
        creaPiano(TAG + " Telepass uno", "180.00", (short) 2, null);
        creaPiano(TAG + " Telepass due", "181.00", (short) 2, null);
        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "500.00", ADDEBITO);

        RicorrenteParcheggiataDTO dto = leggi(riga);
        assertNull(dto.propostaRataId(), "due piani altrettanto plausibili: non si indovina su un movimento di denaro");
        assertEquals(2, dto.candidati().size(), "la UI li mostra entrambi");
        assertTrue(dto.candidati().get(0).scartoImporto().abs()
                        .compareTo(dto.candidati().get(1).scartoImporto().abs()) <= 0,
                "ordinati dal più probabile (scarto d'importo crescente)");
    }

    /** R2-bis — lo spareggio d'importo separa i due piani Confidi reali (stesso conto, stesso giorno). */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r2bis_spareggioImporto_sceglieUnSoloCandidato() {
        UUID pianoA = creaPiano(TAG + " Confidi rata A", "118.54", (short) 1, null);
        creaPiano(TAG + " Confidi rata B", "523.93", (short) 1, null);
        UUID riga = seedRiga("ADDEBITO DIRETTO SDD - SDD B2B : 98181 CONFIDI", (short) 1, "118.54", ADDEBITO);

        RicorrenteParcheggiataDTO dto = leggi(riga);
        assertEquals(2, dto.candidati().size(), "entrambi i piani sono candidati");
        assertEquals(primaRataPending(pianoA), dto.propostaRataId(),
                "solo uno ha l'importo entro il 2%: è quello proposto");
    }

    // ── R3 + R4 — routing dell'import: riconosciuta ⇒ parcheggiata, MAI contabilizzata ──
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r3_r4_rigaSenzaKeyword_conPianoAttivo_finisceInCodaNonInContabilita() throws Exception {
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        long movimentiPrima = movimentiTotali();

        var r = importa(rigaCa("16/04/2026", "-194,76", DESCR_TELEPASS));

        assertEquals(1, codaDelLog(r.importLogId()), "la riga va nella coda ricorrenti (R3)");
        assertEquals(0, movimentiDelLog(r.importLogId()),
                "il riconoscimento non contabilizza nulla: decide l'operatore (R3)");
        assertEquals(movimentiPrima, movimentiTotali(), "nessun movimento creato altrove");

        // R4: la descrizione non contiene nessuna parola chiave della rete attuale
        assertFalse(DESCR_TELEPASS.matches("(?i).*(CANONE|ASSICURAZ|POLIZZA|MUTUO|LEASING|FINANZIAMENTO|BOLLO|ASCONFIDI|\\bRATA\\b).*"),
                "il caso ha senso solo se nessuna keyword la descrive");
        assertNotNull(piano);
    }

    // ── R5 — nessun piano attivo ⇒ comportamento identico a oggi ─────────────────
    @Test
    void r5_senzaPiani_nessunaRegressione() throws Exception {
        // (a) la riga senza keyword torna a essere contabilizzata come prima
        var r1 = importa(rigaCa("16/04/2026", "-194,76", DESCR_TELEPASS));
        assertEquals(0, codaDelLog(r1.importLogId()), "senza piani il match strutturato non fa nulla");
        assertEquals(1, movimentiDelLog(r1.importLogId()), "resta il comportamento di prima");

        // (b) la rete a keyword continua a parcheggiare le rate di piani inesistenti
        var r2 = importa(rigaCa("17/04/2026", "-515,73",
                "SDD A : CREDIT AGRICOLE LEASING ITALIA SRL FT V3 /2026/26183707"));
        assertEquals(1, codaDelLog(r2.importLogId()), "la keyword LEASING resta la rete di sicurezza");
        assertEquals(0, movimentiDelLog(r2.importLogId()));
    }

    // ── R6 — un click sulla proposta: importo reale nel movimento, rata PAID ─────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r6_confermandoLaProposta_movimentoRealeERataPagata() {
        accreditaConto((short) 2);
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "194.76", ADDEBITO);

        RicorrenteParcheggiataDTO dto = leggi(riga);
        UUID rata = dto.propostaRataId();
        assertNotNull(rata, "serve la proposta a un click");

        given().contentType(ContentType.JSON)
                .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
                .when().put("/api/movimenti/import/ricorrenti/" + riga + "/risolvi")
                .then().statusCode(204);

        assertEquals("PAID", statoRata(rata));
        assertEquals(0, new BigDecimal("194.76").compareTo(importoRata(rata)),
                "la rata registra l'addebito reale, non la stima del piano");
        UUID mov = movimentoDiRata(rata);
        assertNotNull(mov);
        assertEquals(0, new BigDecimal("194.76").compareTo(importoMovimento(mov)));
    }

    // ── R7 — piani/rate non collegabili non sono mai candidati ───────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r7_pianoCompletatoERataPagata_nonSonoCandidati() {
        UUID completato = creaPiano(TAG + " Telepass completato", "180.00", (short) 2, null);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE recurring_expense_plan SET stato = 'COMPLETATO' WHERE id = :p")
                .setParameter("p", completato).executeUpdate());

        UUID attivo = creaPiano(TAG + " Telepass attivo", "180.00", (short) 2, null);
        UUID rata = primaRataPending(attivo);
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE recurring_expense_installment SET stato = 'PAID' WHERE id = :r")
                .setParameter("r", rata).executeUpdate());

        // terzo piano sano: la sua rata DEVE restare l'unica proposta. Senza questo controllo
        // il test passerebbe anche con il matcher spento, e non difenderebbe niente.
        UUID sano = creaPiano(TAG + " Telepass buono", "180.00", (short) 2, null);

        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "194.76", ADDEBITO);
        RicorrenteParcheggiataDTO dto = leggi(riga);

        assertEquals(1, dto.candidati().size(),
                "solo il piano attivo con rata PENDING è candidato, erano " + dto.candidati());
        assertEquals(primaRataPending(sano), dto.propostaRataId());
        assertEquals(sano, dto.candidati().get(0).pianoId(),
                "piano COMPLETATO e rata PAID restano fuori");
    }

    /** R7-bis — addebito fuori dalla finestra (2° addebito Enel del mese): candidati vuoti. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r7bis_addebitoFuoriFinestra_nessunaProposta() {
        creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID fuori = seedRiga(DESCR_TELEPASS, (short) 2, "166.16", SCADENZA.plusDays(12));

        RicorrenteParcheggiataDTO dto = leggi(fuori);
        assertNull(dto.propostaRataId(), "a 12 giorni dalla scadenza non si aggancia nulla");
        assertTrue(dto.candidati().isEmpty(), "il piano non avanza mai da solo");

        // controprova: la stessa riga dentro la finestra viene invece proposta — è la finestra
        // che decide, non il matcher spento.
        UUID dentro = seedRiga(DESCR_TELEPASS, (short) 2, "166.16", SCADENZA.plusDays(6));
        assertNotNull(leggi(dentro).propostaRataId(), "a 6 giorni la proposta esce");
    }

    // ── PUT piano: correggere il riferimento fa scattare il riconoscimento ──────
    /**
     * È il motivo per cui il PUT esiste: un piano creato col riferimento sbagliato (o senza) non
     * aggancia niente, e prima si poteva solo cestinare e ricreare. Dopo la correzione la stessa
     * riga, già in coda, riceve la proposta — senza rifare l'import.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void putPiano_correggeIlRiferimento_eLaRigaGiaInCodaRiceveLaProposta() {
        // nome che non compare in banca + riferimento sbagliato ⇒ nessun aggancio
        UUID piano = creaPiano(TAG + " Bolletta luce", "600.00", (short) 1, "MANDATO-SBAGLIATO");
        UUID riga = seedRiga(DESCR_ENEL, (short) 1, "1449.04", ADDEBITO);
        assertNull(leggi(riga).propostaRataId(), "col riferimento sbagliato non si aggancia nulla");

        given().contentType(ContentType.JSON)
                .body("{\"descrizione\":\"" + TAG + " Bolletta luce\",\"contoBancarioId\":1,"
                        + "\"riferimentoEstrattoConto\":\"2C1071113500569T\",\"note\":null}")
                .when().put("/api/spese-ricorrenti/piani/" + piano)
                .then().statusCode(200)
                .body("riferimentoEstrattoConto", equalTo("2C1071113500569T"));

        assertEquals(primaRataPending(piano), leggi(riga).propostaRataId(),
                "corretto il riferimento, la riga già in coda riceve la proposta");
    }

    /** Il PUT non tocca gli importi: le rate restano quelle generate alla creazione. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void putPiano_nonToccaLeRate() {
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID rata = primaRataPending(piano);
        BigDecimal importoPrima = importoRata(rata);
        long ratePrima = contaRate(piano);

        given().contentType(ContentType.JSON)
                .body("{\"descrizione\":\"" + TAG + " Telepass pedaggi\",\"contoBancarioId\":2,"
                        + "\"riferimentoEstrattoConto\":\"TELEPASS S.P.A.\",\"note\":\"rinominato\"}")
                .when().put("/api/spese-ricorrenti/piani/" + piano)
                .then().statusCode(200);

        assertEquals(ratePrima, contaRate(piano), "il numero di rate non cambia");
        assertEquals(0, importoPrima.compareTo(importoRata(rata)), "l'importo della rata non cambia");
    }

    /** Conto bancario inesistente → 400 al boundary, nessuna scrittura. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void putPiano_contoInesistente_400() {
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        given().contentType(ContentType.JSON)
                .body("{\"descrizione\":\"" + TAG + " Telepass\",\"contoBancarioId\":99,"
                        + "\"riferimentoEstrattoConto\":null,\"note\":null}")
                .when().put("/api/spese-ricorrenti/piani/" + piano)
                .then().statusCode(400).body("code", equalTo("CONTO_BANCARIO_NON_TROVATO"));
    }

    /**
     * Collegare REGISTRA un addebito già eseguito dalla banca: non deve chiedere il permesso al
     * saldo. Senza questo, dopo il reset del go-live 3 collegamenti di luglio su 10 fallivano con
     * SALDO_INSUFFICIENTE — mutuo compreso. Qui il conto è a zero di proposito.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collega_nonRichiedeSaldoDisponibile() {
        // nessun accreditaConto(): il conto 2 non ha fondi per una rata da 194,76
        UUID piano = creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "194.76", ADDEBITO);
        UUID rata = leggi(riga).propostaRataId();
        assertNotNull(rata);

        given().contentType(ContentType.JSON)
                .body("{\"azione\":\"COLLEGA\",\"pianoId\":\"" + piano + "\",\"rataId\":\"" + rata + "\"}")
                .when().put("/api/movimenti/import/ricorrenti/" + riga + "/risolvi")
                .then().statusCode(204);

        assertEquals("PAID", statoRata(rata), "l'addebito è un fatto avvenuto: si registra sempre");
        assertNotNull(movimentoDiRata(rata));
    }

    /** Un piano FLAT può stare su un CoGe di COSTO (bolletta, canone): non è un rimborso di debito. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void pianoFlat_accettaCogeDiCosto() {
        String body = "{\"descrizione\":\"" + TAG + " Enel\",\"contoBancarioId\":1,"
                + "\"contoCoge\":" + cogeId("40.05.002") + ",\"importoRata\":600.00,"
                + "\"giornoDelMese\":25,\"frequenza\":\"MENSILE\",\"numeroRate\":12,"
                + "\"dataInizio\":\"" + SCADENZA + "\",\"tipoPiano\":\"FLAT\"}";
        given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().statusCode(201);
    }

    /** Su un FINANZIAMENTO il CoGe resta patrimoniale: la quota capitale rimborsa un debito. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void pianoFinanziamento_rifiutaCogeDiCosto() {
        String body = "{\"descrizione\":\"" + TAG + " Mutuo sbagliato\",\"contoBancarioId\":1,"
                + "\"contoCoge\":" + cogeId("40.05.002") + ",\"importoRata\":600.00,"
                + "\"giornoDelMese\":25,\"frequenza\":\"MENSILE\",\"numeroRate\":12,"
                + "\"dataInizio\":\"" + SCADENZA + "\",\"tipoPiano\":\"FINANZIAMENTO\","
                + "\"importoDebitoIniziale\":10000.00,\"tassoInteresseAnnuo\":3.5,"
                + "\"contoCogeInteressiId\":" + cogeId("60.01.001") + "}";
        given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().statusCode(400).body("code", equalTo("COGE_NON_PASSIVITA"));
    }

    // ── R8 — leggere la coda non tocca la contabilità ────────────────────────────
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r8_ilRiconoscimentoNonScriveNulla() {
        creaPiano(TAG + " Telepass", "180.00", (short) 2, null);
        UUID riga = seedRiga(DESCR_TELEPASS, (short) 2, "194.76", ADDEBITO);
        long movimentiPrima = movimentiTotali();
        Object[] prima = rigaRaw(riga);

        assertNotNull(leggi(riga).propostaRataId());
        assertNotNull(leggi(riga).propostaRataId(), "idempotente: la proposta è calcolata, non consumata");

        assertEquals(movimentiPrima, movimentiTotali(), "nessun movimento creato");
        Object[] dopo = rigaRaw(riga);
        assertEquals(prima[0], dopo[0], "lo stato della riga non cambia");
        assertNull(dopo[1], "recurring_plan_id resta NULL: la proposta non è persistita (I3)");
        assertNull(dopo[2], "movimento_id resta NULL");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    RicorrenteParcheggiataDTO leggi(UUID riga) {
        return triageService.listRicorrenti("DA_RICONCILIARE", 0, 500).content().stream()
                .filter(d -> d.id().equals(riga)).findFirst()
                .orElseThrow(() -> new AssertionError("riga " + riga + " non in coda"));
    }

    /** Piano MENSILE attivo di 3 rate, creato dall'API come lo farebbe l'utente. */
    UUID creaPiano(String descrizione, String importoRata, short conto, String riferimento) {
        String rif = riferimento == null ? "" : ",\"riferimentoEstrattoConto\":\"" + riferimento + "\"";
        String body = "{\"descrizione\":\"" + descrizione + "\",\"contoBancarioId\":" + conto
                + ",\"contoCoge\":" + cogeId("20.01.001") + ",\"importoRata\":" + importoRata
                + ",\"giornoDelMese\":15,\"frequenza\":\"MENSILE\",\"numeroRate\":3,"
                + "\"dataInizio\":\"" + SCADENZA + "\",\"tipoPiano\":\"FLAT\"" + rif + "}";
        return UUID.fromString(given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().log().ifValidationFails().statusCode(201).extract().path("id"));
    }

    UUID seedRiga(String descrizione, short conto, String importo, LocalDate data) {
        UUID log = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("INSERT INTO import_log (id, fonte, stato, filename) "
                    + "VALUES (:id, 'IMPORT_BANCA', 'COMPLETATO', :f)")
                    .setParameter("id", log).setParameter("f", TAG + "-seed.csv").executeUpdate();
            em.createNativeQuery(
                    "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, importo, "
                    + "tipo, conto_bancario_id, descrizione_norm, tipo_presunto, stato, raw_data) "
                    + "VALUES (:id, :log, 'IMPORT_BANCA', :data, CAST(:imp AS numeric), 'USCITA', :conto, :descr, "
                    + "'ALTRO', 'DA_RICONCILIARE', CAST('{}' AS jsonb))")
                    .setParameter("id", id).setParameter("log", log).setParameter("data", data)
                    .setParameter("imp", importo).setParameter("conto", conto)
                    .setParameter("descr", descrizione).executeUpdate();
        });
        return id;
    }

    /** Import congiunto di UNA riga CA (Billy e BPM vuoti): esercita il gate di routing vero. */
    com.agostinelli.gestionale.movimenti.dto.EtlImportResponse importa(String rigaCa) throws Exception {
        String billy = "Elaborazione corrispettivi\ndal 01-04-2026 al 30-04-2026;\n"
                + "Data;Importo;Numero (Pagamento);Carne;Agriturismo;Prodotti trasformati;Servizi;Ortofrutta;"
                + "Iva Imponibile;Iva Importo;Contanti;Elettronico;Non riscosso servizi;Non riscosso beni;"
                + "Non riscosso fattura;Buoni pasto;Omaggi\n";
        String bpm = "\"Data contabile\";\"Data valuta\";\"Importo\";\"Divisa\";\"Causale\";\"Descrizione\";\"Canale\"\n";
        String ca = "Data Operazione,Data valuta,Causale,Descrizione,Entrate,Uscite,Divisa\n" + rigaCa;
        try (InputStream b = stream(billy); InputStream p = stream(bpm); InputStream c = stream(ca)) {
            return importService.importCongiunto(b, p, c,
                    TAG + "-billy.csv", TAG + "-bpm.csv", TAG + "-ca.csv", TEST_USER);
        }
    }

    private static String rigaCa(String data, String uscita, String descrizione) {
        return "\"" + data + "," + data + ",PAGAMENTO UTENZE," + descrizione + ",,\"\""
                + uscita + "\"\",EUR\"\n";
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    void accreditaConto(short conto) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "INSERT INTO movimenti (id, tipo, importo_lordo, data_movimento, data_competenza, "
                + "data_finanziaria, conto_bancario_id, conto_coge_id, business_unit_id, descrizione, "
                + "stato, fonte, importo_commissione, created_by) "
                + "VALUES (:id, 'ENTRATA', 10000.00, :d, :d, :d, :conto, :coge, 2, :descr, 'REGISTRATO', "
                + "'MANUALE', 0, :uid)")
                .setParameter("id", UUID.randomUUID()).setParameter("d", SCADENZA)
                .setParameter("conto", conto).setParameter("coge", cogeId("30.01.001"))
                .setParameter("descr", TAG + " fondi").setParameter("uid", TEST_USER)
                .executeUpdate());
    }

    UUID primaRataPending(UUID piano) {
        Object v = em.createNativeQuery(
                "SELECT id FROM recurring_expense_installment WHERE piano_id = :p AND stato = 'PENDING' "
                + "ORDER BY numero_rata LIMIT 1").setParameter("p", piano).getSingleResult();
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    Object[] rigaRaw(UUID riga) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT stato, recurring_plan_id, movimento_id FROM ricorrenti_da_riconciliare WHERE id = :id")
                .setParameter("id", riga).getResultList();
        return rows.get(0);
    }

    long codaDelLog(UUID log) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM ricorrenti_da_riconciliare WHERE import_log_id = :l")
                .setParameter("l", log).getSingleResult()).longValue();
    }

    long movimentiDelLog(UUID log) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM movimenti WHERE fonte_importazione_id = :l")
                .setParameter("l", log).getSingleResult()).longValue();
    }

    long movimentiTotali() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM movimenti").getSingleResult()).longValue();
    }

    String statoRata(UUID rata) {
        return (String) em.createNativeQuery("SELECT stato FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
    }

    BigDecimal importoRata(UUID rata) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT importo FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
    }

    UUID movimentoDiRata(UUID rata) {
        Object v = em.createNativeQuery(
                "SELECT movimento_id FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", rata).getSingleResult();
        return v == null ? null : (v instanceof UUID u ? u : UUID.fromString(v.toString()));
    }

    BigDecimal importoMovimento(UUID mov) {
        return (BigDecimal) em.createNativeQuery("SELECT importo_lordo FROM movimenti WHERE id = :id")
                .setParameter("id", mov).getSingleResult();
    }

    long contaRate(UUID piano) {
        return ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM recurring_expense_installment WHERE piano_id = :p")
                .setParameter("p", piano).getSingleResult()).longValue();
    }

    int cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }
}
