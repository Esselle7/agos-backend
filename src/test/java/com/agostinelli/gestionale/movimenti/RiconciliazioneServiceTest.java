package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.DatasetRiconciliato;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.DettagliBilly;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.EsitoMatch;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.QuadraturaPeriodo;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.RawMovimentoArricchito;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.RiconciliazioneService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) della funzione pura {@link RiconciliazioneService} nel modello a PERIODO
 * (PROMPT-RICONCILIAZIONE-PERIODO §4): Billy = verità per i ricavi, banche solo per ripartizione
 * e quadratura. Copre: ricavo POS da Billy, ripartizione deterministica BPM/CA, contanti → Cassa,
 * agriturismo elettronico = evento atteso, coda testa esclusa per anno, coda fondo in attesa,
 * quadratura scomposta, idempotenza/indipendenza dall'ordine.
 */
class RiconciliazioneServiceTest {

    private final RiconciliazioneService svc = new RiconciliazioneService();
    private static final LocalDate D = LocalDate.of(2026, 6, 5);

    @Test
    void ricavoPos_elettronicoNonAgri_categoriaDaBilly() {
        RawMovimento scontrino = billyEle(D, "39.90", "carne", "DCW/1");
        RawMovimento banca = bankPos((short) 1, "39.90", D, D.plusDays(1));

        DatasetRiconciliato ds = svc.riconcilia(List.of(scontrino), List.of(banca), List.of());

        DettagliBilly d = soloArricchito(ds);
        assertEquals(EsitoMatch.RICAVO_POS, d.esito());
        assertEquals("30.03.001", d.cogeCodice(), "carne → 30.03.001");
        assertEquals((short) 3, d.bu());
        assertEquals(0, new BigDecimal("0.10").compareTo(d.aliquotaIva()));
        assertEquals(1, ds.billyContabilizzati().size());
        assertEquals(0, ds.inAttesaAccredito().size());
        assertEquals(0, ds.eventiAttesi().size());
        // un solo POS BPM (Σ_BPM = 39,90) → assegnato a BPM
        assertEquals((short) 1, d.contoBancarioId());
        assertEquals("POS_BPM", d.metodoCodice());
    }

    @Test
    void ripartizione_totaliComplessiviTornano_eDeterministica() {
        // Ricavi Billy: 50 + 30 + 20 = 100. Banche: BPM 60, CA 40 (Σtot=100=tot → nessun residuo).
        RawMovimento s1 = billyEle(D, "50.00", "carne", "a");
        RawMovimento s2 = billyEle(D, "30.00", "carne", "b");
        RawMovimento s3 = billyEle(D, "20.00", "carne", "c");
        RawMovimento bpm = bankPos((short) 1, "60.00", D, D.plusDays(1));
        RawMovimento ca = bankPos((short) 2, "40.00", D, D.plusDays(1));

        DatasetRiconciliato ds = svc.riconcilia(List.of(s1, s2, s3), List.of(bpm), List.of(ca));
        QuadraturaPeriodo q = ds.quadratura();

        // target proporzionale BPM = 100 * 60/100 = 60. Il totale torna sempre al centesimo.
        assertEquals(0, new BigDecimal("100.00").compareTo(q.billyContabilizzato()));
        assertEquals(0, q.assegnatoBpm().add(q.assegnatoCa()).compareTo(new BigDecimal("100.00")));

        // determinismo: invertendo l'ordine, stessa ripartizione
        var rev = new ArrayList<>(List.of(s3, s1, s2));
        DatasetRiconciliato ds2 = svc.riconcilia(rev, List.of(bpm), List.of(ca));
        assertEquals(0, ds.quadratura().assegnatoBpm().compareTo(ds2.quadratura().assegnatoBpm()));
        assertEquals(0, ds.quadratura().assegnatoCa().compareTo(ds2.quadratura().assegnatoCa()));
    }

