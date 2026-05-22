package com.agostinelli.gestionale.reporting;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifica end-to-end della correttezza degli aggregati delle materialized view
 * <ul>
 *   <li>mv_conto_economico_mensile (P&amp;L: ricavi, costi_operativi, capex,
 *       oneri_finanziari, imposte, ebitda_proxy) raggruppata su data_competenza</li>
 *   <li>mv_cash_flow_statement (entrate/uscite operative, investimento,
 *       finanziarie) raggruppata su data_finanziaria (V17), filtrando movimenti
 *       DA_LIQUIDARE (data_finanziaria IS NULL)</li>
 * </ul>
 *
 * Copertura intenzionale (vedi commento di ogni test): scenari M1-M9 (movimenti
 * manuali), E1-E5 (eventi), F1-F5 (FLAT), R1-R6 (FINANZIAMENTO), C1-C2 (cassa).
 *
 * Strategia d'isolamento: ogni test usa un (anno, mese, BU) o (anno, mese, conto
 * bancario) unico nell'anno 2099 (partizione default di movimenti), in modo da
 * non interferire con i seed V9/V27 (2025-2026) né con altri test della suite.
 *
 * Le MV vengono refreshate prima di ogni assertion all'interno del test stesso
 * (non in @BeforeEach), perché i test che creano dati devono vedere i propri
 * insert.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AggregatiPlCashFlowIntegrationTest {

    static final String TEST_USER = "00000000-0000-0000-0000-000000000099";
    static final UUID   TEST_USER_UUID = UUID.fromString(TEST_USER);

    @Inject EntityManager em;
    @Inject UserTransaction tx;

    // COGE IDs risolti dal DB nel @BeforeEach (codici stabili da V5/V8/V29)
    private static Integer cogeRicavoRist;       // 30.01.001 — RICAVO
    private static Integer cogeCostoOpex;        // 40.04.010 — COSTO non capex
    private static Integer cogeCapex;            // 50.01.001 — COSTO is_capex=true
    private static Integer cogePassivitaMutuo;   // 20.01.001 — PASSIVITA
    private static Integer cogeOnereFin;         // 60.01.001 — ONERE_FINANZIARIO
    private static Integer cogeImposta;          // 70.01.001 — IMPOSTA
    private static Integer cogeGiroconto;        // 10.03.001 — ATTIVITA
    private static Integer metodoContanti;       // CONTANTI

    @BeforeEach
    @Transactional
    void resolveLookupIds() {
        if (cogeRicavoRist == null) {
            cogeRicavoRist     = lookupCoge("30.01.001");
            cogeCostoOpex      = lookupCoge("40.04.010");
            cogeCapex          = lookupCoge("50.01.001");
            cogePassivitaMutuo = lookupCoge("20.01.001");
            cogeOnereFin       = lookupCoge("60.01.001");
            cogeImposta        = lookupCoge("70.01.001");
            cogeGiroconto      = lookupCoge("10.03.001");
            metodoContanti = ((Number) em.createNativeQuery(
                    "SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'")
                    .getSingleResult()).intValue();
        }
    }

    private Integer lookupCoge(String codice) {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice)
                .getSingleResult()).intValue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // MOVIMENTI MANUALI
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M2 — ENTRATA ricavo con dataMovimento (competenza) e dataFinanziaria in
     * mesi diversi. Il P&amp;L deve registrare il ricavo nel mese di competenza
     * (data_movimento → data_competenza), mentre il Cash Flow deve registrare
     * l'incasso nel mese di liquidazione (data_finanziaria).
     */
    @Test @Order(10)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m2_entrata_crossMonth_plMeseCompetenza_cfMeseLiquidita() throws Exception {
        short buId = 1;
        int annoComp = 2099, meseComp = 1;
        int annoFin  = 2099, meseFin  = 3;

        insertMovimento(
                "ENTRATA", new BigDecimal("1000.00"),
                LocalDate.of(annoComp, meseComp, 15),  // data_movimento (= data_competenza)
                LocalDate.of(annoFin,  meseFin,  15),  // data_finanziaria
                LocalDate.of(annoFin,  meseFin,  15),  // data_liquidita
                (short) 1, metodoContanti, cogeRicavoRist, buId,
                "REGISTRATO", "M2 ENTRATA cross-month");

        refreshMvs();

        assertEquals(1000.00, sumPnlRicavi(annoComp, meseComp, buId), 0.01,
                "P&L: il ricavo deve essere registrato nel mese di competenza (data_movimento) = 2099-01");
        assertEquals(0.0, sumPnlRicavi(annoFin, meseFin, buId), 0.01,
                "P&L: il ricavo NON deve apparire nel mese di liquidazione");

        assertEquals(1000.00, sumCfEntrateOperative(annoFin, meseFin, (short) 1), 0.01,
                "CF: l'incasso deve essere registrato nel mese di data_finanziaria = 2099-03 (V17)");
        assertEquals(0.0, sumCfEntrateOperative(annoComp, meseComp, (short) 1), 0.01,
                "CF: l'incasso NON deve apparire nel mese di competenza economica");
    }

    /**
     * M3 — ENTRATA in sospeso (DA_LIQUIDARE): data_finanziaria NULL.
     * Il P&amp;L registra comunque il ricavo nel mese di competenza, ma il
     * Cash Flow NON deve mai contenere movimenti senza data_finanziaria.
     */
    @Test @Order(11)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m3_entrata_daLiquidare_pl_si_cf_no() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 4;

        insertMovimento(
                "ENTRATA", new BigDecimal("500.00"),
                LocalDate.of(anno, mese, 10),  // data_movimento
                null,                          // data_finanziaria — DA_LIQUIDARE
                LocalDate.of(anno, 7, 10),     // data_liquidita (scadenza futura)
                null, null, cogeRicavoRist, buId,
                "DA_LIQUIDARE", "M3 ENTRATA DA_LIQUIDARE");

        refreshMvs();

        assertEquals(500.00, sumPnlRicavi(anno, mese, buId), 0.01,
                "P&L: il ricavo va riconosciuto nel mese di competenza anche se non liquidato");

        // CF deve filtrare data_finanziaria IS NOT NULL (V17): la riga non deve apparire da nessuna parte
        assertEquals(0.0, sumCfEntrateOperative(anno, mese, (short) 1), 0.01,
                "CF: un movimento DA_LIQUIDARE non deve apparire nel CF (data_finanziaria IS NULL)");
    }

    /**
     * M6 — USCITA CAPEX (is_capex=true). Va in investimenti_capex (P&amp;L) e in
     * uscite_investimento (CF), non in costi_operativi né in uscite_operative.
     */
    @Test @Order(12)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m6_uscita_capex_investimentiCapex_e_usciteInvestimento() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 5;

        insertMovimento(
                "USCITA", new BigDecimal("2500.00"),
                LocalDate.of(anno, mese, 7),
                LocalDate.of(anno, mese, 7),
                LocalDate.of(anno, mese, 7),
                (short) 1, metodoContanti, cogeCapex, buId,
                "REGISTRATO", "M6 USCITA CAPEX");

        refreshMvs();

        assertEquals(2500.00, sumPnlInvestimentiCapex(anno, mese, buId), 0.01,
                "P&L: CAPEX deve confluire in investimenti_capex");
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "P&L: CAPEX NON deve apparire in costi_operativi");
        assertEquals(0.0, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "P&L: CAPEX non impatta l'EBITDA");
        assertEquals(2500.00, sumCfUsciteInvestimento(anno, mese, (short) 1), 0.01,
                "CF: CAPEX deve confluire in uscite_investimento");
        assertEquals(0.0, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "CF: CAPEX NON deve apparire in uscite_operative");
    }

    /**
     * M7 — USCITA IMPOSTA. Confluisce nella colonna imposte del P&amp;L, sotto la
     * linea EBIT, e nelle uscite_finanziarie del CF (V29: ONERE_FINANZIARIO e
     * PASSIVITA sono finanziarie; IMPOSTA NON è classificata come finanziaria
     * nella MV, quindi finisce nelle uscite operative del CF).
     *
     * NB: questa è l'attesa derivata dalla MV V29; se il P&amp;L includesse
     * l'imposta nei costi_operativi sarebbe un baco.
     */
    @Test @Order(13)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m7_uscita_imposta_pl_imposte() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 6;

        insertMovimento(
                "USCITA", new BigDecimal("3300.00"),
                LocalDate.of(anno, mese, 16),
                LocalDate.of(anno, mese, 16),
                LocalDate.of(anno, mese, 16),
                (short) 1, metodoContanti, cogeImposta, buId,
                "REGISTRATO", "M7 USCITA IMPOSTA IRAP");

        refreshMvs();

        assertEquals(3300.00, sumPnlImposte(anno, mese, buId), 0.01,
                "P&L: l'imposta deve confluire nella colonna imposte");
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "P&L: l'imposta NON deve confluire in costi_operativi");
        assertEquals(0.0, sumPnlOneriFinanziari(anno, mese, buId), 0.01,
                "P&L: l'imposta NON deve confluire in oneri_finanziari");
    }

    /**
     * M9 — Storno costo fornitore (ENTRATA negativa su COSTO).
     * Fix introdotto in V31: l'ENTRATA negativa su un conto COSTO deve ridurre
     * la voce costi_operativi (segno negativo) e aumentare conseguentemente
     * l'ebitda_proxy.
     */
    @Test @Order(14)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m9_storno_costo_fornitore_riduce_costiOperativi() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 7;

        // Uscita "normale" di costo
        insertMovimento(
                "USCITA", new BigDecimal("400.00"),
                LocalDate.of(anno, mese, 5),
                LocalDate.of(anno, mese, 5),
                LocalDate.of(anno, mese, 5),
                (short) 1, metodoContanti, cogeCostoOpex, buId,
                "REGISTRATO", "M9 costo originario");

        // Storno: ENTRATA negativa sullo stesso conto COSTO (nota di credito)
        insertMovimento(
                "ENTRATA", new BigDecimal("-150.00"),
                LocalDate.of(anno, mese, 20),
                LocalDate.of(anno, mese, 20),
                LocalDate.of(anno, mese, 20),
                (short) 1, metodoContanti, cogeCostoOpex, buId,
                "REGISTRATO", "M9 storno fornitore (V31)");

        refreshMvs();

        // V31: costi_operativi = 400 (USCITA) + (-150) (ENTRATA neg su COSTO) = 250
        assertEquals(250.00, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "V31: costi_operativi deve includere la nota di credito (ENTRATA negativa su COSTO)");
        // ebitda_proxy = -250 (i ricavi sono 0)
        assertEquals(-250.00, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "V31: lo storno aumenta l'EBITDA proxy rispetto al solo costo lordo");
    }

    /**
     * Isolamento BU: un movimento di BU1 non deve interferire con gli aggregati
     * di BU2 nello stesso periodo. La MV è raggruppata per (anno, mese,
     * business_unit_id) quindi questa è una protezione strutturale, ma la
     * verifica esplicita previene regressioni future (per es. join sbagliati).
     */
    @Test @Order(15)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void bu_isolation_buA_nonInfluenzaBuB() throws Exception {
        int anno = 2099, mese = 8;
        short buA = 1, buB = 2;

        insertMovimento(
                "ENTRATA", new BigDecimal("777.77"),
                LocalDate.of(anno, mese, 11),
                LocalDate.of(anno, mese, 11),
                LocalDate.of(anno, mese, 11),
                (short) 1, metodoContanti, cogeRicavoRist, buA,
                "REGISTRATO", "BU-iso ricavo BU1");

        refreshMvs();

        assertEquals(777.77, sumPnlRicavi(anno, mese, buA), 0.01,
                "Il ricavo deve essere visibile per la BU di appartenenza");
        assertEquals(0.0, sumPnlRicavi(anno, mese, buB), 0.01,
                "Il ricavo NON deve essere visibile per altre BU");
    }

    // ════════════════════════════════════════════════════════════════════════
    // EVENTI — la registrazione di un pagamento evento usa
    //   data_movimento = e.dataEvento (competenza economica)
    //   data_finanziaria = req.data() (data pagamento effettiva)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * E4 — Sequenza completa CAPARRA + ACCONTO + SALDO su mesi diversi per un
     * evento in un mese ancora successivo. Il P&amp;L deve mostrare l'intero
     * ricavo nel mese dell'evento (competenza); il CF deve distribuire gli
     * incassi nei rispettivi mesi di pagamento.
     */
    @Test @Order(20)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void e4_sequenza_pagamenti_pl_competenza_cf_pagamento() throws Exception {
        // Evento BU2, data 2099-12-15, importo totale 3000
        String eventoId = createEvento("E4 sequenza", "2099-12-15", "3000.00");
        // Auto-transizione: CONFERMATO al primo CAPARRA/ACCONTO
        registraPagamento(eventoId, "CAPARRA", "1000.00", "2099-09-01");
        registraPagamento(eventoId, "ACCONTO", "1000.00", "2099-10-01");
        registraPagamento(eventoId, "SALDO",   "1000.00", "2099-11-01");

        refreshMvs();

        // P&L: TUTTI i 3000€ nel mese dell'evento (2099-12)
        assertEquals(3000.00, sumPnlRicavi(2099, 12, (short) 2), 0.01,
                "P&L: l'intero ricavo è di competenza del mese dell'evento (2099-12)");
        assertEquals(0.0, sumPnlRicavi(2099, 9,  (short) 2), 0.01,
                "P&L: nessun ricavo nel mese della CAPARRA");
        assertEquals(0.0, sumPnlRicavi(2099, 10, (short) 2), 0.01,
                "P&L: nessun ricavo nel mese dell'ACCONTO");
        assertEquals(0.0, sumPnlRicavi(2099, 11, (short) 2), 0.01,
                "P&L: nessun ricavo nel mese del SALDO");

        // CF: ogni incasso nel mese in cui i soldi sono entrati
        // Il conto bancario è quello che ha usato registraPagamento (lookup runtime),
        // quindi sommiamo su tutti i conti per quel mese.
        assertEquals(1000.00, sumCfEntrateOperativeAllAccounts(2099,  9), 0.01,
                "CF: la caparra deve apparire nel mese di pagamento (2099-09)");
        assertEquals(1000.00, sumCfEntrateOperativeAllAccounts(2099, 10), 0.01,
                "CF: l'acconto deve apparire nel mese di pagamento (2099-10)");
        assertEquals(1000.00, sumCfEntrateOperativeAllAccounts(2099, 11), 0.01,
                "CF: il saldo deve apparire nel mese di pagamento (2099-11)");
        assertEquals(0.0, sumCfEntrateOperativeAllAccounts(2099, 12), 0.01,
                "CF: nessun incasso nel mese dell'evento (nessun pagamento in dicembre)");
    }

    /**
     * E5 — PENALE su evento ANNULLATO è l'unico tipo di pagamento consentito;
     * la verifica end-to-end è che il movimento generato sia visibile nel P&amp;L
     * come ricavo (la PENALE è registrata via cogeRicavi di default).
     */
    @Test @Order(21)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void e5_penale_su_evento_annullato() throws Exception {
        // Evento in 2099-06 (NB: mese diverso da e4 per evitare contaminazione P&L).
        // PREVENTIVATO → ANNULLATO, poi unica PENALE consentita.
        String eventoId = createEvento("E5 evento annullato", "2099-06-25", "5000.00");
        given()
            .contentType(ContentType.JSON)
            .body("{\"stato\":\"ANNULLATO\",\"noteAnnullamento\":\"Disdetta cliente\"}")
            .when().put("/api/eventi/" + eventoId)
            .then().statusCode(200);

        // PENALE sul evento ANNULLATO (req.data() può essere differente dal data_evento).
        registraPagamento(eventoId, "PENALE", "500.00", "2099-08-15");

        refreshMvs();

        // P&L 2099-06 BU2 deve contenere SOLO la penale di questo test (mese isolato)
        double ricavi = sumPnlRicavi(2099, 6, (short) 2);
        assertEquals(500.00, ricavi, 0.01,
                "P&L: la PENALE su evento ANNULLATO confluisce nei ricavi del mese dell'evento");
    }

    // ════════════════════════════════════════════════════════════════════════
    // SPESE RICORRENTI — verifichiamo direttamente la MV su movimenti che
    // corrispondono a quelli generati dal RecurringExpenseService (vedi
    // buildMovimento / buildMovimentoInteressi).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * F1 — Piano FLAT su un conto di tipo COSTO. Il pagamento della singola
     * rata deve apparire come costo operativo nel P&amp;L e come uscita operativa
     * nel CF.
     */
    @Test @Order(30)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void f1_flat_su_costo_pl_e_cf_operativi() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 9;

        insertMovimento(
                "USCITA", new BigDecimal("300.00"),
                LocalDate.of(anno, mese, 5),
                LocalDate.of(anno, mese, 5),
                LocalDate.of(anno, mese, 5),
                (short) 1, metodoContanti, cogeCostoOpex, buId,
                "REGISTRATO", "F1 rata FLAT su COSTO");

        refreshMvs();

        assertEquals(300.00, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "F1: una rata FLAT su COSTO deve apparire in costi_operativi");
        assertEquals(300.00, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "F1: una rata FLAT su COSTO deve apparire in uscite_operative");
    }

    /**
     * F2 — Piano FLAT su un conto di tipo PASSIVITA (es. rata mutuo). Il
     * pagamento riduce il debito: NON è un costo del P&amp;L (PASSIVITA non
     * confluisce in alcuna colonna) ed è un'uscita finanziaria nel CF.
     */
    @Test @Order(31)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void f2_flat_su_passivita_no_pl_cf_finanziaria() throws Exception {
        short buId = 5;          // Overhead
        int anno = 2099, mese = 10;

        insertMovimento(
                "USCITA", new BigDecimal("800.00"),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                (short) 1, metodoContanti, cogePassivitaMutuo, buId,
                "REGISTRATO", "F2 rata FLAT su PASSIVITA");

        refreshMvs();

        // PASSIVITA NON è né RICAVO né COSTO né CAPEX né IMPOSTA né ONERE_FINANZIARIO
        // → tutte le colonne del P&L sono 0
        assertEquals(0.0, sumPnlRicavi(anno, mese, buId), 0.01);
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "F2: la rata su PASSIVITA NON deve essere un costo operativo");
        assertEquals(0.0, sumPnlInvestimentiCapex(anno, mese, buId), 0.01);
        assertEquals(0.0, sumPnlOneriFinanziari(anno, mese, buId), 0.01);
        assertEquals(0.0, sumPnlImposte(anno, mese, buId), 0.01);
        assertEquals(0.0, sumPnlEbitdaProxy(anno, mese, buId), 0.01);

        // CF: la rata è un'uscita finanziaria (rimborso capitale)
        assertEquals(800.00, sumCfUsciteFinanziarie(anno, mese, (short) 1), 0.01,
                "F2: la rata su PASSIVITA deve apparire in uscite_finanziarie");
        assertEquals(0.0, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "F2: la rata su PASSIVITA NON deve apparire in uscite_operative");
    }

    /**
     * R1+R2 — Pagamento di una singola rata di un piano FINANZIAMENTO genera
     * DUE movimenti: quota capitale su PASSIVITA e quota interessi su
     * ONERE_FINANZIARIO. Verifichiamo che:
     *  - P&amp;L: solo gli interessi appaiono in oneri_finanziari; il capitale
     *    NON appare nei costi operativi né in oneri_finanziari
     *  - CF: entrambe le quote confluiscono in uscite_finanziarie
     */
    @Test @Order(40)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void r1_r2_finanziamento_singola_rata_pl_solo_interessi_cf_entrambi() throws Exception {
        short buId = 5;
        int anno = 2099, mese = 11;

        // Quota capitale (PASSIVITA) — replica buildMovimento
        insertMovimento(
                "USCITA", new BigDecimal("8199.00"),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                (short) 1, metodoContanti, cogePassivitaMutuo, buId,
                "REGISTRATO", "R1 rata 1 (cap.)");
        // Quota interessi (ONERE_FINANZIARIO) — replica buildMovimentoInteressi
        insertMovimento(
                "USCITA", new BigDecimal("291.67"),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                LocalDate.of(anno, mese, 1),
                (short) 1, metodoContanti, cogeOnereFin, buId,
                "REGISTRATO", "R1 rata 1 (int.)");

        refreshMvs();

        // P&L: solo gli interessi nel waterfall
        assertEquals(291.67, sumPnlOneriFinanziari(anno, mese, buId), 0.01,
                "R2: gli interessi devono apparire in oneri_finanziari");
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "R2: il capitale (PASSIVITA) NON deve apparire in costi operativi");
        // EBITDA non impattato dagli interessi
        assertEquals(0.0, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "R2: gli interessi non impattano l'EBITDA proxy (sono sotto la linea)");

        // CF: entrambi confluiscono in uscite_finanziarie (PASSIVITA + ONERE_FINANZIARIO)
        assertEquals(8199.00 + 291.67, sumCfUsciteFinanziarie(anno, mese, (short) 1), 0.01,
                "R2: capitale + interessi entrambi in uscite_finanziarie nel CF");
        assertEquals(0.0, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "R2: nessuna quota della rata FINANZIAMENTO finisce in uscite_operative");
    }

    /**
     * R3 — Sequenza di 3 rate FINANZIAMENTO: somma capitali pagati = capitale
     * complessivamente rimborsato; somma interessi crescente (capitale residuo
     * decresce → interessi non crescenti). Verifichiamo gli aggregati P&amp;L su 3
     * mesi separati.
     */
    @Test @Order(41)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void r3_finanziamento_sequenza_3_rate_somma_capitali() throws Exception {
        short buId = 5;
        // Piano semplificato: 3 rate da 1000€, di cui:
        //  rata 1: cap=900 int=100
        //  rata 2: cap=920 int=80
        //  rata 3: cap=940 int=60
        BigDecimal[] capitali  = { new BigDecimal("900.00"), new BigDecimal("920.00"), new BigDecimal("940.00") };
        BigDecimal[] interessi = { new BigDecimal("100.00"), new BigDecimal( "80.00"), new BigDecimal( "60.00") };

        for (int i = 0; i < 3; i++) {
            int mese = 1 + i;  // 2098-01, 2098-02, 2098-03
            insertMovimento("USCITA", capitali[i],
                    LocalDate.of(2098, mese, 5), LocalDate.of(2098, mese, 5), LocalDate.of(2098, mese, 5),
                    (short) 1, metodoContanti, cogePassivitaMutuo, buId,
                    "REGISTRATO", "R3 rata " + (i + 1) + " (cap.)");
            insertMovimento("USCITA", interessi[i],
                    LocalDate.of(2098, mese, 5), LocalDate.of(2098, mese, 5), LocalDate.of(2098, mese, 5),
                    (short) 1, metodoContanti, cogeOnereFin, buId,
                    "REGISTRATO", "R3 rata " + (i + 1) + " (int.)");
        }

        refreshMvs();

        // Σ oneri_finanziari su 3 mesi = 100 + 80 + 60 = 240
        double totOneri = sumPnlOneriFinanziari(2098, 1, buId)
                        + sumPnlOneriFinanziari(2098, 2, buId)
                        + sumPnlOneriFinanziari(2098, 3, buId);
        assertEquals(240.00, totOneri, 0.01,
                "R3: somma oneri finanziari sui 3 mesi = somma interessi (100+80+60)");

        // Σ uscite_finanziarie nel CF su 3 mesi = somma rate complete = 3000
        double totUsciteFin = sumCfUsciteFinanziarie(2098, 1, (short) 1)
                            + sumCfUsciteFinanziarie(2098, 2, (short) 1)
                            + sumCfUsciteFinanziarie(2098, 3, (short) 1);
        assertEquals(3000.00, totUsciteFin, 0.01,
                "R3: somma uscite_finanziarie = somma rate (capitale + interessi su 3 mesi)");
    }

    // ════════════════════════════════════════════════════════════════════════
    // CASSA — il giroconto automatico crea un movimento bancario su un conto
    // di tipo ATTIVITA (codice 10.03.x). Verifichiamo che NON appaia né nel
    // P&L (P&L conta solo RICAVO/COSTO/CAPEX/ONERE_FINANZIARIO/IMPOSTA) né
    // nel CF operativo/finanziario (un giroconto non è cash flow vero).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * C1 — PRELIEVO_DA_BANCA genera un movimento bancario USCITA sul conto
     * giroconto. Verifichiamo che NON impatti P&amp;L né CF operativo/finanziario.
     */
    @Test @Order(50)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void c1_prelievo_giroconto_non_in_pl_ne_cf_operativo() throws Exception {
        // Inserisce direttamente un movimento bancario con conto_coge = giroconto
        // (replica esattamente quello che fa CassaService.createMovimentoBancaCollegato)
        int anno = 2098, mese = 7;
        short buId = 1;
        insertMovimento(
                "USCITA", new BigDecimal("200.00"),
                LocalDate.of(anno, mese, 3),
                LocalDate.of(anno, mese, 3),
                LocalDate.of(anno, mese, 3),
                (short) 1, metodoContanti, cogeGiroconto, buId,
                "REGISTRATO", "C1 giroconto prelievo");

        refreshMvs();

        // P&L: ATTIVITA non rientra in nessuna colonna del P&L
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "C1: il giroconto NON deve apparire in costi_operativi");
        assertEquals(0.0, sumPnlInvestimentiCapex(anno, mese, buId), 0.01,
                "C1: il giroconto NON deve apparire in investimenti_capex");
        assertEquals(0.0, sumPnlOneriFinanziari(anno, mese, buId), 0.01,
                "C1: il giroconto NON deve apparire in oneri_finanziari");

        // CF: un giroconto NON deve apparire in uscite_operative né uscite_finanziarie
        assertEquals(0.0, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "C1: il giroconto NON deve apparire in uscite_operative del CF");
        assertEquals(0.0, sumCfUsciteFinanziarie(anno, mese, (short) 1), 0.01,
                "C1: il giroconto NON deve apparire in uscite_finanziarie del CF");
    }

    /**
     * C2 — VERSAMENTO_IN_BANCA genera un movimento bancario ENTRATA sul conto
     * giroconto. Stessa invariante di C1: nessun impatto su P&amp;L né su CF.
     */
    @Test @Order(51)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void c2_versamento_giroconto_non_in_pl_ne_cf_operativo() throws Exception {
        int anno = 2098, mese = 8;
        short buId = 1;
        insertMovimento(
                "ENTRATA", new BigDecimal("250.00"),
                LocalDate.of(anno, mese, 4),
                LocalDate.of(anno, mese, 4),
                LocalDate.of(anno, mese, 4),
                (short) 1, metodoContanti, cogeGiroconto, buId,
                "REGISTRATO", "C2 giroconto versamento");

        refreshMvs();

        assertEquals(0.0, sumPnlRicavi(anno, mese, buId), 0.01,
                "C2: il giroconto NON deve apparire in ricavi");
        assertEquals(0.0, sumCfEntrateOperative(anno, mese, (short) 1), 0.01,
                "C2: il giroconto NON deve apparire in entrate_operative del CF");
        assertEquals(0.0, sumCfEntrateFinanziarie(anno, mese, (short) 1), 0.01,
                "C2: il giroconto NON deve apparire in entrate_finanziarie del CF");
    }

    // ════════════════════════════════════════════════════════════════════════
    // MOVIMENTI IMMEDIATI E STORNI — happy path comuni sugli aggregati
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M1 — ENTRATA ricavo pagata immediatamente: data_movimento =
     * data_finanziaria nello stesso mese. È il caso più frequente (incasso
     * giornaliero ristorazione, Billy ecc.). Verifichiamo che lo stesso mese
     * compaia coerentemente in P&amp;L (ricavi, EBITDA) e CF (entrate operative,
     * flusso operativo netto positivo).
     */
    @Test @Order(60)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m1_entrata_immediata_pl_e_cf_stesso_mese() throws Exception {
        short buId = 1;
        int anno = 2099, mese = 2;
        LocalDate data = LocalDate.of(anno, mese, 10);

        insertMovimento(
                "ENTRATA", new BigDecimal("1000.00"),
                data, data, data,
                (short) 1, metodoContanti, cogeRicavoRist, buId,
                "REGISTRATO", "M1 ENTRATA immediata su RICAVO");

        refreshMvs();

        // P&L: ricavo nel mese; EBITDA == ricavi (nessun costo registrato in questo mese/BU)
        assertEquals(1000.00, sumPnlRicavi(anno, mese, buId), 0.01,
                "M1: ricavo presente nel mese di competenza");
        assertEquals(0.0,    sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "M1: nessun costo operativo nel mese");
        assertEquals(1000.00, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "M1: EBITDA = ricavi quando i costi sono zero");

        // CF: entrata operativa nello stesso mese; flusso operativo netto > 0
        assertEquals(1000.00, sumCfEntrateOperative(anno, mese, (short) 1), 0.01,
                "M1: l'incasso compare in entrate_operative del CF nello stesso mese");
        assertEquals(0.0, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "M1: nessuna uscita operativa nel mese");
        double flussoNetto = sumCfFlussoOperativoNetto(anno, mese, (short) 1);
        assertTrue(flussoNetto > 0,
                "M1: flusso operativo netto deve essere > 0 con solo ENTRATA; trovato: " + flussoNetto);
        assertEquals(1000.00, flussoNetto, 0.01,
                "M1: flusso operativo netto == entrate_operative quando uscite_operative == 0");
    }

    /**
     * M4 — USCITA costo operativo pagata immediatamente. Speculare di M1: la
     * spesa compare in costi_operativi (P&amp;L) e uscite_operative (CF) nello
     * stesso mese; EBITDA negativo, flusso operativo netto &lt; 0.
     */
    @Test @Order(61)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m4_uscita_costo_immediato_pl_e_cf_stesso_mese() throws Exception {
        short buId = 1;
        int anno = 2098, mese = 4;
        LocalDate data = LocalDate.of(anno, mese, 15);

        insertMovimento(
                "USCITA", new BigDecimal("500.00"),
                data, data, data,
                (short) 1, metodoContanti, cogeCostoOpex, buId,
                "REGISTRATO", "M4 USCITA costo immediato");

        refreshMvs();

        // P&L
        assertEquals(0.0, sumPnlRicavi(anno, mese, buId), 0.01,
                "M4: nessun ricavo nel mese");
        assertEquals(500.00, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "M4: il costo compare in costi_operativi");
        assertEquals(-500.00, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "M4: EBITDA = -costo quando ricavi == 0");

        // CF
        assertEquals(0.0, sumCfEntrateOperative(anno, mese, (short) 1), 0.01,
                "M4: nessuna entrata operativa nel mese");
        assertEquals(500.00, sumCfUsciteOperative(anno, mese, (short) 1), 0.01,
                "M4: il pagamento compare in uscite_operative nello stesso mese");
        double flussoNetto = sumCfFlussoOperativoNetto(anno, mese, (short) 1);
        assertTrue(flussoNetto < 0,
                "M4: flusso operativo netto deve essere < 0 con solo USCITA; trovato: " + flussoNetto);
        assertEquals(-500.00, flussoNetto, 0.01,
                "M4: flusso operativo netto == -uscite_operative quando entrate_operative == 0");
    }

    /**
     * M8 — Storno ricavo: ENTRATA negativa sullo stesso conto RICAVO. Speculare
     * di M9 (V31) ma sul lato ricavi: l'aggregato ricavi del mese si riduce
     * esattamente dell'importo stornato. Setup: un ricavo base + uno storno
     * nello stesso mese; verifichiamo che il delta sia quello atteso.
     */
    @Test @Order(62)
    @TestSecurity(user = TEST_USER, roles = {"ADMIN"})
    void m8_storno_ricavo_riduce_ricavi_e_ebitda() throws Exception {
        short buId = 1;
        int anno = 2098, mese = 5;

        // Ricavo base
        insertMovimento(
                "ENTRATA", new BigDecimal("1000.00"),
                LocalDate.of(anno, mese, 10),
                LocalDate.of(anno, mese, 10),
                LocalDate.of(anno, mese, 10),
                (short) 1, metodoContanti, cogeRicavoRist, buId,
                "REGISTRATO", "M8 ricavo originario");

        // Storno: ENTRATA negativa sullo stesso conto RICAVO (nota di credito a cliente)
        insertMovimento(
                "ENTRATA", new BigDecimal("-300.00"),
                LocalDate.of(anno, mese, 20),
                LocalDate.of(anno, mese, 20),
                LocalDate.of(anno, mese, 20),
                (short) 1, metodoContanti, cogeRicavoRist, buId,
                "REGISTRATO", "M8 storno ricavo (nota di credito cliente)");

        refreshMvs();

        // Il branch RICAVO nella MV P&L è `m.tipo='ENTRATA' AND pc.tipo='RICAVO'`,
        // quindi una ENTRATA negativa riduce naturalmente l'aggregato.
        assertEquals(700.00, sumPnlRicavi(anno, mese, buId), 0.01,
                "M8: i ricavi devono essere ridotti dell'importo stornato (1000 - 300 = 700)");
        // EBITDA: ricavi 700, costi 0 → 700
        assertEquals(700.00, sumPnlEbitdaProxy(anno, mese, buId), 0.01,
                "M8: l'EBITDA si riduce coerentemente di 300 (era 1000, ora 700)");
        assertEquals(0.0, sumPnlCostiOperativi(anno, mese, buId), 0.01,
                "M8: lo storno NON deve trasformarsi in un costo operativo (V31 vale solo per pc.tipo='COSTO')");
    }

    // ════════════════════════════════════════════════════════════════════════
    // HELPERS — inserimento e query MV
    // ════════════════════════════════════════════════════════════════════════

    /** Refresha le MV non concorrenti dentro una transazione. */
    private void refreshMvs() throws Exception {
        tx.begin();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_conto_economico_mensile").executeUpdate();
        em.createNativeQuery("REFRESH MATERIALIZED VIEW mv_cash_flow_statement").executeUpdate();
        tx.commit();
    }

    /**
     * INSERT diretto su movimenti (bypassa cross-field validation). Usato per
     * generare scenari come storno (importo negativo, vietato dal DTO) o
     * DA_LIQUIDARE (data_finanziaria NULL).
     */
    @Transactional
    void insertMovimento(String tipo, BigDecimal importo,
                         LocalDate dataMov, LocalDate dataFin, LocalDate dataLiq,
                         Short contoBancarioId, Integer metodoPagamentoId,
                         Integer cogeId, short buId,
                         String stato, String descrizione) {
        em.createNativeQuery("""
                INSERT INTO movimenti (
                    id, data_movimento, tipo, importo_lordo, importo_commissione,
                    data_competenza, data_finanziaria, data_liquidita,
                    conto_bancario_id, metodo_pagamento_id,
                    conto_coge_id, business_unit_id,
                    descrizione, stato, fonte, created_by, created_at
                ) VALUES (
                    gen_random_uuid(), :dataMov, :tipo, :importo, 0,
                    :dataMov, :dataFin, :dataLiq,
                    :conto, :metodo,
                    :coge, :bu,
                    :descr, :stato, 'MANUALE', CAST(:user AS uuid), now()
                )
                """)
                .setParameter("dataMov",  dataMov)
                .setParameter("tipo",     tipo)
                .setParameter("importo",  importo)
                .setParameter("dataFin",  dataFin)
                .setParameter("dataLiq",  dataLiq)
                .setParameter("conto",    contoBancarioId)
                .setParameter("metodo",   metodoPagamentoId)
                .setParameter("coge",     cogeId)
                .setParameter("bu",       (int) buId)
                .setParameter("descr",    descrizione)
                .setParameter("stato",    stato)
                .setParameter("user",     TEST_USER)
                .executeUpdate();
    }

    private String createEvento(String nome, String dataEvento, String importo) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {"nome":"%s","tipo":"BANCHETTO_PRIVATO","dataEvento":"%s",
                         "contattoNome":"Test","numeroTotalePartecipanti":50,
                         "importoTotalePreviventivato":%s,"businessUnitId":2}
                        """.formatted(nome, dataEvento, importo))
                .when().post("/api/eventi")
                .then().statusCode(201)
                .extract().path("id");
    }

    private void registraPagamento(String eventoId, String tipo, String importo, String data) {
        // Risolvi metodo/conto al volo per coerenza con i test eventi esistenti
        Integer metodo = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice='BONIFICO'").getSingleResult()).intValue();
        Short conto = ((Number) em.createNativeQuery(
                "SELECT id FROM conti_bancari LIMIT 1").getSingleResult()).shortValue();
        given()
            .contentType(ContentType.JSON)
            .body("""
                    {"tipo":"%s","importo":%s,"data":"%s",
                     "metodoPagamentoId":%d,"contoBancarioId":%d}
                    """.formatted(tipo, importo, data, metodo, conto))
            .when().post("/api/eventi/" + eventoId + "/pagamenti")
            .then().statusCode(201);
    }

    // ── Query MV: P&L ─────────────────────────────────────────────────────────

    private double sumPnlRicavi(int anno, int mese, short buId) {
        return sumPnlColumn("ricavi", anno, mese, buId);
    }
    private double sumPnlCostiOperativi(int anno, int mese, short buId) {
        return sumPnlColumn("costi_operativi", anno, mese, buId);
    }
    private double sumPnlInvestimentiCapex(int anno, int mese, short buId) {
        return sumPnlColumn("investimenti_capex", anno, mese, buId);
    }
    private double sumPnlOneriFinanziari(int anno, int mese, short buId) {
        return sumPnlColumn("oneri_finanziari", anno, mese, buId);
    }
    private double sumPnlImposte(int anno, int mese, short buId) {
        return sumPnlColumn("imposte", anno, mese, buId);
    }
    private double sumPnlEbitdaProxy(int anno, int mese, short buId) {
        return sumPnlColumn("ebitda_proxy", anno, mese, buId);
    }

    private double sumPnlColumn(String column, int anno, int mese, short buId) {
        Object r = em.createNativeQuery(
                "SELECT COALESCE(SUM(" + column + "),0) FROM mv_conto_economico_mensile " +
                "WHERE anno=:a AND mese=:m AND business_unit_id=:bu")
                .setParameter("a", anno)
                .setParameter("m", mese)
                .setParameter("bu", (int) buId)
                .getSingleResult();
        return toDouble(r);
    }

    // ── Query MV: Cash Flow ──────────────────────────────────────────────────

    private double sumCfEntrateOperative(int anno, int mese, short conto) {
        return sumCfColumn("entrate_operative", anno, mese, conto);
    }
    private double sumCfUsciteOperative(int anno, int mese, short conto) {
        return sumCfColumn("uscite_operative", anno, mese, conto);
    }
    private double sumCfUsciteInvestimento(int anno, int mese, short conto) {
        return sumCfColumn("uscite_investimento", anno, mese, conto);
    }
    private double sumCfUsciteFinanziarie(int anno, int mese, short conto) {
        return sumCfColumn("uscite_finanziarie", anno, mese, conto);
    }
    private double sumCfEntrateFinanziarie(int anno, int mese, short conto) {
        return sumCfColumn("entrate_finanziarie", anno, mese, conto);
    }
    private double sumCfFlussoOperativoNetto(int anno, int mese, short conto) {
        return sumCfColumn("flusso_operativo_netto", anno, mese, conto);
    }

    private double sumCfColumn(String column, int anno, int mese, short conto) {
        Object r = em.createNativeQuery(
                "SELECT COALESCE(SUM(" + column + "),0) FROM mv_cash_flow_statement " +
                "WHERE anno=:a AND mese=:m AND conto_bancario_id=:c")
                .setParameter("a", anno)
                .setParameter("m", mese)
                .setParameter("c", (int) conto)
                .getSingleResult();
        return toDouble(r);
    }

    /**
     * Somma una colonna del CF su tutti i conti bancari per (anno, mese): utile
     * quando il conto bancario non è noto a priori (es. evento pagato).
     */
    private double sumCfEntrateOperativeAllAccounts(int anno, int mese) {
        Object r = em.createNativeQuery(
                "SELECT COALESCE(SUM(entrate_operative),0) FROM mv_cash_flow_statement " +
                "WHERE anno=:a AND mese=:m")
                .setParameter("a", anno)
                .setParameter("m", mese)
                .getSingleResult();
        return toDouble(r);
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(o.toString());
    }
}
