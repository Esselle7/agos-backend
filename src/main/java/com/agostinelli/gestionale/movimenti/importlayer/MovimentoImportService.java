package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.EtlImportResponse;
import com.agostinelli.gestionale.movimenti.dto.EtlRowError;
import com.agostinelli.gestionale.movimenti.dto.FaseImportDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.DatasetRiconciliato;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.RawMovimentoArricchito;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.RiconciliazioneService;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrazione dell'import ETL (Billy / BPM / CA):
 *  parse → normalize → map → (dedup) → persist diretto.
 *
 * Le righe ambigue NON vengono salvate in movimenti: finiscono in import_ambiguita
 * con stato DA_CLASSIFICARE per revisione manuale. Il refresh delle materialized view
 * e l'invalidazione cache avvengono UNA sola volta al termine del loop.
 */
@ApplicationScoped
public class MovimentoImportService {

    private static final Logger log = Logger.getLogger(MovimentoImportService.class);

    @Inject EntityManager em;
    @Inject MovimentiService movimentiService;
    @Inject MovimentiRepository repo;
    @Inject MvRefreshService mvRefresh;
    @Inject MovimentoMappingEngineImpl mappingEngine;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.parser.BillyParser billyParser;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.parser.BancaBpmParser bpmParser;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.parser.BancaCaParser caParser;
    @Inject MovimentoNormalizerImpl normalizer;
    @Inject RiconciliazioneService riconciliazione;
    @Inject ObjectMapper objectMapper;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService keywordLearning;
    @Inject MatchingDifferitiService matchingDifferitiService;
    @Inject ContatoreImportService contatore;
    @Inject ImportAuditLog audit;   // AUDIT-TEMP: traccia decisionale su file, vedi ImportAuditLog