    /**
     * REGRESSION (2026-08-09) — le banche sono l'ossatura: ogni riga POS bancaria diventa un
     * movimento con il SUO conto e il SUO importo. Prima di questo fix i ricavi nascevano dagli
     * scontrini Billy con banca assegnata per riempimento proporzionale: BPM riceveva 72,00 e CA
     * 48,00 (cioè né l'una né l'altra il proprio incasso reale) e le righe banca sparivano.
     */
    @Test
    void banchePos_sonoLOssatura_importoEContoRestanoQuelliDellaBanca() {
        List<RawMovimento> scontrini = new ArrayList<>();
        for (int i = 0; i < 10; i++) scontrini.add(billyEle(D, "12.00", "carne", "s" + i));
        RawMovimento bpm = bankPos((short) 1, "90.00", D, D.plusDays(1));
        RawMovimento ca = bankPos((short) 2, "50.00", D, D.plusDays(1));

        DatasetRiconciliato ds = svc.riconcilia(scontrini, List.of(bpm), List.of(ca));
        QuadraturaPeriodo q = ds.quadratura();

        // Ogni banca porta a libro ESATTAMENTE il proprio incasso POS, non una quota calcolata.
        assertEquals(0, new BigDecimal("90.00").compareTo(q.assegnatoBpm()), "BPM = il suo POS reale");
        assertEquals(0, new BigDecimal("50.00").compareTo(q.assegnatoCa()), "CA = il suo POS reale");
        assertEquals(0, q.assegnatoBpm().compareTo(q.sigmaBpm()), "niente scarto: la riga banca è il movimento");
        assertEquals(0, q.assegnatoCa().compareTo(q.sigmaCa()), "niente scarto: la riga banca è il movimento");
        assertEquals(2, ds.stat().ricaviPos(), "entrambe le righe POS bancarie sono contabilizzate");

        // Nessuno scontrino Billy elettronico diventa un movimento bancario: solo le righe banca.
        long daBanca = ds.daMappare().stream().filter(a -> a.isArricchito()).count();
        assertEquals(2, daBanca, "esattamente le 2 righe POS bancarie, nessuno scontrino Billy");
    }

    @Test
    void contanti_diventaCassa() {
        RawMovimento contante = billyContante(D, "50.00", "carne");

        DatasetRiconciliato ds = svc.riconcilia(List.of(contante), List.of(), List.of());

        DettagliBilly d = soloArricchito(ds);
        assertEquals(EsitoMatch.CONTANTI, d.esito());
        assertEquals("CONTANTI", d.metodoCodice());
        assertEquals((short) 3, d.contoBancarioId());
        assertEquals(1, ds.stat().contanti());
    }

    @Test
    void agriturismoElettronico_eventoAtteso_nonContabilizzato() {
        RawMovimento agri = billyAgriEle(D, "2000.00");

        DatasetRiconciliato ds = svc.riconcilia(List.of(agri), List.of(), List.of());

        assertEquals(1, ds.eventiAttesi().size(), "agriturismo a POS → evento atteso");
        assertEquals(0, ds.billyContabilizzati().size(), "nessun ricavo spaccio dall'agriturismo");
        assertTrue(ds.daMappare().isEmpty());
    }

    @Test
    void agriturismoContanti_esclusoComeEvento_nonVaInCassa() {
        // L'agriturismo è materia del modulo Eventi a PRESCINDERE dal metodo: anche un corrispettivo
        // agriturismo in CONTANTI dev'essere escluso (non finire in Cassa come ristorazione).
        RawMovimento agriCash = billy(D, "500.00", "C", new BigDecimal("500.00"), null, null, "agri-cash");

        DatasetRiconciliato ds = svc.riconcilia(List.of(agriCash), List.of(), List.of());

        assertEquals(1, ds.eventiAttesi().size(), "agriturismo contante → escluso (evento)");
        assertEquals(0, ds.stat().contanti(), "non deve diventare un movimento di Cassa");
        assertTrue(ds.daMappare().isEmpty(), "nessun movimento contabilizzato");
    }

