package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.dto.RisolviScartatoRequest;
import com.agostinelli.gestionale.movimenti.dto.ScartatoDTO;
import com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService;
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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coda «Righe fuori dai conti» (docs/specs/righe-fuori-dai-conti.md, audit §7.4).
 *
 * <p>Parte dal corpus reale in {@code esempi_dati_storici/}: 9 righe SKIP_POS (959,55 €) + 1 riga
 * SKIP_CODA_TESTA (230,00 €) = 1.189,55 € di accrediti bancari che fino all'11/08/2026 esistevano
 * a DB ma non li mostrava nessuna schermata. Qui si verifica che l'endpoint le elenchi e che
 * «Mettila nei conti» crei ESATTAMENTE un movimento del giusto importo, sul conto giusto.
 */
@QuarkusTest
class RigheFuoriDaiContiIntegrationTest {

    static final UUID TEST_USER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final Path STORICI = Path.of("..", "esempi_dati_storici");
    static final String BILLY = "corrispettivi-12.csv";
    static final String BPM = "MovimentiCC_OnLine_10_06_2026_11.56.28.csv";
    static final String CA = "Movimenti_in_tempo_reale_2026_06_10_115335.csv";

    /** 49.99.999 «da classificare» costi — un conto qualunque, purché non 30.02.* (guard-rail §7.6). */
    static final String COGE_TRANSITORIO_COSTI = "49.99.999";

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
    void laCodaElencaLeRigheFuoriDaiConti_conIlPercheInChiaro() throws Exception {
        importaCongiunto();

        List<ScartatoDTO> coda = triageService.listScartati("DA_VEDERE", 0, 100).content();

        assertEquals(10, coda.size(), "9 SKIP_POS + 1 coda testa devono essere TUTTE elencate");
        BigDecimal totale = coda.stream().map(ScartatoDTO::importo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1189.55").compareTo(totale),
                "il denaro fuori dai conti è 959,55 + 230,00 = 1.189,55 €");

        ScartatoDTO codaTesta = trovaCodaTesta(coda);
        assertEquals("ENTRATA", codaTesta.tipo(), "è un accredito, non un addebito");
        assertNotNull(codaTesta.contoBancarioId(), "senza conto la riga non è contabilizzabile");
        assertTrue(codaTesta.normalizzabile(), "il grezzo della coda testa è ri-normalizzabile");
        assertFalse(codaTesta.motivoLeggibile().contains("SKIP_"),
                "il motivo va detto in italiano, non col codice interno: " + codaTesta.motivoLeggibile());

        // Regola §7.4 n.3: con la coda non a zero l'import non si dichiara «completato».
        String stato = (String) em.createNativeQuery(
                "SELECT stato FROM import_log ORDER BY data_import DESC LIMIT 1").getSingleResult();
        assertNotEquals("COMPLETATO", stato, "coda non vuota ⇒ l'import non dice «completato»");
    }

    @Test
    void mettilaNeiConti_creaUnSoloMovimentoDelGiustoImporto() throws Exception {
        importaCongiunto();
        ScartatoDTO riga = trovaCodaTesta(triageService.listScartati("DA_VEDERE", 0, 100).content());

        triageService.risolviScartato(riga.id(),
                new RisolviScartatoRequest("CONTABILIZZA", cogeId(COGE_TRANSITORIO_COSTI)), TEST_USER);

        Object[] creati = (Object[]) em.createNativeQuery(
                "SELECT count(*), COALESCE(sum(importo_lordo), 0) FROM movimenti m "
                + "JOIN import_scartati s ON s.movimento_id = m.id WHERE s.id = :id")
                .setParameter("id", riga.id()).getSingleResult();
        assertEquals(1L, ((Number) creati[0]).longValue(), "un solo movimento, non zero e non due");
        assertEquals(0, new BigDecimal("230.00").compareTo((BigDecimal) creati[1]),
                "il movimento vale quanto dice la banca, al centesimo");

        Object[] mov = (Object[]) em.createNativeQuery(
                "SELECT m.tipo, m.conto_bancario_id, m.metodo_pagamento_id, m.data_movimento FROM movimenti m "
                + "JOIN import_scartati s ON s.movimento_id = m.id WHERE s.id = :id")
                .setParameter("id", riga.id()).getSingleResult();
        assertEquals("ENTRATA", mov[0]);
        assertEquals(riga.contoBancarioId().shortValue(), ((Number) mov[1]).shortValue(),
                "il conto è quello dell'estratto conto, non uno scelto dal client");
        assertNotNull(mov[2], "metodo_pagamento_id mai NULL (lezione del 27/07)");
        assertEquals(riga.dataMovimento(), ((java.sql.Date) mov[3]).toLocalDate());

        // La riga resta, marcata: traccia conservata (I1) e coda scesa di uno.
        assertEquals("CONTABILIZZATA", statoDi(riga.id()));
        assertEquals(9, triageService.listScartati("DA_VEDERE", 0, 100).content().size());
    }

