package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.RicorrenteParcheggiataDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wizard «Questa rata di cosa?» (docs/specs/wizard-rate-ricorrenti.md, audit import §7.3).
 *
 * <p>Verifica i requisiti che il <b>wizard</b> mostra a schermo, sul corpus reale
 * {@code esempi_dati_storici/}: 11 voci in sei mesi per <b>51.790,09 €</b> — poche decisioni,
 * molto denaro. La logica di match è già coperta da {@code RicorrentiMatchStrutturatoIntegrationTest}
 * (SPEC ricorrenti-match-strutturato, casi R1..R8 su righe costruite a mano); qui si parte dai file
 * veri e si controlla ciò che il wizard promette all'operatore:
 * R1/R2 il «perché» in chiaro, R3 lo scarto che riscrive la rata, R4 lo stato «non hai piani»,
 * R5 l'entrata che non è una rata.
 */
@QuarkusTest
class WizardRateIntegrationTest {

    private static final String USER = "00000000-0000-0000-0000-000000000099";
    private static final UUID TEST_USER = UUID.fromString(USER);
    private static final String TAG = "ZZRATE";

    static final Path STORICI = Path.of("..", "esempi_dati_storici");
    static final String BILLY = "corrispettivi-12.csv";
    static final String BPM = "MovimentiCC_OnLine_10_06_2026_11.56.28.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_06_10_115335.csv";

    /** La rata di maggio del mutuo BPM: è la riga del mock §7.3 dell'audit. */
    private static final BigDecimal RATA_MAGGIO = new BigDecimal("2488.57");
    /** L'erogazione Asconfidi del 12/03: unica ENTRATA della coda. */
    private static final BigDecimal EROGAZIONE = new BigDecimal("38800.00");