    /**
     * Import ETL CONGIUNTO (REFACTOR-IMPORT-CONGIUNTO §FASE4): i 3 file (Billy + BPM + CA)
     * dello stesso periodo vengono caricati insieme e riconciliati in un'unica fonte di verità
     * PRIMA della persistenza. Le banche sono l'ossatura (banca/data/importo/metodo); Billy
     * arricchisce gli incassi POS con la categoria (→ COGE/BU/IVA).
     *
     * <p>UNA sola riga import_log (fonte = IMPORT_CONGIUNTO) per rollback atomico dei 3 file;
     * i singoli movimenti mantengono la loro fonte reale per-riga (IMPORT_BANCA / IMPORT_BILLY)
     * così la dedup su riferimento_esterno resta valida. Nessuno stato nuovo: gli incassi POS
     * senza match finiscono sul transitorio 39.99.999 (triage "Da catalogare").
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public EtlImportResponse importCongiunto(InputStream billy, InputStream bpm, InputStream ca,
                                             String fnBilly, String fnBpm, String fnCa, UUID userId) {
        // Le native executeUpdate dei rami skip (scartati/ambigui/parcheggiati) altrimenti
        // forzano un auto-flush del persistence context ad ogni riga: rompono il batch JDBC dei
        // movimenti managed e ri-dirty-checkano il context che cresce (O(n²): 27ms/riga su 949).
        // COMMIT differisce il flush dei managed al commit (batch unico); le native colpiscono il
        // DB subito comunque, e nel loop nessuna native rilegge i movimenti persistiti → sicuro.
        em.setFlushMode(FlushModeType.COMMIT);

        mappingEngine.refreshLookups();

        UUID importLogId = creaImportLog("IMPORT_CONGIUNTO", fileLabel(fnBilly, fnBpm, fnCa), userId);

        // ── [1] PARSE + NORMALIZE (invariato) ──
        // Parser scelti a compile time: le 3 fonti sono fisse e note qui. C'era una
        // ImportStrategy + Factory + @All risolta con stringhe letterali ("IMPORT_BILLY", …):
        // astrazione senza guadagno, rimossa il 2026-08-11 (audit §6.2/5, intervento §8 #10).
        // Il normalizzatore è lo stesso oggetto per tutte e 3 le sorgenti (era già così).

        List<RawMovimento> normBilly = new ArrayList<>();
        List<RawMovimento> normBpm = new ArrayList<>();
        List<RawMovimento> normCa = new ArrayList<>();
        List<EtlRowError> errori = new ArrayList<>();

        // I passi che l'utente vede a schermo (FaseImportDTO): cronometrati QUI, nei punti che li
        // eseguono davvero. Se un giorno una fase sparisce dal codice, sparisce anche dalla
        // schermata — che è l'unico modo perché quello che si mostra resti vero.
        List<FaseImportDTO> fasi = new ArrayList<>(4);
        long tFase = System.nanoTime();

        List<RawRow> billyRows = billyParser.parse(billy);
        List<RawRow> bpmRows = bpmParser.parse(bpm);
        List<RawRow> caRows = caParser.parse(ca);
        normalizeAll(normalizer, billyRows, normBilly, errori);
        normalizeAll(normalizer, bpmRows, normBpm, errori);
        normalizeAll(normalizer, caRows, normCa, errori);
        tFase = fase(fasi, "Lettura dei tre file", (billyRows.size() + bpmRows.size() + caRows.size())
                + " righe lette · Billy " + billyRows.size() + " · BPM " + bpmRows.size()
                + " · CA " + caRows.size(), tFase);

        // ── UNIVERSO DEL CONTATORE (SPEC import-v2 §5, R7) ──
        // Misurato QUI, sulle righe banca normalizzate, PRIMA di qualunque mapping: è il termine
        // sinistro dell'invariante. Derivarlo dalla somma dei destini lo renderebbe vero per
        // costruzione — una guardia vacua che non può fallire. Billy resta fuori dall'universo.
        long bancaEntrate = 0, bancaUscite = 0;
        for (List<RawMovimento> banca : List.of(normBpm, normCa)) {
            for (RawMovimento r : banca) {
                long c = r.importo() == null ? 0 : ContatoreImportService.cents(r.importo());
                if ("ENTRATA".equals(r.tipo())) bancaEntrate += c; else bancaUscite += c;
            }
        }
        int righeBanca = normBpm.size() + normCa.size();

        audit.inizia(importLogId, fnBilly, fnBpm, fnCa);
        audit.fase("PARSE", "righe lette: Billy " + billyRows.size() + " · BPM " + bpmRows.size()
                + " · CA " + caRows.size() + " (normalizzate: " + normBilly.size() + "/"
                + normBpm.size() + "/" + normCa.size() + ", errori " + errori.size() + ")");

        // ── [2] RICONCILIAZIONE A PERIODO (funzione pura, Billy = verità) ──
        DatasetRiconciliato ds = riconciliazione.riconcilia(normBilly, normBpm, normCa);
        audit.fase("RICONCILIAZIONE POS a periodo (Billy = verità)",
                "da mappare " + ds.daMappare().size() + " · eventi attesi " + ds.eventiAttesi().size()
                + " · in attesa accredito " + ds.inAttesaAccredito().size());
        tFase = fase(fasi, "Riconciliazione degli incassi POS",
                ds.stat().righeBancaPos() + " accrediti POS confrontati con gli scontrini Billy · "
                + ds.inAttesaAccredito().size() + " in attesa di accredito", tFase);

        // ── [3]+[4] MAP (gate riusato) + PERSIST ──
        int importati = 0, duplicati = 0, ambigui = 0, scartati = 0, parcheggiati = 0, ricorrenti = 0;
        Set<String> rifEsistenti = new java.util.HashSet<>(repo.findRifimentiEsterniByFonte("IMPORT_BANCA"));
        rifEsistenti.addAll(repo.findRifimentiEsterniByFonte("IMPORT_BILLY"));

        // Feature 2 — Matching differiti: indice in memoria (O(1) lookup per riga) dei movimenti
        // DA_LIQUIDARE aperti. Caricato una sola volta a inizio loop; ogni riga banca fa una
        // lookup nella mappa (chiave = importoAlCentesimo + "|" + descrizione_LOWER_TRIM).
        // Se la riga combacia con un movimento già presente, NON viene persistita come nuovo
        // movimento: si salva in matching_differiti e l'utente risolve dallo smistamento.
        var idxDifferiti = matchingDifferitiService.buildIndiceDifferitiAperti();
        int matchingDifferiti = 0;

        for (RawMovimentoArricchito a : ds.daMappare()) {
            RawMovimento n = a.banca();
            RawRow raw = n.rawOriginale();
            try {
                audit.iniziaRiga(n.fonte(), raw.riga(), raw.campi());
                audit.passo("[NORM]", "data=" + n.dataMovimento() + " · tipo=" + n.tipo()
                        + " · importo=" + n.importo() + " · conto=" + n.contoBancarioId()
                        + " · metodo=" + n.metodoPagamentoCodice()
                        + " · girosalto=" + (n.girosalto() == null ? "no" : n.girosalto())
                        + " · rif.esterno=" + n.riferimentoEsterno());
                audit.passo("[NORM]", "descrizione normalizzata: \"" + n.descrizione() + "\"");

                MappingResult mapped = mappingEngine.map(a);

                if (mapped.outcome() == MappingResult.MappingOutcome.SKIP_RICORRENTE) {
                    // Spese ricorrenti/finanziamenti: parcheggiate (NON contabilizzate, gestite a mano).
                    salvaRicorrenteParcheggiata(importLogId, raw, n, n.fonte());
                    ricorrenti++;
                    audit.esito("PARCHEGGIATA in ricorrenti_da_riconciliare",
                            "nessun movimento creato: la rata la decide l'operatore dallo smistamento");
                    continue;
                }
                if (mapped.outcome().isSkip()) {
                    salvaScartato(importLogId, raw, n, mapped.motivoAmbiguita(), n.fonte());
                    scartati++;
                    audit.esito("SCARTATA (" + mapped.outcome() + ")",
                            "traccia: " + mapped.trace());
                    continue;
                }
                if (mapped.outcome() == MappingResult.MappingOutcome.PARK_EVENTO) {
                    boolean ins = salvaEventoParcheggiato(importLogId, raw, n, mapped.park(), n.fonte());
                    if (ins) {
                        parcheggiati++;
                    } else {
                        // R8: la duplicata lascia una traccia. Prima spariva incrementando un contatore.
                        salvaScartato(importLogId, raw, n, "DUPLICATA", n.fonte(), "DUPLICATA");
                        duplicati++;
                    }
                    audit.esito(ins ? "PARCHEGGIATA in eventi_da_riconciliare"
                                    : "SCARTATA come duplicato di un incasso-evento già in coda",
                            ins ? "l'incasso va attribuito a un evento dallo smistamento" : "dedup su chiave_aggancio");
                    continue;
                }
                if (mapped.outcome() == MappingResult.MappingOutcome.AMBIGUOUS
                        || mapped.outcome() == MappingResult.MappingOutcome.ERROR) {
                    salvaAmbiguita(importLogId, raw, n, mapped.motivoAmbiguita(), n.fonte());
                    ambigui++;
                    audit.esito("AMBIGUA → import_ambiguita", mapped.motivoAmbiguita());
                    continue;
                }

                MovimentoCreateRequest req = mapped.request();
                String rif = req.riferimentoEsterno();
                if (rif != null && !rif.isBlank() && rifEsistenti.contains(rif)) {
                    // R8: traccia visibile. Fino al 12/08/2026 la riga spariva qui dentro senza
                    // lasciare nulla — denaro bancario vero che nessuna schermata poteva mostrare.
                    salvaScartato(importLogId, raw, n, "DUPLICATA", n.fonte(), "DUPLICATA");
                    duplicati++;
                    audit.passo("[8] DEDUP", "riferimento esterno «" + rif + "» già importato");
                    audit.esito("SCARTATA come duplicato", "stessa riga già presente da un import precedente");
                    continue;
                }
                audit.passo("[8] DEDUP", rif == null || rif.isBlank()
                        ? "nessun riferimento esterno da confrontare"
                        : "riferimento esterno «" + rif + "» mai visto");

                // Feature 2 — Matching differiti: riga banca che combacia con un movimento
                // DA_LIQUIDARE già presente in gestionale (importo al centesimo + descrizione
                // uguale). NON persistiamo la riga come nuovo movimento: si salva in
                // matching_differiti e l'utente risolve dallo smistamento (COLLEGA/IGNORA).
                UUID movEsistenteId = matchingDifferitiService.trovaMatch(
                        idxDifferiti, req.importo(), req.descrizione());
                if (movEsistenteId != null) {
                    matchingDifferitiService.salvaMatch(importLogId, movEsistenteId, req,
                            n.fonte(), raw.riga());
                    matchingDifferiti++;
                    audit.passo("[8] DIFFERITI", "combacia con un movimento DA_LIQUIDARE già a libro "
                            + "(stesso importo e stessa descrizione)");
                    audit.esito("MESSA IN «Già a libro»",
                            "non si crea un doppione: l'operatore collega la riga al movimento esistente");
                    continue;
                }
                audit.passo("[8] DIFFERITI", "nessun movimento DA_LIQUIDARE corrispondente");

                var creato = movimentiService.createMovimentoImport(req, userId, importLogId);
                if (MovimentoNormalizerImpl.VERSAMENTO_CONTANTI.equals(n.girosalto())
                        && creaContropartitaCassaVersamento(req, userId, importLogId)) {
                    importati++;
                }
                // Conflitto keyword di MATCH (§4.6): la riga è booked sul transitorio; registra il
                // conflitto così l'utente lo risolve dalla pagina Gestione Keyword (mai catalog cieco).
                if (mapped.keywordConflittoSig() != null) {
                    keywordLearning.registraConflittoMatch(mapped.keywordConflittoSig(), creato.id(), n.descrizione());
                }
                if (rif != null && !rif.isBlank()) rifEsistenti.add(rif);
                importati++;
                audit.esito("CONTABILIZZATA (movimento " + creato.id() + ")", mapped.trace());

            } catch (Exception e) {
                log.warnf("Import congiunto riga %d (%s) fallita: %s", raw.riga(), n.fonte(), e.getMessage());
                errori.add(new EtlRowError(raw.riga(), e.getMessage(), raw.campi()));
                audit.esito("ERRORE", e.getMessage());
            }
        }

        // ── CODA TESTA — righe POS bancarie con DEL dell'anno precedente ──
        // Restano NON contabilizzate (il criterio è l'anno solare del DEL), ma non spariscono più:
        // ognuna lascia una riga in import_scartati, dove l'operatore la vede e decide. Fino al
        // 2026-08-11 l'unica traccia era una nota dentro quadratura_periodo.note, che nessun
        // contatore obbliga ad aprire: 230,00 € di accredito bancario vero fuori dai conti e da
        // ogni coda (audit-catena-import-2026-08-11.md §2, FINDING F1).
        for (RawMovimento t : ds.codaTesta()) {
            RawRow rawTesta = t.rawOriginale();
            audit.iniziaRiga(t.fonte(), rawTesta.riga(), rawTesta.campi());
            audit.passo("[2] RICONCILIAZIONE", "riga POS con DEL " + t.dataIncassoPos()
                    + ", cioè dell'anno precedente al periodo importato");
            salvaScartato(importLogId, rawTesta, t, "SKIP_CODA_TESTA", t.fonte());
            scartati++;
            audit.esito("SCARTATA (SKIP_CODA_TESTA) → import_scartati",
                    "accredito di un incasso dell'anno prima: non contabilizzato qui, ma tracciato "
                    + "perché è denaro vero — va guardato, non perso");
        }

        tFase = fase(fasi, "Classificazione e scrittura",
                importati + " righe messe a libro · " + (parcheggiati + ricorrenti + matchingDifferiti)
                + " in coda per te · " + (scartati + ambigui + duplicati) + " fuori dai conti", tFase);

        // AVVISI non bloccanti (≠ errori, ≠ movimenti): (1) scontrini agriturismo pagati a POS →
        // incasso-evento atteso (il ricavo arriva dal bonifico parcheggiato); (2) coda fondo →
        // vendite Billy dopo l'ultima DEL banca, non ancora accreditate: contabilizzate al prossimo
        // import (dedup su Numero DCW). Entrambe restano "segnalate a parte".
        List<EtlRowError> avvisi = new ArrayList<>();
        for (RawMovimento ev : ds.eventiAttesi()) {
            avvisi.add(new EtlRowError(ev.rawOriginale().riga(),
                    "EVENTO_AGRITURISMO: scontrino agriturismo escluso dalla contabilità import (gestito dal modulo Eventi)",
                    ev.rawOriginale().campi()));
        }
        for (RawMovimento att : ds.inAttesaAccredito()) {
            avvisi.add(new EtlRowError(att.rawOriginale().riga(),
                    "IN_ATTESA_ACCREDITO: venduto dopo l'ultima DEL banca — contabilizzato al prossimo import",
                    att.rawOriginale().campi()));
        }
        log.infof("Import congiunto %s (anno %d): righePOS=%d, testaEsclusa=%d, ricaviPOS=%d "
                        + "(BPM=%d,CA=%d), contanti=%d, eventiAttesi=%d, inAttesa=%d, bancaNonPOS=%d | "
                        + "Σ_BPM=%s Σ_CA=%s",
                importLogId, ds.quadratura().anno(), ds.stat().righeBancaPos(), ds.stat().testaEsclusa(),
                ds.stat().ricaviPos(), ds.stat().assegnatiBpm(), ds.stat().assegnatiCa(), ds.stat().contanti(),
                ds.stat().eventiAttesi(), ds.stat().inAttesaAccredito(), ds.stat().bancaNonPos(),
                ds.quadratura().sigmaBpm(), ds.quadratura().sigmaCa());

        salvaQuadratura(importLogId, ds.quadratura(), ds.inAttesaAccredito());

        int totali = billyRows.size() + bpmRows.size() + caRows.size();
        String statoFinale = statoFinale(errori.size(), ambigui, scartati, totali);
        // import_log: errori reali (qui 0); gli avvisi restano in errori_dettaglio per tracciabilità.
        List<EtlRowError> diagnostica = new ArrayList<>(errori);
        diagnostica.addAll(avvisi);
        chiudiImportLog(importLogId, totali, importati, errori.size(), duplicati, ambigui,
                scartati, parcheggiati, ricorrenti, matchingDifferiti, statoFinale, diagnostica,
                righeBanca, ContatoreImportService.euro(bancaEntrate), ContatoreImportService.euro(bancaUscite));

        audit.chiudi("righe totali " + totali + " · contabilizzate " + importati
                + " · parcheggiate eventi " + parcheggiati + " · parcheggiate ricorrenti " + ricorrenti
                + " · ambigue " + ambigui + " · scartate " + scartati + " · duplicate " + duplicati
                + " · già a libro " + matchingDifferiti + " · errori " + errori.size()
                + " → stato " + statoFinale);

        if (importati > 0) mvRefresh.requestRefreshAfterCommit();

        fase(fasi, "Quadratura dell'estratto conto",
                righeBanca + " righe bancarie · entrate " + euroIt(bancaEntrate)
                + " · uscite " + euroIt(bancaUscite), tFase);

        return new EtlImportResponse(importLogId, importati, duplicati, ambigui, scartati, parcheggiati,
                ricorrenti, errori, avvisi, matchingDifferiti, List.copyOf(fasi));
    }

    /**
     * Il numero è il messaggio (PRODUCT.md §5): questa cifra la legge il titolare accanto
     * all'estratto conto, quindi si scrive come la scrive la banca — 42.359,14 €, non 42359.14.
     */
    private static String euroIt(long cents) {
        return String.format(java.util.Locale.ITALY, "%,.2f €",
                ContatoreImportService.euro(cents));
    }

