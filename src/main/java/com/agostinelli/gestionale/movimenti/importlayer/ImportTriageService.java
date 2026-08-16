package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.BuPanelDTO;
import com.agostinelli.gestionale.movimenti.dto.ClassificaTransitorioRequest;
import com.agostinelli.gestionale.movimenti.dto.EventoParcheggiatoDTO;
import com.agostinelli.gestionale.movimenti.dto.ImportBadgeDTO;
import com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.movimenti.dto.QuadraturaPeriodoDTO;
import com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO;
import com.agostinelli.gestionale.movimenti.dto.RicorrenteParcheggiataDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviEventoRequest;
import com.agostinelli.gestionale.movimenti.dto.RisolviRicorrenteRequest;
import com.agostinelli.gestionale.movimenti.dto.RisolviScartatoRequest;
import com.agostinelli.gestionale.movimenti.dto.SpostaRigaRequest;
import com.agostinelli.gestionale.movimenti.dto.ScartatoDTO;
import com.agostinelli.gestionale.movimenti.dto.TransitorioDTO;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.Proposta;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.movimenti.dto.AnalisiDuplicatiDTO;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Backend del triage assistito (ETL v2 §8/§13): KPI di qualità dell'import,
 * CRUD regole data-driven, e il "centro di smistamento" dei movimenti su conti
 * transitori e degli eventi parcheggiati. La catalogazione può apprendere KEYWORD
 * (PROMPT-KEYWORD-LEARNING.md §4.4). La UI vive nel frontend; qui solo i servizi.
 */
@ApplicationScoped
public class ImportTriageService {

    private static final String COGE_RICAVI_DACLASS = CogeTransitorio.RICAVI;
    private static final String COGE_COSTI_DACLASS = CogeTransitorio.COSTI;

