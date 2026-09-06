package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.reporting.dto.ForecastingDettaglioDTO;
import com.agostinelli.gestionale.reporting.dto.ForecastingRispostaDTO;
import com.agostinelli.gestionale.reporting.dto.ForecastingTimelineDTO;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P7 di docs/specs/previsionale-correzioni.md — stima dei costi ricorrenti, su DATASET COSTRUITO.
 *
 * <p><b>Perché una classe a parte, e perché in questo package.</b> Il gate di sufficienza dati con
 * i default (min-mesi = 3) è <b>irraggiungibile in questa data</b>: il go-live è il 01/07/2026 e la
 * finestra non può scendere sotto di esso (R7.7), quindi al 06/09/2026 esistono al massimo due mesi
 * interi (luglio, agosto). Non è un limite del test: è esattamente ciò che la spec dichiara —
 * «con go-live al 01/07, tre mesi interi significa che la stima non può accendersi prima di ottobre
 * 2026». Per provare il MOTORE bisogna quindi abbassare le soglie, e stare nello stesso package del
 * servizio è il modo più economico per farlo: i campi {@code @ConfigProperty} sono package-private.
 *
 * <p>L'alternativa — un {@code @TestProfile} — farebbe ripartire Quarkus e, con
 * {@code flyway.clean-at-start=true}, azzererebbe {@code agosdb_test} sotto le altre classi della
 * suite. Prezzo troppo alto per una soglia.
 *
 * <p>Il caso sui dati VERI (gate che non passa, R7.1) sta in {@code ForecastingIntegrationTest}.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForecastingStimaCostiIntegrationTest {

    static final String USER = "00000000-0000-0000-0000-000000000099";

    /** Conto di costo normale (40.01.001 «Costo aziendale – Carlo», seed V4). */
    static final int CONTO_COSTO = 39;
    /** Secondo conto di costo normale (40.01.002 «Costo aziendale – Max»). */
    static final int CONTO_COSTO_2 = 40;
    /** Conto transitorio «Costi da classificare» (49.99.999, seed V4) — R7.4. */
    static final int CONTO_TRANSITORIO = 135;

    static final BigDecimal COSTO_MENSILE = new BigDecimal("300.00");

    @Inject EntityManager em;
    @Inject ForecastingService proxy;

    /**
     * L'istanza VERA dietro il client proxy CDI. Scrivere una @ConfigProperty sul proxy non
     * raggiunge il bean: misurato: impostando minMesiCosti = 2 il servizio continuava a rispondere
     * «servono 3 mesi interi». `ClientProxy.unwrap` restituisce l'oggetto contestuale.
     */
    private ForecastingService svc;

    @BeforeAll
    void unwrap() {
        svc = (ForecastingService) io.quarkus.arc.ClientProxy.unwrap(proxy);
    }

    private final UUID pianoAttivoId = UUID.randomUUID();

    /**
     * Due mesi interi di costi (luglio e agosto 2026, entrambi ≥ go-live) su quattro conti:
     * uno normale, uno transitorio, uno agganciato a un piano ricorrente ATTIVO, e uno con costi
     * imputati a un evento. Più una riga di cassa a oggi, che tiene {@code datiIncompleti} a false
     * (terza condizione del gate).
     */
    @BeforeAll
    @Transactional
    void seed() {
        for (YearMonth ym : List.of(YearMonth.of(2026, 7), YearMonth.of(2026, 8))) {
            LocalDate d = ym.atDay(15);
            costo("ZZP7 costo proiettabile", CONTO_COSTO, d, COSTO_MENSILE, null);
            costo("ZZP7 costo transitorio",  CONTO_TRANSITORIO, d, new BigDecimal("500.00"), null);
            costo("ZZP7 costo su piano ricorrente", CONTO_COSTO_2, d, new BigDecimal("900.00"), null);
        }

        // Il conto CONTO_COSTO_2 è agganciato a un piano ricorrente ATTIVO: le sue rate sono già nel
        // CERTO, stimarlo sarebbe il doppio conteggio più probabile della feature (R7.3).
        em.createNativeQuery("""
            INSERT INTO recurring_expense_plan (id, descrizione, business_unit_id, conto_bancario_id,
                conto_coge_id, importo_rata, giorno_del_mese, frequenza, numero_rate, data_prima_rata,
                stato, tipo_piano, created_by, created_at)
            VALUES (:id, 'ZZP7 piano attivo', 2, 2, :coge, 900.00, 15, 'MENSILE', 24,
                DATE '2026-07-15', 'ATTIVO', 'FLAT', CAST(:u AS uuid), now())
            """)
            .setParameter("id", pianoAttivoId).setParameter("coge", CONTO_COSTO_2)
            .setParameter("u", USER)
            .executeUpdate();

        // Cassa a oggi: senza questa datiIncompleti resta true e il gate non passa mai.
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, created_by, created_at)
            VALUES (:id, :oggi, :oggi, :oggi, :oggi, 'ENTRATA', 5.00, 0, 30, 2, 2,
                'ATTIVO', 'MANUALE', 'ZZP7 cassa fresca', CAST(:u AS uuid), now())
            """)
            .setParameter("id", UUID.randomUUID()).setParameter("oggi", LocalDate.now())
            .setParameter("u", USER)
            .executeUpdate();
    }

    private void costo(String desc, int conto, LocalDate competenza, BigDecimal importo, UUID evento) {
        em.createNativeQuery("""
            INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria, data_liquidita,
                tipo, importo_lordo, importo_commissione, conto_coge_id, conto_bancario_id,
                business_unit_id, stato, fonte, descrizione, evento_id, created_by, created_at)
            VALUES (:id, :d, :d, :d, :d, 'USCITA', :imp, 0, :coge, 2, 2,
                'ATTIVO', 'MANUALE', :desc, :evento, CAST(:u AS uuid), now())
            """)
            .setParameter("id", UUID.randomUUID()).setParameter("d", competenza)
            .setParameter("imp", importo).setParameter("coge", conto)
            .setParameter("desc", desc).setParameter("evento", evento)
            .setParameter("u", USER)
            .executeUpdate();
    }

    /** Esegue il forecasting con le soglie abbassate a 2 mesi (l'unico valore raggiungibile oggi). */
    private ForecastingRispostaDTO conSoglia2Mesi() {
        int minOrig = svc.minMesiCosti, finOrig = svc.finestraMesiCosti;
        try {
            svc.minMesiCosti = 2;
            svc.finestraMesiCosti = 2;
            return svc.computeForecasting("90");
        } finally {
            svc.minMesiCosti = minOrig;
            svc.finestraMesiCosti = finOrig;
        }
    }

    private static List<ForecastingDettaglioDTO> stimate(ForecastingRispostaDTO r) {
        return r.economico().dettaglio().stream()
                .filter(d -> "STIMATO".equals(d.affidabilita()))
                .filter(d -> d.importoUscita().signum() > 0)
                .toList();
    }

    @Test
    void precondizioni() {
        assertTrue(YearMonth.now().isAfter(YearMonth.of(2026, 8)),
            "questo test assume che luglio e agosto 2026 siano mesi INTERI passati");
    }

    /**
     * R7.2 — una riga per conto per mese futuro, importo = media mensile, affidabilita = STIMATO.
     * La media è il totale della finestra diviso i MESI DELLA FINESTRA (non i mesi con occorrenza):
     * un costo comparso in 2 mesi su 3 deve mediare più basso, non uguale.
     */
    @Test
    void r7_2_unaRigaPerContoPerMese_conMediaMensile() {
        ForecastingRispostaDTO r = conSoglia2Mesi();

        assertNull(r.economico().notaStimaCosti(),
            "con 2 mesi interi e soglia 2 il gate deve passare. Nota: " + r.economico().notaStimaCosti());

        List<ForecastingDettaglioDTO> righe = stimate(r).stream()
                .filter(d -> d.descrizione().contains("Carlo"))
                .toList();
        assertFalse(righe.isEmpty(), "il conto proiettabile deve produrre righe stimate");

        // Orizzonte 90 da oggi: i mesi la cui FINE cade nella finestra (convenzione ammortamenti).
        long mesiAttesi = righe.size();
        assertTrue(mesiAttesi >= 2, "atteso almeno un paio di mesi proiettati, trovati " + mesiAttesi);

        for (ForecastingDettaglioDTO d : righe) {
            assertEquals(COSTO_MENSILE, d.importoUscita(),
                "importo = media mensile (600,00 su 2 mesi di finestra = 300,00)");
            assertEquals(BigDecimal.ZERO, d.importoEntrata(), "una stima di costo non ha entrate");
            assertEquals("STIMATO", d.affidabilita());
            assertEquals("ENTRAMBE", d.vista());
            assertEquals(d.data(), d.data().withDayOfMonth(d.data().lengthOfMonth()),
                "la quota matura l'ultimo giorno del mese, come gli ammortamenti");
        }
        assertEquals(righe.size(), righe.stream().map(ForecastingDettaglioDTO::data).distinct().count(),
            "una riga per mese, non due sullo stesso mese");
    }

    /**
     * R7.3 — un conto agganciato a un piano ricorrente ATTIVO non produce riga stimata: le sue rate
     * sono già nel certo. È il doppio conteggio più probabile di tutta la feature.
     */
    @Test
    void r7_3_contoDiPianoRicorrenteAttivo_nonStimato() {
        assertTrue(stimate(conSoglia2Mesi()).stream()
                .noneMatch(d -> d.descrizione().contains("Max")),
            "il conto del piano ricorrente ATTIVO è già nel certo come rata: stimarlo lo conta due volte");
    }

    /** R7.4 — un conto transitorio non produce riga stimata: proiettare «da classificare» è inventare. */
    @Test
    void r7_4_contoTransitorio_nonStimato() {
        assertTrue(stimate(conSoglia2Mesi()).stream()
                .noneMatch(d -> d.descrizione().toLowerCase().contains("classificare")),
            "un conto transitorio non è un costo ricorrente, è un'attribuzione ancora da fare");
    }

    /** R7.5 — le uscite stimate non toccano il saldo progressivo, che resta sul solo certo (G3). */
    @Test
    void r7_5_saldoProgressivo_ignoraLeUsciteStimate() {
        ForecastingRispostaDTO r = conSoglia2Mesi();
        List<ForecastingTimelineDTO> tl = r.finanziario().timeline();

        assertTrue(tl.stream().anyMatch(t -> t.usciteStimate().signum() > 0),
            "precondizione: almeno un bucket deve avere uscite stimate");

        BigDecimal saldo = r.finanziario().saldoPartenza();
        for (ForecastingTimelineDTO t : tl) {
            saldo = saldo.add(t.entratePreviste()).subtract(t.uscitePreviste());
            assertEquals(0, saldo.compareTo(t.saldoLiquiditaFine()),
                "bucket " + t.bucket() + ": il saldo si muove solo col certo. Atteso " + saldo
              + ", trovato " + t.saldoLiquiditaFine());
        }
        assertEquals(0, r.finanziario().saldoFinale().compareTo(saldo),
            "G2: il saldo dell'ultimo bucket è il saldo finale, stime escluse");
    }

    /** Le stime non entrano nei subtotali CERTI: costiPrevisti resta la misura, non la congettura. */
    @Test
    void costiPrevisti_restaIlCerto() {
        ForecastingRispostaDTO r = conSoglia2Mesi();
        BigDecimal certi = r.economico().dettaglio().stream()
                .filter(d -> !"FINANZIARIA".equals(d.vista()))
                .filter(d -> !"STIMATO".equals(d.affidabilita()))
                .filter(d -> !d.categoria().startsWith("RATA_RICORRENTE")
                          && !"AMMORTAMENTO".equals(d.categoria()))
                .map(ForecastingDettaglioDTO::importoUscita)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, certi.compareTo(r.economico().costiPrevisti()),
            "costiPrevisti non deve contenere nessuna riga STIMATO");
    }

    /**
     * R7.7 — nessun mese ante go-live entra nella finestra. Con una finestra di 12 mesi la media
     * NON deve cambiare: i mesi che si aggiungerebbero sono tutti sotto il go-live e vengono tagliati.
     */
    @Test
    void r7_7_nessunMeseAnteGoLive_nellaFinestra() {
        // Un costo a maggio 2026, ben prima del go-live: non deve influenzare nessuna media.
        inserisciCostoAnteGoLive();

        int minOrig = svc.minMesiCosti, finOrig = svc.finestraMesiCosti;
        try {
            svc.minMesiCosti = 2;
            svc.finestraMesiCosti = 12;          // vorrebbe risalire a ottobre 2025
            List<ForecastingDettaglioDTO> righe = stimate(svc.computeForecasting("90")).stream()
                    .filter(d -> d.descrizione().contains("Carlo"))
                    .toList();
            assertFalse(righe.isEmpty(), "il gate deve passare anche con finestra 12");
            assertEquals(COSTO_MENSILE, righe.get(0).importoUscita(),
                "la finestra si ferma al go-live: 600,00 su 2 mesi = 300,00. Se il taglio non ci "
              + "fosse, il divisore sarebbe 12 e la media crollerebbe a 50,00.");
        } finally {
            svc.minMesiCosti = minOrig;
            svc.finestraMesiCosti = finOrig;
        }
    }

    @Transactional
    void inserisciCostoAnteGoLive() {
        costo("ZZP7 costo ante go-live", CONTO_COSTO, LocalDate.of(2026, 5, 15),
              new BigDecimal("9999.00"), null);
    }
}
