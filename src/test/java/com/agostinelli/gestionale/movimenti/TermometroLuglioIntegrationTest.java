package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;

import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TERMOMETRO — apparato di misura dell'import di luglio 2026 (non è un test di regressione:
 * è lo strumento che stampa i delta contro gli ORACOLI confermati dal proprietario).
 *
 * <p>Gira su {@code agosdb_test} (profilo %test, flyway clean-at-start). NON tocca mai agosdb.
 * L'import passa dal servizio applicativo {@link MovimentoImportService#importCongiunto} — lo
 * stesso punto d'ingresso dell'endpoint {@code /import/congiunto} — non da SQL grezzo.
 *
 * <p>Riseguibile e idempotente: pulisce prima e dopo, riscrive i saldi iniziali agli oracoli.
 *
 * <p>ORACOLI (fonte: estratti conto + conteggio a mano del proprietario, 09/08/2026):
 * <pre>
 *   Billy luglio lordo         17.738,60
 *   BPM   entrate/uscite/netto  8.037,28 / 11.293,63 / −3.256,35
 *   CA    entrate/uscite/netto 34.321,86 / 38.043,20 / −3.721,34
 *   saldo 30/06 BPM / CA          486,93 /  8.564,05
 *   saldo atteso 31/07 BPM / CA −2.769,42 / +4.842,71
 * </pre>
 */