    // BU 5 = Overhead (mutui/assicurazioni/ammortamenti): natura delle rate ricorrenti.
    private static final short BU_OVERHEAD = 5;
    private static final String COGE_FINANZIAMENTO_ENTRATA = "90.01.001"; // Finanziamenti ricevuti

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;
    @Inject MvRefreshService mvRefresh;
    @Inject KeywordLearningService keywordLearning;
    /** Serve al SUGGERIMENTO del wizard §7.1: quale conto propone una firma già appresa. */
    @Inject com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine keywordEngine;
    @Inject MovimentiService movimentiService;
    @Inject RataMatchService matchService;
    /** Serve all'azione COLLEGA: la contabilità della rata resta di competenza del modulo spese. */
    @Inject com.agostinelli.gestionale.spese.service.RecurringExpenseService recurringService;
    /** Serve all'azione RICONCILIA: il ricavo evento nasce SOLO qui (invariante DACLASS). */
    @Inject com.agostinelli.gestionale.eventi.service.EventiService eventiService;
    @Inject com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ── KPI (§13) ────────────────────────────────────────────────────────────────
    // 4 aggregazioni full-table: cache (TTL 2m) invalidata dai mutator di import.
    @CacheResult(cacheName = "import-kpi")
    public ImportKpiDTO getKpi() {
        Object[] s = (Object[]) em.createNativeQuery(
                "SELECT COALESCE(SUM(righe_totali),0), COALESCE(SUM(righe_importate),0), " +
                "COALESCE(SUM(righe_ambigue),0), COALESCE(SUM(righe_scartate),0), " +
                "COALESCE(SUM(righe_parcheggiate),0) FROM import_log").getSingleResult();
        long totali = num(s[0]), importate = num(s[1]), ambigue = num(s[2]),
                scartate = num(s[3]), parcheggiate = num(s[4]);

        // Transitori divisi per natura: il "saldo" complessivo è una somma LORDA (entrate+uscite),
        // utile solo come volume; i numeri sensati sono ricavi-da-classificare e costi-da-classificare.
        Object[] tr = (Object[]) em.createNativeQuery(
                "SELECT " +
                "  COUNT(*) FILTER (WHERE p.codice = '39.99.999'), " +
                "  COALESCE(SUM(m.importo_lordo) FILTER (WHERE p.codice = '39.99.999'),0), " +
                "  COUNT(*) FILTER (WHERE p.codice = '49.99.999'), " +
                "  COALESCE(SUM(m.importo_lordo) FILTER (WHERE p.codice = '49.99.999'),0) " +
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice IN ('39.99.999','49.99.999') AND m.stato <> 'ANNULLATO'")
                .getSingleResult();
        long ricaviTransitoriCount = num(tr[0]);
        BigDecimal ricaviDaClassificare = tr[1] == null ? BigDecimal.ZERO : (BigDecimal) tr[1];
        long costiTransitoriCount = num(tr[2]);
        BigDecimal costiDaClassificare = tr[3] == null ? BigDecimal.ZERO : (BigDecimal) tr[3];
        long transitoriCount = ricaviTransitoriCount + costiTransitoriCount;
        BigDecimal saldoTransitori = ricaviDaClassificare.add(costiDaClassificare);

        // Copertura fornitori: si escludono dal denominatore le uscite che per NATURA non hanno
        // un fornitore (spese/commissioni bancarie 40.02.*, tributi F24, metodo ADDEBITO_CONTO):
        // includerle abbasserebbe artificiosamente la percentuale (ANALISI-IMPORT Fix copertura).
        Object[] f = (Object[]) em.createNativeQuery(
                "SELECT COUNT(*) FILTER (WHERE m.fornitore_id IS NOT NULL), COUNT(*) " +
                "FROM movimenti m " +
                "JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "LEFT JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id " +
                "WHERE m.tipo = 'USCITA' AND m.fonte_importazione_id IS NOT NULL AND m.stato <> 'ANNULLATO' " +
                "AND p.codice NOT LIKE '40.02.%' " +
                "AND COALESCE(mp.codice,'') NOT IN ('ADDEBITO_CONTO','F24')")
                .getSingleResult();
        long usciteConFornitore = num(f[0]), usciteTot = num(f[1]);

        double tassoAmbiguita = totali == 0 ? 0 : (ambigue * 100.0) / totali;
        double copertura = usciteTot == 0 ? 0 : (usciteConFornitore * 100.0) / usciteTot;

        return new ImportKpiDTO(totali, importate, ambigue, scartate, parcheggiate,
                transitoriCount, saldoTransitori,
                round2(tassoAmbiguita), round2(copertura),
                ricaviTransitoriCount, ricaviDaClassificare,
                costiTransitoriCount, costiDaClassificare);
    }

    /**
     * I badge della console Import in UNA query e UNA risposta (vedi {@link ImportBadgeDTO}).
     * Le sottoquery ripetono alla lettera le clausole delle liste che sostituiscono: se una di
     * quelle cambia, questa deve cambiare con lei — il badge non è una seconda verità.
     */
    public ImportBadgeDTO getBadge() {
        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT " +
                "  (SELECT COUNT(*) FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "    WHERE p.codice IN ('" + COGE_RICAVI_DACLASS + "','" + COGE_COSTI_DACLASS + "') " +
                "      AND m.stato <> 'ANNULLATO'), " +
                "  (SELECT COUNT(*) FROM ricorrenti_da_riconciliare WHERE stato = 'DA_RICONCILIARE'), " +
                "  (SELECT COUNT(*) FROM eventi_da_riconciliare WHERE stato = 'DA_RICONCILIARE'), " +
                "  (SELECT COUNT(*) FROM matching_differiti WHERE stato = 'DA_RICONCILIARE'), " +
                "  (SELECT COUNT(*) FROM import_scartati WHERE stato = 'DA_VEDERE'), " +
                "  (SELECT id FROM import_log ORDER BY data_import DESC LIMIT 1)")
                .getSingleResult();
        return new ImportBadgeDTO(num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4]),
                r[5] == null ? null : toUuid(r[5]));
    }

    // I suggerimenti basati sulla rubrica controparti (IBAN/fuzzy) sono rimossi con la dismissione
    // della tabella controparti (V7): l'auto-riconoscimento è ora a keyword (auto-catalogazione in
    // import) e la catalogazione manuale resta assistita dall'anagrafica fornitori/COGE.

    // ── CRUD regole_classificazione (§9, modificabili senza redeploy) ─────────────
    @SuppressWarnings("unchecked")
    public List<RegolaClassificazioneDTO> listRegole() {
        List<RegolaClassificazioneDTO> out = new ArrayList<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT id, priorita, sorgente, tipo_movimento, campo, match_type, pattern, azione, " +
                "coge_codice, bu_id, metodo_codice, confidence, attivo, note " +
                "FROM regole_classificazione ORDER BY priorita, id").getResultList()) {
            out.add(new RegolaClassificazioneDTO(
                    ((Number) r[0]).intValue(), ((Number) r[1]).intValue(), (String) r[2], (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6], (String) r[7], (String) r[8],
                    r[9] == null ? null : ((Number) r[9]).shortValue(), (String) r[10],
                    (BigDecimal) r[11], (Boolean) r[12], (String) r[13]));
        }
        return out;
    }

    @Transactional
    public Integer createRegola(RegolaClassificazioneDTO d) {
        Object id = em.createNativeQuery(
                "INSERT INTO regole_classificazione (priorita, sorgente, tipo_movimento, campo, match_type, " +
                "pattern, azione, coge_codice, bu_id, metodo_codice, confidence, attivo, note) " +
                "VALUES (:pri, :sorg, :tipo, :campo, :mt, :pat, :az, :coge, :bu, :met, :conf, :att, :note) RETURNING id")
                .setParameter("pri", d.priorita())
                .setParameter("sorg", coalesce(d.sorgente(), "*"))
                .setParameter("tipo", coalesce(d.tipoMovimento(), "*"))
                .setParameter("campo", d.campo())
                .setParameter("mt", d.matchType())
                .setParameter("pat", d.pattern())
                .setParameter("az", d.azione())
                .setParameter("coge", d.cogeCodice())
                .setParameter("bu", d.buId())
                .setParameter("met", d.metodoCodice())
                .setParameter("conf", d.confidence() == null ? BigDecimal.ONE : d.confidence())
                .setParameter("att", d.attivo())
                .setParameter("note", d.note())
                .getSingleResult();
        regoleEngine.refresh();
        return ((Number) id).intValue();
    }

    @Transactional
    public void setRegolaAttiva(int id, boolean attiva) {
        int upd = em.createNativeQuery("UPDATE regole_classificazione SET attivo = :a WHERE id = :id")
                .setParameter("a", attiva).setParameter("id", id).executeUpdate();
        if (upd == 0) throw new ApiException(Response.Status.NOT_FOUND, "REGOLA_NON_TROVATA", "Regola " + id);
        regoleEngine.refresh();
    }

    @Transactional
    public void deleteRegola(int id) {
        int del = em.createNativeQuery("DELETE FROM regole_classificazione WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        if (del == 0) throw new ApiException(Response.Status.NOT_FOUND, "REGOLA_NON_TROVATA", "Regola " + id);
        regoleEngine.refresh();
    }

    // ── Centro smistamento: movimenti su conti transitori (39/49.99.999) ──────────

    /**
     * Lista paginata dei movimenti ancora su conto transitorio (da catalogare), con la chiave di
     * raggruppamento del wizard §7.1 e l'eventuale suggerimento di una firma keyword appresa.
     *
     * <p>Da 11/08/2026 include ANCHE le righe EFFETTI/RiBa (audit §7.7: «sono uscite da catalogare
     * come le altre»); la coda separata e {@code listRibaTransitori} sono cancellate. Gli incassi
     * POS non finiscono più sul transitorio (li categorizza Billy): un ricavo Billy con categoria
     * non determinabile (raro) resta visibile qui invece di sparire.
     */
    @SuppressWarnings("unchecked")
    public PagedResponse<TransitorioDTO> listTransitori(String tipo, int page, int size) {
        String from = "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id ";
        // L'anagrafica serve solo alla pagina: il COUNT resta sulla forma minima. I due LEFT JOIN
        // sono su chiave primaria e non moltiplicano le righe.
        String fromConAnagrafica = from
                + "LEFT JOIN fornitori f ON f.id = m.fornitore_id "
                + "LEFT JOIN metodi_pagamento mp ON mp.id = m.metodo_pagamento_id ";
        String where =
                "WHERE p.codice IN ('" + COGE_RICAVI_DACLASS + "','" + COGE_COSTI_DACLASS + "') " +
                "AND m.stato <> 'ANNULLATO' AND (CAST(:tipo AS VARCHAR) IS NULL OR m.tipo = :tipo)";

        // Tutto quello che la riga sa di sé, in una lettura sola: la ragione sociale del fornitore
        // che il motore ha già riconosciuto e il metodo di pagamento arrivano dall'anagrafica
        // agganciata qui (nessun N+1), il riferimento esterno è la chiave con cui si ritrova la
        // riga sull'estratto conto della banca.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.id, m.tipo, m.importo_lordo, m.data_movimento, m.descrizione, p.codice, " +
                "m.fornitore_id, m.conto_bancario_id, m.note, f.ragione_sociale, " +
                "m.riferimento_esterno, mp.descrizione, f.bu_default_id " + fromConAnagrafica + where +
                " ORDER BY m.data_movimento, m.id LIMIT :size OFFSET :offset")
                .setParameter("tipo", tipo)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        // La proposta: prima quella che il motore ha SCRITTO all'import (R1/R6 — porta il perché
        // esatto, firma o alias che sia), poi, come rete, quella ricalcolata adesso dal motore
        // keyword. Il ricalcolo serve alle righe già in coda da prima e a quelle che una firma
        // nuova sa spiegare solo ora (stesso principio di listRicorrenti, SPEC I3).
        List<String> codiciSugg = new ArrayList<>(rows.size());
        List<String> perche = new ArrayList<>(rows.size());
        List<Short> buSugg = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            var scritta = Proposta.leggi((String) r[8]);
            // Una proposta che punta al transitorio è «ti propongo di lasciarla dov'è»: rumore.
            // Vale anche per le righe già a DB, importate prima della guardia in applyKeyword.
            if (scritta != null && (COGE_RICAVI_DACLASS.equals(scritta.cogeCodice())
                    || COGE_COSTI_DACLASS.equals(scritta.cogeCodice()))) {
                scritta = null;
            }
            if (scritta != null) {
                codiciSugg.add(scritta.cogeCodice());
                perche.add(scritta.perche());
                buSugg.add(scritta.bu());
            } else {
                // Il ricalcolo nomina la firma esattamente come fa il motore all'import (R6): i
                // token sono già nel match, e una frase che dice «c'è una firma» senza dire QUALE
                // chiede fiducia invece di darne le ragioni.
                var m = suggerimentoKeyword((String) r[4], (String) r[1], r[7]);
                codiciSugg.add(m == null ? null : m.cogeCodice());
                perche.add(m == null ? null
                        : "la causale contiene la firma «" + m.firmaLeggibile() + "» ("
                          + m.natura().name().toLowerCase(Locale.ROOT) + "): l'hai già catalogata così");
                buSugg.add(m == null ? null : m.bu());
            }
        }
        Map<String, Integer> idByCodice = cogeIdByCodice(new java.util.HashSet<>(codiciSugg));

        // Riscontro Billy: UNA query di aggregazione per pagina (giorno+conto+voce), poi l'aggancio
        // si fa in memoria. Non è un join per riga: sarebbe N+1 su un dato indicativo.
        Map<String, BillyGiorno> billy = ricaviBillyPerGiorno();

        List<TransitorioDTO> content = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            String descr = (String) r[4];
            Short conto = r[7] == null ? null : ((Number) r[7]).shortValue();
            LocalDate data = ((java.sql.Date) r[3]).toLocalDate();
            BigDecimal importo = (BigDecimal) r[2];
            EntitaEstratte ent = estraiEntita(descr, conto);
            String controparte = ent.beneficiario() != null ? ent.beneficiario() : ent.ordinante();
            Integer idSugg = codiciSugg.get(i) == null ? null : idByCodice.get(codiciSugg.get(i));

            // Solo sulle righe POS: altrove «cosa ha incassato Billy quel giorno» non vuol dire nulla.
            // E un incasso POS è per definizione un'ENTRATA: senza questo filtro un addebito SDD
            // verso Nexi ("ADDEBITO DIRETTO SDD … NEXI P.") veniva letto come riga POS e portava con
            // sé il riscontro Billy di giornata — un confronto fra un COSTO e i ricavi del giorno.
            String circuito = "ENTRATA".equals(r[1]) ? DescNormalizer.circuitoPos(descr) : null;
            TransitorioDTO.RiscontroBillyDTO riscontro = null;
            if (circuito != null && conto != null) {
                BillyGiorno g = billy.get(data + "|" + conto);
                if (g != null) {
                    riscontro = new TransitorioDTO.RiscontroBillyDTO(
                            g.scontrini, g.totale, importo.subtract(g.totale), List.copyOf(g.voci));
                }
            }

            UUID fornitoreId = r[6] == null ? null : toUuid(r[6]);
            // Il ramo proposto: prima quello che il motore aveva calcolato per QUESTA riga, poi il
            // default in anagrafica del fornitore riconosciuto — che è anche l'unica fonte per i
            // movimenti importati prima del 13/08 (le loro note non portano il ramo). Nessuno dei
            // due viene applicato da solo: il wizard lo pre-seleziona e lo scrive a schermo, la
            // conferma resta dell'operatore (R4/R6).
            Short bu = buSugg.get(i) != null ? buSugg.get(i)
                    : r[12] == null ? null : ((Number) r[12]).shortValue();

            content.add(new TransitorioDTO(
                    toUuid(r[0]), (String) r[1], importo, data, descr, (String) r[5],
                    fornitoreId, conto,
                    ent.ibanControparte(), controparte,
                    DescNormalizer.chiaveGruppo(controparte, descr),
                    DescNormalizer.dataOperazione(descr), circuito, riscontro,
                    idSugg,
                    idSugg == null ? null : perche.get(i),
                    bu, (String) r[9], (String) r[10], (String) r[11],
                    firmeDaImparare(descr, controparte, ent)));
        }

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + from + where)
                .setParameter("tipo", tipo).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }


    /** Mastri dei ricavi che nascono dagli scontrini Billy (spaccio/agriturismo). */
    private static final String COGE_RICAVI_BILLY = "30.03.";

    /**
     * Ricavi Billy aggregati per giorno e conto: {@code "2026-01-12|2" → totale}, e nella mappa
     * {@code conteggi} il numero di righe.
     *
     * <p>È il riscontro di GIORNATA del wizard §7.1 sugli incassi POS. <b>Non</b> è un abbinamento
     * scontrino↔accredito: quello non esiste nei dati (la ripartizione POS lavora sui totali di
     * periodo). Misurato l'11/08/2026 sul corpus: per data-accredito 32 righe POS su 48 hanno un
     * riscontro, con importi che non coincidono — per questo il DTO lo dichiara come indicativo.
     */
    @SuppressWarnings("unchecked")
    private Map<String, BillyGiorno> ricaviBillyPerGiorno() {
        Map<String, BillyGiorno> out = new LinkedHashMap<>();
        // Il GROUP BY scende di un livello (anche per voce di bilancio): stessa query, stessa
        // scansione, in più il DI CHE COSA era fatta la giornata. Il totale e il conteggio si
        // ricompongono sommando le voci — una sola fonte, nessun rischio che raccontino due storie.
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT m.data_movimento, m.conto_bancario_id, p.descrizione, COUNT(*), SUM(m.importo_lordo) " +
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE p.codice LIKE '" + COGE_RICAVI_BILLY + "%' AND m.stato <> 'ANNULLATO' " +
                "  AND m.conto_bancario_id IS NOT NULL " +
                "GROUP BY 1, 2, 3 ORDER BY 5 DESC").getResultList()) {
            String k = ((java.sql.Date) r[0]).toLocalDate() + "|" + ((Number) r[1]).shortValue();
            BillyGiorno g = out.computeIfAbsent(k, x -> new BillyGiorno());
            g.scontrini += ((Number) r[3]).longValue();
            g.totale = g.totale.add((BigDecimal) r[4]);
            g.voci.add(new TransitorioDTO.VoceBillyDTO((String) r[2], (BigDecimal) r[4]));
        }
        return out;
    }

    /** Che cosa ha incassato Billy in una giornata su un conto, e di che cosa era fatto. */
    private static final class BillyGiorno {
        long scontrini;
        BigDecimal totale = BigDecimal.ZERO;
        final List<TransitorioDTO.VoceBillyDTO> voci = new ArrayList<>();
    }

    /**
     * Codice CoGe proposto da una firma keyword APPRESA per questa riga, o null.
     *
     * <p>Declassamento del gate 9b (audit §7.8): la firma non cataloga più da sola fuori
     * dall'import — qui diventa una proposta col «perché» in chiaro, che l'utente conferma con un
     * click. Un target sul mastro riservato non si propone nemmeno (invariante DACLASS).
     */
    private KeywordClassificazioneEngine.KeywordMatch suggerimentoKeyword(
            String descrizione, String tipo, Object contoBancarioId) {
        Short conto = contoBancarioId == null ? null : ((Number) contoBancarioId).shortValue();
        String sorgente = conto == null ? Sorgente.CA
                : (conto == 1 ? Sorgente.BPM : (conto == 2 ? Sorgente.CA : Sorgente.BILLY));
        return keywordEngine.valuta(descrizione, tipo, sorgente)
                // Un target sul mastro riservato non si propone nemmeno (invariante DACLASS); un
                // target sul TRANSITORIO neanche: proporre il conto su cui la riga già sta non è
                // una proposta, è rumore.
                .filter(m -> !m.conflitto() && !CogeRiservatoEventi.riservato(m.cogeCodice()))
                .filter(m -> !CogeTransitorio.transitorio(m.cogeCodice()))
                .orElse(null);
    }


    /**
     * Classifica un movimento transitorio: lo sposta sul conto COGE/BU corretto (e
     * fornitore), opzionalmente apprende la controparte per IBAN (auto-riconoscimento
     * ai prossimi import). Monitorabile: dopo la chiamata il movimento esce dai transitori.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void classificaTransitorio(UUID movimentoId, ClassificaTransitorioRequest req) {
        Movimento m = em.find(Movimento.class, movimentoId);
        if (m == null) throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_TROVATO", "Movimento " + movimentoId);

        // R13 — La correzione resta SEMPRE aperta: fino al 12/08/2026 qui c'era un 409
        // NON_TRANSITORIO che rendeva definitivo tutto ciò che il motore aveva scritto da solo.
        // Ora catalogare e ri-catalogare sono la stessa operazione. Restano due confini:
        //  · il movimento dev'essere nato da un import (un movimento manuale si corregge dalla
        //    pagina Movimenti, che è la sua strada);
        //  · un annullato è fuori da ogni lettura contabile: non si riclassifica.
        if (m.fonteImportazioneId == null) {
            throw new ApiException(Response.Status.CONFLICT, "NON_DA_IMPORT",
                    "Questo movimento non viene da un import: si corregge dalla pagina Movimenti");
        }
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Il movimento è annullato: non è ricatalogabile");
        }

        // Boundary sul conto scelto: deve esistere (senza, la FK darebbe un 500 illeggibile), non
        // può essere il mastro riservato agli eventi né uno dei due conti d'attesa dell'import —
        // la UI che li nasconde è cortesia, non guardia.
        List<?> target = em.createNativeQuery(
                "SELECT codice FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", req.cogeId()).getResultList();
        if (target.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_TROVATO",
                    "Conto CoGe inesistente: " + req.cogeId());
        }
        CogeRiservatoEventi.vieta((String) target.get(0));
        CogeTransitorio.vieta((String) target.get(0));

        // R17 — il voto sulla firma, PRIMA di riscrivere la nota (che è dove vive la proposta).
        votaFirma(m, (String) target.get(0));

        m.contoCoge = req.cogeId();
        m.businessUnitId = req.businessUnitId();
        m.fornitoreId = req.fornitoreId();
        // La proposta ha esaurito il suo compito: lasciarla nella nota farebbe leggere domani
        // «proposta non applicata» su una riga che l'utente ha già deciso (R14: si riscrive).
        m.note = aggiungiNota(Proposta.rimuovi(m.note), req.nota());
        em.merge(m);

        if (req.apprendiKeyword()) {
            EntitaEstratte ent = estraiEntita(m.descrizione, m.contoBancarioId);
            keywordLearning.apprendi(m.descrizione, ent, m.tipo, req.businessUnitId(), req.cogeId(),
                    req.fornitoreId(), movimentoId, null);
        }

        mvRefresh.requestRefreshAfterCommit();
    }

    /**
     * R17 — Confermare la proposta di una firma fa {@code usi_confermati++}; correggerla fa
     * {@code usi_corretti++}, e una sola correzione le toglie per sempre la promozione (R18).
     *
     * <p>Quale firma? Quella che il motore keyword riconosce ADESSO su questa descrizione: è
     * deterministica sullo stesso input, è la stessa che ha prodotto la proposta, ed è l'unica
     * fonte che non richieda di persistere un puntatore (ADR 008). Se oggi nessuna firma matcha —
     * la proposta veniva da un alias fornitore, o la firma è stata cancellata — non si vota:
     * meglio nessun voto che il voto sulla firma sbagliata.
     */
    private void votaFirma(Movimento m, String cogeScelto) {
        Proposta proposta = Proposta.leggi(m.note);
        if (proposta == null) return;   // la riga non portava una proposta: niente da votare
        String sorgente = m.contoBancarioId == null ? Sorgente.CA
                : (m.contoBancarioId == 1 ? Sorgente.BPM : (m.contoBancarioId == 2 ? Sorgente.CA : Sorgente.BILLY));
        var match = keywordEngine.valuta(m.descrizione, m.tipo, sorgente)
                .filter(k -> !k.conflitto() && k.firmaId() != null)
                .filter(k -> proposta.cogeCodice().equals(k.cogeCodice()));
        if (match.isEmpty()) return;

        boolean confermata = proposta.cogeCodice().equals(cogeScelto);
        em.createNativeQuery("UPDATE keyword_firma SET "
                + (confermata ? "usi_confermati = usi_confermati + 1" : "usi_corretti = usi_corretti + 1")
                + ", updated_at = now() WHERE id = :id")
                .setParameter("id", match.get().firmaId())
                .executeUpdate();
        keywordEngine.refresh();   // il voto conta solo se il motore lo vede al prossimo import
    }

    /** Concatena una nota utente a quella esistente, senza lasciare separatori orfani. */
    private static String aggiungiNota(String esistente, String nuova) {
        String base = esistente == null ? "" : esistente.trim();
        String add = nuova == null ? "" : nuova.trim();
        if (add.isEmpty()) return base.isEmpty() ? null : base;
        return base.isEmpty() ? add : base + " | " + add;
    }

    // ── «Non è una spesa»: rimanda la riga alla coda giusta ──────────────────────

    /**
     * Sposta una riga bancaria dal transitorio alla coda che sa lavorarla: incassi evento o rate.
     *
     * <p><b>Perché serve.</b> Il motore riconosce gli incassi-evento da una keyword nella causale
     * (Gate B). Una causale come {@code "8RIST 14/06/26"} non ne ha nessuna: la riga arriva fra le
     * spese da sistemare, dove la risposta giusta <b>non esiste</b> — un ricavo-evento non si
     * cataloga scegliendo un conto, nasce da {@code EventiService.registraPagamento} (invariante
     * DACLASS) che aggiorna anche incassato/stato dell'evento e applica le guardie del
     * percorso-soldi. Senza questa via d'uscita l'operatore ha due sole scelte: lasciarla in
     * sospeso per sempre, o forzarla su un conto sbagliato.
     *
     * <p><b>Perché il movimento si CANCELLA e non si annulla.</b> Un movimento annullato esce da
     * ogni bucket del contatore e apre un buco nell'invariante (§5): direbbe che quel denaro ha
     * lasciato i libri, che è falso — è solo passato in un'altra coda. Cancellandolo, la riga
     * cambia casa restando nello stesso bucket «da catalogare»: il totale non si muove, ed è
     * esattamente ciò che R10 chiede. Il prima/dopo resta in {@code audit_log} (DELETE con
     * {@code dati_precedenti}), come per il rollback di un import.
     *
     * <p><b>Conseguenza dichiarata:</b> finché la riga è in coda, il suo importo non è nel saldo
     * del conto — lo stesso identico trattamento delle righe che l'import parcheggia da solo.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void spostaInCoda(UUID movimentoId, SpostaRigaRequest req, UUID userId) {
        Movimento m = em.find(Movimento.class, movimentoId);
        if (m == null) throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_TROVATO",
                "Movimento " + movimentoId);
        if (m.fonteImportazioneId == null) {
            throw new ApiException(Response.Status.CONFLICT, "NON_DA_IMPORT",
                    "Questo movimento non viene da un import: non c'è una coda a cui rimandarlo");
        }
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Il movimento è annullato: non è spostabile");
        }
        // Se una riga di coda punta già a questo movimento, quella riga È la sua casa: spostarlo
        // lascerebbe un puntatore morto e il contatore conterebbe la riga due volte (una come coda
        // risolta, una come nuova riga). Si risolve da lì, non da qui.
        if (riferitoDaUnaCoda(movimentoId)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_GIA_IN_UNA_CODA",
                    "Questa riga è già stata risolta da una coda: va corretta da quella schermata");
        }

        String dest = req.destinazione() == null ? "" : req.destinazione().toUpperCase();
        switch (dest) {
            case "EVENTO" -> accodaEvento(m, req.nota());
            case "RICORRENTE" -> accodaRicorrente(m, req.nota());
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "DESTINAZIONE_NON_VALIDA",
                    "Destinazione non valida: " + req.destinazione() + " (EVENTO | RICORRENTE)");
        }

        em.remove(m);
        mvRefresh.requestRefreshAfterCommit();
    }

    private boolean riferitoDaUnaCoda(UUID movimentoId) {
        for (String t : List.of("import_scartati", "import_ambiguita", "ricorrenti_da_riconciliare")) {
            long n = ((Number) em.createNativeQuery(
                    "SELECT count(*) FROM " + t + " WHERE movimento_id = :id")
                    .setParameter("id", movimentoId).getSingleResult()).longValue();
            if (n > 0) return true;
        }
        return false;
    }

    private void accodaEvento(Movimento m, String nota) {
        EntitaEstratte ent = estraiEntita(m.descrizione, m.contoBancarioId);
        String controparte = ent.beneficiario() != null ? ent.beneficiario() : ent.ordinante();
        em.createNativeQuery(
                "INSERT INTO eventi_da_riconciliare (id, import_log_id, fonte, data_movimento, " +
                "importo, tipo, conto_bancario_id, descrizione_norm, controparte_nome, " +
                "controparte_iban, data_evento_estratta, raw_data) " +
                "VALUES (:id, :logId, 'IMPORT_BANCA', :data, :imp, :tipo, :conto, :descr, :nome, " +
                ":iban, :dataEv, CAST(:raw AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", m.fonteImportazioneId)
                .setParameter("data", m.dataMovimento)
                .setParameter("imp", m.importo)
                .setParameter("tipo", m.tipo)
                .setParameter("conto", m.contoBancarioId)
                .setParameter("descr", m.descrizione)
                .setParameter("nome", controparte)
                .setParameter("iban", ent.ibanControparte())
                // La data evento si ri-estrae dalla causale con lo stesso parser del Gate B: senza,
                // la coda non saprebbe proporre nessun evento (suggerisciEvento parte da lì).
                .setParameter("dataEv", mappingEngine.extractEventoDate(m.descrizione, m.dataMovimento))
                .setParameter("raw", rawRicostruito(m, nota))
                .executeUpdate();
    }

    private void accodaRicorrente(Movimento m, String nota) {
        em.createNativeQuery(
                "INSERT INTO ricorrenti_da_riconciliare (id, import_log_id, fonte, data_movimento, " +
                "importo, tipo, conto_bancario_id, descrizione_norm, tipo_presunto, raw_data, note) " +
                "VALUES (:id, :logId, 'IMPORT_BANCA', :data, :imp, :tipo, :conto, :descr, :tipoP, " +
                "CAST(:raw AS jsonb), :nota)")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", m.fonteImportazioneId)
                .setParameter("data", m.dataMovimento)
                .setParameter("imp", m.importo)
                .setParameter("tipo", m.tipo)
                .setParameter("conto", m.contoBancarioId)
                .setParameter("descr", m.descrizione)
                .setParameter("tipoP", "ALTRO")
                .setParameter("raw", rawRicostruito(m, nota))
                .setParameter("nota", nota)
                .executeUpdate();
    }

    /**
     * Le code vogliono il grezzo, ma il grezzo dell'import NON è conservato sui movimenti
     * contabilizzati (SPEC §7). Si ricostruisce dai campi del movimento e si marca come tale:
     * chi lo rilegge deve sapere che non è la riga com'era nel CSV.
     */
    private String rawRicostruito(Movimento m, String nota) {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("_ricostruito", "true");
        raw.put("_origine", "spostata a mano dal wizard spese");
        raw.put("CAUSALE", m.descrizione == null ? "" : m.descrizione);
        raw.put("DATA", String.valueOf(m.dataMovimento));
        raw.put("IMPORTO", m.importo == null ? "" : m.importo.toPlainString());
        if (nota != null && !nota.isBlank()) raw.put("_nota_triage", nota.trim());
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            return "{}";
        }
    }

    @Inject MovimentoMappingEngineImpl mappingEngine;

    // ── Pannello BU dell'import (docs/adr/006) ───────────────────────────────────
    //
    // Vista di rifinitura post-import: i movimenti di UN import raggruppati per Business Unit,
    // con gli INCERTI a parte. "Incerto" è DERIVATO dal dato — conto CoGe transitorio
    // (39.99.999 / 49.99.999) + BU ancora sul fallback 5 — non marcato dal motore: nel mapping
    // engine ogni assegnazione di quei conti porta con sé bu=5, mentre le altre bu=5 (spese banca
    // 40.02.*, giroconti 10.03.*, versamento soci) sono classificazioni VOLUTE, non incertezza.
    // Nessuna migration, nessun campo nuovo.

    /**
     * Movimenti creati dall'import {@code importLogId}, raggruppati per BU (annullati esclusi).
     * Una sola query: i volumi sono quelli di un import (~10²), il raggruppamento sta in Java.
     */
    @SuppressWarnings("unchecked")
    public BuPanelDTO getBuPanel(UUID importLogId) {
        List<Object[]> log = em.createNativeQuery(
                "SELECT data_import, filename FROM import_log WHERE id = :id")
                .setParameter("id", importLogId)
                .getResultList();
        if (log.isEmpty()) throw new ApiException(Response.Status.NOT_FOUND,
                "IMPORT_NON_TROVATO", "Import " + importLogId);

        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.id, m.tipo, m.importo_lordo, m.data_movimento, m.descrizione, " +
                "p.codice, p.descrizione, m.business_unit_id, bu.codice, bu.nome " +
                "FROM movimenti m " +
                "JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "JOIN business_units bu ON bu.id = m.business_unit_id " +
                "WHERE m.fonte_importazione_id = :imp AND m.stato <> 'ANNULLATO' " +
                "ORDER BY m.business_unit_id, m.data_movimento, m.id")
                .setParameter("imp", importLogId)
                .getResultList();

        // Accumulatori per gruppo: le BU vere in una mappa ordinata, gli incerti a parte.
        Map<Short, Acc> perBu = new LinkedHashMap<>();
        Acc incerti = new Acc(null, null, "Da assegnare (transitori)");

        for (Object[] r : rows) {
            String cogeCodice = (String) r[5];
            boolean transitorio = COGE_RICAVI_DACLASS.equals(cogeCodice) || COGE_COSTI_DACLASS.equals(cogeCodice);
            Short buId = ((Number) r[7]).shortValue();
            BuPanelDTO.Riga riga = new BuPanelDTO.Riga(
                    toUuid(r[0]), (String) r[1], (BigDecimal) r[2], toLocalDate(r[3]),
                    (String) r[4], cogeCodice, (String) r[6], buId, transitorio);

            // Coda "da assegnare" = transitorio ANCORA sulla BU di fallback: appena l'operatore
            // sceglie una BU vera la riga esce dalla coda e compare nel gruppo scelto (resta
            // marcata transitorio: la CoGe si sistema in "Da catalogare", è un altro lavoro).
            Acc acc = transitorio && BU_OVERHEAD == buId ? incerti
                    : perBu.computeIfAbsent(buId, k -> new Acc(k, (String) r[8], (String) r[9]));
            acc.add(riga);
        }

        List<BuPanelDTO.Gruppo> gruppi = new ArrayList<>(perBu.size());
        for (Acc a : perBu.values()) gruppi.add(a.toGruppo());
        return new BuPanelDTO(importLogId, toInstant(log.get(0)[0]), (String) log.get(0)[1],
                rows.size(), gruppi, incerti.toGruppo());
    }

    /** Accumulatore di gruppo (conteggio + entrate/uscite + righe): vive solo dentro getBuPanel. */
    private static final class Acc {
        private final Short buId;
        private final String codice;
        private final String nome;
        private final List<BuPanelDTO.Riga> righe = new ArrayList<>();
        private BigDecimal entrate = BigDecimal.ZERO;
        private BigDecimal uscite = BigDecimal.ZERO;

        Acc(Short buId, String codice, String nome) { this.buId = buId; this.codice = codice; this.nome = nome; }

        void add(BuPanelDTO.Riga r) {
            righe.add(r);
            if ("ENTRATA".equals(r.tipo())) entrate = entrate.add(r.importo());
            else uscite = uscite.add(r.importo());
        }

        BuPanelDTO.Gruppo toGruppo() {
            return new BuPanelDTO.Gruppo(buId, codice, nome, righe.size(), entrate, uscite, righe);
        }
    }

    /**
     * Sposta un movimento dell'import su un'altra Business Unit.
     *
     * INVARIANTE (app che tratta soldi): la BU è una dimensione ANALITICA — nessun saldo cambia.
     * mv_saldi_conti / mv_cash_flow_statement / mv_riconciliazione_bancaria non leggono
     * business_unit_id; la ri-partizione riguarda solo mv_kpi_mensili e mv_conto_economico_mensile,
     * i cui TOTALI restano invariati (cambia solo su quale BU sono appoggiati). Per questo qui si
     * tocca UNA colonna e si chiede il refresh delle MV analitiche — niente ricalcoli contabili.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public void cambiaBusinessUnit(UUID importLogId, UUID movimentoId, Short businessUnitId) {
        // Boundary 1: la BU deve esistere ed essere attiva (la FK darebbe un 500 illeggibile).
        List<?> bu = em.createNativeQuery("SELECT id FROM business_units WHERE id = :id AND is_active = true")
                .setParameter("id", businessUnitId)
                .getResultList();
        if (bu.isEmpty()) throw new ApiException(Response.Status.NOT_FOUND, "BU_NON_TROVATA",
                "Business Unit " + businessUnitId);

        // Boundary 2: il movimento deve esistere...
        Movimento m = em.find(Movimento.class, movimentoId);
        if (m == null) throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_TROVATO",
                "Movimento " + movimentoId);
        // ...ed essere nato da QUESTO import (il pannello è per-import: fuori da lì non si tocca).
        if (!importLogId.equals(m.fonteImportazioneId)) {
            throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_NON_DI_QUESTO_IMPORT",
                    "Il movimento " + movimentoId + " non appartiene all'import " + importLogId);
        }
        // Boundary 3: uno storno/annullato è fuori da ogni lettura analitica: non si riclassifica.
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Il movimento è annullato: la BU non è modificabile");
        }

        m.businessUnitId = businessUnitId;
        em.merge(m);
        mvRefresh.requestRefreshAfterCommit();
    }

    /** Ri-estrae IBAN/nome dalla descrizione del movimento (sorgente dedotta dal conto). */
    /**
     * Che cosa imparerebbe il sistema confermando questa riga — la stessa cosa che scriverà
     * {@code KeywordLearningService.apprendi}, perché è lo stesso estrattore sulla stessa
     * {@code EntitaEstratte}.
     *
     * <p><b>Lista vuota = da qui non si impara</b>, e il wizard deve dirlo invece di tacere. La
     * condizione è la stessa che il client applicava da solo (`apprendiKeyword` solo con una
     * controparte vera): da «EFFETTI RITIRATI» o da una causale POS nascerebbe una firma spuria
     * che dirotterebbe tutte le righe simili dei prossimi import. La regola vive qui, dove sta il
     * dato, non nel componente Angular.
     */
    private List<TransitorioDTO.FirmaDaImparareDTO> firmeDaImparare(
            String descrizione, String controparte, EntitaEstratte ent) {
        if (controparte == null || controparte.isBlank()) return List.of();
        List<TransitorioDTO.FirmaDaImparareDTO> out = new ArrayList<>();
        for (var f : KeywordExtractor.estraiFirme(
                descrizione, ent, keywordEngine.stopwords(), keywordEngine.domainTokens())) {
            out.add(new TransitorioDTO.FirmaDaImparareDTO(
                    new ArrayList<>(f.valori()), f.natura().name()));
        }
        return out;
    }

    private EntitaEstratte estraiEntita(String descrizione, Short contoBancarioId) {
        String sorgente = contoBancarioId == null ? Sorgente.CA
                : (contoBancarioId == 1 ? Sorgente.BPM : (contoBancarioId == 2 ? Sorgente.CA : Sorgente.BILLY));
        return DescNormalizer.extract(descrizione, sorgente);
    }

    // ── Centro smistamento: eventi parcheggiati (eventi_da_riconciliare) ──────────

    @SuppressWarnings("unchecked")
    public PagedResponse<EventoParcheggiatoDTO> listEventi(String stato, int page, int size) {
        String where = "FROM eventi_da_riconciliare WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)";
        // C2 — il gemello a libro, cercato UNA volta per tutta la pagina con una LATERAL:
        // stesso importo, stessa data finanziaria, stesso conto. È la stessa terna con cui il
        // titolare riconosce «questo bonifico l'ho già messo dentro», e serve PRIMA di scegliere
        // l'evento — oggi il doppione si scopre solo quando il server rifiuta, cioè dopo.
        // Costo misurato (SPEC §C, EXPLAIN ANALYZE 14/08): l'analisi dell'intera coda ≈ 1,06 ms,
        // quanto un singolo controllo di riga ⇒ gate §9.0: nessun indice nuovo, nessuna cache.
        // Conto NULL da una parte o dall'altra ⇒ nessun match (NULL = NULL è «unknown»): stessa
        // scelta fail-open della guardia alla conferma, ADR 009.
        List<Object[]> rows = em.createNativeQuery(
                "SELECT e.id, e.fonte, e.chiave_aggancio, e.data_movimento, e.importo, e.tipo, e.conto_bancario_id, " +
                "e.descrizione_norm, e.tipo_evento_presunto, e.keyword_match, e.controparte_nome, e.controparte_iban, " +
                "e.data_evento_estratta, e.stato, g.created_at, g.nome " +
                "FROM eventi_da_riconciliare e " +
                "LEFT JOIN LATERAL (" +
                "  SELECT m.created_at, ev.nome FROM movimenti m JOIN eventi ev ON ev.id = m.evento_id " +
                "   WHERE m.stato <> 'ANNULLATO' AND m.importo_lordo = e.importo " +
                "     AND m.data_finanziaria = e.data_movimento AND m.conto_bancario_id = e.conto_bancario_id " +
                "   ORDER BY m.created_at LIMIT 1) g ON true " +
                "WHERE (CAST(:stato AS VARCHAR) IS NULL OR e.stato = :stato)" +
                " ORDER BY e.data_movimento, e.id LIMIT :size OFFSET :offset")
                .setParameter("stato", stato)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<EventoParcheggiatoDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            LocalDate dataEv = r[12] == null ? null : ((java.sql.Date) r[12]).toLocalDate();
            Object[] sugg = suggerisciEvento((String) r[10], dataEv);
            content.add(new EventoParcheggiatoDTO(
                    toUuid(r[0]), (String) r[1], (String) r[2],
                    r[3] == null ? null : ((java.sql.Date) r[3]).toLocalDate(),
                    (BigDecimal) r[4], (String) r[5],
                    r[6] == null ? null : ((Number) r[6]).shortValue(),
                    (String) r[7], (String) r[8], (String) r[9], (String) r[10], (String) r[11],
                    dataEv, (String) r[13],
                    sugg == null ? null : (UUID) sugg[0],
                    sugg == null ? null : (String) sugg[1],
                    toLocalDate(r[14]), (String) r[15]));
        }

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Analizza la coda DA_RICONCILIARE alla ricerca di coppie di eventi che il matcher
     * giudica sospette duplicate (CERTA o PROBABILE), con punteggio e motivazioni
     * leggibili. Espone la stessa logica usata in import per l'auto-aggancio, qui in
     * sola lettura: utile per rivedere a mano i casi che il sistema NON ha unito da solo
     * (più candidati ambigui) o per audit.
     */
    public AnalisiDuplicatiDTO analisiDuplicati() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, fonte, data_movimento, importo, tipo, controparte_nome, " +
                "controparte_iban, data_evento_estratta, tipo_evento_presunto, descrizione_norm " +
                "FROM eventi_da_riconciliare WHERE stato = 'DA_RICONCILIARE' " +
                "ORDER BY importo, tipo, data_movimento, id").getResultList();

        int n = rows.size();
        List<AnalisiDuplicatiDTO.EventoBreveDTO> dto = new ArrayList<>(n);
        List<EventoMatcher.Segnali> seg = new ArrayList<>(n);
        for (Object[] r : rows) {
            LocalDate dm = r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate();
            BigDecimal imp = (BigDecimal) r[3];
            String tipo = (String) r[4], nome = (String) r[5], iban = (String) r[6];
            LocalDate ev = r[7] == null ? null : ((java.sql.Date) r[7]).toLocalDate();
            String tipoEv = (String) r[8], descr = (String) r[9];
            dto.add(new AnalisiDuplicatiDTO.EventoBreveDTO(
                    toUuid(r[0]), (String) r[1], dm, imp, tipo, nome, iban, ev, tipoEv, descr));
            seg.add(new EventoMatcher.Segnali(imp, tipo, dm, nome, iban, ev, tipoEv));
        }

        List<AnalisiDuplicatiDTO.CoppiaSospettaDTO> coppie = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            EventoMatcher.Segnali a = seg.get(i);
            if (a.importo() == null) continue;
            for (int j = i + 1; j < n; j++) {
                EventoMatcher.Segnali b = seg.get(j);
                if (b.importo() == null || a.importo().compareTo(b.importo()) != 0) continue;
                if (!Objects.equals(a.tipo(), b.tipo())) continue;
                if (a.dataMovimento() != null && b.dataMovimento() != null
                        && Math.abs(ChronoUnit.DAYS.between(a.dataMovimento(), b.dataMovimento())) > EventoMatcher.GIORNI_FINESTRA) {
                    continue;
                }
                EventoMatcher.Spiegazione sp = EventoMatcher.spiega(a, b);
                if (sp.esito() == EventoMatcher.Esito.NESSUNO) continue;
                List<AnalisiDuplicatiDTO.MotivoDTO> motivi = new ArrayList<>(sp.motivi().size());
                for (EventoMatcher.Motivo m : sp.motivi()) {
                    motivi.add(new AnalisiDuplicatiDTO.MotivoDTO(m.segnale(), m.dettaglio(), m.tono().name()));
                }
                coppie.add(new AnalisiDuplicatiDTO.CoppiaSospettaDTO(
                        sp.esito().name(), sp.punteggio(), dto.get(i), dto.get(j), motivi));
            }
        }
        coppie.sort((x, y) -> Integer.compare(y.punteggio(), x.punteggio()));
        return new AnalisiDuplicatiDTO(n, coppie.size(), coppie);
    }

    /**
     * Risolve una voce-evento: SCARTA (non è un evento) o RICONCILIA (collega a un evento
     * dell'anagrafica). In entrambi i casi la riga esce dalla coda DA_RICONCILIARE.
     * CLASSIFICA è bloccata: le voci evento non generano mai movimenti (invariante DACLASS).
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void risolviEvento(UUID eventoParkId, RisolviEventoRequest req, UUID userId) {
        List<Object[]> found = em.createNativeQuery(
                "SELECT import_log_id, fonte, data_movimento, importo, tipo, conto_bancario_id, " +
                "descrizione_norm, stato, controparte_nome, data_evento_estratta, tipo_evento_presunto " +
                "FROM eventi_da_riconciliare WHERE id = :id")
                .setParameter("id", eventoParkId).getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "EVENTO_NON_TROVATO", "Evento parcheggiato " + eventoParkId);
        }
        Object[] e = found.get(0);
        if (!"DA_RICONCILIARE".equals((String) e[7])) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_GIA_RISOLTO", "La voce è già stata risolta");
        }

        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            // R9: motivo scritto obbligatorio — l'incasso resta fuori dai conti, si deve sapere perché.
            case "SCARTA" -> aggiornaStatoEvento(eventoParkId, "SCARTATO", null,
                    EsclusioneMotivata.obbligatorio(req.nota()));

            // Invariante DACLASS: le voci evento parcheggiate NON generano MAI movimenti —
            // il ricavo evento nasce solo dal modulo Eventi (registrazione pagamenti).
            case "CLASSIFICA" -> throw new ApiException(Response.Status.CONFLICT,
                    "EVENTO_NON_CONTABILIZZABILE",
                    "Le voci evento parcheggiate non generano movimenti: il ricavo nasce dal "
                    + "modulo Eventi (caparra/acconto/saldo). Usa SCARTA o RICONCILIA.");

            case "RICONCILIA" -> riconciliaEvento(eventoParkId, e, req, userId);

            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (SCARTA | CLASSIFICA | RICONCILIA)");
        }
    }

    /**
     * Propone un evento per l'incasso parcheggiato, con regola deliberatamente stretta:
     * serve un token in comune col nome/contatto dell'evento <b>e</b> la data evento esatta.
     *
     * <p>Il solo nome non basta: misurato sull'import di luglio 2026 dà 5 candidati di cui 1 giusto
     * (20%) — "EVENTO" aggancia "Evento del 10/01", "Alberto" aggancia "Pranzo Alberto", "Molteni"
     * aggancia "Battesimo Laura Molteni". Su un aggancio che muove denaro è meglio nessuna proposta
     * che una sbagliata: chi conferma a occhi chiusi sposta soldi sull'evento di un altro.
     *
     * <p>Sono esclusi gli eventi SALDATO: {@code registraPagamento} li rifiuta comunque
     * (EventiService:252), quindi proporli offre all'operatore un solo esito possibile, il 409.
     * Misurato sulla copia di produzione del 07/08/2026: l'unica proposta che il sistema
     * produceva — MOLTENI ELENA 1.000,00 → "Elena molteni" — puntava a un evento SALDATO con
     * residuo 0,00. Edge case già scritto nella SPEC ("la UI deve escluderlo dai candidati").
     *
     * @return {id, nome} dell'unico evento compatibile, o null (nessuno o più d'uno)
     */
    private Object[] suggerisciEvento(String controparte, LocalDate dataEvento) {
        if (dataEvento == null) return null;
        // senza controparte il match per nome non è calcolabile: si passa direttamente alla data
        if (controparte == null || controparte.isBlank()) return soloEventoInQuellaData(dataEvento);
        List<Object[]> hit = em.createNativeQuery(
                "SELECT e.id, e.nome FROM eventi e " +
                "WHERE e.is_segnaposto = false AND e.data_evento = :d " +
                "  AND e.stato NOT IN ('ANNULLATO', 'SALDATO') " +
                "  AND EXISTS (" +
                "    SELECT 1 FROM unnest(string_to_array(upper(regexp_replace(" +
                "        coalesce(e.contatto_nome,'') || ' ' || e.nome, '[^A-Za-z ]', ' ', 'g')), ' ')) te" +
                "    JOIN unnest(string_to_array(upper(regexp_replace(" +
                "        CAST(:c AS TEXT), '[^A-Za-z ]', ' ', 'g')), ' ')) tp ON tp = te" +
                "    WHERE length(te) > 3 AND te <> 'EVENTO')")
                .setParameter("d", dataEvento)
                .setParameter("c", controparte)
                .getResultList();
        // Più di un candidato = ambiguo: non si propone nulla, sceglie l'operatore.
        if (hit.size() == 1) return new Object[]{ toUuid(hit.get(0)[0]), hit.get(0)[1] };

        return soloEventoInQuellaData(dataEvento);
    }

    /**
     * Secondo segnale: la <b>data evento è già di per sé identificante</b> quando in agenda c'è
     * un solo evento aperto quel giorno. Serve perché il nome dell'evento è una descrizione
     * ("Compleanno Ravera"), non il nominativo di chi bonifica: sulle 26 righe di luglio 2026,
     * 14 portavano la data evento ma nessuna agganciava per nome.
     *
     * <p>Misurato sulla copia di produzione del 08/08/2026: sugli 8 incassi in cui la risposta
     * giusta è nota (match nome+data), la sola data individuava l'evento corretto <b>8 volte su
     * 8</b>; e 10 delle 14 date orfane puntano a un unico evento. Copertura attesa 8/26 → 18/26.
     * Resta la regola d'oro: un solo candidato o nessuna proposta.
     */
    private Object[] soloEventoInQuellaData(LocalDate dataEvento) {
        if (dataEvento == null) return null;
        List<Object[]> hit = em.createNativeQuery(
                "SELECT e.id, e.nome FROM eventi e " +
                "WHERE e.is_segnaposto = false AND e.data_evento = :d " +
                "  AND e.stato NOT IN ('ANNULLATO', 'SALDATO')")
                .setParameter("d", dataEvento)
                .getResultList();
        return hit.size() == 1 ? new Object[]{ toUuid(hit.get(0)[0]), hit.get(0)[1] } : null;
    }

    /** I 5 codici di lk_tipi_evento_mov. AFFITTO_SALA, che l'ETL sa suggerire, NON è tra questi. */
    private static final List<String> TIPI_PAGAMENTO_EVENTO =
            List.of("CAPARRA", "ACCONTO", "SALDO", "PENALE", "RIMBORSO");

    private static final int METODO_BONIFICO = 5;

    /**
     * Attribuisce l'incasso parcheggiato a un evento e ne registra il pagamento.
     *
     * <p>È il passo che fa entrare il denaro nei saldi: prima di questo, "Riconcilia" si limitava
     * a marcare la riga e i soldi restavano fuori (misurato: 18.924,00 € invisibili dopo l'import
     * di luglio 2026). Il movimento nasce comunque in {@code EventiService.registraPagamento},
     * così l'invariante DACLASS resta intatta — il triage attribuisce, non contabilizza.
     */
    private void riconciliaEvento(UUID parkId, Object[] e, RisolviEventoRequest req, UUID userId) {
        String tipo = req.tipo() == null ? null : req.tipo().toUpperCase();
        if (!TIPI_PAGAMENTO_EVENTO.contains(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "TIPO_EVENTO_NON_VALIDO",
                    "Tipo pagamento non valido: " + req.tipo() + " (ammessi: " + TIPI_PAGAMENTO_EVENTO + ")");
        }
        if (e[5] == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_BANCARIO_MANCANTE",
                    "La riga non ha un conto bancario: assegnalo prima di attribuirla a un evento");
        }
        if (req.eventoId() == null && !req.creaSegnaposto()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "EVENTO_RICHIESTO",
                    "Serve un eventoId, oppure creaSegnaposto=true se l'evento non è in anagrafica");
        }

        BigDecimal importo    = (BigDecimal) e[3];
        LocalDate dataMov     = ((java.sql.Date) e[2]).toLocalDate();
        Short conto           = ((Number) e[5]).shortValue();
        String controparte    = (String) e[8];
        LocalDate dataEvento  = e[9] == null ? null : ((java.sql.Date) e[9]).toLocalDate();

        UUID eventoId = req.eventoId() != null
                ? req.eventoId()
                : creaSegnaposto(controparte, dataEvento != null ? dataEvento : dataMov, importo, userId);

        eventiService.registraPagamento(eventoId,
                new com.agostinelli.gestionale.eventi.dto.PagamentoRequest(
                        tipo, importo, dataMov, req.nota(), METODO_BONIFICO, conto, req.cogeId(),
                        controparte),
                userId);

        aggiornaStatoEvento(parkId, "RICONCILIATO", eventoId, req.nota());
    }

    /**
     * Contenitore temporaneo per un incasso il cui evento non è ancora in anagrafica.
     *
     * <p>Uno per pagamento, non uno condiviso: con un contenitore unico il pagamento successivo
     * verrebbe rifiutato dalle guardie del percorso-soldi (max 1 CAPARRA/ACCONTO/SALDO per evento,
     * importo ≤ residuo, auto-chiusura a SALDATO) e la competenza economica di tutti gli incassi
     * collasserebbe sulla data del contenitore. Vedi docs/specs/import-eventi-attribuzione.md.
     */
    private UUID creaSegnaposto(String controparte, LocalDate dataEvento, BigDecimal importo, UUID userId) {
        String nome = "[DA ATTRIBUIRE] "
                + (controparte == null || controparte.isBlank() ? "Incasso senza controparte" : controparte.trim());
        UUID id = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO eventi (id, nome, tipo, data_evento, importo_totale_preventivato, " +
                "importo_incassato, caparre_incassate, costi_diretti_imputati, stato, business_unit_id, " +
                "contatto_nome, numero_totale_partecipanti, is_segnaposto, created_by, created_at) " +
                "VALUES (:id, :nome, 'ALTRO', :data, :imp, 0, 0, 0, 'PREVENTIVATO', 2, " +
                ":contatto, 0, true, :user, now())")
                .setParameter("id", id)
                .setParameter("nome", nome)
                .setParameter("data", dataEvento)
                // preventivato = importo: il residuo copre esattamente questo pagamento e nulla di più
                .setParameter("imp", importo)
                .setParameter("contatto", controparte)
                .setParameter("user", userId)
                .executeUpdate();

        // Una voce di preventivo pari all'importo: senza, il segnaposto sarebbe l'unico evento
        // con preventivato != somma voci, e la prima mutazione di voce lo azzererebbe
        // (EventiService.ricalcolaPreventivato) portando incassato > preventivato.
        em.createNativeQuery(
                "INSERT INTO evento_voce (evento_id, label, prezzo_unitario, quantita_preventivo, " +
                "importo_preventivo, origine, created_by) " +
                "VALUES (:ev, 'Incasso da attribuire', :imp, 1, :imp, 'MANUALE', :user)")
                .setParameter("ev", id)
                .setParameter("imp", importo)
                .setParameter("user", userId)
                .executeUpdate();
        em.flush();
        return id;
    }

    private void aggiornaStatoEvento(UUID id, String stato, UUID eventoId, String nota) {
        em.createNativeQuery(
                "UPDATE eventi_da_riconciliare SET stato = :stato, evento_id = :ev, " +
                "raw_data = jsonb_set(raw_data, '{_nota_triage}', to_jsonb(CAST(:nota AS TEXT)), true) " +
                "WHERE id = :id")
                .setParameter("stato", stato)
                .setParameter("ev", eventoId)
                .setParameter("nota", nota == null ? "" : nota)
                .setParameter("id", id)
                .executeUpdate();
    }

    // ── Parcheggio spese ricorrenti / finanziamenti (V9) ──────────────────────────

    /** Coda delle spese ricorrenti parcheggiate (non contabilizzate): da riconciliare a mano. */
    @SuppressWarnings("unchecked")
    public PagedResponse<RicorrenteParcheggiataDTO> listRicorrenti(String stato, int page, int size) {
        String where = "FROM ricorrenti_da_riconciliare WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)";
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, fonte, data_movimento, importo, tipo, conto_bancario_id, descrizione_norm, " +
                "tipo_presunto, recurring_plan_id, stato " + where +
                " ORDER BY data_movimento, id LIMIT :size OFFSET :offset")
                .setParameter("stato", stato).setParameter("size", size).setParameter("offset", (long) page * size)
                .getResultList();
        // Suggerimento CoGe: calcolato in Java dalla descrizione, poi UNA sola query per risolvere
        // i codici in id (no N+1 sul piano dei conti).
        List<String> codiciSugg = new ArrayList<>(rows.size());
        for (Object[] r : rows) codiciSugg.add(suggerisciCogeCodice((String) r[4] /* tipo */, (String) r[6] /* descr */));
        java.util.Map<String, Integer> idByCodice = cogeIdByCodice(new java.util.HashSet<>(codiciSugg));

        // Match strutturato coi piani attivi: UNA query per pagina, poi confronto in memoria.
        // Ricalcolato a ogni lettura e mai persistito (SPEC I3): così le righe già in coda
        // ricevono la proposta appena i piani vengono creati, senza rifare l'import.
        List<RataMatcher.Piano> piani = rows.isEmpty() ? List.of() : matchService.pianiAttivi();

        List<RicorrenteParcheggiataDTO> content = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            String codiceSugg = codiciSugg.get(i);
            Integer idSugg = codiceSugg == null ? null : idByCodice.get(codiceSugg);
            LocalDate dataMov = r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate();
            Short conto = r[5] == null ? null : ((Number) r[5]).shortValue();
            RataMatcher.Esito esito = "USCITA".equals((String) r[4])
                    ? RataMatcher.valuta(conto, (BigDecimal) r[3], dataMov, (String) r[6], piani)
                    : new RataMatcher.Esito(null, List.of());
            content.add(new RicorrenteParcheggiataDTO(
                    toUuid(r[0]), (String) r[1], dataMov,
                    (BigDecimal) r[3], (String) r[4], conto,
                    (String) r[6], (String) r[7],
                    r[8] == null ? null : toUuid(r[8]), (String) r[9],
                    idSugg, idSugg == null ? null : codiceSugg,
                    esito.proposta() == null ? null : esito.proposta().rataId(),
                    esito.candidati().stream().map(ImportTriageService::toDto).toList()));
        }
        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    private static RicorrenteParcheggiataDTO.CandidatoRataDTO toDto(RataMatcher.Candidato c) {
        return new RicorrenteParcheggiataDTO.CandidatoRataDTO(
                c.pianoId(), c.pianoDescrizione(), c.rataId(), c.numeroRata(), c.dataScadenza(),
                c.importoRata(), c.scartoGiorni(), c.scartoImporto(), c.motivo());
    }

    /**
     * Risolve una ricorrente parcheggiata (V22): CONFERMA crea il movimento contabile reale dalla
     * riga (rata già addebitata in banca ma non ancora a libro dopo la ricreazione dei piani sul
     * debito residuo), IGNORA la archivia. L'azione COLLEGA è rimossa (→ 400 AZIONE_NON_VALIDA).
     *
     * Invarianti (Design by Contract): la riga si conferma UNA sola volta (stato != DA_RICONCILIARE
     * → 409); su USCITA il CoGe è obbligatorio (400); importo > 0 (400).
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void risolviRicorrente(UUID id, RisolviRicorrenteRequest req, UUID userId) {
        List<Object[]> found = em.createNativeQuery(
                "SELECT stato, import_log_id, data_movimento, importo, tipo, conto_bancario_id, descrizione_norm " +
                "FROM ricorrenti_da_riconciliare WHERE id = :id").setParameter("id", id).getResultList();
        if (found.isEmpty()) throw new ApiException(Response.Status.NOT_FOUND, "RICORRENTE_NON_TROVATA", "Ricorrente " + id);
        Object[] r = found.get(0);
        if (!"DA_RICONCILIARE".equals((String) r[0])) {
            throw new ApiException(Response.Status.CONFLICT, "RICORRENTE_GIA_RISOLTA", "La voce è già stata risolta");
        }
        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            case "COLLEGA" -> collegaRicorrente(id, r, req, userId);
            case "CONFERMA" -> confermaRicorrente(id, r, req, userId);
            case "IGNORA" -> {
                // R9: la rata resta fuori dai conti solo con un motivo scritto.
                int claimed = em.createNativeQuery(
                        "UPDATE ricorrenti_da_riconciliare SET stato = 'IGNORATA', note = :nota, " +
                        "risolto_at = now(), risolto_by = :uid WHERE id = :id AND stato = 'DA_RICONCILIARE'")
                        .setParameter("nota", EsclusioneMotivata.obbligatorio(req.nota()))
                        .setParameter("uid", userId).setParameter("id", id).executeUpdate();
                if (claimed == 0) {
                    throw new ApiException(Response.Status.CONFLICT, "RICORRENTE_GIA_RISOLTA",
                            "La voce è già stata risolta");
                }
            }
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (COLLEGA | CONFERMA | IGNORA)");
        }
    }

    /**
     * COLLEGA: aggancia la riga parcheggiata alla rata di un piano ricorrente.
     * Il lavoro contabile sta in {@code RecurringExpenseService.collegaRataDaImport}, che decide se
     * creare i movimenti (rata PENDING) o riusare quelli esistenti (rata già PAID). Qui si fa solo
     * il claim atomico della riga e si scrive il collegamento.
     *
     * NB: il movimento appartiene al PIANO (fonte RICORRENTE, senza fonte_importazione_id), quindi
     * un rollback dell'import non lo cancella e non può lasciare una rata PAID che punta al vuoto.
     */
    private void collegaRicorrente(UUID id, Object[] r, RisolviRicorrenteRequest req, UUID userId) {
        if (req.pianoId() == null || req.rataId() == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PIANO_O_RATA_MANCANTE",
                    "Per collegare servono il piano e la rata");
        }
        String tipo = (String) r[4];
        if ("ENTRATA".equals(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COLLEGA_SOLO_USCITE",
                    "Un'entrata (erogazione) non è una rata: usa CONFERMA");
        }
        LocalDate dataAddebito = r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate();

        // L'importo reale si legge QUI dalla riga parcheggiata, non dal client: è il dato certo
        // dell'estratto conto e sovrascrive la stima del piano (SPEC ricorrenti-importo-reale-da-import).
        BigDecimal importoReale = (BigDecimal) r[3];
        UUID movimentoId = recurringService.collegaRataDaImport(
                req.pianoId(), req.rataId(), dataAddebito, importoReale, userId);

        int claimed = em.createNativeQuery(
                "UPDATE ricorrenti_da_riconciliare SET stato = 'RICONCILIATA', recurring_plan_id = :piano, " +
                "movimento_id = :mov, note = :nota, risolto_at = now(), risolto_by = :uid " +
                "WHERE id = :id AND stato = 'DA_RICONCILIARE'")
                .setParameter("piano", req.pianoId()).setParameter("mov", movimentoId)
                .setParameter("nota", req.nota()).setParameter("uid", userId).setParameter("id", id)
                .executeUpdate();
        if (claimed == 0) {
            throw new ApiException(Response.Status.CONFLICT, "RICORRENTE_GIA_RISOLTA",
                    "La voce è già stata risolta");
        }
    }

    /** Crea il movimento contabile da una riga ricorrente confermata e la marca CONFERMATA. */
    private void confermaRicorrente(UUID id, Object[] r, RisolviRicorrenteRequest req, UUID userId) {
        UUID importLogId = toUuid(r[1]);
        LocalDate data = r[2] == null ? null : ((java.sql.Date) r[2]).toLocalDate();
        BigDecimal importo = (BigDecimal) r[3];
        String tipo = (String) r[4];
        Short conto = r[5] == null ? null : ((Number) r[5]).shortValue();
        String descr = (String) r[6];

        if (importo == null || importo.signum() <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_NON_VALIDO",
                    "L'importo della ricorrente deve essere maggiore di zero");
        }
        if (data == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "DATA_MANCANTE",
                    "La riga ricorrente non ha una data movimento da usare per il movimento");
        }

        Integer cogeId;
        String metodoCodice;
        if ("ENTRATA".equals(tipo)) {
            // Erogazione finanziamento: CoGe FISSO 90.01.001, cogeId del client ignorato.
            cogeId = cogeIdByCodiceOrThrow(COGE_FINANZIAMENTO_ENTRATA);
            metodoCodice = "BONIFICO";
        } else {
            if (req.cogeId() == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "COGE_OBBLIGATORIO",
                        "Seleziona il conto CoGe per contabilizzare la rata");
            }
            cogeId = req.cogeId();
            // Fail fast al boundary: il CoGe scelto deve esistere.
            Long exists = ((Number) em.createNativeQuery(
                    "SELECT COUNT(*) FROM piano_dei_conti_coge WHERE id = :id")
                    .setParameter("id", cogeId).getSingleResult()).longValue();
            if (exists == 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_TROVATO",
                        "Conto CoGe inesistente: " + cogeId);
            }
            // Addebito diretto SEPA (SDD) vs addebito generico sul conto.
            metodoCodice = descr != null && descr.toUpperCase().contains("SDD") ? "RID_SDDMANDAT" : "ADDEBITO_CONTO";
        }
        Integer metodoId = metodoIdByCodiceOrThrow(metodoCodice);

        // Claim ATOMICO anti doppia-conferma (TOCTOU): la transizione di stato con predicato è
        // l'unico guard concorrenza-safe — due richieste sovrapposte si serializzano sul row-lock
        // e la perdente vede 0 righe → 409. Se la creazione del movimento poi fallisce, il
        // rollback della @Transactional rilascia anche il claim (niente righe bruciate).
        int claimed = em.createNativeQuery(
                "UPDATE ricorrenti_da_riconciliare SET stato = 'CONFERMATA', note = :nota, " +
                "risolto_at = now(), risolto_by = :uid WHERE id = :id AND stato = 'DA_RICONCILIARE'")
                .setParameter("nota", req.nota()).setParameter("uid", userId)
                .setParameter("id", id).executeUpdate();
        if (claimed == 0) {
            throw new ApiException(Response.Status.CONFLICT, "RICORRENTE_GIA_RISOLTA",
                    "La voce è già stata risolta");
        }

        MovimentoCreateRequest mreq = new MovimentoCreateRequest(
                tipo, importo, null, null,
                data, data, data, null,          // dataMovimento = dataCompetenza = dataFinanziaria = data riga
                conto, metodoId, BU_OVERHEAD, cogeId,
                null, null, null, null,
                descr != null && !descr.isBlank() ? descr : "Rata ricorrente",
                null, null, "IMPORT_BANCA", null);
        UUID movimentoId = movimentiService.createMovimentoImport(mreq, userId, importLogId).id();

        em.createNativeQuery(
                "UPDATE ricorrenti_da_riconciliare SET movimento_id = :mid WHERE id = :id")
                .setParameter("mid", movimentoId).setParameter("id", id).executeUpdate();

        mvRefresh.requestRefreshAfterCommit();
    }

    // ── Coda «Righe fuori dai conti» (audit §7.4 · SPEC docs/specs/righe-fuori-dai-conti.md) ──
    // Le righe bancarie che l'import ha escluso stanno in import_scartati da sempre, ma fino
    // all'11/08/2026 nessuna schermata le leggeva: 1.189,55 € di accrediti veri, muti, in 6 mesi.

    @Inject MovimentoNormalizer normalizer;

    /** La coda: righe escluse dalla pipeline, col «perché» in italiano e i dati per contabilizzarle. */
    @SuppressWarnings("unchecked")
    public PagedResponse<ScartatoDTO> listScartati(String stato, int page, int size) {
        String where = "FROM import_scartati WHERE (CAST(:stato AS VARCHAR) IS NULL OR stato = :stato)";
        List<Object[]> rows = em.createNativeQuery(
                "SELECT id, import_log_id, riga_numero, fonte, motivo, data_movimento, importo, " +
                "CAST(raw_data AS text), stato, movimento_id " + where +
                " ORDER BY data_movimento, riga_numero LIMIT :size OFFSET :offset")
                .setParameter("stato", stato).setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        Map<Short, String> contoNomi = contiBancariNomi();
        List<ScartatoDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            String motivo = (String) r[4];
            var norm = rinormalizza((String) r[7], ((Number) r[2]).intValue());
            Short conto = norm == null ? null : norm.contoBancarioId();
            content.add(new ScartatoDTO(
                    toUuid(r[0]), toUuid(r[1]), (String) r[3], ((Number) r[2]).intValue(),
                    motivo, motivoLeggibile(motivo),
                    r[5] == null ? null : ((java.sql.Date) r[5]).toLocalDate(),
                    (BigDecimal) r[6],
                    norm == null ? null : norm.tipo(),
                    norm == null ? null : norm.descrizione(),
                    conto, conto == null ? null : contoNomi.get(conto),
                    (String) r[8], r[9] == null ? null : toUuid(r[9]),
                    norm != null && norm.contoBancarioId() != null));
        }
        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * CONTABILIZZA («mettila nei conti») crea il movimento dalla riga; IGNORA («lasciala fuori»)
     * la chiude senza toccare i saldi. In entrambi i casi la riga resta in tabella (invariante I1).
     *
     * <p>Il client manda SOLO il CoGe: importo, tipo, data, conto e metodo si ri-derivano dal
     * grezzo col normalizzatore dell'import (I3) — un client che manda 10.000 € non li contabilizza.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void risolviScartato(UUID id, RisolviScartatoRequest req, UUID userId) {
        List<Object[]> found = em.createNativeQuery(
                "SELECT stato, import_log_id, riga_numero, CAST(raw_data AS text) " +
                "FROM import_scartati WHERE id = :id").setParameter("id", id).getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "SCARTATO_NON_TROVATO", "Riga " + id);
        }
        Object[] r = found.get(0);
        if (!"DA_VEDERE".equals((String) r[0])) {
            throw new ApiException(Response.Status.CONFLICT, "SCARTATO_GIA_RISOLTO",
                    "Questa riga è già stata decisa");
        }
        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            // R9: chiudere una riga bancaria senza contabilizzarla richiede un motivo scritto.
            case "IGNORA" -> claimScartato(id, "IGNORATA", userId, EsclusioneMotivata.obbligatorio(req.nota()));
            case "CONTABILIZZA" -> contabilizzaScartato(id, toUuid(r[1]),
                    ((Number) r[2]).intValue(), (String) r[3], req.cogeId(), userId);
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (CONTABILIZZA | IGNORA)");
        }
    }

    private void contabilizzaScartato(UUID id, UUID importLogId, int riga, String rawJson,
                                      Integer cogeId, UUID userId) {
        if (cogeId == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_OBBLIGATORIO",
                    "Scegli il conto su cui contabilizzare la riga");
        }
        List<Object[]> coge = em.createNativeQuery(
                "SELECT id, codice FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId).getResultList();
        if (coge.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_TROVATO",
                    "Conto CoGe inesistente: " + cogeId);
        }
        // Guard-rail §7.6: un ricavo-evento senza evento collegato non compare in nessun bilancio.
        CogeRiservatoEventi.vieta((String) coge.get(0)[1]);
        // NB: qui il transitorio è una destinazione LEGITTIMA e non va vietato (≠
        // classificaTransitorio, vedi CogeTransitorio): la riga entra nei libri e diventa «da
        // catalogare». È un passo avanti vero — il denaro smette di stare fuori dai conti.

        var n = rinormalizza(rawJson, riga);
        if (n == null || n.contoBancarioId() == null || n.dataMovimento() == null
                || n.importo() == null || n.importo().signum() <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RIGA_NON_LEGGIBILE",
                    "Il grezzo di questa riga non basta per creare un movimento (conto, data o "
                    + "importo mancanti): va inserita a mano dalla pagina Movimenti.");
        }
        // Il metodo non è MAI null (I6): un movimento senza metodo sparisce dalle viste per metodo.
        String metodoCodice = n.metodoPagamentoCodice() != null ? n.metodoPagamentoCodice()
                : "ENTRATA".equals(n.tipo()) ? "BONIFICO" : "ADDEBITO_CONTO";

        // Claim ATOMICO anti doppia-contabilizzazione (I2): la perdente vede 0 righe → 409.
        claimScartato(id, "CONTABILIZZATA", userId);

        MovimentoCreateRequest req = new MovimentoCreateRequest(
                n.tipo(), n.importo(), null, null,
                n.dataMovimento(), n.dataMovimento(), n.dataMovimento(), null,
                n.contoBancarioId(), metodoIdByCodiceOrThrow(metodoCodice), BU_OVERHEAD, cogeId,
                null, null, null, null,
                n.descrizione() != null && !n.descrizione().isBlank()
                        ? n.descrizione() : "Riga bancaria fuori dai conti",
                null,
                // Stesso riferimento esterno della riga: al prossimo import il dedup la riconosce
                // e non la contabilizza una seconda volta.
                n.riferimentoEsterno(), "IMPORT_BANCA", null);
        UUID movimentoId = movimentiService.createMovimentoImport(req, userId, importLogId).id();

        em.createNativeQuery("UPDATE import_scartati SET movimento_id = :mid WHERE id = :id")
                .setParameter("mid", movimentoId).setParameter("id", id).executeUpdate();
        mvRefresh.requestRefreshAfterCommit();
    }

    private void claimScartato(UUID id, String nuovoStato, UUID userId) {
        claimScartato(id, nuovoStato, userId, null);
    }

    /** Transizione di stato con predicato: è l'unico guard concorrenza-safe (row-lock). */
    private void claimScartato(UUID id, String nuovoStato, UUID userId, String nota) {
        int claimed = em.createNativeQuery(
                "UPDATE import_scartati SET stato = :stato, risolto_at = now(), risolto_by = :uid, " +
                "note = COALESCE(:nota, note) WHERE id = :id AND stato = 'DA_VEDERE'")
                .setParameter("stato", nuovoStato).setParameter("uid", userId)
                .setParameter("nota", nota)
                .setParameter("id", id).executeUpdate();
        if (claimed == 0) {
            throw new ApiException(Response.Status.CONFLICT, "SCARTATO_GIA_RISOLTO",
                    "Questa riga è già stata decisa");
        }
    }

    /**
     * Ricostruisce i dati contabili dal grezzo con lo stesso normalizzatore dell'import (DRY: la
     * riga non li duplica in colonne). null se il grezzo non è più interpretabile.
     */
    private com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento rinormalizza(
            String rawJson, int riga) {
        return contatore.rinormalizza(rawJson, riga);
    }

    @Inject ContatoreImportService contatore;

    /** Il «perché» detto in italiano: il codice motivo non spiega niente a chi deve decidere. */
    private String motivoLeggibile(String motivo) {
        return switch (motivo == null ? "" : motivo) {
            case "SKIP_CODA_TESTA" -> "l'incasso POS è dell'anno prima (la banca lo accredita a "
                    + "gennaio, ma la vendita è di dicembre): l'import lo lascia fuori dal periodo";
            case "SKIP_POS" -> "l'import l'ha presa per un incasso già registrato da Billy — ma "
                    + "questo accredito in Billy non c'è: controlla se è denaro tuo";
            case "SKIP_GIROCONTO" -> "riconosciuta come trasferimento fra due conti tuoi "
                    + "(nessun ricavo né costo)";
            default -> "esclusa dall'import con motivo «" + motivo + "»";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<Short, String> contiBancariNomi() {
        Map<Short, String> out = new LinkedHashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT id, nome FROM conti_bancari").getResultList()) {
            out.put(((Number) r[0]).shortValue(), (String) r[1]);
        }
        return out;
    }

    /** Suggerisce il codice CoGe dalla descrizione (case-insensitive); null = l'utente sceglie. */
    private String suggerisciCogeCodice(String tipo, String descrizione) {
        if ("ENTRATA".equals(tipo)) return COGE_FINANZIAMENTO_ENTRATA;
        if (descrizione == null) return null;
        String d = descrizione.toUpperCase();
        if (d.contains("MUTUO")) return "20.01.001";
        if (d.contains("ASCONFIDI") || d.contains("CONFIDI")) return "20.01.006";
        if (d.contains("LEASING")) return "20.01.005";
        if (d.contains("PROTEZIONE VITA")) return "40.05.002";
        if (d.contains("ASSICURAZ") || d.contains("POLIZZA")) return "40.05.002";
        if (d.contains("BOLLO") || d.contains("CANONE")) return "40.02.002";
        return null;
    }

    /** Risolve i codici CoGe in id con UNA query (no N+1). Codici null/ignoti scartati. */
    @SuppressWarnings("unchecked")
    private java.util.Map<String, Integer> cogeIdByCodice(java.util.Set<String> codici) {
        codici.remove(null);
        if (codici.isEmpty()) return java.util.Map.of();
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (Object[] row : (List<Object[]>) em.createNativeQuery(
                "SELECT codice, id FROM piano_dei_conti_coge WHERE codice IN (:cc)")
                .setParameter("cc", codici).getResultList()) {
            out.put((String) row[0], ((Number) row[1]).intValue());
        }
        return out;
    }

    private Integer cogeIdByCodiceOrThrow(String codice) {
        List<?> ids = em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getResultList();
        if (ids.isEmpty()) throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "COGE_MANCANTE",
                "Conto CoGe di sistema mancante: " + codice);
        return ((Number) ids.get(0)).intValue();
    }

    private Integer metodoIdByCodiceOrThrow(String codice) {
        List<?> ids = em.createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = :c")
                .setParameter("c", codice).getResultList();
        if (ids.isEmpty()) throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "METODO_MANCANTE",
                "Metodo di pagamento di sistema mancante: " + codice);
        return ((Number) ids.get(0)).intValue();
    }

    /**
     * Quali rami (BU) sono stati storicamente usati per ciascun conto CoGe.
     *
     * <p>Serve al secondo mezzo-passo del wizard §7.1/§7.5: scelta la voce di spesa, il ramo nella
     * gran parte dei casi DISCENDE da quella e non va chiesto. Il wizard chiede solo quando la
     * lista ha più di un elemento (voce usata da più rami) o è vuota (voce mai usata).
     * Una query di aggregazione, letta all'apertura della pagina — non per riga.
     */
    @SuppressWarnings("unchecked")
    public Map<Integer, List<Short>> buPerCoge() {
        Map<Integer, List<Short>> out = new LinkedHashMap<>();
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT conto_coge_id, business_unit_id, COUNT(*) AS n FROM movimenti " +
                "WHERE stato <> 'ANNULLATO' AND conto_coge_id IS NOT NULL AND business_unit_id IS NOT NULL " +
                "GROUP BY 1, 2 ORDER BY 1, n DESC").getResultList()) {
            out.computeIfAbsent(((Number) r[0]).intValue(), k -> new ArrayList<>())
                    .add(((Number) r[1]).shortValue());
        }
        return out;
    }

    /**
     * Pannello di quadratura di periodo (PROMPT-RICONCILIAZIONE-PERIODO §5): sostituisce la
     * vecchia vista "Incassi POS da ripartire" a scontrino. Restituisce la quadratura dell'ultimo
     * import congiunto (o di {@code importLogId} se valorizzato): Σ Billy ↔ Σ POS banca scomposto
     * per causa (coda testa esclusa, coda fondo in attesa). Informativo: i ricavi
     * sono comunque contabilizzati da Billy. Null se non c'è ancora nessuna quadratura.
     */
    @SuppressWarnings("unchecked")
    public QuadraturaPeriodoDTO getQuadratura(UUID importLogId) {
        String where = importLogId == null ? "" : " WHERE import_log_id = :id";
        var qy = em.createNativeQuery(
                "SELECT import_log_id, created_at, anno, billy_elettronico_non_agri, billy_contabilizzato, " +
                "pos_banca_totale, pos_banca_core, sigma_bpm, sigma_ca, assegnato_bpm, assegnato_ca, " +
                // NB: CAST(... AS text), NON '::text' → in una query native Hibernate i ':' sono
                // marcatori di parametro e '::' rompe il parser (syntax error at or near ":").
                "coda_testa, coda_fondo, max_del_banca, CAST(note AS text), CAST(in_attesa AS text) " +
                "FROM quadratura_periodo" + where + " ORDER BY created_at DESC LIMIT 1");
        if (importLogId != null) qy.setParameter("id", importLogId);
        List<Object[]> rows = qy.getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = rows.get(0);

        List<String> note = jsonToStringList((String) r[14]);
        List<QuadraturaPeriodoDTO.InAttesaDTO> attesa = jsonToInAttesa((String) r[15]);
        List<String> approssimazioni = buildApprossimazioni(
                (BigDecimal) r[7], (BigDecimal) r[8], (BigDecimal) r[9], (BigDecimal) r[10]);
        return new QuadraturaPeriodoDTO(
                toUuid(r[0]),
                toLocalDate(r[1]),   // created_at (timestamptz): il driver lo dà come Instant, non Timestamp
                ((Number) r[2]).intValue(),
                (BigDecimal) r[3], (BigDecimal) r[4], (BigDecimal) r[5], (BigDecimal) r[6],
                (BigDecimal) r[7], (BigDecimal) r[8], (BigDecimal) r[9], (BigDecimal) r[10],
                (BigDecimal) r[11], (BigDecimal) r[12],
                toLocalDate(r[13]),  // max_del_banca (date)
                note, approssimazioni, attesa);
    }

    /**
     * Approssimazioni dichiarate del metodo (PROMPT-RICONCILIAZIONE-PERIODO): vengono mostrate
     * ESPLICITAMENTE all'utente nel pannello, così sa esattamente cosa è una convenzione e cosa
     * uno scarto atteso (non un errore). Calcolate dai numeri persistiti della quadratura.
     */
    private List<String> buildApprossimazioni(BigDecimal sigmaBpm, BigDecimal sigmaCa,
                                              BigDecimal assBpm, BigDecimal assCa) {
        BigDecimal sigmaTot = sigmaBpm.add(sigmaCa);
        BigDecimal assTot = assBpm.add(assCa);
        BigDecimal deltaBpm = assBpm.subtract(sigmaBpm);
        BigDecimal deltaCa = assCa.subtract(sigmaCa);
        String pct = sigmaTot.signum() == 0 ? "0"
                : BigDecimal.ONE.subtract(assTot.divide(sigmaTot, 6, java.math.RoundingMode.HALF_UP))
                        .multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        List<String> a = new ArrayList<>();
        a.add("Attribuzione ricavo↔conto CONVENZIONALE: non si sa quale banca/carta abbia incassato "
                + "ogni scontrino (le righe banca sono aggregate per circuito). I ricavi sono distribuiti "
                + "per far quadrare i TOTALI di periodo, non la singola transazione.");
        a.add("Ripartizione PROPORZIONALE: BPM e CA risultano entrambe ~" + pct + "% sotto il rispettivo "
                + "POS lordo (BPM Δ " + deltaBpm.toPlainString() + " €, CA Δ " + deltaCa.toPlainString() + " €). "
                + "Quello scarto è il NON-spaccio (eventi a POS, Satispay, storni), NON un errore di import.");
        a.add("Granularità scontrino: uno scontrino non si spezza tra due conti, quindi i totali per "
                + "banca non centrano il target al centesimo.");
        a.add("Anche la CATEGORIA attribuita a ciascun conto è convenzionale: deriva dallo scontrino "
                + "Billy, non dalla riga banca.");
        return a;
    }

    /** Converte un valore temporale JDBC (Instant/OffsetDateTime/Timestamp/Date/Local*) in LocalDate. */
    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        if (o instanceof java.time.Instant i) return i.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    /** Converte un timestamp JDBC (Instant/OffsetDateTime/Timestamp) in Instant. */
    private java.time.Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.Instant i) return i;
        if (o instanceof java.sql.Timestamp t) return t.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return java.time.Instant.parse(o.toString());
    }

    private List<String> jsonToStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<QuadraturaPeriodoDTO.InAttesaDTO> jsonToInAttesa(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<java.util.Map<String, String>> raw = objectMapper.readValue(
                    json, new com.fasterxml.jackson.core.type.TypeReference<List<java.util.Map<String, String>>>() {});
            List<QuadraturaPeriodoDTO.InAttesaDTO> out = new ArrayList<>(raw.size());
            for (var m : raw) {
                LocalDate d = m.get("data") == null || "null".equals(m.get("data")) ? null : LocalDate.parse(m.get("data"));
                BigDecimal imp = m.get("importo") == null ? null : new BigDecimal(m.get("importo"));
                out.add(new QuadraturaPeriodoDTO.InAttesaDTO(d, imp, m.get("rif"), m.get("descrizione")));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }
    private String coalesce(String a, String b) { return a == null || a.isBlank() ? b : a; }
    private UUID toUuid(Object o) { return o instanceof UUID u ? u : UUID.fromString(o.toString()); }
}