    /** Chiude una fase col tempo davvero speso e restituisce il nuovo istante di partenza. */
    private static long fase(List<FaseImportDTO> fasi, String nome, String dettaglio, long da) {
        long ora = System.nanoTime();
        fasi.add(new FaseImportDTO(nome, dettaglio, (int) ((ora - da) / 1_000_000L)));
        return ora;
    }

    private void normalizeAll(MovimentoNormalizer norm, List<RawRow> rows,
                              List<RawMovimento> out, List<EtlRowError> errori) {
        for (RawRow raw : rows) {
            try {
                out.add(norm.normalize(raw));
            } catch (Exception e) {
                log.warnf("Normalizzazione riga %d fallita: %s", raw.riga(), e.getMessage());
                errori.add(new EtlRowError(raw.riga(), e.getMessage(), raw.campi()));
            }
        }
    }

    private String fileLabel(String b, String bpm, String ca) {
        return "CONGIUNTO [" + safeName(b) + " + " + safeName(bpm) + " + " + safeName(ca) + "]";
    }

    private String safeName(String s) {
        return (s == null || s.isBlank()) ? "?" : s;
    }

    /**
     * Regola §7.4 n.3: se restano righe fuori dai conti, l'import NON è «completato» — c'è denaro
     * bancario in attesa di una decisione, e un'etichetta verde lo farebbe dimenticare.
     */
    private String statoFinale(int errori, int ambigui, int scartati, int totali) {
        if (totali > 0 && errori > totali * 0.5) return "ERRORE";
        if (ambigui > 0 || errori > 0 || scartati > 0) return "COMPLETATO_CON_AMBIGUITA";
        return "COMPLETATO";
    }

