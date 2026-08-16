package com.agostinelli.gestionale.contanti;

import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Modulo «Contanti» (docs/specs/modulo-contanti.md): R1–R11 e gli invarianti I1–I5.
 *
 * <p>Ogni test parte dal saldo <b>letto adesso</b> e verifica un delta, non un valore assoluto:
 * il DB di test è una copia della produzione e il valore assoluto non è un fatto stabile.
 *
 * <p>Il conto CASSA viene aperto al 2020-01-01 in {@code @BeforeAll} e riportato a
 * {@code (0, NULL)} in {@code @AfterAll}, esattamente come fa
 * {@code ContiSaldoInizialeIntegrationTest} — altrimenti l'ordine fra le due classi deciderebbe
 * se le date dei test cadono dentro o fuori dal saldo.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContantiIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    static final UUID USER_ID = UUID.fromString(USER);

    @Inject EntityManager em;
    @Inject MovimentoImportService importService;

    private short cassaId;
    private short bancaId;
    private String bancaNome;

    @BeforeAll
    void setup() {
        tx(() -> {
            em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = 0, "
                    + "data_saldo_iniziale = DATE '2020-01-01' WHERE tipo = 'CASSA'").executeUpdate();
            return null;
        });
        pulisci();
        cassaId = (short) num("SELECT id FROM conti_bancari WHERE tipo = 'CASSA' AND is_active = true ORDER BY id LIMIT 1");
        bancaId = (short) num("SELECT id FROM conti_bancari WHERE tipo <> 'CASSA' AND is_active = true ORDER BY id LIMIT 1");
        bancaNome = str("SELECT nome FROM conti_bancari WHERE id = " + bancaId);
    }

    @AfterAll
    void teardown() {
        pulisci();
        tx(() -> {
            em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = 0, data_saldo_iniziale = NULL "
                    + "WHERE tipo = 'CASSA'").executeUpdate();
            return null;
        });
    }

    /** Tutto ciò che questi test scrivono sta sulla cassa o è un import: si cancella per intero. */
    void pulisci() {
        tx(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE created_by = CAST(:u AS uuid) "
                    + "AND conto_bancario_id = (SELECT id FROM conti_bancari WHERE tipo = 'CASSA' ORDER BY id LIMIT 1)")
                    .setParameter("u", USER).executeUpdate();
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IN "
                    + "(SELECT id FROM import_log WHERE filename LIKE '%ZZCONT%')").executeUpdate();
            em.createNativeQuery("DELETE FROM import_scartati WHERE import_log_id IN "
                    + "(SELECT id FROM import_log WHERE filename LIKE '%ZZCONT%')").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE filename LIKE '%ZZCONT%'").executeUpdate();
            return null;
        });
    }

    // ── R1 — il saldo è live, non dalla materialized view ───────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r1_saldoVedeLaScritturaAppenaFatta_senzaRefreshDellaMv() {
        BigDecimal prima = saldoLive();

        prelievo("50.00", oggi());   // nessun fn_refresh_all_mv() nel mezzo

        assertEquals(0, prima.add(new BigDecimal("50.00")).compareTo(saldoLive()),
                "il saldo deve cambiare subito: la MV è asincrona, la formula live no");
    }

    // ── R2 + I2 + I4 — una gamba sola, coi campi imposti dal server ─────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r2_i2_i4_prelievo_scriveUnaSolaGambaSullaCassaColFormatoAtteso() {
        String id = prelievo("120.00", "2026-07-10");

        Object[] r = riga(id);
        assertEquals(cassaId, ((Number) r[0]).shortValue(), "I2: la gamba sta sulla cassa, sempre");
        assertEquals("CONTANTI", r[1]);
        assertEquals("10.03.004", r[2]);
        assertEquals("ENTRATA", r[3]);
        assertEquals("REGISTRATO", r[4]);
        assertEquals("MANUALE", r[5]);
        assertNull(r[6], "I4: nessun movimento del modulo ha evento_id");
        assertEquals((short) 5, ((Number) r[7]).shortValue(), "prelievo/deposito → BU Overhead");
        // §1: data finanziaria = data movimento = data competenza (il contante è liquido subito)
        assertEquals("2026-07-10", r[8].toString());
        assertEquals("2026-07-10", r[9].toString());
        assertEquals("2026-07-10", r[10].toString());
        // Formato non cosmetico: è l'unico aggancio quando la gamba bancaria arriva dall'import
        assertEquals("Prelievo contanti da " + bancaNome, r[11]);

        assertEquals(0, num("SELECT count(*) FROM movimenti WHERE descrizione = 'Prelievo contanti da "
                        + bancaNome.replace("'", "''") + "' AND conto_bancario_id <> " + cassaId),
                "I2: il modulo non scrive mai la gamba bancaria");
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r2_deposito_scriveUnUscitaDiCassaColFormatoAtteso() {
        prelievo("300.00", "2026-07-11");
        String id = deposito("100.00", "2026-07-11");

        Object[] r = riga(id);
        assertEquals(cassaId, ((Number) r[0]).shortValue());
        assertEquals("10.03.003", r[2]);
        assertEquals("USCITA", r[3]);
        assertEquals("Versamento contanti su " + bancaNome, r[11]);
    }

    // ── R3 + I1 — dal cassetto non esce denaro che non c'è ──────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r3_i1_depositoOltreIlSaldo_409_eNessunMovimentoPersistito() {
        BigDecimal prima = saldoLive();

        given().contentType(ContentType.JSON)
            .body("""
                {"importo":99999999.00,"data":"%s","contoBancarioId":%d}
                """.formatted(oggi(), bancaId))
            .when().post("/api/contanti/deposito")
            .then().statusCode(409).body("code", equalTo("FONDI_INSUFFICIENTI"));

        assertEquals(0, num("SELECT count(*) FROM movimenti WHERE importo_lordo = 99999999.00"),
                "fail closed: il 409 non deve lasciare nessuna riga a libro");
        assertEquals(0, prima.compareTo(saldoLive()));
    }

    // ── R4 + R5 — i conti vietati, e il tipo giusto ─────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r4_incassoSuMastroRiservatoEventi_409() {
        int coge = num("SELECT id FROM piano_dei_conti_coge WHERE codice = '30.02.001'");
        given().contentType(ContentType.JSON).body(bodyIncasso("10.00", coge))
            .when().post("/api/contanti/incasso")
            .then().statusCode(409).body("code", equalTo("COGE_RISERVATO_EVENTI"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r4_spesaSulTransitorioDellImport_409() {
        prelievo("50.00", oggi());
        int coge = num("SELECT id FROM piano_dei_conti_coge WHERE codice = '49.99.999'");
        given().contentType(ContentType.JSON).body(bodySpesa("10.00", coge))
            .when().post("/api/contanti/spesa")
            .then().statusCode(409).body("code", equalTo("COGE_TRANSITORIO"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r4_incassoSuUnContoDiCosto_400_cogeTipoErrato() {
        given().contentType(ContentType.JSON).body(bodyIncasso("10.00", cogeCosto()))
            .when().post("/api/contanti/incasso")
            .then().statusCode(400).body("code", equalTo("COGE_TIPO_ERRATO"));
    }

    /**
     * R5/I4 — {@code eventoId} non esiste nel DTO: anche mandandolo, il movimento nasce senza.
     * I ricavi evento nascono solo da {@code EventiService.registraPagamento}.
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r5_incassoConEventoIdNelBody_loIgnora() {
        String id = given().contentType(ContentType.JSON)
            .body("""
                {"importo":33.00,"data":"%s","contoCoge":%d,"businessUnitId":1,
                 "descrizione":"ZZCONT incasso con evento forzato",
                 "eventoId":"11111111-1111-1111-1111-111111111111"}
                """.formatted(oggi(), cogeRicavo()))
            .when().post("/api/contanti/incasso")
            .then().statusCode(201).body("id", notNullValue())
            .extract().path("id");

        assertNull(riga(id)[6], "I4: evento_id resta null");
    }

    // ── R6 — conta cassa ───────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r6_contatoUgualeAlTeorico_nessunMovimento() {
        BigDecimal saldo = saldoLive();

        given().contentType(ContentType.JSON)
            .body("""
                {"contato":%s,"data":"%s","motivo":"controllo di fine giornata"}
                """.formatted(saldo.toPlainString(), oggi()))
            .when().post("/api/contanti/conta")
            .then().statusCode(200)
                .body("creato", equalTo(false))
                .body("movimento", org.hamcrest.Matchers.nullValue());

        assertEquals(0, saldo.compareTo(saldoLive()), "delta zero ⇒ niente movimento da 0 €");
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r6_eccedenzaEAmmanco_portanoIlSaldoSulContato() {
        prelievo("200.00", oggi());
        BigDecimal saldo = saldoLive();

        BigDecimal piu = saldo.add(new BigDecimal("15.00"));
        String id = conta(piu, "mancia trovata nel cassetto");
        assertEquals("ENTRATA", riga(id)[3]);
        assertEquals("10.03.005", riga(id)[2]);
        assertEquals("Rettifica di cassa: mancia trovata nel cassetto", riga(id)[11]);
        assertEquals(0, piu.compareTo(saldoLive()));

        BigDecimal meno = piu.subtract(new BigDecimal("40.00"));
        String id2 = conta(meno, "resto sbagliato");
        assertEquals("USCITA", riga(id2)[3]);
        assertEquals(0, meno.compareTo(saldoLive()));
    }

    // ── R8 — il deposito registrato a mano non viene duplicato dall'import ──────────

    /**
     * Un versamento allo sportello BPM (causale 78A) genera dall'import la contropartita di cassa.
     * Se il titolare l'ha già registrata dal modulo, il cassetto si scaricherebbe due volte: il
     * dedup per {@code riferimento_esterno} non copre il caso (la riga manuale non ha il rif banca).
     */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r8_depositoManualeGiaALibro_lImportNonCreaLaContropartita() throws Exception {
        prelievo("500.00", "2026-07-10");
        deposito("200.00", "2026-07-10");

        importa78A("12/07/2026", "200,00");

        assertEquals(1, num("SELECT count(*) FROM movimenti m "
                        + "JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id "
                        + "WHERE m.conto_bancario_id = " + cassaId + " AND m.tipo = 'USCITA' "
                        + "AND m.stato <> 'ANNULLATO' AND pc.codice = '10.03.003' "
                        + "AND m.importo_lordo = 200.00"),
                "una sola uscita di cassa da 200 €: quella registrata a mano");
    }

    /** Controprova: senza la riga manuale la contropartita si crea come sempre. */
    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void r8_senzaDepositoManuale_lImportCreaLaContropartita() throws Exception {
        importa78A("20/07/2026", "310,00");

        assertEquals(1, num("SELECT count(*) FROM movimenti m "
                        + "JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id "
                        + "WHERE m.conto_bancario_id = " + cassaId + " AND m.tipo = 'USCITA' "
                        + "AND pc.codice = '10.03.003' AND m.importo_lordo = 310.00"),
                "senza gamba manuale la contropartita dell'import resta necessaria");
    }

    // ── R11 + I5 — il vecchio modulo Cassa non esiste più ──────────────────────────

    @Test
    void r11_i5_nessunSecondoLibroMastro() {
        assertEquals(0, num("SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_name IN ('cassa_movimenti','lk_tipi_cassa_mov')"),
                "V35 deve aver droppato le tabelle del modulo morto: il saldo cassa ha una fonte sola");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private static String oggi() { return LocalDate.now().toString(); }

    private BigDecimal saldoLive() {
        return new BigDecimal(given().when().get("/api/contanti/saldo")
                .then().statusCode(200).extract().jsonPath().getString("saldo"));
    }

    private String prelievo(String importo, String data) {
        return given().contentType(ContentType.JSON)
            .body("""
                {"importo":%s,"data":"%s","contoBancarioId":%d}
                """.formatted(importo, data, bancaId))
            .when().post("/api/contanti/prelievo")
            .then().statusCode(201).extract().path("id");
    }

    private String deposito(String importo, String data) {
        return given().contentType(ContentType.JSON)
            .body("""
                {"importo":%s,"data":"%s","contoBancarioId":%d}
                """.formatted(importo, data, bancaId))
            .when().post("/api/contanti/deposito")
            .then().statusCode(201).extract().path("id");
    }

    private String conta(BigDecimal contato, String motivo) {
        return given().contentType(ContentType.JSON)
            .body("""
                {"contato":%s,"data":"%s","motivo":"%s"}
                """.formatted(contato.toPlainString(), oggi(), motivo))
            .when().post("/api/contanti/conta")
            .then().statusCode(200).body("creato", equalTo(true))
            .extract().path("movimento.id");
    }

    private String bodyIncasso(String importo, int coge) {
        return """
            {"importo":%s,"data":"%s","contoCoge":%d,"businessUnitId":1,"descrizione":"ZZCONT incasso"}
            """.formatted(importo, oggi(), coge);
    }

    private String bodySpesa(String importo, int coge) {
        return """
            {"importo":%s,"data":"%s","contoCoge":%d,"businessUnitId":1,"descrizione":"ZZCONT spesa"}
            """.formatted(importo, oggi(), coge);
    }

    private int cogeRicavo() {
        return num("SELECT id FROM piano_dei_conti_coge WHERE tipo = 'RICAVO' AND is_active = true "
                + "AND codice NOT LIKE '30.02.%' AND codice <> '39.99.999' ORDER BY codice LIMIT 1");
    }

    private int cogeCosto() {
        return num("SELECT id FROM piano_dei_conti_coge WHERE tipo = 'COSTO' AND is_active = true "
                + "AND codice <> '49.99.999' ORDER BY codice LIMIT 1");
    }

    /** Import congiunto di UNA riga BPM con causale 78A (Billy e CA vuoti). */
    private void importa78A(String data, String importo) throws Exception {
        String billy = "Elaborazione corrispettivi\ndal 01-07-2026 al 31-07-2026;\n"
                + "Data;Importo;Numero (Pagamento);Carne;Agriturismo;Prodotti trasformati;Servizi;Ortofrutta;"
                + "Iva Imponibile;Iva Importo;Contanti;Elettronico;Non riscosso servizi;Non riscosso beni;"
                + "Non riscosso fattura;Buoni pasto;Omaggi\n";
        String bpm = "\"Data contabile\";\"Data valuta\";\"Importo\";\"Divisa\";\"Causale\";\"Descrizione\";\"Canale\"\n"
                + "\"" + data + "\";\"" + data + "\";\"" + importo + "\";\"EUR\";\"78A\";"
                + "\"VERSAMENTO CONTANTE SPORTELLO AUTOMATICO\";\"ATM\"\n";
        String ca = "Data Operazione,Data valuta,Causale,Descrizione,Entrate,Uscite,Divisa\n";
        try (InputStream b = stream(billy); InputStream p = stream(bpm); InputStream c = stream(ca)) {
            importService.importCongiunto(b, p, c,
                    "ZZCONT-billy.csv", "ZZCONT-bpm.csv", "ZZCONT-ca.csv", USER_ID);
        }
    }

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * I campi che la spec impone (§1), in un colpo solo:
     * conto · metodo · coge · tipo · stato · fonte · evento · bu · 3 date · descrizione.
     */
    private Object[] riga(String id) {
        return tx(() -> (Object[]) em.createNativeQuery("""
                SELECT m.conto_bancario_id, mp.codice, pc.codice, m.tipo, m.stato, m.fonte,
                       m.evento_id, m.business_unit_id,
                       m.data_movimento, m.data_finanziaria, m.data_competenza, m.descrizione
                FROM movimenti m
                JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id
                JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id
                WHERE m.id = CAST(:id AS uuid)
                """).setParameter("id", id).getSingleResult());
    }

    private int num(String sql) {
        return tx(() -> ((Number) em.createNativeQuery(sql).getSingleResult()).intValue());
    }

    private String str(String sql) {
        return tx(() -> (String) em.createNativeQuery(sql).getSingleResult());
    }

    private static <T> T tx(java.util.function.Supplier<T> s) {
        try {
            return QuarkusTransaction.requiringNew().call(s::get);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