    @Test
    void codaTesta_annoPrecedente_esclusaDaiTotali() {
        RawMovimento scontrino = billyEle(D, "10.00", "carne", "x");
        RawMovimento core = bankPos((short) 1, "10.00", D, D.plusDays(1));
        RawMovimento testa = bankPos((short) 1, "230.00", LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 2));

        DatasetRiconciliato ds = svc.riconcilia(List.of(scontrino), List.of(core, testa), List.of());

        assertEquals(1, ds.stat().testaEsclusa());
        // F1 (audit 2026-08-11): la riga esclusa esce NOMINATA, non solo contata — l'orchestratore
        // la scrive in import_scartati. Prima spariva: 230,00 € di accredito vero fuori da ogni coda.
        assertEquals(1, ds.codaTesta().size(), "la riga esclusa deve restare visibile");
        assertEquals(0, new BigDecimal("230.00").compareTo(ds.codaTesta().get(0).importo()));
        QuadraturaPeriodo q = ds.quadratura();
        assertEquals(2026, q.anno());
        assertEquals(0, new BigDecimal("230.00").compareTo(q.codaTesta()));
        assertEquals(0, new BigDecimal("10.00").compareTo(q.sigmaBpm()), "la testa non entra in Σ_BPM");
        assertEquals(0, new BigDecimal("240.00").compareTo(q.posBancaTotale()));
        assertEquals(0, new BigDecimal("10.00").compareTo(q.posBancaCore()));
    }

    @Test
    void codaFondo_vendutoDopoMaxDel_inAttesaNonContabilizzato() {
        // maxDEL banca = 5/6; uno scontrino venduto il 7/6 (dopo) → in attesa di accredito
        RawMovimento venduto = billyEle(D, "10.00", "carne", "ok");
        RawMovimento dopo = billyEle(D.plusDays(2), "39.90", "carne", "fondo");
        RawMovimento banca = bankPos((short) 1, "10.00", D, D.plusDays(1));

        DatasetRiconciliato ds = svc.riconcilia(List.of(venduto, dopo), List.of(banca), List.of());

        assertEquals(1, ds.stat().inAttesaAccredito());
        assertEquals(1, ds.inAttesaAccredito().size());
        assertEquals(0, new BigDecimal("39.90").compareTo(ds.quadratura().codaFondo()));
        assertEquals(1, ds.billyContabilizzati().size(), "solo il venduto entro maxDEL è contabilizzato");
        // billyElettronicoNonAgri include tutto (49,90), contabilizzato solo 10,00
        assertEquals(0, new BigDecimal("49.90").compareTo(ds.quadratura().billyElettronicoNonAgri()));
        assertEquals(0, new BigDecimal("10.00").compareTo(ds.quadratura().billyContabilizzato()));
    }

    /**
     * REGRESSION (2026-08-09) — a libro va l'incasso REALE della banca, non il totale Billy.
     * Il campo residuoCore è stato rimosso l'11/08 (era ≡ 0 per costruzione: guardia vacua,
     * audit §8 #9); lo scarto che può davvero divergere — POS banca − Billy spaccio — resta
     * esposto come nota informativa, e questo test lo presidia.
     */
    @Test
    void quadratura_aLibroVaLIncassoDellaBanca_eLoScartoConBillyResta() {
        // Billy 100 (tutto entro maxDEL), banca POS core 120: la banca porta a libro 120.
        RawMovimento s = billyEle(D, "100.00", "carne", "s");
        RawMovimento banca = bankPos((short) 1, "120.00", D, D.plusDays(1));

        QuadraturaPeriodo q = svc.riconcilia(List.of(s), List.of(banca), List.of()).quadratura();

        assertEquals(0, new BigDecimal("120.00").compareTo(q.billyContabilizzato()),
                "a libro va l'incasso reale della banca (120), non il totale Billy (100)");
        // Lo scarto POS-banca vs Billy resta esposto come informazione, senza toccare i saldi.
        assertTrue(q.note().stream().anyMatch(n -> n.contains("Scarto informativo")),
                "il pannello espone lo scarto POS banca − Billy spaccio");
    }

    @Test
    void idempotenza_indipendenteDallOrdine() {
        RawMovimento s1 = billyEle(D, "39.90", "carne", "a");
        RawMovimento s2 = billyEle(D, "57.00", "carne", "b");
        RawMovimento b1 = bankPos((short) 1, "50.00", D, D.plusDays(1));
        RawMovimento b2 = bankPos((short) 2, "46.90", D, D.plusDays(1));

        var a = svc.riconcilia(List.of(s1, s2), List.of(b1), List.of(b2));
        var billyRev = new ArrayList<>(List.of(s1, s2)); Collections.reverse(billyRev);
        var b = svc.riconcilia(billyRev, List.of(b1), List.of(b2));

        assertEquals(a.stat().ricaviPos(), b.stat().ricaviPos());
        assertEquals(a.stat().assegnatiBpm(), b.stat().assegnatiBpm());
        assertEquals(a.stat().assegnatiCa(), b.stat().assegnatiCa());
        assertEquals(0, a.quadratura().assegnatoBpm().compareTo(b.quadratura().assegnatoBpm()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private DettagliBilly soloArricchito(DatasetRiconciliato ds) {
        List<RawMovimentoArricchito> a = ds.daMappare().stream().filter(RawMovimentoArricchito::isArricchito).toList();
        assertEquals(1, a.size(), "atteso un solo arricchito");
        return a.get(0).dettagli();
    }

    /** Scontrino Billy elettronico non-agriturismo. categoria = "carne" | "orto". */
    private RawMovimento billyEle(LocalDate data, String importo, String categoria, String rif) {
        BigDecimal carne = "carne".equals(categoria) ? new BigDecimal(importo) : null;
        BigDecimal orto = "orto".equals(categoria) ? new BigDecimal(importo) : null;
        return billy(data, importo, "E", null, carne, orto, rif);
    }

    private RawMovimento billyContante(LocalDate data, String importo, String categoria) {
        BigDecimal carne = "carne".equals(categoria) ? new BigDecimal(importo) : null;
        return billy(data, importo, "C", null, carne, null, "cash-" + importo);
    }

    private RawMovimento billyAgriEle(LocalDate data, String importo) {
        return billy(data, importo, "E", new BigDecimal(importo), null, null, "agri-" + importo);
    }

    private RawMovimento billy(LocalDate data, String importo, String pag,
                               BigDecimal agri, BigDecimal carne, BigDecimal orto, String rif) {
        Map<String, String> campi = new HashMap<>();
        campi.put("_SORGENTE", "BILLY");
        campi.put("PAGAMENTO", pag);
        RawRow row = new RawRow(1, campi);
        return new RawMovimento(1, "IMPORT_BILLY", data, null, new BigDecimal(importo), "ENTRATA",
                "SCONTRINO " + rif, null, "C".equals(pag) ? "CONTANTI" : null, BigDecimal.ZERO, null,
                rif, null, agri, null, carne, orto, "SCONTRINO" + rif, "", EntitaEstratte.EMPTY, row, null, null);
    }

    /** Riga banca POS: conto 1=BPM (circuito NUMIA) / 2=CA (NEXI), con data reale DEL. */
    private RawMovimento bankPos(short conto, String importo, LocalDate del, LocalDate contabile) {
        Map<String, String> campi = new HashMap<>();
        campi.put("_SORGENTE", conto == 1 ? "BPM" : "CA");
        RawRow row = new RawRow(1, campi);
        String metodo = conto == 1 ? "POS_BPM" : "POS_CA_NEXI";
        String circuito = conto == 1 ? "NUMIA" : "NEXI";
        String desc = "INCASSO POS DEL " + del;
        return new RawMovimento(1, "IMPORT_BANCA", contabile, null, new BigDecimal(importo), "ENTRATA",
                desc, conto, metodo, BigDecimal.ZERO, null, "rif-" + conto + "-" + importo + "-" + del, null,
                null, null, null, null, desc.replace(" ", ""), "", EntitaEstratte.EMPTY, row, del, circuito);
    }
}
