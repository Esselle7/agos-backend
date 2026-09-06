package com.agostinelli.gestionale.reporting;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.dto.ScadenzaDTO;
import com.agostinelli.gestionale.reporting.dto.ScadenzeImminentiDTO;
import com.agostinelli.gestionale.reporting.dto.UscitaDaLiquidareDTO;
import com.agostinelli.gestionale.reporting.service.DashboardService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo Scadenzario non conta due volte lo stesso euro.
 *
 * <p><b>Il difetto misurato (05/09/2026).</b> La colonna «Eventi» mostra il residuo dell'evento
 * ({@code preventivato − incassato}); la colonna «Incassi da ricevere» pesca i movimenti
 * {@code ENTRATA / DA_LIQUIDARE / data_finanziaria IS NULL}. Ma la riga di competenza creata da
 * {@code EventiService#creaRigaDiCompetenza} <i>è</i> esattamente quel residuo con quella forma:
 * lo stesso credito compariva in entrambe le colonne, con la stessa cifra e — nel calendario —
 * nella stessa cella ({@code data_liquidita = data_evento}).
 *
 * <p><b>La regola difesa qui.</b> Il credito verso il cliente di un evento vive nella colonna
 * Eventi; «Incassi da ricevere» tiene solo i crediti che non hanno un'altra pagina che li
 * racconta ({@code evento_id IS NULL}). La riga di competenza resta a DB — serve al conto
 * economico (SPEC {@code docs/specs/competenza-ricavo-evento.md}) — semplicemente non si mostra
 * due volte.
 *
 * <p><b>Secondo invariante.</b> Il residuo della colonna Eventi si legge dai MOVIMENTI, non dalla
 * colonna denormalizzata {@code eventi.importo_incassato}, che va stale dopo un annullamento
 * (nessun trigger dal V20 — vedi {@code EventiService#incassatoDaiMovimenti}).
 *
 * <p>Richiede PostgreSQL su localhost:5432/agosdb_test (test profile).
 */
@QuarkusTest
class ScadenzarioSenzaDoppioniIntegrationTest {

    private static final String TEST_USER_UUID = "00000000-0000-0000-0000-000000000099";
    /** Prefisso di tutte le fixture: è anche la chiave della pulizia in @AfterEach. */
    private static final String PFX = "SC4 ";

    /** L'anno intero: è il range che lo Scadenzario usa col periodo YTD. */
    private static final LocalDate DAL = LocalDate.of(2026, 1, 1);
    private static final LocalDate AL  = LocalDate.of(2026, 12, 31);

    @Inject EntityManager    em;
    @Inject DashboardService dashboard;
    @Inject MovimentiService movimentiService;

    private static Integer metodoPagamentoId;
    private static Short   contoBancarioId;
    private static Integer contoCoge;

    @BeforeEach
    @Transactional
    void resolveIds() {
        if (metodoPagamentoId == null) {
            metodoPagamentoId = ((Number) em.createNativeQuery(
                    "SELECT id FROM metodi_pagamento WHERE codice = 'BONIFICO'").getSingleResult()).intValue();
        }
        if (contoBancarioId == null) {
            contoBancarioId = ((Number) em.createNativeQuery(
                    "SELECT id FROM conti_bancari LIMIT 1").getSingleResult()).shortValue();
        }
        if (contoCoge == null) {
            contoCoge = ((Number) em.createNativeQuery(
                    "SELECT id FROM piano_dei_conti_coge LIMIT 1").getSingleResult()).intValue();
        }
    }

    /**
     * Le fixture incassano su un conto bancario vero: lasciate a terra sposterebbero i saldi letti
     * da TermometroLuglioIntegrationTest, che gira sullo STESSO agosdb_test sommando tutti i
     * movimenti del conto. Stessa cura di {@code EventiIntegrationTest#ripulisciFixtureFase4}.
     */
    @AfterEach
    void ripulisciFixture() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE evento_id IN "
                    + "(SELECT id FROM eventi WHERE nome LIKE '" + PFX + "%')").executeUpdate();
            em.createNativeQuery("DELETE FROM movimenti WHERE descrizione LIKE '" + PFX + "%'").executeUpdate();
            em.createNativeQuery("DELETE FROM eventi WHERE nome LIKE '" + PFX + "%'").executeUpdate();
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // R1 — la riga di competenza non finisce fra gli «Incassi da ricevere»
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Scenario e conti attesi, calcolati a mano prima di guardare il risultato:
     * <pre>
     *   A  evento celebrato ieri, preventivato 2.000, caparra 500  → residuo 1.500
     *      (dentro il perimetro Fase 4 ⇒ nasce la riga di competenza da 1.500)
     *   B  evento futuro (15/12), preventivato 1.000, caparra 200  → residuo   800
     *      (fuori perimetro ⇒ nessuna riga di competenza)
     *   C  credito non-evento (affitto), 750, senza evento_id      → credito    750
     *
     *   Credito vero verso terzi = 1.500 + 800 + 750 = 3.050
     *   Prima del fix: eventi 2.300 + incassi 2.250 = 4.550  (1.500 contati due volte)
     * </pre>
     */
    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void ilCreditoDiUnEventoCompareUnaVoltaSola() {
        String a = eventoConfermato("SC4 Celebrato", "2000");
        dataEventoAIeri(a);
        pagamento(a, "CAPARRA", "500.00");

        String b = eventoConfermato("SC4 Futuro", "1000");
        pagamento(b, "CAPARRA", "200.00");

        creditoNonEvento("SC4 Affitto sala", new BigDecimal("750.00"));

        // Precondizione: la riga di competenza di A esiste davvero, altrimenti il test
        // dimostrerebbe l'assenza del doppione solo perché manca il dato che lo produce.
        assertEquals(1, righeDiCompetenza(a), "precondizione: A deve avere la sua riga di competenza");
        assertEquals(0, righeDiCompetenza(b), "precondizione: un evento futuro non matura nulla");

        ScadenzeImminentiDTO d = dashboard.getScadenzeImminenti(DAL, AL);

        // ── colonna Eventi: entrambi gli eventi, col loro residuo ──
        assertEquals(0, new BigDecimal("1500.00").compareTo(residuoEvento(d, "SC4 Celebrato")),
                "A: 2.000 preventivati - 500 incassati");
        assertEquals(0, new BigDecimal("800.00").compareTo(residuoEvento(d, "SC4 Futuro")),
                "B: 1.000 preventivati - 200 incassati");

        // ── colonna Incassi da ricevere: solo il credito non-evento ──
        List<UscitaDaLiquidareDTO> entrate = mie(d.entrateDaRicevere());
        assertEquals(1, entrate.size(),
                "gli incassi da ricevere devono contenere il solo credito non-evento, trovati: " + entrate);
        assertEquals("SC4 Affitto sala", entrate.get(0).descrizione());
        assertEquals(0, new BigDecimal("750.00").compareTo(entrate.get(0).importo()));

        // ── nessun euro contato due volte ──
        BigDecimal totale = totaleEventi(d).add(somma(entrate));
        assertEquals(0, new BigDecimal("3050.00").compareTo(totale),
                "eventi (1.500 + 800) + credito non-evento (750): col doppione farebbe 4.550");
    }

    /**
     * Il verso opposto della guardia: il fix non deve svuotare la colonna. Un credito che non
     * appartiene a nessun evento resta un incasso da ricevere, sempre.
     */
    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void ilCreditoNonEventoRestaFraGliIncassiDaRicevere() {
        creditoNonEvento("SC4 Fattura attiva", new BigDecimal("1234.56"));

        List<UscitaDaLiquidareDTO> entrate = mie(dashboard.getScadenzeImminenti(DAL, AL).entrateDaRicevere());

        assertEquals(1, entrate.size(), "il credito senza evento non deve sparire");
        assertEquals(0, new BigDecimal("1234.56").compareTo(entrate.get(0).importo()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // R2 — il residuo dell'evento si legge dai movimenti, non dalla colonna stale
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Controesempio già misurato in produzione (21/08/2026, evento «Elena molteni»): annullato
     * l'incasso, {@code eventi.importo_incassato} continua a dichiararlo — non c'è più nessun
     * trigger che la riallinei (rimosso in V20). Lo Scadenzario che legge quella colonna mostra
     * un residuo più piccolo del vero, cioè un credito che non chiede indietro tutti i suoi soldi.
     *
     * <pre>
     *   preventivato 1.000, caparra 400 poi ANNULLATA
     *   colonna denormalizzata: 400  →  residuo dichiarato   600   (sbagliato)
     *   movimenti vivi:           0  →  residuo vero       1.000
     * </pre>
     */
    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void residuoEventoLettoDaiMovimenti_nonDallaColonnaStale() {
        String id = eventoConfermato("SC4 Stale", "1000");
        dataEventoAIeri(id);
        String movId = pagamento(id, "CAPARRA", "400.00");

        given().when().delete("/api/movimenti/" + movId).then().statusCode(204);

        assertEquals(0, new BigDecimal("400.00").compareTo(incassatoDenormalizzato(id)),
                "precondizione: la colonna resta stale dopo l'annullamento (nessun trigger dal V20)");

        assertEquals(0, new BigDecimal("1000.00").compareTo(
                        residuoEvento(dashboard.getScadenzeImminenti(DAL, AL), "SC4 Stale")),
                "annullato l'unico incasso, l'evento deve tornare a chiedere tutti i 1.000");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // R3 — tolto il doppione, il credito non deve sparire quando cambia il periodo
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Il rovescio di R1. Da quando gli «Incassi da ricevere» non mostrano piu' il credito degli
     * eventi, la colonna Eventi e' l'UNICA che lo racconta — ma era filtrata per periodo, mentre
     * la Query 4 non lo era: scegliendo un periodo che non copre la data dell'evento il credito
     * spariva dalla pagina invece di essere contato una volta sola.
     *
     * <p>CONTROESEMPIO MISURATO il 06/09/2026 su copia di produzione: con {@code period=MTD} — che
     * e' anche il {@code @DefaultValue} dell'endpoint — sparivano <b>21.016,00 €</b> su 18 eventi
     * celebrati a luglio/agosto. Decisione dell'utente del 06/09/2026: il credito di un evento gia'
     * celebrato si mostra SEMPRE.
     *
     * <p>Il backlog no: un evento FUTURO fuori finestra non e' credito, e' lavoro non ancora
     * svolto, e resta legato al periodo. Il test verifica entrambi i versi.
     */
    @Test
    @TestSecurity(user = TEST_USER_UUID, roles = {"ADMIN"})
    void ilCreditoDiUnEventoCelebratoSiVedeAncheFuoriDalPeriodo() {
        String celebrato = eventoConfermato("SC4 Fuori periodo", "2000");
        dataEventoAIeri(celebrato);
        pagamento(celebrato, "CAPARRA", "500.00");

        // Evento futuro (2026-12-15 per costruzione dell'helper), lasciato dov'e'.
        String futuro = eventoConfermato("SC4 Backlog futuro", "1000");

        // Finestra che NON contiene ne' ieri ne' dicembre: e' il caso «period=MTD» del difetto.
        LocalDate dal = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        ScadenzeImminentiDTO d = dashboard.getScadenzeImminenti(dal, dal.plusDays(20));

        assertEquals(0, new BigDecimal("1500.00").compareTo(residuoEvento(d, "SC4 Fuori periodo")),
                "il credito di un evento gia' celebrato non deve sparire cambiando periodo");
        assertTrue(d.eventi().stream().noneMatch(e -> "SC4 Backlog futuro".equals(e.descrizione())),
                "un evento futuro fuori finestra e' backlog, non credito: resta legato al periodo");

        // E non e' tornato il doppione che R1 ha chiuso: il credito sta in UNA colonna sola.
        assertTrue(mie(d.entrateDaRicevere()).isEmpty(),
                "il credito dell'evento non deve rientrare fra gli incassi da ricevere, trovati: "
                + mie(d.entrateDaRicevere()));

        // Saldato l'evento, sparisce da solo: il ramo «sempre visibile» filtra sul residuo, non
        // sulla data. Senza questo, un evento chiuso resterebbe in pagina per sempre.
        pagamento(celebrato, "SALDO", "1500.00");
        ScadenzeImminentiDTO dopo = dashboard.getScadenzeImminenti(dal, dal.plusDays(20));
        assertTrue(dopo.eventi().stream().noneMatch(e -> "SC4 Fuori periodo".equals(e.descrizione())),
                "saldato il residuo, l'evento non ha piu' credito da mostrare fuori periodo");
        assertNotNull(futuro);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String eventoConfermato(String nome, String preventivato) {
        String id = given().contentType(ContentType.JSON)
                .body("""
                      {"nome":"%s","tipo":"BANCHETTO_PRIVATO","dataEvento":"2026-12-15",
                       "contattoNome":"Test Contatto","numeroTotalePartecipanti":50,
                       "importoTotalePreviventivato":%s,"businessUnitId":2}
                      """.formatted(nome, preventivato))
                .when().post("/api/eventi").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("{\"stato\":\"CONFERMATO\"}")
                .when().put("/api/eventi/" + id).then().statusCode(200);
        return id;
    }

    /** @return l'id del movimento di incasso creato. */
    private String pagamento(String eventoId, String tipo, String importo) {
        return given().contentType(ContentType.JSON)
                .body("""
                      {"tipo":"%s","importo":%s,"data":"2026-08-01",
                       "metodoPagamentoId":%d,"contoBancarioId":%d}
                      """.formatted(tipo, importo, metodoPagamentoId, contoBancarioId))
                .when().post("/api/eventi/" + eventoId + "/pagamenti")
                .then().statusCode(201).extract().path("movimentoId");
    }

    /** Credito verso terzi che non nasce da un evento: la riga che DEVE restare in colonna. */
    private UUID creditoNonEvento(String descrizione, BigDecimal importo) {
        return movimentiService.createMovimento(new MovimentoCreateRequest(
                "ENTRATA", importo, null, null,
                LocalDate.now(), null, null, LocalDate.now().plusDays(10),
                null, null, (short) 2, contoCoge,
                null, null, null, null,
                descrizione, null, null, "MANUALE", null),
                UUID.fromString(TEST_USER_UUID)).id();
    }

    /** Sposta la data evento con SQL diretto: il service non la vede, come nelle fixture Fase 4. */
    private void dataEventoAIeri(String eventoId) {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                "UPDATE eventi SET data_evento = current_date - 1 WHERE id = CAST(:i AS uuid)")
                .setParameter("i", eventoId).executeUpdate());
    }

    private int righeDiCompetenza(String eventoId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE evento_id = CAST(:e AS uuid) "
                + "AND tipo_evento_movimento = 'COMPETENZA' AND stato <> 'ANNULLATO'")
                .setParameter("e", eventoId).getSingleResult()).intValue();
    }

    private BigDecimal incassatoDenormalizzato(String eventoId) {
        return (BigDecimal) em.createNativeQuery(
                "SELECT importo_incassato FROM eventi WHERE id = CAST(:e AS uuid)")
                .setParameter("e", eventoId).getSingleResult();
    }

    /**
     * Solo le righe di questo test: agosdb_test è condiviso con le fixture di tutte le altre classi.
     *
     * <p>{@code contains} e non {@code startsWith}: la riga di competenza non si chiama come
     * l'evento, si chiama {@code "[EVENTO] SC4 … – da incassare"}. Con lo startsWith il test
     * passava anche col doppione in casa — filtrava via proprio la riga che deve cercare.
     */
    private List<UscitaDaLiquidareDTO> mie(List<UscitaDaLiquidareDTO> righe) {
        return righe.stream().filter(r -> r.descrizione() != null && r.descrizione().contains(PFX)).toList();
    }

    private List<ScadenzaDTO> mieiEventi(ScadenzeImminentiDTO d) {
        return d.eventi().stream().filter(e -> e.descrizione().startsWith(PFX)).toList();
    }

    private BigDecimal residuoEvento(ScadenzeImminentiDTO d, String nome) {
        return d.eventi().stream().filter(e -> nome.equals(e.descrizione())).findFirst()
                .orElseThrow(() -> new AssertionError("evento assente dallo scadenzario: " + nome))
                .importoAtteso();
    }

    private BigDecimal totaleEventi(ScadenzeImminentiDTO d) {
        return mieiEventi(d).stream().map(ScadenzaDTO::importoAtteso)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal somma(List<UscitaDaLiquidareDTO> righe) {
        return righe.stream().map(UscitaDaLiquidareDTO::importo).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
