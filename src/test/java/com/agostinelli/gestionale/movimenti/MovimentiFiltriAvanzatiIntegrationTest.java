package com.agostinelli.gestionale.movimenti;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Filtri avanzati movimenti — docs/specs/movimenti-filtri-avanzati.md.
 *
 * Semina 4 movimenti marcati "ZZFILTRO" che differiscono in una dimensione per volta, così ogni
 * asserzione isola un predicato. Tutte le query passano da search=ZZFILTRO per non dipendere
 * dal resto del DB di test.
 *
 *   A: CA (2)   USCITA  1500  IMPORT_BANCA  fin. 2026-03-10  liq. 2026-03-10
 *   B: BPM (1)  ENTRATA  800  MANUALE       fin. 2026-03-20  liq. 2026-03-20
 *   C: CA (2)   USCITA    50  RICORRENTE    fin. 2026-05-01  liq. 2026-05-01
 *   D: NULL     ENTRATA  300  MANUALE       fin. NULL        liq. 2026-09-01  (DA_LIQUIDARE)
 *
 * Tutti hanno data_movimento 2026-03-05 tranne C (2026-05-01): serve a dimostrare che il range
 * si sposta davvero di campo quando cambia dateField.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovimentiFiltriAvanzatiIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";
    static final String BASE = "/api/movimenti?search=ZZFILTRO";

    @Inject EntityManager em;

    private final UUID idA = UUID.randomUUID();
    private final UUID idB = UUID.randomUUID();
    private final UUID idC = UUID.randomUUID();
    private final UUID idD = UUID.randomUUID();

    @BeforeAll
    @Transactional
    void seed() {
        ins(idA, "2026-03-05", "2026-03-10", "2026-03-10", "USCITA",  "1500", 2, "REGISTRATO",   "IMPORT_BANCA", "ZZFILTRO alfa");
        ins(idB, "2026-03-05", "2026-03-20", "2026-03-20", "ENTRATA",  "800", 1, "REGISTRATO",   "MANUALE",      "ZZFILTRO bravo");
        ins(idC, "2026-05-01", "2026-05-01", "2026-05-01", "USCITA",     "50", 2, "REGISTRATO",   "RICORRENTE",   "ZZFILTRO charlie");
        insSenzaBanca(idD);
    }

    @AfterAll
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE 'ZZFILTRO%'").executeUpdate();
    }

    private void ins(UUID id, String dataMov, String dataFin, String dataLiq, String tipo,
                     String importo, int conto, String stato, String fonte, String descr) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (CAST(:id AS uuid), CAST(:dm AS date), CAST(:dm AS date), CAST(:df AS date), CAST(:dl AS date),
                :tipo, CAST(:imp AS numeric), 0, 30, :conto, 1, :stato, :fonte, :descr,
                CAST(:u AS uuid), now())
            """)
            .setParameter("id", id.toString()).setParameter("dm", dataMov)
            .setParameter("df", dataFin).setParameter("dl", dataLiq)
            .setParameter("tipo", tipo).setParameter("imp", importo)
            .setParameter("conto", (short) conto).setParameter("stato", stato)
            .setParameter("fonte", fonte).setParameter("descr", descr)
            .setParameter("u", USER)
            .executeUpdate();
    }

    private void insSenzaBanca(UUID id) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (CAST(:id AS uuid), DATE '2026-03-05', DATE '2026-03-05', NULL, DATE '2026-09-01',
                'ENTRATA', 300, 0, 30, NULL, 1, 'DA_LIQUIDARE', 'MANUALE', 'ZZFILTRO delta',
                CAST(:u AS uuid), now())
            """)
            .setParameter("id", id.toString()).setParameter("u", USER)
            .executeUpdate();
    }

    /** Estrae le descrizioni ZZFILTRO restituite da una query. */
    private io.restassured.response.ValidatableResponse get(String query) {
        return given().when().get(query).then().statusCode(200);
    }

    // ── Invariante: nessun filtro ⇒ tutti i movimenti seminati ──────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void senzaFiltri_tuttiEQuattro() {
        get(BASE).body("content.descrizione", containsInAnyOrder(
                "ZZFILTRO alfa", "ZZFILTRO bravo", "ZZFILTRO charlie", "ZZFILTRO delta"));
    }

    // ── Conto bancario, multi-selezione e sentinella «senza banca» ──────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void conto_singolo_soloQuelConto() {
        get(BASE + "&contoId=2").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO charlie"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void conto_multiSelezione_vaInOr() {
        get(BASE + "&contoId=1&contoId=2").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO bravo", "ZZFILTRO charlie"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void conto_zero_soloSenzaBanca() {
        get(BASE + "&contoId=0").body("content.descrizione", contains("ZZFILTRO delta"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void conto_realePiuSenzaBanca_unioneDeiDue() {
        get(BASE + "&contoId=1&contoId=0").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO bravo", "ZZFILTRO delta"));
    }

    // ── AND fra dimensioni diverse ─────────────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dimensioniDiverse_vannoInAnd() {
        // conto ∈ {CA, BPM} AND tipo = USCITA → solo le uscite del CA
        get(BASE + "&contoId=1&contoId=2&tipo=USCITA").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO charlie"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void fonte_multiSelezione() {
        get(BASE + "&fonte=MANUALE&fonte=RICORRENTE").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO bravo", "ZZFILTRO charlie", "ZZFILTRO delta"));
    }

    // ── Range importo (su valore assoluto) ─────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void importoMin_escludeIPiccoli() {
        get(BASE + "&importoMin=500").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO bravo"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void rangeImporto_estremiInclusi() {
        get(BASE + "&importoMin=300&importoMax=800").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO bravo", "ZZFILTRO delta"));
    }

    // ── dateField: il range cambia davvero campo ───────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dateField_default_usaDataMovimento() {
        // marzo su data_movimento: A, B, D (C è di maggio)
        get(BASE + "&from=2026-03-01&to=2026-03-31").body("content.descrizione",
                containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO bravo", "ZZFILTRO delta"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dateField_finanziaria_escludeIlNonLiquidato() {
        // marzo su data_finanziaria: A (10/03) e B (20/03). D ha data_finanziaria NULL
        // → fuori per costruzione: chi guarda la cassa non vede ciò che non è liquidato.
        get(BASE + "&dateField=FINANZIARIA&from=2026-03-01&to=2026-03-31")
                .body("content.descrizione", containsInAnyOrder("ZZFILTRO alfa", "ZZFILTRO bravo"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dateField_finanziaria_restringeRispettoAMovimento() {
        // 01–15 marzo: su data_movimento prende A, B, D; su data_finanziaria solo A (10/03).
        get(BASE + "&dateField=FINANZIARIA&from=2026-03-01&to=2026-03-15")
                .body("content.descrizione", contains("ZZFILTRO alfa"));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dateField_liquidita_vedeLaScadenzaFutura() {
        // settembre su data_liquidita: solo D, che scade il 01/09 pur essendo di marzo.
        get(BASE + "&dateField=LIQUIDITA&from=2026-09-01&to=2026-09-30")
                .body("content.descrizione", contains("ZZFILTRO delta"));
    }

    // ── Fail fast al trust boundary ────────────────────────────────────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void dateFieldNonValido_400_maiJpqlCostruitaConLInput() {
        given().when().get(BASE + "&dateField=descrizione' OR '1'='1")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void rangeDateInvertito_400() {
        given().when().get(BASE + "&from=2026-06-01&to=2026-01-01")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void rangeImportoInvertito_400() {
        given().when().get(BASE + "&importoMin=900&importoMax=100")
                .then().statusCode(400);
    }

    // ── Invariante: il sommario vede lo STESSO insieme della lista ─────────────

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void sommario_rispecchiaLaListaFiltrata() {
        // stesso filtro su lista e sommario: CA + uscite = alfa (1500) + charlie (50)
        String filtro = "search=ZZFILTRO&contoId=2&tipo=USCITA";

        given().when().get("/api/movimenti?" + filtro)
                .then().statusCode(200)
                .body("totalElements", equalTo(2));

        given().when().get("/api/movimenti/sommario?" + filtro)
                .then().statusCode(200)
                .body("totaleCount", equalTo(2))
                // il JSON serializza gli interi senza decimali: confronto sul valore, non sul tipo
                .body("totaleUscite.toFloat()", equalTo(1550f))
                .body("totaleEntrate.toFloat()", equalTo(0f));
    }

    @Test
    @TestSecurity(user = USER, roles = {"ADMIN"})
    void sommario_accettaDateFieldComeLaLista() {
        String filtro = "search=ZZFILTRO&dateField=FINANZIARIA&from=2026-03-01&to=2026-03-15";

        given().when().get("/api/movimenti?" + filtro)
                .then().statusCode(200).body("totalElements", equalTo(1));

        given().when().get("/api/movimenti/sommario?" + filtro)
                .then().statusCode(200).body("totaleCount", equalTo(1));
    }
}
