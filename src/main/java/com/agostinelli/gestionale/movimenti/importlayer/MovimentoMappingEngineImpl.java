package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.Confidenza;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.Proposta;
import com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordClassificazioneEngine;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.DettagliBilly;
import com.agostinelli.gestionale.movimenti.importlayer.reconcile.RawMovimentoArricchito;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risolve coge / BU / fornitore / evento e costruisce il MovimentoCreateRequest.
 * Le lookup FK sono caricate dal DB (lazy, alla prima chiamata dentro la transazione
 * d'import) e mai hardcodate come INT. In caso di dubbio su un campo obbligatorio
 * restituisce AMBIGUOUS: nessuna euristica per "provare a mappare".
 */
@ApplicationScoped
public class MovimentoMappingEngineImpl {

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;
    @Inject KeywordClassificazioneEngine keywordEngine;
    @Inject RataMatchService matchService;
    @Inject ImportAuditLog audit;   // AUDIT-TEMP

    // COGE per codice (mai hardcodare l'ID)
    private static final String COGE_CARNE_10 = "30.03.001";
    private static final String COGE_ORTOFRUTTA_4 = "30.03.002";
    private static final String COGE_ALVEARE_STRIPE = "30.03.003";
    private static final String COGE_AGRITURISMO = "30.01.001";
    private static final String COGE_COMMISSIONI_POS = "40.02.001";
    private static final String COGE_SPESE_BANCA = "40.02.002";
    // Nuovi conti ETL v2 (V40)
    private static final String COGE_CONTRIBUTI = "30.05.001";   // contributi pubblici / PAC
    private static final String COGE_VERSAMENTO_SOCI = "90.02.001";
    private static final String COGE_RICAVI_DACLASS = "39.99.999"; // transitorio entrate
    private static final String COGE_COSTI_DACLASS = "49.99.999";  // transitorio uscite

    private static final BigDecimal IVA_10 = new BigDecimal("0.10");
    private static final BigDecimal IVA_04 = new BigDecimal("0.04");
    private static final BigDecimal IVA_00 = new BigDecimal("0.00");

    // Gate B — keyword evento (ETL v2 §5), cercate sulla vista COMPACT. Le liste FORTE/DEBOLE
    // NON sono più hardcoded: provengono dalle firme DOMINIO azione=PARK_EVENTO (editabili da UI)
    // via KeywordClassificazioneEngine. Vedi PROMPT-KEYWORD-LEARNING.md §3.2/§4.3.

    // Data evento best-effort: prima occorrenza gg/mm/aaaa (gg.mm.aaaa, gg-mm-aaaa).
    private static final Pattern EVENTO_DATE = Pattern.compile(
            "\\b(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{2,4})\\b");

    // Gli estratti conto vanno a capo dentro la causale e l'a capo arriva qui come spazio:
    // "12 LUGLIO 202 6", "28.06. 26", "18.09 .26", "8/7/202 6". Prima di cercare la data si
    // richiudono SOLO gli spazi che stanno fra due caratteri da data (cifre e separatori):
    // "202 6" -> "2026". Misurato su 198 descrizioni della copia di produzione del 07/08/2026:
    // 5 differenze, tutte correzioni, 0 date nuove sbagliate. Senza questa chiusura
    // "8/7/202 6" veniva letto come anno 202 (LocalDate.of(202,7,8) non solleva nulla).
    private static final Pattern SPAZI_DENTRO_DATA = Pattern.compile("(?<=[0-9./\\-])[ ]+(?=[0-9./\\-])");

    // Data evento in forma testuale italiana: "7 MARZO 2026". I bonifici esteri/SEPA in
    // entrata riportano spesso la causale per esteso (niente "BON.DA", data a parole),
    // quindi senza questo riconoscimento il Gate B non aggancia il contesto evento.
    private static final List<String> MESI_IT = List.of(
            "GENNAIO", "FEBBRAIO", "MARZO", "APRILE", "MAGGIO", "GIUGNO",
            "LUGLIO", "AGOSTO", "SETTEMBRE", "OTTOBRE", "NOVEMBRE", "DICEMBRE");
    private static final Pattern EVENTO_DATE_TESTUALE = Pattern.compile(
            "\\b(\\d{1,2})\\s+(" + String.join("|", MESI_IT) + ")\\s+(\\d{4})\\b");

    // Data evento SENZA anno: il cliente scrive "SALDO EVENTO 6/07", "ACCONTO EVENTO 5 DICEMBRE".
    // Si accetta solo se ancorata a un marcatore di pagamento-evento entro 40 caratteri non
    // numerici, perché una coppia di numeri isolata è rumore: nel bonifico estero
    // "BONIFICO IN ENTRATA - 02-02 00650071 ... ACCONTO FESTA 7 MARZO 2026" il "02-02" senza
    // ancora batteva (sta più a sinistra) la data vera scritta per esteso.
    // L'anno non si inventa: lo dà la data del movimento bancario (vedi extractEventoDate).
    // Misurato su 1096 descrizioni bancarie reali (BPM/CA luglio 2026 + storico gen-giu in
    // esempi_dati_storici/ + descrizioni a DB): date estratte 349 -> 363, cioe' 14 nuove,
    // 2 cambiate (entrambe correzioni), 0 perse, 0 falsi positivi. L'anno opzionale OVUNQUE,
    // misurato in alternativa, dava 28 nuove ma 3 cambiate con falsi positivi sui riferimenti
    // SEPA. Vedi docs/adr/007-data-evento-senza-anno.md.
    private static final String MARCATORE_EVENTO = "(?:EVENTO|SALDO|ACCONTO|CAPARRA|AFFITTO|FESTA)";
    private static final Pattern EVENTO_DATE_SENZA_ANNO = Pattern.compile(
            MARCATORE_EVENTO + "[^0-9]{0,40}(\\d{1,2})[/.\\-](\\d{1,2})(?![/.\\-]?\\d)");
    private static final Pattern EVENTO_DATE_TESTUALE_SENZA_ANNO = Pattern.compile(
            MARCATORE_EVENTO + "[^0-9]{0,40}(\\d{1,2})\\s+(" + String.join("|", MESI_IT) + ")\\b(?!\\s+\\d{4})");

    private volatile boolean loaded = false;
    private final Map<String, Integer> cogeByCode = new HashMap<>();
    private final Map<Integer, String> cogeCodeById = new HashMap<>(); // reverse, per log leggibili
    private final Map<String, Integer> metodiByCode = new HashMap<>();
    private final List<AliasRule> aliasRules = new ArrayList<>();

    private record AliasRule(String pattern, String matchType, UUID fornitoreId,
                             Integer cogeDefaultId, Short buDefaultId) {}

    /** Esito intermedio della classificazione contabile. */
    private static final class Classify {
        Integer cogeId;
        Short bu;
        UUID fornitoreId;
        UUID eventoId;
        String tipoEvento;
        BigDecimal aliquota;
        String metodoCodiceOverride;
        String descrizioneOverride; // es. tag Alveare "[ALVEARE] …"
        String note;                // es. "Incasso Alveare (Stripe)"
        String motivo;              // se valorizzato → AMBIGUOUS
        String keywordConflittoSig; // se valorizzato → conflitto keyword di MATCH (riga su transitorio)
        /** R1/R3: quanto il motore SA. Default IGNOTA — la certezza va guadagnata, non assunta. */
        Confidenza confidenza = Confidenza.IGNOTA;
        /** R6: il perché in chiaro, quando la confidenza è PROPOSTA. */
        String perche;

        Classify certa() { this.confidenza = Confidenza.CERTA; return this; }
        Classify proposta(String perche) {
            this.confidenza = Confidenza.PROPOSTA; this.perche = perche; return this;
        }
    }

    public MappingResult map(RawMovimento n) {
        ensureLoaded();

        String sorgente = n.rawOriginale().campi().get(Sorgente.KEY);

        // ── REGOLE DATA-DRIVEN (priorità) — ETL v2 §9: valutate PRIMA dei gate ──
        RegoleClassificazioneEngine.Match rule = regoleEngine.evaluate(n, sorgente);
        audit.passo("[1] REGOLE", rule == null
                ? "nessuna regola data-driven ha fatto match"
                : "match regola → azione " + rule.azione()
                  + (rule.cogeCodice() != null ? " (coge=" + rule.cogeCodice() + ", bu=" + rule.buId() + ")" : ""));
        Classify cl;
        String via; // traccia del percorso decisionale (per il log per-import)
        if (rule != null && !"MAP".equals(rule.azione())) {
            return switch (rule.azione()) {
                // Le esclusioni deterministiche sono CERTE (nessun conto proposto da confermare);
                // rate ed eventi sono PROPOSTE: la coda chiede all'utente a cosa attaccarli (§1).
                case "SKIP_POS" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_POS, n)
                        .with(Confidenza.CERTA, null).withTrace("REGOLA DATA-DRIVEN → SKIP_POS");
                case "SKIP_GIROCONTO" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_GIROCONTO, n)
                        .with(Confidenza.CERTA, null).withTrace("REGOLA DATA-DRIVEN → SKIP_GIROCONTO");
                case "SKIP_RICORRENTE" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_RICORRENTE, n)
                        .with(Confidenza.PROPOSTA, null).withTrace("REGOLA DATA-DRIVEN → SKIP_RICORRENTE");
                case "PARK_EVENTO" -> MappingResult.parkEvento(
                        buildPark(safe(n.descrizione()), safe(n.descCompact()), n.dataMovimento()), n)
                        .with(Confidenza.PROPOSTA, null).withTrace("REGOLA DATA-DRIVEN → PARK_EVENTO");
                default -> MappingResult.ambiguous("AZIONE_REGOLA_SCONOSCIUTA", n)
                        .with(Confidenza.IGNOTA, null)
                        .withTrace("REGOLA DATA-DRIVEN → azione sconosciuta: " + rule.azione());
            };
        }
        if (rule != null) { // azione MAP
            cl = new Classify();
            cl.cogeId = coge(rule.cogeCodice());
            cl.bu = rule.buId();
            cl.metodoCodiceOverride = rule.metodoCodice();
            // R3: EQUALS/IN_LIST leggono un campo, non indovinano su una sottostringa.
            if (rule.strutturale()) cl.certa();
            else cl.proposta("la regola «" + rule.matchType() + " " + rule.pattern() + "» ha fatto match");
            via = "REGOLA DATA-DRIVEN MAP (coge=" + rule.cogeCodice() + ", bu=" + rule.buId() + ")";
        } else {
            // ── GATE A — esclusioni deterministiche (ETL v2 §4) ──
            MappingResult.MappingOutcome skip = gateA(n, sorgente);
            if (skip != null) {
                return MappingResult.skip(skip, n)
                        .with(skip == MappingResult.MappingOutcome.SKIP_RICORRENTE
                                ? Confidenza.PROPOSTA : Confidenza.CERTA, null)
                        .withTrace("GATE A → " + skip);
            }
            // ── Giroconti / versamenti contanti → CoGe patrimoniale 10.03.x (dopo il Gate A:
            // la precedenza SKIP_POS di Satispay resta intatta) ──
            if (n.girosalto() != null) {
                cl = classifyGirosalto(n, sorgente).certa();   // R3: marcato dal normalizzatore
                via = "GIROSALTO → " + n.girosalto();
                audit.passo("[3] GIRO", "riga marcata come " + n.girosalto()
                        + " → conto patrimoniale " + codeOf(cl.cogeId) + " (fuori P&L)");
            } else {
                // ── GATE B — parcheggio eventi (ETL v2 §5) ──
                ParkEvento park = gateB(n, sorgente);
                audit.passo("[6] GATE B", park == null
                        ? ("nessun incasso-evento riconosciuto" + ("ENTRATA".equals(n.tipo())
                            ? " (nessuna keyword evento forte/debole con contesto)" : " (solo le ENTRATE possono esserlo)"))
                        : "incasso-evento riconosciuto: keyword=" + park.keywordMatch()
                          + ", tipo presunto=" + park.tipoEventoPresunto()
                          + ", data evento estratta=" + park.dataEventoEstratta()
                          + " (la controparte la legge la coda dal movimento normalizzato)");
                if (park != null) {
                    return MappingResult.parkEvento(park, n).with(Confidenza.PROPOSTA, null).withTrace(
                            "GATE B → PARK_EVENTO (kw=" + park.keywordMatch()
                            + ", tipo=" + park.tipoEventoPresunto() + ")");
                }
                boolean entrata = "ENTRATA".equals(n.tipo());
                cl = entrata ? classifyEntrata(n, sorgente) : classifyUscita(n, sorgente);
                via = "GATE C → " + (entrata ? "classifyEntrata" : "classifyUscita");
                audit.passo("[7] COGE", (entrata ? "classifyEntrata" : "classifyUscita") + " → conto "
                        + codeOf(cl.cogeId) + " · BU " + cl.bu
                        + (cl.fornitoreId != null ? " · fornitore riconosciuto da alias" : " · nessun fornitore")
                        + (cl.aliquota != null ? " · IVA " + cl.aliquota : "")
                        + (cl.keywordConflittoSig != null ? " · CONFLITTO KEYWORD " + cl.keywordConflittoSig : "")
                        + (cl.motivo != null ? " · AMBIGUA: " + cl.motivo : ""));
            }
        }

        if (cl.motivo != null) {
            return MappingResult.ambiguous(cl.motivo, n).with(Confidenza.IGNOTA, null)
                    .withTrace(via + " → AMBIGUOUS: " + cl.motivo);
        }

        // Metodo di pagamento: override (es. Stripe) o codice dal normalizzatore
        String metodoCodice = cl.metodoCodiceOverride != null ? cl.metodoCodiceOverride : n.metodoPagamentoCodice();
        Integer metodoId = metodoCodice == null ? null : metodiByCode.get(metodoCodice);

        // ── Validazione invarianti (§6.1) ──
        String motivo = validate(n, metodoCodice, metodoId, cl);
        audit.passo("[7] VALID", motivo == null
                ? "invarianti ok (metodo=" + metodoCodice + ")"
                : "violata: " + motivo + " → la riga finisce in import_ambiguita");
        if (motivo != null) {
            return MappingResult.ambiguous(motivo, n).with(Confidenza.IGNOTA, null)
                    .withTrace(via + " → AMBIGUOUS (validazione): " + motivo);
        }

        // ── R4/R5 — SCRITTURA SELETTIVA: solo la CERTA finisce sul conto definitivo ──
        // La PROPOSTA nasce sul transitorio con la proposta ALLEGATA e NON applicata. Il denaro
        // resta a libro (saldo del conto invariato, §5: il denaro non si perde mai), ma la
        // CATEGORIA la decide l'utente. Il dirottamento sta QUI, in un punto solo: l'orchestratore
        // non deve sapere nulla di confidenza, e nessun ramo del motore può dimenticarsene.
        Proposta proposta = null;
        if (cl.confidenza == Confidenza.PROPOSTA) {
            proposta = new Proposta(codeOf(cl.cogeId), cl.bu, cl.fornitoreId, cl.perche);
            cl.cogeId = coge("ENTRATA".equals(n.tipo()) ? COGE_RICAVI_DACLASS : COGE_COSTI_DACLASS);
            cl.bu = BU_TRANSITORIO;
            cl.aliquota = IVA_00;
            // Il perché arriva AL DATO (R1), non solo al file di audit: è la frase che l'operatore
            // legge nel wizard accanto alla riga, insieme al conto proposto.
            cl.note = (cl.note == null ? "" : cl.note + " | ")
                    + proposta.marcatore() + " " + proposta.perche();
        }

        MovimentoCreateRequest req = new MovimentoCreateRequest(
                n.tipo(),
                n.importo(),
                null,                      // importoLordo
                cl.aliquota,
                n.dataMovimento(),
                n.dataCompetenza(),
                n.dataMovimento(),         // dataFinanziaria = dataMovimento (già liquidato)
                null,                      // dataLiquidita (auto = dataFinanziaria nel service)
                n.contoBancarioId(),
                metodoId,
                cl.bu,
                cl.cogeId,
                null,                      // categoriaId
                cl.fornitoreId,
                cl.eventoId,
                cl.tipoEvento,
                cl.descrizioneOverride != null ? cl.descrizioneOverride : n.descrizione(),
                cl.note,
                n.riferimentoEsterno(),
                n.fonte(),
                null                       // allegatoPath
        );
        MappingResult res = MappingResult.success(req, n)
                .with(cl.confidenza, proposta)
                .withTrace(via + " → " + cl.confidenza
                + (proposta == null ? " BOOK" : " PROPOSTA non applicata (" + proposta.cogeCodice()
                        + ": " + proposta.perche() + ") → transitorio")
                + " (coge=" + codeOf(cl.cogeId) + ", bu=" + cl.bu
                + (cl.fornitoreId != null ? ", fornitore" : "")
                + (cl.keywordConflittoSig != null ? ", KEYWORD_CONFLITTO" : "")
                + (cl.descrizioneOverride != null ? ", tag ALVEARE" : "") + ")");
        // Conflitto keyword di MATCH: la riga è booked sul transitorio; l'orchestratore registrerà
        // il keyword_conflitto (la scrittura non avviene nel motore puro, §4.6).
        return cl.keywordConflittoSig != null ? res.withKeywordConflitto(cl.keywordConflittoSig) : res;
    }

    // BU di default del transitorio ricavi (allineata al fallback di classifyEntrata).
    private static final short BU_TRANSITORIO = 5;

    /**
     * Mapping dell'import congiunto a periodo (PROMPT-RICONCILIAZIONE-PERIODO §4). Qui le righe
     * arricchite sono <b>scontrini Billy</b> già trasformati in ricavo dal
     * {@link com.agostinelli.gestionale.movimenti.importlayer.reconcile.RiconciliazioneService}:
     * categoria/COGE/BU/IVA da Billy, conto bancario (BPM/CA) dalla ripartizione di periodo, o
     * Cassa per i contanti. Se la categoria Billy non è determinabile → transitorio ricavi
     * 39.99.999 (triage "Da catalogare"). Le righe banca NON-POS (dettagli == null: costi, eventi,
     * SDD, commissioni, Stripe, giroconti, Satispay) sono delegate al mapping AS-IS invariato.
     */
    public MappingResult map(RawMovimentoArricchito a) {
        if (!a.isArricchito()) {
            return map(a.banca()); // riga banca non-POS → pipeline single-file invariata
        }
        ensureLoaded();

        RawMovimento n = a.banca();
        DettagliBilly d = a.dettagli();

        // Categoria da Billy se determinabile; altrimenti transitorio ricavi (→ triage "Da catalogare").
        boolean categorizzato = d.cogeCodice() != null;
        String cogeCodice = categorizzato ? d.cogeCodice() : COGE_RICAVI_DACLASS;
        Short bu = categorizzato ? d.bu() : BU_TRANSITORIO;
        BigDecimal aliquota = categorizzato ? d.aliquotaIva() : IVA_00;
        Integer cogeId = coge(cogeCodice);

        // Metodo e conto: assegnati dalla ripartizione (POS_BPM/POS_CA_NEXI/CONTANTI; conto 1/2/3).
        String metodoCodice = d.metodoCodice();
        Integer metodoId = metodoCodice == null ? null : metodiByCode.get(metodoCodice);
        Short conto = d.contoBancarioId();

        String motivo = validateArricchito(n, conto, metodoCodice, metodoId, cogeId, bu);
        if (motivo != null) {
            return MappingResult.ambiguous(motivo, n).with(Confidenza.IGNOTA, null)
                    .withTrace("CONGIUNTO " + d.esito() + " → AMBIGUOUS (validazione): " + motivo);
        }

        MovimentoCreateRequest req = new MovimentoCreateRequest(
                n.tipo(),
                n.importo(),
                null,                      // importoLordo
                aliquota,
                n.dataMovimento(),         // = data vendita Billy
                n.dataCompetenza(),
                n.dataMovimento(),         // dataFinanziaria = dataMovimento (già liquidato)
                null,
                conto,
                metodoId,
                bu,
                cogeId,
                null, null, null, null,
                n.descrizione(),
                null,
                n.riferimentoEsterno(),
                n.fonte(),
                null);
        // R3: una riga POS con lo scontrino Billy agganciato su (DEL + importo) è un campo, non
        // un'ipotesi — l'aggancio è deterministico. Senza scontrino resta IGNOTA sul transitorio.
        Confidenza conf = categorizzato ? Confidenza.CERTA : Confidenza.IGNOTA;
        return MappingResult.success(req, n).with(conf, null).withTrace(
                "CONGIUNTO " + d.esito() + " → " + conf + " BOOK (coge=" + cogeCodice + ", bu=" + bu
                + (categorizzato ? "" : ", TRANSITORIO") + ", conto=" + conto + ", metodo=" + metodoCodice + ")");
    }

    /** Validazione invarianti per le righe arricchite (conto/metodo risolti fuori dal Classify). */
    private String validateArricchito(RawMovimento n, Short conto, String metodoCodice,
                                      Integer metodoId, Integer cogeId, Short bu) {
        if (n.importo() == null || n.importo().compareTo(BigDecimal.ZERO) <= 0) return "IMPORTO_NON_POSITIVO";
        if (n.dataMovimento() == null) return "DATA_MANCANTE";
        if (n.dataMovimento().isAfter(LocalDate.now())) return "DATA_FUTURA";
        if (n.dataMovimento().isBefore(LocalDate.of(2023, 1, 1))) return "DATA_TROPPO_VECCHIA";
        if (conto == null) return "BANCA_NON_IDENTIFICATA";
        if (metodoId == null) return metodoCodice == null ? "METODO_NON_MAPPATO" : "METODO_NON_IDENTIFICATO";
        if (cogeId == null) return "COGE_NON_DETERMINABILE";
        if (bu == null) return "BU_AMBIGUA";
        return null;
    }

    /** Codice COGE da id (per i log leggibili). */
    private String codeOf(Integer id) {
        if (id == null) return null;
        return cogeCodeById.get(id);
    }

    // ── GATE A — esclusioni deterministiche (SKIP, ETL v2 §4) ──────────────────
    /**
     * Restituisce l'esito di scarto se la riga va esclusa a monte, altrimenti null.
     * POS e ricorrenti si valutano solo sulle banche: Billy è la fonte originale
     * degli incassi POS e non contiene spese ricorrenti.
     */
    private MappingResult.MappingOutcome gateA(RawMovimento n, String sorgente) {
        if (Sorgente.BILLY.equals(sorgente)) {
            audit.passo("[2] GATE A", "saltato: Billy è la fonte originale (niente POS duplicati né giroconti)");
            return null;
        }

        String desc = n.descrizione() == null ? "" : n.descrizione();
        String causale = upperCausale(n);

        // A1 — POS / Satispay (duplicati di Billy). Il check Satispay precede il giroconto:
        // il payout Satispay riporta in descrizione il beneficiario "SOCIETA AGRICOLA
        // AGOSTINELLI", la stessa stringa che marca i trasferimenti interni; senza questa
        // precedenza verrebbe scartato come SKIP_GIROCONTO invece che SKIP_POS.
        if (desc.contains("SATISPAY EUROPE")) {
            audit.passo("[2] GATE A1", "«SATISPAY EUROPE» in descrizione → SKIP_POS (duplicato di Billy)");
            return MappingResult.MappingOutcome.SKIP_POS;
        }

        // A2 — i giroconti NON sono più uno scarto: il normalizzatore li marca (girosalto)
        // e map() li contabilizza sul CoGe patrimoniale 10.03.x (classifyGirosalto).
        if (Sorgente.CA.equals(sorgente)) {
            if ("INCASSO TRAMITE POS".equals(causale)
                    || desc.contains("INCASSO POS") || desc.contains("NUMIA")
                    || desc.contains("ACCREDITO POS")) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
        } else if (Sorgente.BPM.equals(sorgente)) {
            // Solo le ENTRATE possono essere un incasso POS. Le USCITE che nominano Numia sono le
            // commissioni del gestore POS (causale 660, "spese - commissioni …"): scartarle come
            // SKIP_POS faceva sparire denaro realmente uscito dal conto (2 righe, 36,60 € a luglio).
            if ("ENTRATA".equals(n.tipo())
                    && ("090".equals(causale) || "092".equals(causale)
                        || desc.contains("INC.POS") || desc.contains("INCAS. TRAMITE P.O.S")
                        || desc.contains("NUMIA"))) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
            // POS che arriva come bonifico (causale 480 + accredito Nexi)
            if ("480".equals(causale) && desc.contains("NEXI") && desc.contains("ACCREDITO POS")) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
        }

        audit.passo("[2] GATE A1", "non è POS/Satispay (causale=" + causale + ")");

        // A3 — spese ricorrenti / finanziamenti (fallback keyword editabile)
        boolean kw = isRicorrente(desc);
        audit.passo("[4] GATE A3", kw
                ? "keyword ricorrente trovata nella descrizione → SKIP_RICORRENTE (parcheggio)"
                : "nessuna keyword ricorrente (ASSICURAZ/POLIZZA/MUTUO/LEASING/FINANZIAMENTO/ASCONFIDI/RATA)");
        if (kw) return MappingResult.MappingOutcome.SKIP_RICORRENTE;

        // A3-bis — match STRUTTURATO contro i piani ricorrenti attivi: intercetta le rate che
        // nessuna parola chiave descrive (SDD Enel/Telepass/Nexi). Senza piani attivi non fa
        // nulla e il comportamento resta quello della sola rete a keyword (SPEC R5).
        if ("USCITA".equals(n.tipo())) {
            var piani = matchService.pianiAttivi();
            boolean somiglia = RataMatcher.somigliaARata(n.contoBancarioId(), n.importo(), desc, piani);
            if (audit.attivo()) {
                audit.passo("[5] GATE A3-bis", "confronto con " + piani.size() + " piani ricorrenti ATTIVI:");
                for (String d : RataMatcher.diagnostica(n.contoBancarioId(), n.importo(), desc, piani)) {
                    audit.dettaglio(d);
                }
                audit.passo("[5] GATE A3-bis", somiglia
                        ? "almeno un piano aggancia → SKIP_RICORRENTE (parcheggio, mai contabilizzata da sola)"
                        : "nessun piano aggancia → la riga prosegue verso la contabilizzazione");
            }
            if (somiglia) return MappingResult.MappingOutcome.SKIP_RICORRENTE;
        } else {
            audit.passo("[5] GATE A3-bis", "saltato: solo le USCITE possono essere rate");
        }

        return null;
    }

    /**
     * Giroconti e versamenti contanti → CoGe patrimoniale 10.03.x, BU Overhead.
     * Direzione del giroconto: la gamba vista da BPM in ENTRATA (o da CA in USCITA)
     * è un CA→BPM (10.03.001); il caso opposto è un BPM→CA (10.03.002).
     */
    private Classify classifyGirosalto(RawMovimento n, String sorgente) {
        Classify cl = new Classify();
        String codice;
        if (MovimentoNormalizerImpl.VERSAMENTO_CONTANTI.equals(n.girosalto())) {
            codice = "10.03.003";
            cl.metodoCodiceOverride = "CONTANTI";
            cl.note = "Versamento contanti su banca (contropartita cassa creata dall'import)";
        } else {
            boolean versoBpm = (Sorgente.BPM.equals(sorgente) && "ENTRATA".equals(n.tipo()))
                    || (Sorgente.CA.equals(sorgente) && "USCITA".equals(n.tipo()));
            codice = versoBpm ? "10.03.001" : "10.03.002";
            cl.metodoCodiceOverride = "BONIFICO";
            cl.note = "Giroconto interno tra conti propri";
        }
        cl.cogeId = coge(codice);
        cl.bu = BU_TRANSITORIO; // Overhead: partita patrimoniale, fuori P&L
        if (cl.cogeId == null) cl.motivo = "COGE_GIROCONTO_MANCANTE"; // fail fast → ambiguità
        return cl;
    }

    private static final Pattern RATA_WORD = Pattern.compile("\\bRATA\\b");

    /**
     * Fallback keyword per le ricorrenti (ETL v2 §4 A3). NOTA: {@code AFFITTO} è
     * volutamente escluso finché il Gate B (eventi) non distingue "AFFITTO SALA"
     * (evento) dall'affitto-non-evento. Resta come rete per le rate di piani non ancora
     * creati: il match strutturato contro i piani attivi è in A3-bis ({@link RataMatcher}).
     */
    private boolean isRicorrente(String desc) {
        // ⚠️ DUPLICAZIONE NOTA (audit-catena-import-2026-08-11.md §6.2/4, intervento §8 #5):
        // questa lista vive anche a DB, nelle regole 1 (CA) e 2 (BPM) di regole_classificazione
        // (pattern IN_LIST, azione SKIP_RICORRENTE, priorità 30 → valutate PRIMA di qui). Toccarne
        // una sola lascia il motore incoerente: la scheda A4 del 09/08 dovette correggerle
        // entrambe. Unica differenza voluta: \bRATA\b esiste solo qui, ed è la rete per le righe
        // che dicono «RATA» senza nominare il tipo di finanziamento. Non si toglie: con zero piani
        // in archivio è l'unica protezione attiva (A3-bis non ha nulla da confrontare).
        //
        // CANONE e BOLLO sono usciti dalla lista (V28): sono spese bancarie ricorrenti ma NON piani
        // di ammortamento, e classifyUscita le manda già su 40.02.002. Tenerle qui faceva parcheggiare
        // bolli da 29,42 € chiedendo all'operatore di creare un piano (5 righe / 98,94 € a luglio 2026).
        if (desc.contains("ASSICURAZ") || desc.contains("POLIZZA")
                || desc.contains("MUTUO") || desc.contains("LEASING")
                || desc.contains("FINANZIAMENTO")
                || desc.contains("ASCONFIDI")) {
            return true;
        }
        return RATA_WORD.matcher(desc).find();
    }

    // ── GATE B — parcheggio eventi (PARK_EVENTO, ETL v2 §5) ────────────────────

    /**
     * Contesto "fattura" che spegne il Gate B. Era {@code contains("FATT")}: agganciava la ragione
     * sociale del cliente — «ORD:FATTI DI SETA … ACCONTO PER EVENTO AZIENDALE DEL 17/05/26» non
     * veniva parcheggiato come incasso-evento. Misurato sul corpus gen–giu 2026: 2 falsi positivi
     * su 6 ENTRATE contenenti «FATT» (audit-catena-import-2026-08-11.md §6.2/1, intervento §8 #4).
     * Ora servono la parola intera o l'abbreviazione col punto: FATTURA / FATTURE / FATT.
     */
    private static final Pattern FATTURA_CTX = Pattern.compile("\\bFATT(URA|URE|\\.)");

    /** Package-private per il test: è la riga che decide se il Gate B si spegne. */
    boolean fatturaCtx(String descrizione) {
        String d = descrizione == null ? "" : descrizione;
        return FATTURA_CTX.matcher(d).find() || d.contains("DOCUM") || d.contains("NOTA CREDITO");
    }

    /**
     * Restituisce i metadati evento se la riga va parcheggiata, altrimenti null.
     * Solo le ENTRATE possono essere eventi. Riconoscimento robusto:
     *  - Billy: colonna Agriturismo>0 ⇒ evento, salvo carve-out (incasso POS / Satispay
     *    ristorazione → ricavo; KAIROS → triage), gestiti in {@link #classifyEntrata};
     *  - Banca: keyword forte (da sola), o debole + contesto (ordinante/data), con
     *    esclusione dei falsi positivi (FATTURA/DOCUM/NOTA CREDITO).
     */
    private ParkEvento gateB(RawMovimento n, String sorgente) {
        if (!"ENTRATA".equals(n.tipo())) return null;

        String spaced = n.descrizione() == null ? "" : n.descrizione();
        String compact = n.descCompact() == null ? "" : n.descCompact();
        boolean fatturaCtx = fatturaCtx(spaced);

        java.util.Set<String> forti = keywordEngine.eventiForti();
        java.util.Set<String> deboli = keywordEngine.eventiDeboli();

        if (Sorgente.BILLY.equals(sorgente)) {
            if (!positive(n.billyAgriturismo())) {
                // Billy non-agri: parcheggia solo su keyword evento esplicita (forte)
                return (!fatturaCtx && containsAny(compact, forti)) ? buildPark(spaced, compact, n.dataMovimento()) : null;
            }
            // Agriturismo>0: evento salvo carve-out (gestiti in classifyEntrata)
            if (isPosIncasso(spaced) || "SATISPAY".equals(n.metodoPagamentoCodice())) return null;
            if (compact.contains("KAIROS")) return null;
            return buildPark(spaced, compact, n.dataMovimento());
        }

        // Banca (CA / BPM)
        if (fatturaCtx) return null;
        if (containsAny(compact, forti)) return buildPark(spaced, compact, n.dataMovimento());
        if (containsAny(compact, deboli) && hasEventoContext(n)) return buildPark(spaced, compact, n.dataMovimento());
        return null;
    }

    /** Contesto evento: ordinante (persona fisica) presente o data nella descrizione. */
    private boolean hasEventoContext(RawMovimento n) {
        if (n.entita() != null && n.entita().ordinante() != null) return true;
        return extractEventoDate(n.descrizione(), n.dataMovimento()) != null;
    }

    /**
     * Estrae i segnali evento (tipo presunto, keyword, data) da una descrizione già normalizzata.
     * Riusata dal triage quando l'operatore dichiara che una riga finita in ambiguità è in realtà
     * un incasso-evento: l'euristica dev'essere la stessa del Gate B, non una copia divergente.
     */
    public ParkEvento estraiSegnaliEvento(String descrizione, String descCompact, LocalDate dataMovimento) {
        return buildPark(safe(descrizione), safe(descCompact), dataMovimento);
    }

    private ParkEvento buildPark(String spaced, String compact, LocalDate dataMovimento) {
        String tipo;
        String keyword;
        if (compact.contains("CAPARRA")) { tipo = "CAPARRA"; keyword = "CAPARRA"; }
        else if (compact.contains("ACCONTO")) { tipo = "ACCONTO"; keyword = "ACCONTO"; }
        // AFFITTO_SALA NON e' un tipo valido: lk_tipi_evento_mov elenca i MOMENTI di pagamento
        // (acconto, caparra, competenza, penale, rimborso, saldo), mentre l'affitto sala e' un
        // SERVIZIO. Aggiungerlo alla lookup la sporcherebbe per sempre. Il motore smette quindi
        // di indovinare su questo ramo e lascia scegliere l'operatore — la keyword resta, perche'
        // serve a spiegare PERCHE' la riga e' stata parcheggiata.
        // Rischio nullo, verificato: la colonna e' nullable, non ha FK, e in produzione 3 righe
        // su 28 sono gia' a NULL (il ramo else qui sotto le produce gia' oggi).
        else if (compact.contains("AFFITTOSALA") || compact.contains("AFFITTO")) { tipo = null; keyword = "AFFITTO"; }
        else if (compact.contains("SALDO")) { tipo = "SALDO"; keyword = "SALDO"; }
        else { tipo = null; keyword = firstMatch(compact, keywordEngine.eventiForti()); }
        return new ParkEvento(tipo, keyword, extractEventoDate(spaced, dataMovimento));
    }

    private boolean isPosIncasso(String spaced) {
        return spaced.contains("INCASSO POS") || spaced.contains("NUMIA")
                || spaced.contains("ACCREDITO POS") || spaced.contains("INC.POS")
                || spaced.contains("INCAS. TRAMITE P.O.S");
    }

    private boolean containsAny(String s, java.util.Set<String> keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private String firstMatch(String s, java.util.Set<String> keys) {
        for (String k : keys) if (s.contains(k)) return k;
        return null;
    }

    /**
     * Data evento dalla causale, best-effort.
     *
     * <p>Vince la data che compare <b>più a sinistra</b>, numerica o testuale che sia: la
     * causale scritta dal cliente ("EVENTO DOMENICA 12 LUGLIO 2026") precede sempre i
     * timestamp che la banca appende in coda ("SCT ISTANTANEO DEL 18/07/2026 ORE 13:14").
     * Preferire sempre il formato numerico, come faceva prima, significava preferire il
     * timestamp bancario: misurato su LO MONACO VANESSA 274,00 → data evento 18/07 invece
     * di 12/07, cioè l'evento sbagliato proposto all'operatore.
     *
     * <p>Quattro forme concorrono, tutte sulla stessa regola "vince la più a sinistra":
     * gg/mm/aaaa, "7 MARZO 2026", e le due forme <b>senza anno</b> ancorate a un marcatore
     * di pagamento-evento ("SALDO EVENTO 6/07", "ACCONTO EVENTO 5 DICEMBRE").
     *
     * @param riferimento data del movimento bancario: dà l'anno alle date scritte senza.
     *                    Se è {@code null} le forme senza anno sono ignorate — l'anno non
     *                    si inventa, e senza anno la data non identifica nessun evento.
     */
    // package-private: pura funzione di parsing, testata da MovimentoMappingEngineDateTest
    LocalDate extractEventoDate(String descrizione, LocalDate riferimento) {
        if (descrizione == null) return null;
        String s = SPAZI_DENTRO_DATA.matcher(descrizione).replaceAll("");

        Candidata migliore = null;
        migliore = piuASinistra(migliore, prima(EVENTO_DATE, s,
                m -> toDate(m.group(3), m.group(2), m.group(1))));
        migliore = piuASinistra(migliore, prima(EVENTO_DATE_TESTUALE, s,
                m -> toDate(m.group(3), mese(m.group(2)), m.group(1))));
        if (riferimento != null) {
            String anno = String.valueOf(riferimento.getYear());
            migliore = piuASinistra(migliore, prima(EVENTO_DATE_SENZA_ANNO, s,
                    m -> toDate(anno, m.group(2), m.group(1))));
            migliore = piuASinistra(migliore, prima(EVENTO_DATE_TESTUALE_SENZA_ANNO, s,
                    m -> toDate(anno, mese(m.group(2)), m.group(1))));
        }
        return migliore == null ? null : migliore.data();
    }

    /** Una data trovata nella causale e la posizione in cui è scritta (gruppo 1 = il giorno). */
    private record Candidata(LocalDate data, int pos) {}

    /**
     * Prima data <b>valida</b> prodotta dal pattern: una tripletta impossibile (32/13/2026)
     * non interrompe la ricerca, si continua con l'occorrenza successiva.
     */
    private static Candidata prima(Pattern p, String s, java.util.function.Function<Matcher, LocalDate> aData) {
        Matcher m = p.matcher(s);
        while (m.find()) {
            LocalDate d = aData.apply(m);
            if (d != null) return new Candidata(d, m.start(1));
        }
        return null;
    }

    private static Candidata piuASinistra(Candidata a, Candidata b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.pos() < a.pos() ? b : a;
    }

    private static String mese(String nomeMese) {
        return String.valueOf(MESI_IT.indexOf(nomeMese) + 1);
    }

    /**
     * null se la tripletta non è una data reale (32/13/2026) o se l'anno non è plausibile.
     * L'anno a 3 cifre ("8/7/202") non è una data: senza questo controllo
     * {@code LocalDate.of(202, 7, 8)} passa senza un fiato e nasce una data dell'anno 202.
     */
    LocalDate toDate(String anno, String mese, String giorno) {
        try {
            int y = Integer.parseInt(anno);
            if (y < 100) y += 2000;
            if (y < 2000) return null;
            return LocalDate.of(y, Integer.parseInt(mese), Integer.parseInt(giorno));
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── ENTRATE ────────────────────────────────────────────────────────────────
    private Classify classifyEntrata(RawMovimento n, String sorgente) {
        Classify cl = new Classify();
        String desc = n.descrizione() == null ? "" : n.descrizione();

        if (Sorgente.BILLY.equals(sorgente)) {
            boolean agri = positive(n.billyAgriturismo());
            boolean carne = positive(n.billyCarne10());
            boolean orto = positive(n.billyOrtofrutta4());
            boolean altro = positive(n.billyAltro());

            // Agriturismo>0 raggiunge il classify SOLO per i carve-out: gli eventi sono già
            // parcheggiati dal Gate B. Incasso POS / Satispay ristorazione → ricavo 30.01.001;
            // il resto (es. KAIROS) → transitorio ricavi da classificare (triage).
            if (agri) {
                if (isPosIncasso(desc) || "SATISPAY".equals(n.metodoPagamentoCodice())) {
                    cl.cogeId = coge(COGE_AGRITURISMO); cl.bu = 1; cl.aliquota = IVA_10;
                    return cl.certa();   // campo strutturale Billy, non un indovinello
                }
                cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
                return cl;
            }

            if ("SATISPAY".equals(n.metodoPagamentoCodice())) {
                if (altro) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; return cl.certa(); }
                cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
                return cl;
            }
            if (altro && carne) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; return cl.certa(); }
            if (altro && orto) { cl.cogeId = coge(COGE_ORTOFRUTTA_4); cl.bu = 3; cl.aliquota = IVA_04; return cl.certa(); }
            // fallback Billy → transitorio (non più BU_AMBIGUA): il dato c'è, si rifinisce in triage
            cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
            return cl;
        }

        // ── Banca (BPM / CA) in entrata ──
        if (desc.contains("STRIPE")) {
            cl.cogeId = coge(COGE_ALVEARE_STRIPE);
            cl.bu = 3;
            cl.aliquota = IVA_00;
            cl.metodoCodiceOverride = "ALVEARE_STRIPE";
            // R3 — tag Alveare: origine esplicita nel movimento (descrizione + note)
            cl.descrizioneOverride = "[ALVEARE] " + desc;
            cl.note = "Incasso Alveare (Stripe)";
            return cl.certa();   // R3: mittente scritto in causale
        }

        // C4 — partite speciali (entrate non operative)
        if (desc.contains("ORGANISMO PAGATORE") || desc.contains("AGEA")
                || desc.contains("REGIME DI PAGAMENTO UNICO")) {
            cl.cogeId = coge(COGE_CONTRIBUTI); cl.bu = 1; cl.aliquota = IVA_00;
            return cl.certa();   // R3: mittente scritto in causale (AGEA / organismo pagatore)
        }
        if (desc.contains("VERSAMENTO SOCIO") || desc.contains("VERSAMENTO SOCI")) {
            cl.cogeId = coge(COGE_VERSAMENTO_SOCI); cl.bu = 5; cl.aliquota = IVA_00;
            return cl.certa();   // R3: mittente scritto in causale (versamento soci)
        }
        // Rimborso/storno su carta (es. reso o chargeback): NON è un ricavo di vendita. Lo si lascia
        // sul transitorio ricavi ma marcato, così l'operatore lo riconduce al costo originario.
        // (Non è un incasso POS: il normalizzatore non gli assegna il circuito.)
        if (desc.contains("RIMBORSO CARTA")) {
            cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
            cl.note = "Rimborso/storno su carta — da ricondurre al costo originario";
            return cl;
        }

        // Keyword apprese (§4.6): consultate PRIMA del fallback. Match unico → target appreso;
        // conflitto di MATCH → segnalato (cl.keywordConflittoSig) e riga lasciata al transitorio.
        if (applyKeyword(cl, n, sorgente)) return cl;

        // Fornitore/COGE non bloccante (ETL v2 §6 C3): entrata non riconosciuta →
        // transitorio "Ricavi da classificare" + monitoraggio triage (niente più scarto).
        cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
        return cl;
    }

    /**
     * Consulta il motore keyword (§4.6). Se una firma BOOK matcha senza conflitto valorizza
     * coge/bu/fornitore e ritorna true (auto-catalogazione). In caso di conflitto di MATCH
     * valorizza {@code cl.keywordConflittoSig} e ritorna false (la riga resta sul transitorio,
     * l'orchestratore registra il conflitto). COGE per codice, come il resto del motore.
     */
    private boolean applyKeyword(Classify cl, RawMovimento n, String sorgente) {
        var match = keywordEngine.classifica(n, sorgente);
        if (match.isEmpty()) return false;
        var m = match.get();
        if (m.conflitto()) {
            cl.keywordConflittoSig = m.signatureHash();
            return false;
        }
        Integer cogeId = coge(m.cogeCodice());
        if (cogeId == null || m.bu() == null) return false; // target non risolvibile → fallback
        // Una firma appresa da una riga che era ANCORA sul transitorio punta al transitorio: la
        // «proposta» sarebbe «ti propongo di lasciarla dov'è», cioè rumore che l'operatore deve
        // leggere e scartare a ogni riga. Non è una proposta, è l'assenza di risposta → IGNOTA.
        // Misurato su agosdb il 13/08/2026: 1 riga su 40 (firma «AZIENDA+PANZERI+VIVAI», 492,80 €).
        if (COGE_RICAVI_DACLASS.equals(m.cogeCodice()) || COGE_COSTI_DACLASS.equals(m.cogeCodice())) {
            return false;
        }
        cl.cogeId = cogeId;
        cl.bu = m.bu();
        cl.fornitoreId = m.fornitoreId();
        if ("ENTRATA".equals(n.tipo())) cl.aliquota = IVA_00;
        // R5/R18: una firma appresa è un'ipotesi su un NOME (ENEL, SOGEGROSS, una persona fisica),
        // non un campo strutturale → si propone. Torna CERTA DA SOLA quando accumula conferme
        // senza correzioni (§4/R18): finché N = ∞ nessuna firma è promossa, e la classe CERTA
        // resta l'elenco chiuso di R3.
        if (m.promossa()) cl.certa();
        else cl.proposta("la firma keyword «" + m.firmaLeggibile() + "» ("
                + m.natura().name().toLowerCase() + ") l'hai già catalogata così");
        return true;
    }

    // ── USCITE (solo CA) ─────────────────────────────────────────────────────────
    private Classify classifyUscita(RawMovimento n, String sorgente) {
        Classify cl = new Classify();
        String desc = n.descrizione() == null ? "" : n.descrizione();
        String causale = upperCausale(n);

        // Addebiti automatici di conto (commissioni, spese, competenze, interessi, bolli):
        // il metodo ADDEBITO_CONTO è già il segnale univoco → spese bancarie 40.02.002.
        if ("ADDEBITO_CONTO".equals(n.metodoPagamentoCodice())) {
            cl.cogeId = coge(COGE_SPESE_BANCA);
            cl.bu = 5;
            return cl.certa();   // R3: il metodo ADDEBITO_CONTO è il segnale, non un'ipotesi
        }

        // "50C" e' il codice con cui Banco BPM identifica un addebito SDD: non contiene la
        // stringa "SDD", quindi il ramo Nexi non scattava sulle righe BPM. Il raggio d'azione
        // resta chiuso dalla guardia desc.contains("NEXI") alla riga sotto — misurato sul corpus:
        // 28 righe hanno causale 50C, di cui 2 nominano NEXI; le altre 26 (12 Confidi, 6 TIM,
        // 6 Enel) non la nominano e NON sono toccate.
        boolean sdd = causale.contains("SDD") || "PAGAMENTO UTENZE".equals(causale)
                   || "50C".equals(causale);
        if (desc.contains("NEXI") && (sdd || "COMMISSIONI/SPESE".equals(causale))) {
            cl.cogeId = coge(COGE_COMMISSIONI_POS);
            cl.bu = 5;
            AliasRule nexi = matchAlias(desc);
            if (nexi != null) cl.fornitoreId = nexi.fornitoreId();
            return cl.certa();   // R3: CoGe 40.02.* da causale strutturata
        }
        if (desc.contains("BOLLO E/C") || desc.contains("CANONE") || desc.contains("COMMISSIONI")) {
            cl.cogeId = coge(COGE_SPESE_BANCA);
            cl.bu = 5;
            return cl.certa();   // R3: CoGe 40.02.* da causale strutturata
        }
        if ("COMMISSIONI/SPESE".equals(causale)) {
            cl.cogeId = coge(COGE_SPESE_BANCA);
            cl.bu = 5;
            return cl.certa();   // R3: CoGe 40.02.* da causale strutturata
        }

        // Keyword apprese (§4.6): sostituiscono la vecchia rubrica controparti (IBAN). Consultate
        // PRIMA del fallback transitorio. Match unico → target; conflitto → transitorio + segnale.
        if (applyKeyword(cl, n, sorgente)) return cl;
        if (cl.keywordConflittoSig != null) { cl.cogeId = coge(COGE_COSTI_DACLASS); cl.bu = 5; return cl; }

        // EFFETTI RITIRATI/RICHIAMATI (RIBA) e fallback: matching alias fornitore storico
        // (fornitore_alias_matching, curato a mano in anagrafica — resta come ulteriore fonte, §2.2)
        AliasRule alias = matchAlias(desc);
        if (alias != null && alias.buDefaultId() != null && alias.cogeDefaultId() != null) {
            cl.cogeId = alias.cogeDefaultId();
            cl.bu = alias.buDefaultId();
            cl.fornitoreId = alias.fornitoreId();
            // R5: un alias è un'ipotesi su un NOME di fornitore, non un campo → si propone.
            return cl.proposta("l'alias fornitore «" + alias.pattern() + "» compare nella causale");
        }
        // Fornitore non bloccante (ETL v2 §6 C3/§7): uscita senza match →
        // transitorio "Costi da classificare" (fornitore già attaccato se controparte trovata).
        cl.cogeId = coge(COGE_COSTI_DACLASS); cl.bu = 5;
        return cl;
    }

    // ── Validazione invarianti (§6.1) ──
    private String validate(RawMovimento n, String metodoCodice, Integer metodoId, Classify cl) {
        if (n.importo() == null || n.importo().compareTo(BigDecimal.ZERO) <= 0) return "IMPORTO_NON_POSITIVO";
        if (n.dataMovimento() == null) return "DATA_MANCANTE";
        if (n.dataMovimento().isAfter(LocalDate.now())) return "DATA_FUTURA";
        if (n.dataMovimento().isBefore(LocalDate.of(2023, 1, 1))) return "DATA_TROPPO_VECCHIA";
        if (n.contoBancarioId() == null) return "BANCA_NON_IDENTIFICATA";
        if (metodoId == null) {
            if (metodoCodice == null && "IMPORT_BANCA".equals(n.fonte())) return "CAUSALE_NON_MAPPATA";
            return "METODO_NON_IDENTIFICATO";
        }
        if (cl.cogeId == null) return "COGE_NON_DETERMINABILE";
        if (cl.bu == null) return "BU_AMBIGUA";
        return null;
    }

    // ── Alias matching (fornitore_alias_matching) ──
    private AliasRule matchAlias(String descrizione) {
        if (descrizione == null) return null;
        String d = descrizione.toUpperCase();
        for (AliasRule r : aliasRules) {
            boolean hit = switch (r.matchType()) {
                case "CONTAINS" -> d.contains(r.pattern());
                case "STARTS_WITH" -> d.startsWith(r.pattern());
                case "REGEX" -> {
                    try { yield d.matches(r.pattern()); } catch (Exception e) { yield false; }
                }
                default -> false;
            };
            if (hit) return r;
        }
        return null;
    }

    private Integer coge(String codice) {
        return cogeByCode.get(codice);
    }

    private boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String upperCausale(RawMovimento n) {
        String c = n.rawOriginale().campi().get("CAUSALE");
        return c == null ? "" : c.trim().toUpperCase();
    }

    private synchronized void ensureLoaded() {
        if (loaded) return;
        loadLookups();
        loaded = true;
    }

    /** Ricarica le lookup (chiamato a inizio import: recepisce alias/controparti dalle classificazioni). */
    public synchronized void refreshLookups() {
        cogeByCode.clear();
        cogeCodeById.clear();
        metodiByCode.clear();
        aliasRules.clear();
        loadLookups();
        regoleEngine.refresh();
        keywordEngine.refresh();
        loaded = true;
    }

    @SuppressWarnings("unchecked")
    private void loadLookups() {
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT codice, id FROM piano_dei_conti_coge").getResultList()) {
            String codice = (String) r[0];
            int id = ((Number) r[1]).intValue();
            cogeByCode.put(codice, id);
            cogeCodeById.put(id, codice);
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT codice, id FROM metodi_pagamento").getResultList()) {
            metodiByCode.put((String) r[0], ((Number) r[1]).intValue());
        }
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT fam.pattern, fam.match_type, f.id, f.coge_default_id, f.bu_default_id " +
                "FROM fornitore_alias_matching fam JOIN fornitori f ON fam.fornitore_id = f.id")
                .getResultList()) {
            UUID fid = r[2] instanceof UUID u ? u : UUID.fromString(r[2].toString());
            Integer cogeDef = r[3] == null ? null : ((Number) r[3]).intValue();
            Short buDef = r[4] == null ? null : ((Number) r[4]).shortValue();
            aliasRules.add(new AliasRule(((String) r[0]).toUpperCase(), (String) r[1], fid, cogeDef, buDef));
        }
        // La rubrica controparti (apprendimento per IBAN) è dismessa (tabella droppata in V7):
        // l'auto-apprendimento ora è a keyword (KeywordClassificazioneEngine), ricaricato in
        // refreshLookups() via keywordEngine.refresh().
    }
}