    @Test
    void laStessaRigaNonSiContabilizzaDueVolte() throws Exception {
        importaCongiunto();
        ScartatoDTO riga = trovaCodaTesta(triageService.listScartati("DA_VEDERE", 0, 100).content());
        Integer coge = cogeId(COGE_TRANSITORIO_COSTI);

        triageService.risolviScartato(riga.id(), new RisolviScartatoRequest("CONTABILIZZA", coge), TEST_USER);
        ApiException e = assertThrows(ApiException.class, () -> triageService.risolviScartato(
                riga.id(), new RisolviScartatoRequest("CONTABILIZZA", coge), TEST_USER));
        assertEquals("SCARTATO_GIA_RISOLTO", e.getCode());

        long quanti = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti m JOIN import_scartati s ON s.movimento_id = m.id "
                + "WHERE s.id = :id").setParameter("id", riga.id()).getSingleResult()).longValue();
        assertEquals(1, quanti, "il secondo click non deve raddoppiare il denaro");
    }

    @Test
    void lasciataFuori_nonToccaISaldi_maRestaTracciata() throws Exception {
        importaCongiunto();
        ScartatoDTO riga = trovaCodaTesta(triageService.listScartati("DA_VEDERE", 0, 100).content());
        long movimentiPrima = contaMovimentiImport();

        triageService.risolviScartato(riga.id(), new RisolviScartatoRequest("IGNORA", null), TEST_USER);

        assertEquals(movimentiPrima, contaMovimentiImport(), "«Lasciala fuori» non muove un centesimo");
        assertEquals("IGNORATA", statoDi(riga.id()), "la riga resta a DB, marcata (invariante I1)");
        assertEquals(9, triageService.listScartati("DA_VEDERE", 0, 100).content().size());
    }

    @Test
    void unCogeDiRicavoEventoVieneRifiutato() throws Exception {
        importaCongiunto();
        ScartatoDTO riga = trovaCodaTesta(triageService.listScartati("DA_VEDERE", 0, 100).content());
        Integer cogeEvento = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '30.02.%' ORDER BY codice LIMIT 1")
                .getSingleResult()).intValue();

        ApiException e = assertThrows(ApiException.class, () -> triageService.risolviScartato(
                riga.id(), new RisolviScartatoRequest("CONTABILIZZA", cogeEvento), TEST_USER));
        assertEquals("COGE_RISERVATO_EVENTI", e.getCode());
        assertEquals("DA_VEDERE", statoDi(riga.id()), "il rifiuto non consuma la riga");
    }

    // ── helper ──────────────────────────────────────────────────────────────────

    ScartatoDTO trovaCodaTesta(List<ScartatoDTO> coda) {
        return coda.stream().filter(s -> "SKIP_CODA_TESTA".equals(s.motivo())).findFirst()
                .orElseThrow(() -> new AssertionError("la riga da 230,00 € (coda testa) manca dalla coda"));
    }

    String statoDi(UUID id) {
        return (String) em.createNativeQuery("SELECT stato FROM import_scartati WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    long contaMovimentiImport() {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM movimenti WHERE fonte_importazione_id IS NOT NULL")
                .getSingleResult()).longValue();
    }

    Integer cogeId(String codice) {
        return ((Number) em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getSingleResult()).intValue();
    }

    EtlImportResponse importaCongiunto() throws Exception {
        try (InputStream b = new FileInputStream(STORICI.resolve(BILLY).toFile());
             InputStream bpm = new FileInputStream(STORICI.resolve(BPM).toFile());
             InputStream ca = new FileInputStream(STORICI.resolve(CA).toFile())) {
            return importService.importCongiunto(b, bpm, ca, BILLY, BPM, CA, TEST_USER);
        }
    }
}