@QuarkusTest
class TermometroLuglioIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path DIR = Path.of("..", "dati_luglio");
    static final String BILLY = "corrispettivi-12 (1).csv";
    static final String BPM = "MovimentiCC_OnLine_07_08_2026_05.51.09.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_08_07_054028.csv";

    // ── ORACOLI ──
    static final BigDecimal O_BILLY_LORDO = new BigDecimal("17738.60");
    /** Accrediti POS di luglio sui due estratti conto: 3.994,73 (BPM) + 3.025,00 (CA). */
    static final BigDecimal O_POS_BANCARIO = new BigDecimal("7019.73");
    static final BigDecimal O_SALDO_BPM_3006 = new BigDecimal("486.93");
    static final BigDecimal O_SALDO_CA_3006 = new BigDecimal("8564.05");
    static final BigDecimal O_SALDO_BPM_3107 = new BigDecimal("-2769.42");
    static final BigDecimal O_SALDO_CA_3107 = new BigDecimal("4842.71");
    static final BigDecimal O_BPM_ENTRATE = new BigDecimal("8037.28");
    static final BigDecimal O_BPM_USCITE = new BigDecimal("11293.63");
    static final BigDecimal O_CA_ENTRATE = new BigDecimal("34321.86");
    static final BigDecimal O_CA_USCITE = new BigDecimal("38043.20");

    @Inject MovimentoImportService importService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(DIR), "Cartella dati_luglio assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(DIR.resolve(f)), "Fixture assente: " + f);
        }
    }

    /** Saldi iniziali com'erano PRIMA che il termometro li riscrivesse (vedi {@link #after()}). */
    private Object[][] saldiOriginali;

    @BeforeEach
    void before() {
        saldiOriginali = fotografaSaldiIniziali();
        reset();
    }

    /**
     * Il termometro riscrive {@code conti_bancari} e riempie {@code movimenti}: senza ripristino
     * si porta dietro le altre classi della suite, che girano sullo STESSO {@code agosdb_test}.
     * Misurato il 2026-08-11: da solo verde, ma {@code TermometroLuglioIntegrationTest} +
     * {@code SpeseRicorrentiIntegrationTest} faceva fallire 10 test su 52 di quest'ultima —
     * fallimenti fantasma che nascondevano le regressioni vere in una suite da 688 test.
     */
    @AfterEach
    void after() {
        reset();
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            for (Object[] r : saldiOriginali) {
                em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = :s, data_saldo_iniziale = :d WHERE id = :id")
                        .setParameter("s", r[1]).setParameter("d", r[2]).setParameter("id", r[0])
                        .executeUpdate();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private Object[][] fotografaSaldiIniziali() {
        List<Object[]> righe = em.createNativeQuery(
                "SELECT id, saldo_iniziale, data_saldo_iniziale FROM conti_bancari WHERE id IN (1, 2)")
                .getResultList();
        return righe.toArray(new Object[0][]);
    }

    /** Idempotente: cancella i dati d'import e riporta i saldi iniziali agli oracoli del 30/06. */
    void reset() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM eventi_da_riconciliare").executeUpdate();
            em.createNativeQuery("DELETE FROM ricorrenti_da_riconciliare").executeUpdate();
            em.createNativeQuery("DELETE FROM import_ambiguita").executeUpdate();
            em.createNativeQuery("DELETE FROM import_scartati").executeUpdate();
            em.createNativeQuery("DELETE FROM quadratura_periodo").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN "
                    + "('IMPORT_CONGIUNTO','IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
            em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = :s, data_saldo_iniziale = DATE '2026-06-30' WHERE id = 1")
                    .setParameter("s", O_SALDO_BPM_3006).executeUpdate();
            em.createNativeQuery("UPDATE conti_bancari SET saldo_iniziale = :s, data_saldo_iniziale = DATE '2026-06-30' WHERE id = 2")
                    .setParameter("s", O_SALDO_CA_3006).executeUpdate();
        });
    }

    @Test
    void termometro() throws Exception {
        EtlImportResponse r = importaCongiunto();
        UUID logId = r.importLogId();

        List<String> fail = new ArrayList<>();
        StringBuilder o = new StringBuilder();
        o.append("\n╔══════════════════════════════════════════════════════════════════════════════════════════\n");
        o.append("║ TERMOMETRO — import luglio 2026 su agosdb_test · importLogId ").append(logId).append('\n');
        o.append("╠══════════════════════════════════════════════════════════════════════════════════════════\n");
        // NB: i 17.738,60 di Billy sono un dato di CONTO ECONOMICO (corrispettivi lordi), non di
        // cassa: dal 09/08/2026 i movimenti bancari nascono dalle righe banca, quindi la misura
        // confrontabile con l'estratto conto è l'incasso POS accreditato (3.994,73 BPM + 3.025,00 CA).
        riga(o, fail, "POS bancario contabilizzato", O_POS_BANCARIO, posContabilizzato(logId));
        info(o, "  (Billy lordo, corrispettivi)", O_BILLY_LORDO);
        riga(o, fail, "BPM  entrate (conto 1)", O_BPM_ENTRATE, flusso(logId, 1, "ENTRATA"));
        riga(o, fail, "BPM  uscite  (conto 1)", O_BPM_USCITE, flusso(logId, 1, "USCITA"));
        riga(o, fail, "CA   entrate (conto 2)", O_CA_ENTRATE, flusso(logId, 2, "ENTRATA"));
        riga(o, fail, "CA   uscite  (conto 2)", O_CA_USCITE, flusso(logId, 2, "USCITA"));
        o.append("╠── saldi al 31/07 (formula V24: saldo_iniziale + movimenti > data_saldo_iniziale) ────────\n");
        riga(o, fail, "SALDO BPM", O_SALDO_BPM_3107, saldo(1));
        riga(o, fail, "SALDO CA ", O_SALDO_CA_3107, saldo(2));
        o.append("╠── residuo non contabilizzato (deve tendere a zero) ──────────────────────────────────────\n");
        info(o, "parcheggiati eventi", scalare("SELECT COALESCE(sum(importo),0) FROM eventi_da_riconciliare"));
        info(o, "parcheggiati ricorrenti", scalare("SELECT COALESCE(sum(importo),0) FROM ricorrenti_da_riconciliare"));
        info(o, "ambigui (n righe)", scalare("SELECT count(*) FROM import_ambiguita"));
        info(o, "scartati (n righe)", scalare("SELECT count(*) FROM import_scartati"));
        info(o, "coda testa (POS anno prec.)", scalare("SELECT COALESCE(sum(importo),0) FROM import_scartati WHERE motivo = 'SKIP_CODA_TESTA'"));
        info(o, "movimenti creati", BigDecimal.valueOf(r.importati()));
        o.append("╚══════════════════════════════════════════════════════════════════════════════════════════\n");
        System.out.println(o);

        if (!fail.isEmpty()) {
            Assertions.fail("Oracoli non soddisfatti (" + fail.size() + "):\n  - " + String.join("\n  - ", fail));
        }
    }

    // ── misure ────────────────────────────────────────────────────────────────────

    /** Incassi POS accreditati in banca e contabilizzati (metodo POS_BPM / POS_CA_NEXI). */
    BigDecimal posContabilizzato(UUID logId) {
        return scalare("SELECT COALESCE(sum(m.importo_lordo),0) FROM movimenti m "
                + "JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id "
                + "WHERE m.fonte_importazione_id = '" + logId + "' AND m.tipo = 'ENTRATA' "
                + "AND mp.codice IN ('POS_BPM','POS_CA_NEXI')");
    }

    /** Flusso per conto: SOLO i movimenti di origine bancaria (gli scontrini Billy non sono righe di banca). */
    BigDecimal flusso(UUID logId, int conto, String tipo) {
        return scalare("SELECT COALESCE(sum(importo_lordo),0) FROM movimenti "
                + "WHERE fonte_importazione_id = '" + logId + "' AND fonte = 'IMPORT_BANCA' "
                + "AND conto_bancario_id = " + conto + " AND tipo = '" + tipo + "'");
    }

    /** Saldo calcolato con la stessa formula di mv_saldi_conti / DashboardService (V24). */
    BigDecimal saldo(int conto) {
        return scalare("SELECT cb.saldo_iniziale + COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' THEN m.importo_lordo "
                + "WHEN m.tipo='USCITA' THEN -m.importo_lordo ELSE 0 END),0) "
                + "FROM conti_bancari cb LEFT JOIN movimenti m ON m.conto_bancario_id = cb.id "
                + "  AND m.stato <> 'ANNULLATO' AND m.data_finanziaria IS NOT NULL "
                + "  AND (cb.data_saldo_iniziale IS NULL OR COALESCE(m.data_finanziaria, m.data_movimento) > cb.data_saldo_iniziale) "
                + "WHERE cb.id = " + conto + " GROUP BY cb.id, cb.saldo_iniziale");
    }

    BigDecimal scalare(String sql) {
        Object v = em.createNativeQuery(sql).getResultList().stream().findFirst().orElse(BigDecimal.ZERO);
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    // ── stampa ────────────────────────────────────────────────────────────────────

    void riga(StringBuilder o, List<String> fail, String etichetta, BigDecimal atteso, BigDecimal reale) {
        BigDecimal delta = reale.subtract(atteso);
        boolean ok = delta.compareTo(BigDecimal.ZERO) == 0;
        o.append(String.format("║ %-28s atteso %14s   reale %14s   delta %14s  %s%n",
                etichetta, atteso.toPlainString(), reale.toPlainString(), delta.toPlainString(), ok ? "OK" : "**"));
        if (!ok) fail.add(etichetta + ": atteso " + atteso.toPlainString()
                + ", reale " + reale.toPlainString() + ", delta " + delta.toPlainString());
    }

    void info(StringBuilder o, String etichetta, BigDecimal v) {
        o.append(String.format("║ %-28s %14s%n", etichetta, v.toPlainString()));
    }

    EtlImportResponse importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(DIR.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(DIR.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(DIR.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
