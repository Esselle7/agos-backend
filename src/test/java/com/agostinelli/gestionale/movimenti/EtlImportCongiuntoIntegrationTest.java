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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test dell'import ETL CONGIUNTO a PERIODO (PROMPT-RICONCILIAZIONE-PERIODO): carica i
 * 3 file NUOVI (Billy + BPM + CA) in UNA chiamata e verifica il modello Billy = verità: un ricavo
 * per scontrino elettronico non-agriturismo (categoria da Billy, conto da ripartizione), banche POS
 * NON contabilizzate, contanti su Cassa, coda testa esclusa per anno, coda fondo in attesa,
 * quadratura scomposta, idempotenza. Numeri reali §3: 20.769,38 / Σ_BPM 12.115,08 / Σ_CA 8.819,70 /
 * testa 230,00 / fondo 39,90 / contabilizzato 20.729,48 / residuo core 205,30.
 */
@QuarkusTest
class EtlImportCongiuntoIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path NEW = Path.of("..", "esempi_input_per_ETL_new");
    static final String BILLY = "corrispettivi-12.csv";
    static final String BPM = "MovimentiCC_OnLine_10_06_2026_11.56.28.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_06_10_115335.csv";

    @Inject MovimentoImportService importService;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService triageService;
    @Inject EntityManager em;

    @BeforeAll
    static void checkFixtures() {
        Assumptions.assumeTrue(Files.isDirectory(NEW), "Cartella esempi_input_per_ETL_new assente: test saltato");
        for (String f : new String[]{BILLY, BPM, CA}) {
            Assumptions.assumeTrue(Files.isRegularFile(NEW.resolve(f)), "Fixture assente: " + f);
        }
    }

    @BeforeEach
    void resetBefore() { cleanEtl(); }

    @AfterEach
    void resetAfter() { cleanEtl(); }

    void cleanEtl() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM movimenti WHERE fonte_importazione_id IS NOT NULL").executeUpdate();
            em.createNativeQuery("DELETE FROM import_log WHERE fonte IN "
                    + "('IMPORT_CONGIUNTO','IMPORT_BILLY','IMPORT_BANCA')").executeUpdate();
        });
    }

    @Test
    void importCongiunto_modelloAPeriodo() throws Exception {
        EtlImportResponse r = importaCongiunto();
        UUID logId = r.importLogId();
        assertTrue(r.importati() > 0, "deve creare movimenti");

        // ── (1) Niente Billy in massa in ambiguità (la criticità AS-IS resta risolta) ──
        assertEquals(0, ambiguitaConMotivo("BANCA_NON_IDENTIFICATA"),
                "senza colonna Banca, Billy NON deve più finire in BANCA_NON_IDENTIFICATA");

        // ── (2) I ricavi POS nascono da BILLY (108 scontrini elettronici non-agri, conto 1/2) ──
        assertEquals(108, movimenti("WHERE m.fonte_importazione_id = :id AND m.fonte = 'IMPORT_BILLY' "
                + "AND m.metodo_pagamento_id IN (SELECT id FROM metodi_pagamento WHERE codice IN ('POS_BPM','POS_CA_NEXI'))", logId),
                "un ricavo POS per scontrino elettronico non-agriturismo (108), tutti da Billy");
        assertEquals(0, movimenti("WHERE m.fonte_importazione_id = :id AND m.fonte = 'IMPORT_BILLY' "
                + "AND m.metodo_pagamento_id IN (SELECT id FROM metodi_pagamento WHERE codice IN ('POS_BPM','POS_CA_NEXI')) "
                + "AND m.conto_bancario_id NOT IN (1,2)", logId),
                "i ricavi POS stanno sui conti BPM(1)/CA(2)");

        // ── (3) Le righe banca POS NON sono più contabilizzate (sono duplicati di Billy) ──
        assertEquals(0, movimenti("WHERE m.fonte_importazione_id = :id AND m.fonte = 'IMPORT_BANCA' "
                + "AND m.metodo_pagamento_id IN (SELECT id FROM metodi_pagamento WHERE codice IN ('POS_BPM','POS_CA_NEXI'))", logId),
                "nessun ricavo POS deve nascere dalle righe banca");
        // Nessun ricavo POS resta sul transitorio 39.99.999 (categoria sempre da Billy).
        assertEquals(0, movimenti("WHERE m.fonte_importazione_id = :id AND m.tipo = 'ENTRATA' "
                + "AND m.conto_coge_id = (SELECT id FROM piano_dei_conti_coge WHERE codice='39.99.999') "
                + "AND m.metodo_pagamento_id IN (SELECT id FROM metodi_pagamento WHERE codice IN ('POS_BPM','POS_CA_NEXI'))", logId),
                "nessun ricavo POS resta sul transitorio 39.99.999");

        // ── (4) Eventi SOLO da banca (0 originati da Billy); agriturismo a POS = avviso, non evento ──
        long eventiBilly = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM eventi_da_riconciliare WHERE import_log_id = :id AND fonte = 'IMPORT_BILLY'")
                .setParameter("id", logId).getSingleResult()).longValue();
        assertEquals(0, eventiBilly, "nessun evento deve essere originato da Billy");
        assertTrue(r.parcheggiati() >= 1, "gli eventi (bonifici caparra/acconto/saldo) arrivano dalle banche");

        // ── (5) Contanti Billy NON-agriturismo → Cassa (conto 3, metodo CONTANTI) ──
        // 18 scontrini contanti totali, di cui 5 agriturismo (eventi) → esclusi: restano 13.
        assertEquals(13, movimentiMetodo(logId, "CONTANTI"), "13 contanti NON-agriturismo → metodo CONTANTI");
        assertEquals(13, movimenti("WHERE m.fonte_importazione_id = :id AND m.metodo_pagamento_id = "
                        + "(SELECT id FROM metodi_pagamento WHERE codice='CONTANTI') AND m.conto_bancario_id = 3", logId),
                "tutti i contanti devono stare su Cassa (conto 3)");
        // gli scontrini agriturismo (qualsiasi metodo) NON diventano movimenti: niente Cassa 30.01.001 da Billy
        assertEquals(0, movimenti("WHERE m.fonte_importazione_id = :id AND m.fonte = 'IMPORT_BILLY' "
                        + "AND m.conto_coge_id = (SELECT id FROM piano_dei_conti_coge WHERE codice='30.01.001')", logId),
                "agriturismo (eventi) escluso: nessun ricavo ristorazione da Billy");

        // ── (6) Versamento ATM (causale 78A) resta scartato (no doppio conteggio cassa→banca) ──
        assertTrue(scartatiConMotivo(logId, "SKIP_GIROCONTO") >= 1,
                "il versamento contante ATM (78A) deve restare scartato come giroconto");

        // ── (7) QUADRATURA DI PERIODO persistita (V10) con i numeri reali §3 ──
        Object[] q = (Object[]) em.createNativeQuery(
                "SELECT anno, billy_elettronico_non_agri, billy_contabilizzato, sigma_bpm, sigma_ca, "
                + "coda_testa, coda_fondo, residuo_core, assegnato_bpm, assegnato_ca FROM quadratura_periodo WHERE import_log_id = :id")
                .setParameter("id", logId).getSingleResult();
        assertEquals(2026, ((Number) q[0]).intValue());
        assertEquals(0, new BigDecimal("20769.38").compareTo((BigDecimal) q[1]), "Billy elettronico no-agri");
        assertEquals(0, new BigDecimal("20729.48").compareTo((BigDecimal) q[2]), "Billy contabilizzato (− coda fondo)");
        assertEquals(0, new BigDecimal("12115.08").compareTo((BigDecimal) q[3]), "Σ_BPM core");
        assertEquals(0, new BigDecimal("8819.70").compareTo((BigDecimal) q[4]), "Σ_CA core");
        assertEquals(0, new BigDecimal("230.00").compareTo((BigDecimal) q[5]), "coda testa (del 31/12/2025)");
        assertEquals(0, new BigDecimal("39.90").compareTo((BigDecimal) q[6]), "coda fondo in attesa di accredito");
        assertEquals(0, new BigDecimal("205.30").compareTo((BigDecimal) q[7]), "residuo core");
        // Ripartizione PROPORZIONALE: lo scarto (205,30) spalmato su entrambe (~0,98%), non tutto su CA.
        assertEquals(0, new BigDecimal("11995.79").compareTo((BigDecimal) q[8]), "assegnato BPM (proporzionale)");
        assertEquals(0, new BigDecimal("8733.69").compareTo((BigDecimal) q[9]), "assegnato CA (proporzionale)");
        // il totale ripartito torna sempre al contabilizzato
        assertEquals(0, new BigDecimal("20729.48").compareTo(((BigDecimal) q[8]).add((BigDecimal) q[9])), "BPM+CA = contabilizzato");

        // ── (7b) Endpoint getQuadratura: esercita il path SQL completo (CAST note/in_attesa + JSON) ──
        var dto = triageService.getQuadratura(logId);
        assertNotNull(dto, "getQuadratura deve restituire la quadratura dell'import");
        assertEquals(2026, dto.anno());
        assertEquals(0, new BigDecimal("20729.48").compareTo(dto.billyContabilizzato()));
        assertFalse(dto.note().isEmpty(), "le note (jsonb) devono essere deserializzate");
        assertFalse(dto.approssimazioni().isEmpty(), "le approssimazioni devono essere esposte all'utente");
        assertTrue(dto.approssimazioni().stream().anyMatch(s -> s.toUpperCase().contains("PROPORZIONALE")),
                "deve dichiarare la ripartizione proporzionale");
        assertEquals(1, dto.inAttesa().size(), "la coda fondo (1 scontrino) deve essere deserializzata");

        // ── (8) Coda fondo segnalata a parte (avvisi), non contabilizzata ──
        assertTrue(r.avvisi() != null && r.avvisi().stream().anyMatch(
                a -> a.messaggio() != null && a.messaggio().contains("IN_ATTESA_ACCREDITO")),
                "le vendite dopo l'ultima DEL sono segnalate in attesa di accredito");

        // ── (9) Idempotenza: re-import dello stesso periodo → 0 nuovi movimenti ──
        EtlImportResponse r2 = importaCongiunto();
        assertEquals(0, r2.importati(), "re-import dello stesso periodo non deve creare nuovi movimenti");
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    EtlImportResponse importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(NEW.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(NEW.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(NEW.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }

    long movimenti(String whereClause, UUID logId) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM movimenti m " + whereClause)
                .setParameter("id", logId).getSingleResult()).longValue();
    }

    long movimentiMetodo(UUID logId, String metodoCodice) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti m JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id "
                + "WHERE m.fonte_importazione_id = :id AND mp.codice = :cod")
                .setParameter("id", logId).setParameter("cod", metodoCodice).getSingleResult()).longValue();
    }

    long ambiguitaConMotivo(String motivo) {
        return ((Number) em.createNativeQuery("SELECT count(*) FROM import_ambiguita WHERE motivo = :m")
                .setParameter("m", motivo).getSingleResult()).longValue();
    }

    long scartatiConMotivo(UUID logId, String motivo) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM import_scartati WHERE import_log_id = :id AND motivo = :m")
                .setParameter("id", logId).setParameter("m", motivo).getSingleResult()).longValue();
    }
}