    /**
     * ROLLBACK reversibile di un import: elimina tutto ciò che l'import ha prodotto,
     * identificato dal suo {@code importLogId}. I movimenti hanno
     * {@code fonte_importazione_id = importLogId} (vanno eliminati esplicitamente);
     * import_scartati / eventi_da_riconciliare / import_ambiguita sono in ON DELETE
     * CASCADE su import_log, quindi spariscono eliminando la riga di import_log.
     *
     * NB: non tocca controparti/alias creati da classificazioni manuali (apprendimento).
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public Map<String, Object> rollbackImport(UUID importLogId) {
        long esiste = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM import_log WHERE id = :id")
                .setParameter("id", importLogId).getSingleResult()).longValue();
        if (esiste == 0) {
            throw new ApiException(Response.Status.NOT_FOUND, "IMPORT_NON_TROVATO",
                    "Import log non trovato: " + importLogId);
        }

        long scartati = contaPerImport("import_scartati", importLogId);
        long eventi = contaPerImport("eventi_da_riconciliare", importLogId);
        long ambiguita = contaPerImport("import_ambiguita", importLogId);
        long ricorrenti = contaPerImport("ricorrenti_da_riconciliare", importLogId);
        long matchingDifferiti = contaPerImport("matching_differiti", importLogId);

        int movimenti = em.createNativeQuery(
                "DELETE FROM movimenti WHERE fonte_importazione_id = :id")
                .setParameter("id", importLogId).executeUpdate();

        // Cascade: import_scartati / eventi_da_riconciliare / import_ambiguita / ricorrenti_da_riconciliare.
        em.createNativeQuery("DELETE FROM import_log WHERE id = :id")
                .setParameter("id", importLogId).executeUpdate();

        mvRefresh.requestRefreshAfterCommit();
        log.infof("Rollback import %s: rimossi %d movimenti, %d scartati, %d eventi, %d ambiguità, %d ricorrenti, %d matching differiti",
                importLogId, movimenti, scartati, eventi, ambiguita, ricorrenti, matchingDifferiti);

        return Map.of(
                "importLogId", importLogId.toString(),
                "movimentiEliminati", (long) movimenti,
                "scartatiEliminati", scartati,
                "eventiEliminati", eventi,
                "ambiguitaEliminate", ambiguita,
                "ricorrentiEliminate", ricorrenti,
                "matchingDifferitiEliminati", matchingDifferiti);
    }


    // ══════════════════════════════════════════════════════════════════════════════
    // ponytail: TEMPORANEO — SPEC docs/specs/bpm-luglio-2026-recupero.md R8/R14.
    // Serve a smaltire UN arretrato: le righe BPM di luglio 2026 rimaste in coda perché
    // l'estratto conto era passato da un foglio di calcolo (date senza secolo → nessuna data
    // → nessun movimento). Il motore di mapping gira solo dentro importCongiunto: le righe
    // già in coda non le rilegge nessuno, quindi serve un innesco esplicito.
    // Il ramo di smistamento qui sotto RIPETE quello di importCongiunto: è una copia voluta,
    // per non rifattorizzare il percorso-soldi al servizio di codice che ha una data di morte.
    // DA RIMUOVERE (con l'endpoint e il bottone) quando l'arretrato è smaltito.
    // ══════════════════════════════════════════════════════════════════════════════

    /** Una riga ancora aperta in coda, con il grezzo da cui si ri-deriva tutto (I3). */
    private record Aperta(String tabella, UUID id, int riga, String rawJson) {}