    @Inject MovimentoImportService importService;
    @Inject ImportTriageService triageService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(STORICI), "Cartella esempi_dati_storici assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(STORICI.resolve(f)), "Fixture assente: " + f);
        }
    }

    @BeforeEach
    void before() { pulisci(); }

    @AfterEach
    void after() { pulisci(); }

    /**
     * I piani FINANZIAMENTO lasciati in giro rompono ForecastingIntegrationTest: si cancellano
     * sempre, insieme a tutto ciò che l'import ha creato.
     */
    void pulisci() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN "
                    + "('IMPORT_CONGIUNTO','IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE '" + TAG + "%'").executeUpdate();
            em.createNativeQuery("DELETE FROM recurring_expense_installment WHERE piano_id IN "
                    + "(SELECT id FROM recurring_expense_plan WHERE descrizione LIKE '" + TAG + "%')")
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recurring_expense_plan WHERE descrizione LIKE '" + TAG + "%'")
                    .executeUpdate();
        });
    }

    /**
     * R4 — senza piani in archivio il wizard non finge un elenco vuoto: lo dice. Qui si verifica
     * il dato su cui poggia quella schermata — coda piena, nessuna proposta, nessun candidato.
     */
    @Test
    void senzaPiani_laCodaEPienaMaNonCEnessunaProposta() throws Exception {
        importaCongiunto();
        List<RicorrenteParcheggiataDTO> coda = triageService.listRicorrenti("DA_RICONCILIARE", 0, 500).content();

        // 14 dal 24/08/2026, erano 11: le 3 in più sono le rate scritte «PAG.RATE SU FIN.TO»,
        // che V45 ha aggiunto al pattern delle regole 1 e 2 (SKIP_RICORRENTE). Prima finivano a
        // smistamento manuale perché la riga scrive FIN.TO mentre il pattern diceva FINANZIAMENTO:
        // ora entrano in questa coda, che è il posto giusto per una rata senza piano collegato.
        // Vedi docs/analisi/keyword-cleanup-esecuzione-2026-08-24.md.
        assertEquals(14, coda.size(), "il corpus di sei mesi porta 14 voci in coda");
        BigDecimal totale = coda.stream().map(RicorrenteParcheggiataDTO::importo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 56.744,20 = i 51.790,09 dell'audit §7.3 + le 3 rate FIN.TO che V45 fa entrare in coda
        // (2.476,46 + 2.226,65 + 251,00 = 4.954,11).
        assertEquals(0, new BigDecimal("56744.20").compareTo(totale),
                "poche decisioni, molto denaro: 56.744,20 € (audit §7.3 + le 3 rate FIN.TO di V45)");

        for (RicorrenteParcheggiataDTO r : coda) {
            assertNull(r.propostaRataId(), "senza piani non si propone nulla: " + r.descrizione());
            assertTrue(r.candidati() == null || r.candidati().isEmpty(),
                    "senza piani non ci sono candidati: " + r.descrizione());
        }
    }

    /**
     * R1 + R2 — con un piano che corrisponde, la proposta esce e porta il «perché» in chiaro:
     * sono esattamente i campi che il wizard stampa sotto il nome del piano.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void conUnPianoCheCorrisponde_laPropostaPortaIlPercheInChiaro() throws Exception {
        importaCongiunto();
        RicorrenteParcheggiataDTO rata = rataMaggioMutuo();
        creaPianoMutuoBpm(rata);

        RicorrenteParcheggiataDTO dopo = ricarica(rata.id());
        assertNotNull(dopo.propostaRataId(), "una sola rata compatibile ⇒ proposta a un click");

        var proposto = dopo.candidati().stream()
                .filter(c -> c.rataId().equals(dopo.propostaRataId())).findFirst()
                .orElseThrow(() -> new AssertionError("la proposta deve essere fra i candidati"));

        // Il wizard scrive: «perché: <motivo>, e la scadenza cade a N giorni dall'addebito».
        assertNotNull(proposto.motivo(), "senza motivo il wizard mostrerebbe una proposta muta");
        assertFalse(proposto.motivo().isBlank(), "il motivo va detto, non lasciato vuoto");
        assertNotNull(proposto.dataScadenza(), "la scadenza si mostra accanto alla rata");
        assertNotNull(proposto.importoRata(), "l'importo previsto serve a calcolare lo scarto");
        assertTrue(proposto.scartoGiorni() >= 0, "la distanza dalla scadenza è un numero mostrabile");
        assertNotNull(proposto.pianoDescrizione(), "il nome del piano è il titolo del bottone");
    }

    /**
     * R3 — il numero che il wizard mostra PRIMA del click: collegare riscrive la rata del piano
     * sull'importo vero della banca. È l'estratto conto ad avere ragione, non la stima del piano.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void collegare_riscriveLaRataSullImportoRealeDellaBanca() throws Exception {
        importaCongiunto();
        RicorrenteParcheggiataDTO rata = rataMaggioMutuo();
        UUID piano = creaPianoMutuoBpm(rata);
        RicorrenteParcheggiataDTO dopo = ricarica(rata.id());
        var proposto = dopo.candidati().stream()
                .filter(c -> c.rataId().equals(dopo.propostaRataId())).findFirst().orElseThrow();

        // La rata del piano vale 2.400,00; l'addebito reale 2.488,57: lo scarto è ciò che il
        // wizard annuncia come «il piano viene riscritto sull'importo vero».
        assertEquals(0, new BigDecimal("2400.00").compareTo(proposto.importoRata()));
        BigDecimal scartoAtteso = RATA_MAGGIO.subtract(proposto.importoRata());
        assertEquals(0, new BigDecimal("88.57").compareTo(scartoAtteso), "scarto annunciato all'utente");

        triageService.risolviRicorrente(rata.id(),
                new RisolviRicorrenteRequest("COLLEGA", null, piano, proposto.rataId(), null), TEST_USER);

        BigDecimal importoRata = (BigDecimal) em.createNativeQuery(
                "SELECT importo FROM recurring_expense_installment WHERE id = :id")
                .setParameter("id", proposto.rataId()).getSingleResult();
        assertEquals(0, RATA_MAGGIO.compareTo(importoRata),
                "dopo COLLEGA la rata vale quanto dice la banca, non quanto diceva il piano");

        assertEquals("RICONCILIATA", statoDi(rata.id()));
    }

    /** R5 — un'entrata è l'erogazione di un finanziamento, non una rata: COLLEGA non si applica. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void unErogazioneInEntrata_nonSiCollegaAUnaRata() throws Exception {
        importaCongiunto();
        RicorrenteParcheggiataDTO erogazione = triageService.listRicorrenti("DA_RICONCILIARE", 0, 500)
                .content().stream()
                .filter(r -> "ENTRATA".equals(r.tipo())).findFirst()
                .orElseThrow(() -> new AssertionError("l'erogazione Asconfidi da 38.800,00 manca dalla coda"));
        assertEquals(0, EROGAZIONE.compareTo(erogazione.importo()));

        ApiException e = assertThrows(ApiException.class, () -> triageService.risolviRicorrente(
                erogazione.id(),
                new RisolviRicorrenteRequest("COLLEGA", null, UUID.randomUUID(), UUID.randomUUID(), null),
                TEST_USER));
        assertEquals("COLLEGA_SOLO_USCITE", e.getCode());
        assertEquals("DA_RICONCILIARE", statoDi(erogazione.id()), "il rifiuto non consuma la riga");
    }

    /** La riga si risolve una volta sola: la guardia è del server, il wizard non la duplica. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void laStessaRigaNonSiRisolveDueVolte() throws Exception {
        importaCongiunto();
        RicorrenteParcheggiataDTO rata = rataMaggioMutuo();

        triageService.risolviRicorrente(rata.id(),
                new RisolviRicorrenteRequest("IGNORA", null, null, null, "non e' una rata di un nostro piano"), TEST_USER);

        ApiException e = assertThrows(ApiException.class, () -> triageService.risolviRicorrente(
                rata.id(), new RisolviRicorrenteRequest("IGNORA", null, null, null, "non e' una rata di un nostro piano"), TEST_USER));
        assertEquals("RICORRENTE_GIA_RISOLTA", e.getCode());
    }

    // ── helper ──────────────────────────────────────────────────────────────────

    /** La rata di maggio del mutuo BPM (2.488,57 €): la riga del mock §7.3. */
    RicorrenteParcheggiataDTO rataMaggioMutuo() {
        return triageService.listRicorrenti("DA_RICONCILIARE", 0, 500).content().stream()
                .filter(r -> RATA_MAGGIO.compareTo(r.importo()) == 0)
                .findFirst()
                .orElseThrow(() -> new AssertionError("la rata da 2.488,57 € manca dalla coda"));
    }

    /**
     * Piano MENSILE sul conto della riga, col numero di mutuo come riferimento estratto conto: è
     * così che il matcher lo riconosce nella causale. Importo rata volutamente DIVERSO
     * dall'addebito reale, per esercitare lo scarto (R3).
     *
     * <p>{@code giornoDelMese} è vincolato a ≤ 28 dal modello (i mesi corti non hanno il 29/30/31),
     * mentre la rata reale è addebitata il 30: la scadenza cade quindi due giorni prima
     * dell'addebito, dentro la finestra del matcher. È anche il caso realistico — la banca addebita
     * a ridosso della scadenza, non il giorno esatto.
     */
    UUID creaPianoMutuoBpm(RicorrenteParcheggiataDTO riga) {
        String body = "{\"descrizione\":\"" + TAG + " Mutuo BPM\","
                + "\"contoBancarioId\":" + riga.contoBancarioId()
                + ",\"contoCoge\":" + cogeId("20.01.001")
                + ",\"importoRata\":2400.00"
                + ",\"giornoDelMese\":28"
                + ",\"frequenza\":\"MENSILE\",\"numeroRate\":3"
                + ",\"dataInizio\":\"" + riga.dataMovimento().withDayOfMonth(28) + "\""
                + ",\"tipoPiano\":\"FLAT\""
                + ",\"riferimentoEstrattoConto\":\"1273 5796807\"}";
        return UUID.fromString(given().contentType(ContentType.JSON).body(body)
                .when().post("/api/spese-ricorrenti/piani")
                .then().log().ifValidationFails().statusCode(201).extract().path("id"));
    }

    RicorrenteParcheggiataDTO ricarica(UUID id) {
        return triageService.listRicorrenti("DA_RICONCILIARE", 0, 500).content().stream()
                .filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("la riga è uscita dalla coda"));
    }

    String statoDi(UUID id) {
        return (String) em.createNativeQuery(
                "SELECT stato FROM ricorrenti_da_riconciliare WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    Integer cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }

    void importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(STORICI.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(STORICI.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(STORICI.resolve(CA).toFile())) {
            importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