    /**
     * Ri-processa le righe ancora APERTE di un import: ri-normalizza il grezzo e lo rimanda nel
     * motore di mapping, così ogni riga finisce dove sarebbe finita il primo giorno.
     *
     * <p>Solo le righe della sorgente BPM: il difetto è del file BPM e il Crédit Agricole di
     * questo import è corretto così com'è (invariante I4 della SPEC — CA non si tocca).
     *
     * <p>Idempotente: ogni riga si chiude con un claim atomico (WHERE stato = …) e il dedup sul
     * riferimento esterno impedisce il doppione anche se l'azione viene rilanciata.
     *
     * @param dryRun true = calcola e riporta il destino di ogni riga senza scrivere nulla.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public Map<String, Object> riprocessaCoda(UUID importLogId, UUID userId, boolean dryRun) {
        long esiste = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM import_log WHERE id = :id")
                .setParameter("id", importLogId).getSingleResult()).longValue();
        if (esiste == 0) {
            throw new ApiException(Response.Status.NOT_FOUND, "IMPORT_NON_TROVATO",
                    "Import log non trovato: " + importLogId);
        }

        List<Aperta> aperte = new ArrayList<>();
        aperte.addAll(aperteDi("import_ambiguita", "DA_CLASSIFICARE", importLogId));
        aperte.addAll(aperteDi("import_scartati", "DA_VEDERE", importLogId));

        // ── ri-normalizzazione: il grezzo torna a parlare ──
        Map<Integer, Aperta> perRiga = new java.util.LinkedHashMap<>();
        List<RawMovimento> righeBpm = new ArrayList<>();
        List<String> illeggibili = new ArrayList<>();
        int chiave = 0;
        for (Aperta ap : aperte) {
            RawMovimento n = contatore.rinormalizza(ap.rawJson(), ++chiave);
            if (n == null || n.dataMovimento() == null || n.importo() == null) {
                // Edge case della SPEC: resta dov'è, con un motivo. Non sparisce.
                illeggibili.add(ap.tabella() + " " + ap.id());
                continue;
            }
            perRiga.put(chiave, ap);
            righeBpm.add(n);
        }

        // ── stesso dataset dell'import, senza Billy: le righe POS restano orfane e si
        //    contabilizzano come ricavo POS sul transitorio («da catalogare»), non si perdono. ──
        DatasetRiconciliato ds = riconciliazione.riconcilia(List.of(), righeBpm, List.of());

        Map<String, Integer> esiti = new java.util.LinkedHashMap<>();
        List<String> dettaglio = new ArrayList<>();
        Set<String> rifEsistenti = new java.util.HashSet<>(repo.findRifimentiEsterniByFonte("IMPORT_BANCA"));
        rifEsistenti.addAll(repo.findRifimentiEsterniByFonte("IMPORT_BILLY"));
        int movimentiCreati = 0;

        for (RawMovimentoArricchito a : ds.daMappare()) {
            RawMovimento n = a.banca();
            RawRow raw = n.rawOriginale();
            Aperta ap = perRiga.get(raw.riga());
            if (ap == null) continue; // difensivo: ogni riga qui viene dalle code
            MappingResult mapped = mappingEngine.map(a);
            String esito;
            UUID movimentoId = null;

            if (mapped.outcome() == MappingResult.MappingOutcome.SKIP_RICORRENTE) {
                esito = "RICORRENTE_PARCHEGGIATA";
                if (!dryRun) { chiudiAperta(ap, null, esito, userId); salvaRicorrenteParcheggiata(importLogId, raw, n, n.fonte()); }
            } else if (mapped.outcome() == MappingResult.MappingOutcome.PARK_EVENTO) {
                esito = "EVENTO_PARCHEGGIATO";
                if (!dryRun) {
                    chiudiAperta(ap, null, esito, userId);
                    if (!salvaEventoParcheggiato(importLogId, raw, n, mapped.park(), n.fonte())) {
                        salvaScartato(importLogId, raw, n, "DUPLICATA", n.fonte(), "DUPLICATA");
                    }
                }
            } else if (mapped.outcome().isSkip()) {
                esito = "RESTA_FUORI_" + mapped.outcome();
                // Era già uno scarto con lo stesso motivo → non si tocca nulla (idempotenza).
                if (!dryRun && !"import_scartati".equals(ap.tabella())) {
                    chiudiAperta(ap, null, esito, userId);
                    salvaScartato(importLogId, raw, n, mapped.motivoAmbiguita(), n.fonte());
                }
            } else if (mapped.outcome() == MappingResult.MappingOutcome.AMBIGUOUS
                    || mapped.outcome() == MappingResult.MappingOutcome.ERROR) {
                esito = "AMBIGUA_" + mapped.motivoAmbiguita();
                // Resta in coda ambiguità: cambia solo il motivo, che ora è quello vero.
                if (!dryRun && "import_ambiguita".equals(ap.tabella())) {
                    em.createNativeQuery("UPDATE import_ambiguita SET motivo = :m WHERE id = :id AND stato = 'DA_CLASSIFICARE'")
                            .setParameter("m", mapped.motivoAmbiguita() == null ? "COGE_NON_DETERMINABILE" : mapped.motivoAmbiguita())
                            .setParameter("id", ap.id()).executeUpdate();
                } else if (!dryRun) {
                    chiudiAperta(ap, null, esito, userId);
                    salvaAmbiguita(importLogId, raw, n, mapped.motivoAmbiguita(), n.fonte());
                }
            } else {
                MovimentoCreateRequest req = mapped.request();
                String rif = req.riferimentoEsterno();
                if (rif != null && !rif.isBlank() && rifEsistenti.contains(rif)) {
                    esito = "DUPLICATA";
                    if (!dryRun) { chiudiAperta(ap, null, esito, userId); salvaScartato(importLogId, raw, n, "DUPLICATA", n.fonte(), "DUPLICATA"); }
                } else {
                    esito = "A_LIBRO";
                    if (!dryRun) {
                        chiudiAperta(ap, null, esito, userId);   // claim PRIMA di creare il movimento
                        movimentoId = movimentiService.createMovimentoImport(req, userId, importLogId).id();
                        collegaMovimento(ap, movimentoId);
                        if (rif != null && !rif.isBlank()) rifEsistenti.add(rif);
                        movimentiCreati++;
                    }
                }
            }
            esiti.merge(esito, 1, Integer::sum);
            dettaglio.add(raw.riga() + " · " + n.dataMovimento() + " · " + n.tipo() + " "
                    + n.importo() + " · " + esito + (movimentoId == null ? "" : " → " + movimentoId));
        }

        // ── R14 — quadratura POS: le colonne BPM smettono di essere 0,00. La quota CA
        //    (già assegnata dall'import vero) NON si tocca: I4. ──
        int quadraturaAggiornata = 0;
        if (!dryRun && ds.quadratura().sigmaBpm().signum() != 0) {
            quadraturaAggiornata = em.createNativeQuery(
                    "UPDATE quadratura_periodo SET sigma_bpm = :s, assegnato_bpm = :a, " +
                    "pos_banca_totale = pos_banca_totale + :s, pos_banca_core = pos_banca_core + :s, " +
                    "billy_contabilizzato = billy_contabilizzato + :a " +
                    "WHERE import_log_id = :id AND sigma_bpm = 0")
                    .setParameter("s", ds.quadratura().sigmaBpm())
                    .setParameter("a", ds.quadratura().assegnatoBpm())
                    .setParameter("id", importLogId).executeUpdate();
        }

        if (!dryRun && movimentiCreati > 0) mvRefresh.requestRefreshAfterCommit();
        log.infof("Ri-processo coda import %s (dryRun=%s): %d righe aperte, %d rileggibili, "
                        + "%d movimenti creati, esiti=%s, illeggibili=%s",
                importLogId, dryRun, aperte.size(), righeBpm.size(), movimentiCreati, esiti, illeggibili);

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("importLogId", importLogId.toString());
        out.put("dryRun", dryRun);
        out.put("righeAperte", aperte.size());
        out.put("righeRileggibili", righeBpm.size());
        out.put("righeIlleggibili", illeggibili);
        out.put("movimentiCreati", movimentiCreati);
        out.put("esiti", esiti);
        out.put("quadraturaPosAggiornata", quadraturaAggiornata);
        out.put("sigmaBpm", ds.quadratura().sigmaBpm());
        out.put("dettaglio", dettaglio);
        return out;
    }

    /** Le righe di UNA coda ancora aperte per questo import, sorgente BPM soltanto (I4). */
    @SuppressWarnings("unchecked")
    private List<Aperta> aperteDi(String tabella, String statoAperto, UUID importLogId) {
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, riga_numero, CAST(raw_data AS text) FROM " + tabella +
                " WHERE import_log_id = :id AND stato = :stato AND raw_data->>'_SORGENTE' = 'BPM' " +
                " ORDER BY riga_numero")
                .setParameter("id", importLogId).setParameter("stato", statoAperto).getResultList();
        List<Aperta> out = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            UUID id = r[0] instanceof UUID u ? u : UUID.fromString(r[0].toString());
            out.add(new Aperta(tabella, id, ((Number) r[1]).intValue(), (String) r[2]));
        }
        return out;
    }

    /**
     * Chiude la riga d'origine con un claim ATOMICO: se qualcuno l'ha già lavorata nel frattempo
     * la UPDATE vede 0 righe e si alza un 409 — mai due destini per la stessa riga bancaria (I1).
     */
    private void chiudiAperta(Aperta ap, UUID movimentoId, String esito, UUID userId) {
        String sql = "import_ambiguita".equals(ap.tabella())
                ? "UPDATE import_ambiguita SET stato = 'RIPROCESSATA', movimento_id = :mov, " +
                  "classificato_da = :uid, classificato_at = now(), note_operatore = :nota " +
                  "WHERE id = :id AND stato = 'DA_CLASSIFICARE'"
                : "UPDATE import_scartati SET stato = 'RIPROCESSATA', movimento_id = :mov, " +
                  "risolto_by = :uid, risolto_at = now(), note = :nota " +
                  "WHERE id = :id AND stato = 'DA_VEDERE'";
        int claimed = em.createNativeQuery(sql)
                .setParameter("mov", movimentoId).setParameter("uid", userId)
                .setParameter("nota", "ri-processata dopo il fix della data a 2 cifre → " + esito)
                .setParameter("id", ap.id()).executeUpdate();
        if (claimed == 0) {
            throw new ApiException(Response.Status.CONFLICT, "RIGA_GIA_LAVORATA",
                    "La riga " + ap.id() + " è stata lavorata da qualcun altro: rilancia il ri-processo");
        }
        // Stesso contatore che aggiorna la classificazione manuale: senza, lo Storico import
        // continuerebbe a mostrare «49 pend.» (e il bottone di rilettura) su una coda ormai vuota.
        if ("import_ambiguita".equals(ap.tabella())) {
            em.createNativeQuery("UPDATE import_log SET righe_ambigue_classificate = " +
                    "COALESCE(righe_ambigue_classificate, 0) + 1 WHERE id = " +
                    "(SELECT import_log_id FROM import_ambiguita WHERE id = :id)")
                    .setParameter("id", ap.id()).executeUpdate();
        }
    }

    /** Scrive il movimento nato dalla riga sulla riga stessa (traccia, I5). */
    private void collegaMovimento(Aperta ap, UUID movimentoId) {
        em.createNativeQuery("UPDATE " + ap.tabella() + " SET movimento_id = :mov WHERE id = :id")
                .setParameter("mov", movimentoId).setParameter("id", ap.id()).executeUpdate();
    }

    private long contaPerImport(String tabella, UUID importLogId) {
        return ((Number) em.createNativeQuery(
                "SELECT count(*) FROM " + tabella + " WHERE import_log_id = :id")
                .setParameter("id", importLogId).getSingleResult()).longValue();
    }

    // ── import_log ──────────────────────────────────────────────────────────────

    private UUID creaImportLog(String fonte, String filename, UUID userId) {
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO import_log (id, fonte, filename, stato, imported_by) " +
                        "VALUES (:id, :fonte, :filename, 'IN_CORSO', :uid)")
                .setParameter("id", id)
                .setParameter("fonte", fonte)
                .setParameter("filename", filename != null ? filename : "unknown")
                .setParameter("uid", userId)
                .executeUpdate();
        return id;
    }

    /**
     * Persiste la quadratura di periodo (V10) per il pannello di quadratura. Una riga per import
     * congiunto, in ON DELETE CASCADE su import_log (sparisce col rollback). La coda fondo (scontrini
     * in attesa di accredito) è serializzata in JSONB per la visualizzazione.
     */
    private void salvaQuadratura(UUID importLogId,
                                 com.agostinelli.gestionale.movimenti.importlayer.reconcile.QuadraturaPeriodo q,
                                 List<RawMovimento> inAttesa) {
        List<Map<String, Object>> attesa = new ArrayList<>(inAttesa.size());
        for (RawMovimento r : inAttesa) {
            attesa.add(Map.of(
                    "data", String.valueOf(r.dataMovimento()),
                    "importo", r.importo() == null ? "0" : r.importo().toPlainString(),
                    "rif", r.riferimentoEsterno() == null ? "" : r.riferimentoEsterno(),
                    "descrizione", r.descrizione() == null ? "" : r.descrizione()));
        }
        em.createNativeQuery(
                        "INSERT INTO quadratura_periodo (id, import_log_id, anno, billy_elettronico_non_agri, " +
                        "billy_contabilizzato, pos_banca_totale, pos_banca_core, sigma_bpm, sigma_ca, " +
                        "assegnato_bpm, assegnato_ca, coda_testa, coda_fondo, max_del_banca, " +
                        "note, in_attesa) VALUES (:id, :logId, :anno, :bena, :bcon, :ptot, :pcore, :sbpm, :sca, " +
                        ":abpm, :aca, :testa, :fondo, :maxdel, CAST(:note AS jsonb), CAST(:attesa AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("anno", q.anno())
                .setParameter("bena", q.billyElettronicoNonAgri())
                .setParameter("bcon", q.billyContabilizzato())
                .setParameter("ptot", q.posBancaTotale())
                .setParameter("pcore", q.posBancaCore())
                .setParameter("sbpm", q.sigmaBpm())
                .setParameter("sca", q.sigmaCa())
                .setParameter("abpm", q.assegnatoBpm())
                .setParameter("aca", q.assegnatoCa())
                .setParameter("testa", q.codaTesta())
                .setParameter("fondo", q.codaFondo())
                .setParameter("maxdel", q.maxDelBanca())
                .setParameter("note", toJson(q.note()))
                .setParameter("attesa", toJson(attesa))
                .executeUpdate();
    }

    private void chiudiImportLog(UUID id, int totali, int importate, int errore, int duplicate,
                                 int ambigue, int scartate, int parcheggiate, int ricorrenti,
                                 int matchingDifferiti,
                                 String stato, List<EtlRowError> errori,
                                 int righeBanca, BigDecimal bancaEntrate, BigDecimal bancaUscite) {
        em.createNativeQuery(
                        "UPDATE import_log SET righe_totali = :tot, righe_importate = :imp, " +
                        "righe_errore = :err, righe_duplicate = :dup, righe_ambigue = :amb, " +
                        "righe_scartate = :sca, righe_parcheggiate = :par, righe_ricorrenti = :ric, " +
                        "righe_matching_differiti = :mat, " +
                        "righe_banca = :rb, banca_entrate = :be, banca_uscite = :bu, " +
                        "stato = :stato, errori_dettaglio = CAST(:json AS jsonb) WHERE id = :id")
                .setParameter("rb", righeBanca)
                .setParameter("be", bancaEntrate)
                .setParameter("bu", bancaUscite)
                .setParameter("tot", totali)
                .setParameter("imp", importate)
                .setParameter("err", errore)
                .setParameter("dup", duplicate)
                .setParameter("amb", ambigue)
                .setParameter("sca", scartate)
                .setParameter("par", parcheggiate)
                .setParameter("ric", ricorrenti)
                .setParameter("mat", matchingDifferiti)
                .setParameter("stato", stato)
                .setParameter("json", toJson(errori))
                .setParameter("id", id)
                .executeUpdate();
    }

    /**
     * Persiste una spesa ricorrente / finanziamento nella coda ricorrenti_da_riconciliare (V9):
     * NON crea un movimento (le ricorrenti si gestiscono nel modulo dedicato). L'utente la
     * riconcilia collegandola a un piano ricorrente, oppure la ignora.
     */
    private void salvaRicorrenteParcheggiata(UUID importLogId, RawRow raw, RawMovimento norm, String fonte) {
        em.createNativeQuery(
                        "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, " +
                        "importo, tipo, conto_bancario_id, descrizione_norm, tipo_presunto, keyword_match, raw_data) " +
                        "VALUES (:id, :logId, :fonte, :data, :importo, :tipo, :conto, :descr, :tipoP, :kw, CAST(:raw AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("fonte", fonte)
                .setParameter("data", norm.dataMovimento())
                .setParameter("importo", norm.importo())
                .setParameter("tipo", norm.tipo())
                .setParameter("conto", norm.contoBancarioId())
                .setParameter("descr", norm.descrizione())
                .setParameter("tipoP", tipoRicorrentePresunto(norm.descrizione()))
                .setParameter("kw", tipoRicorrentePresunto(norm.descrizione()))
                .setParameter("raw", toJson(raw.campi()))
                .executeUpdate();
    }

    /** Deduce il tipo di ricorrente dalla descrizione (per la card di triage). */
    private String tipoRicorrentePresunto(String descrizione) {
        String d = descrizione == null ? "" : descrizione.toUpperCase();
        if (d.contains("MUTUO")) return "MUTUO";
        if (d.contains("CAMBIALE")) return "CAMBIALE";
        if (d.contains("LEASING")) return "LEASING";
        if (d.contains("ASSICURAZ") || d.contains("POLIZZA")) return "ASSICURAZIONE";
        if (d.contains("CANONE")) return "CANONE";
        if (d.contains("BOLLO")) return "BOLLO";
        if (d.contains("ASCONFIDI") || d.contains("FINANZIAMENT")) return "FINANZIAMENTO";
        if (d.contains("RATA")) return "RATA";
        return "ALTRO";
    }

    /**
     * Persiste una voce-evento nella coda eventi_da_riconciliare (ETL v2 §5).
     * Dedup cross-sorgente: a parità di chiave_aggancio (Billy↔CA↔BPM) la seconda
     * occorrenza viene scartata (ON CONFLICT DO NOTHING). Ritorna true se inserita.
     */
    private boolean salvaEventoParcheggiato(UUID importLogId, RawRow raw, RawMovimento norm,
                                            com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento park,
                                            String fonte) {
        // chiave usabile per il dedup solo se valorizzata e con la parte importo (non "<num>/")
        String chiaveRaw = norm.chiaveAggancio();
        String chiaveDedup = (chiaveRaw == null || chiaveRaw.isBlank() || chiaveRaw.endsWith("/"))
                ? null : chiaveRaw;

        // Fallback intelligente quando manca la chiave (export nativi): aggancio cross-sorgente
        // per importo + tipo + nome/IBAN/data-evento/vicinanza temporale. Con chiave presente
        // resta il solo ON CONFLICT (comportamento invariato sugli export "addomesticati").
        if (chiaveDedup == null && eventoDuplicatoFallback(norm, park)) {
            return false;
        }

        var entita = norm.entita();
        int inserted = em.createNativeQuery(
                        "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, chiave_aggancio, " +
                        "data_movimento, importo, tipo, conto_bancario_id, descrizione_norm, " +
                        "tipo_evento_presunto, keyword_match, controparte_nome, controparte_iban, " +
                        "data_evento_estratta, raw_data) " +
                        "VALUES (:id, :logId, :fonte, :chiave, :data, :importo, :tipo, :conto, :descr, " +
                        ":tipoEv, :kw, :nome, :iban, :dataEv, CAST(:raw AS jsonb)) " +
                        "ON CONFLICT (chiave_aggancio) WHERE chiave_aggancio IS NOT NULL DO NOTHING")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("fonte", fonte)
                .setParameter("chiave", chiaveDedup)
                .setParameter("data", norm.dataMovimento())
                .setParameter("importo", norm.importo())
                .setParameter("tipo", norm.tipo())
                .setParameter("conto", norm.contoBancarioId())
                .setParameter("descr", norm.descrizione())
                .setParameter("tipoEv", park != null ? park.tipoEventoPresunto() : null)
                .setParameter("kw", park != null ? park.keywordMatch() : null)
                .setParameter("nome", entita != null ? entita.ordinante() : null)
                .setParameter("iban", entita != null ? entita.ibanControparte() : null)
                .setParameter("dataEv", park != null ? park.dataEventoEstratta() : null)
                .setParameter("raw", toJson(raw.campi()))
                .executeUpdate();
        return inserted > 0;
    }

    /**
     * Aggancio cross-sorgente quando la chiave di registrazione manca (export nativi).
     * Confronta il nuovo evento con quelli già in coda a parità di importo+tipo entro
     * una finestra temporale, delegando la decisione a {@link EventoMatcher}. Le INSERT
     * precedenti del loop sono già a DB (native executeUpdate), quindi visibili qui.
     */
    private boolean eventoDuplicatoFallback(RawMovimento norm,
            com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento park) {
        if (norm.importo() == null || norm.dataMovimento() == null) return false;

        var entita = norm.entita();
        EventoMatcher.Segnali nuovo = new EventoMatcher.Segnali(
                norm.importo(), norm.tipo(), norm.dataMovimento(),
                entita == null ? null : entita.ordinante(),
                entita == null ? null : entita.ibanControparte(),
                park == null ? null : park.dataEventoEstratta(),
                park == null ? null : park.tipoEventoPresunto());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT data_movimento, controparte_nome, controparte_iban, " +
                        "data_evento_estratta, tipo_evento_presunto FROM eventi_da_riconciliare " +
                        "WHERE stato = 'DA_RICONCILIARE' AND tipo = :tipo AND importo = :imp " +
                        "AND data_movimento BETWEEN :d1 AND :d2")
                .setParameter("tipo", norm.tipo())
                .setParameter("imp", norm.importo())
                .setParameter("d1", norm.dataMovimento().minusDays(EventoMatcher.GIORNI_FINESTRA))
                .setParameter("d2", norm.dataMovimento().plusDays(EventoMatcher.GIORNI_FINESTRA))
                .getResultList();

        List<EventoMatcher.Segnali> candidati = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            candidati.add(new EventoMatcher.Segnali(
                    norm.importo(), norm.tipo(), toLocalDate(r[0]),
                    str(r[1]), str(r[2]), toLocalDate(r[3]), str(r[4])));
        }
        return EventoMatcher.isDuplicato(nuovo, candidati);
    }

    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate l) return l;
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Persiste una riga esclusa dal Gate A in import_scartati (ETL v2 §4/§9.4):
     * tracciata e reversibile, conteggiata in import_log, mai un movimento.
     */
    /**
     * Contropartita cassa del versamento contante ATM (78A): la riga banca è l'ENTRATA sul
     * conto corrente, qui esce lo stesso importo dalla Cassa contanti (stesso CoGe 10.03.003,
     * liquidità totale invariata). Il rif ":CASSA" è solo tracciabilità: al re-import la riga
     * banca viene deduplicata prima, quindi la contropartita non si rigenera.
     *
     * <p><b>Guardia anti-doppione</b> (SPEC modulo-contanti R8): dal modulo Contanti il titolare
     * può aver già registrato a mano il deposito, e il dedup per {@code riferimento_esterno} non
     * copre il caso — la riga manuale non ha il rif della banca. Se una gamba cassa manuale con lo
     * stesso CoGe e lo stesso importo esiste già a ±5 giorni, la contropartita <b>non</b> si crea:
     * altrimenti il cassetto si scaricherebbe due volte.
     *
     * @return true se la contropartita è stata creata, false se era già a libro a mano.
     */
    private boolean creaContropartitaCassaVersamento(MovimentoCreateRequest bancario, UUID userId, UUID importLogId) {
        Short contoCassa = ((Number) em.createNativeQuery(
                "SELECT id FROM conti_bancari WHERE tipo = 'CASSA' AND is_active = true ORDER BY id LIMIT 1")
                .getSingleResult()).shortValue();

        // Il CoGe della gamba banca è 10.03.003 per costruzione (MovimentoMappingEngineImpl
        // .classifyGirosalto): la contropartita userebbe lo stesso, quindi è anche il discriminante.
        LocalDate dataBanca = bancario.dataFinanziaria() != null
                ? bancario.dataFinanziaria() : bancario.dataMovimento();
        // ponytail: nessun partition pruning su `movimenti` (partizionata per anno su data_movimento,
        // il filtro è su COALESCE). N è dell'ordine delle decine — si aggiunge
        // AND m.data_movimento BETWEEN data-40 AND data+40 solo se un profilo lo chiede.
        List<?> giaAMano = em.createNativeQuery("""
                SELECT COALESCE(m.data_finanziaria, m.data_movimento)
                FROM movimenti m
                WHERE m.conto_bancario_id = :conto
                  AND m.stato <> 'ANNULLATO'
                  AND m.fonte = 'MANUALE'
                  AND m.conto_coge_id = :coge
                  AND m.importo_lordo = :importo
                  AND COALESCE(m.data_finanziaria, m.data_movimento) BETWEEN :da AND :a
                LIMIT 1
                """)
                .setParameter("conto", contoCassa)
                .setParameter("coge", bancario.contoCoge())
                .setParameter("importo", bancario.importo())
                .setParameter("da", dataBanca.minusDays(5))
                .setParameter("a", dataBanca.plusDays(5))
                .getResultList();
        if (!giaAMano.isEmpty()) {
            Object d = giaAMano.get(0);
            LocalDate quando = d instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) d;
            audit.passo("[CASSA]", "contropartita già registrata a mano il "
                    + quando.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM"))
                    + " — non duplicata");
            return false;
        }

        Integer metodoContanti = ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'")
                .getSingleResult()).intValue();
        String descr = "PRELIEVO CASSA PER VERSAMENTO IN BANCA - " + bancario.descrizione();
        MovimentoCreateRequest mirror = new MovimentoCreateRequest(
                "USCITA", bancario.importo(), null, null,
                bancario.dataMovimento(), bancario.dataCompetenza(), bancario.dataFinanziaria(), null,
                contoCassa, metodoContanti, bancario.businessUnitId(), bancario.contoCoge(),
                null, null, null, null,
                descr.length() > 500 ? descr.substring(0, 500) : descr,
                null,
                bancario.riferimentoEsterno() == null ? null : bancario.riferimentoEsterno() + ":CASSA",
                bancario.fonte(), null);
        movimentiService.createMovimentoImport(mirror, userId, importLogId);
        return true;
    }

    private void salvaScartato(UUID importLogId, RawRow raw, RawMovimento norm, String motivo, String fonte) {
        salvaScartato(importLogId, raw, norm, motivo, fonte, "DA_VEDERE");
    }

    /**
     * @param stato DA_VEDERE = coda che l'operatore deve lavorare; DUPLICATA = traccia di una riga
     *              già importata (R8), fuori dalla coda perché non c'è nulla da decidere.
     */
    private void salvaScartato(UUID importLogId, RawRow raw, RawMovimento norm, String motivo,
                               String fonte, String stato) {
        em.createNativeQuery(
                        "INSERT INTO import_scartati (id, import_log_id, riga_numero, fonte, motivo, " +
                        "chiave_aggancio, data_movimento, importo, causale, raw_data, stato) " +
                        "VALUES (:id, :logId, :riga, :fonte, :motivo, :chiave, :data, :importo, :causale, " +
                        "CAST(:raw AS jsonb), :stato)")
                .setParameter("stato", stato)
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("riga", raw.riga())
                .setParameter("fonte", fonte)
                .setParameter("motivo", motivo)
                .setParameter("chiave", norm.chiaveAggancio())
                .setParameter("data", norm.dataMovimento())
                .setParameter("importo", norm.importo())
                .setParameter("causale", raw.campi().get("CAUSALE"))
                .setParameter("raw", toJson(raw.campi()))
                .executeUpdate();
    }

    private void salvaAmbiguita(UUID importLogId, RawRow raw, RawMovimento norm, String motivo, String fonte) {
        var entita = norm.entita();
        String nome = entita == null ? null
                : (entita.beneficiario() != null ? entita.beneficiario() : entita.ordinante());
        em.createNativeQuery(
                        "INSERT INTO import_ambiguita (id, import_log_id, riga_numero, fonte, raw_data, motivo, stato, " +
                        "controparte_nome, controparte_iban) " +
                        "VALUES (:id, :logId, :riga, :fonte, CAST(:raw AS jsonb), :motivo, 'DA_CLASSIFICARE', :nome, :iban)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("riga", raw.riga())
                .setParameter("fonte", fonte)
                .setParameter("raw", toJson(raw.campi()))
                .setParameter("motivo", motivo != null ? motivo : "COGE_NON_DETERMINABILE")
                .setParameter("nome", nome)
                .setParameter("iban", entita == null ? null : entita.ibanControparte())
                .executeUpdate();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return value instanceof Map ? "{}" : "[]";
        }
    }
}
