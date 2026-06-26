package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
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

    // Data evento best-effort: prima occorrenza gg/mm/aaaa (o gg.mm.aaaa) nella descrizione.
    private static final Pattern EVENTO_DATE = Pattern.compile(
            "\\b(\\d{1,2})[/.](\\d{1,2})[/.](\\d{2,4})\\b");

    // Data evento in forma testuale italiana: "7 MARZO 2026". I bonifici esteri/SEPA in
    // entrata riportano spesso la causale per esteso (niente "BON.DA", data a parole),
    // quindi senza questo riconoscimento il Gate B non aggancia il contesto evento.
    private static final List<String> MESI_IT = List.of(
            "GENNAIO", "FEBBRAIO", "MARZO", "APRILE", "MAGGIO", "GIUGNO",
            "LUGLIO", "AGOSTO", "SETTEMBRE", "OTTOBRE", "NOVEMBRE", "DICEMBRE");
    private static final Pattern EVENTO_DATE_TESTUALE = Pattern.compile(
            "\\b(\\d{1,2})\\s+(" + String.join("|", MESI_IT) + ")\\s+(\\d{4})\\b");

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
    }

    public MappingResult map(RawMovimento n) {
        ensureLoaded();

        String sorgente = n.rawOriginale().campi().get(Sorgente.KEY);

        // ── REGOLE DATA-DRIVEN (priorità) — ETL v2 §9: valutate PRIMA dei gate ──
        RegoleClassificazioneEngine.Match rule = regoleEngine.evaluate(n, sorgente);
        Classify cl;
        String via; // traccia del percorso decisionale (per il log per-import)
        if (rule != null && !"MAP".equals(rule.azione())) {
            return switch (rule.azione()) {
                case "SKIP_POS" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_POS, n)
                        .withTrace("REGOLA DATA-DRIVEN → SKIP_POS");
                case "SKIP_GIROCONTO" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_GIROCONTO, n)
                        .withTrace("REGOLA DATA-DRIVEN → SKIP_GIROCONTO");
                case "SKIP_RICORRENTE" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_RICORRENTE, n)
                        .withTrace("REGOLA DATA-DRIVEN → SKIP_RICORRENTE");
                case "PARK_EVENTO" -> MappingResult.parkEvento(
                        buildPark(safe(n.descrizione()), safe(n.descCompact())), n)
                        .withTrace("REGOLA DATA-DRIVEN → PARK_EVENTO");
                default -> MappingResult.ambiguous("AZIONE_REGOLA_SCONOSCIUTA", n)
                        .withTrace("REGOLA DATA-DRIVEN → azione sconosciuta: " + rule.azione());
            };
        }
        if (rule != null) { // azione MAP
            cl = new Classify();
            cl.cogeId = coge(rule.cogeCodice());
            cl.bu = rule.buId();
            cl.metodoCodiceOverride = rule.metodoCodice();
            via = "REGOLA DATA-DRIVEN MAP (coge=" + rule.cogeCodice() + ", bu=" + rule.buId() + ")";
        } else {
            // ── GATE A — esclusioni deterministiche (ETL v2 §4) ──
            MappingResult.MappingOutcome skip = gateA(n, sorgente);
            if (skip != null) {
                return MappingResult.skip(skip, n).withTrace("GATE A → " + skip);
            }
            // ── GATE B — parcheggio eventi (ETL v2 §5) ──
            ParkEvento park = gateB(n, sorgente);
            if (park != null) {
                return MappingResult.parkEvento(park, n).withTrace(
                        "GATE B → PARK_EVENTO (kw=" + park.keywordMatch()
                        + ", tipo=" + park.tipoEventoPresunto() + ")");
            }
            boolean entrata = "ENTRATA".equals(n.tipo());
            cl = entrata ? classifyEntrata(n, sorgente) : classifyUscita(n, sorgente);
            via = "GATE C → " + (entrata ? "classifyEntrata" : "classifyUscita");
        }

        if (cl.motivo != null) {
            return MappingResult.ambiguous(cl.motivo, n).withTrace(via + " → AMBIGUOUS: " + cl.motivo);
        }

        // Metodo di pagamento: override (es. Stripe) o codice dal normalizzatore
        String metodoCodice = cl.metodoCodiceOverride != null ? cl.metodoCodiceOverride : n.metodoPagamentoCodice();
        Integer metodoId = metodoCodice == null ? null : metodiByCode.get(metodoCodice);

        // ── Validazione invarianti (§6.1) ──
        String motivo = validate(n, metodoCodice, metodoId, cl);
        if (motivo != null) {
            return MappingResult.ambiguous(motivo, n).withTrace(via + " → AMBIGUOUS (validazione): " + motivo);
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
        MappingResult res = MappingResult.success(req, n).withTrace(
                via + " → BOOK (coge=" + codeOf(cl.cogeId) + ", bu=" + cl.bu
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
            return MappingResult.ambiguous(motivo, n)
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
        return MappingResult.success(req, n).withTrace(
                "CONGIUNTO " + d.esito() + " → BOOK (coge=" + cogeCodice + ", bu=" + bu
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
        if (Sorgente.BILLY.equals(sorgente)) return null; // Billy non ha giroconti né POS duplicati

        String desc = n.descrizione() == null ? "" : n.descrizione();
        String causale = upperCausale(n);

        // A1 — POS / Satispay (duplicati di Billy). Il check Satispay precede il giroconto:
        // il payout Satispay riporta in descrizione il beneficiario "SOCIETA AGRICOLA
        // AGOSTINELLI", la stessa stringa che marca i trasferimenti interni; senza questa
        // precedenza verrebbe scartato come SKIP_GIROCONTO invece che SKIP_POS.
        if (desc.contains("SATISPAY EUROPE")) return MappingResult.MappingOutcome.SKIP_POS;

        // A2 — giroconto interno (rilevato dal normalizzatore, simmetrico CA↔BPM)
        if (n.girosalto() != null) return MappingResult.MappingOutcome.SKIP_GIROCONTO;
        if (Sorgente.CA.equals(sorgente)) {
            if ("INCASSO TRAMITE POS".equals(causale)
                    || desc.contains("INCASSO POS") || desc.contains("NUMIA")
                    || desc.contains("ACCREDITO POS")) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
        } else if (Sorgente.BPM.equals(sorgente)) {
            if ("090".equals(causale) || "092".equals(causale)
                    || desc.contains("INC.POS") || desc.contains("INCAS. TRAMITE P.O.S")
                    || desc.contains("NUMIA")) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
            // POS che arriva come bonifico (causale 480 + accredito Nexi)
            if ("480".equals(causale) && desc.contains("NEXI") && desc.contains("ACCREDITO POS")) {
                return MappingResult.MappingOutcome.SKIP_POS;
            }
        }

        // A3 — spese ricorrenti / finanziamenti (fallback keyword editabile)
        if (isRicorrente(desc)) return MappingResult.MappingOutcome.SKIP_RICORRENTE;

        return null;
    }

    private static final Pattern RATA_WORD = Pattern.compile("\\bRATA\\b");

    /**
     * Fallback keyword per le ricorrenti (ETL v2 §4 A3). NOTA: {@code AFFITTO} è
     * volutamente escluso finché il Gate B (eventi) non distingue "AFFITTO SALA"
     * (evento) dall'affitto-non-evento. Il match strutturato contro le ricorrenze
     * attive del modulo dedicato arriverà in una fase successiva.
     */
    private boolean isRicorrente(String desc) {
        if (desc.contains("CANONE") || desc.contains("ASSICURAZ") || desc.contains("POLIZZA")
                || desc.contains("MUTUO") || desc.contains("LEASING")
                || desc.contains("FINANZIAMENTO") || desc.contains("BOLLO")
                || desc.contains("ASCONFIDI")) {
            return true;
        }
        return RATA_WORD.matcher(desc).find();
    }

    // ── GATE B — parcheggio eventi (PARK_EVENTO, ETL v2 §5) ────────────────────
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
        boolean fatturaCtx = spaced.contains("FATTURA") || spaced.contains("FATT")
                || spaced.contains("DOCUM") || spaced.contains("NOTA CREDITO");

        java.util.Set<String> forti = keywordEngine.eventiForti();
        java.util.Set<String> deboli = keywordEngine.eventiDeboli();

        if (Sorgente.BILLY.equals(sorgente)) {
            if (!positive(n.billyAgriturismo())) {
                // Billy non-agri: parcheggia solo su keyword evento esplicita (forte)
                return (!fatturaCtx && containsAny(compact, forti)) ? buildPark(spaced, compact) : null;
            }
            // Agriturismo>0: evento salvo carve-out (gestiti in classifyEntrata)
            if (isPosIncasso(spaced) || "SATISPAY".equals(n.metodoPagamentoCodice())) return null;
            if (compact.contains("KAIROS")) return null;
            return buildPark(spaced, compact);
        }

        // Banca (CA / BPM)
        if (fatturaCtx) return null;
        if (containsAny(compact, forti)) return buildPark(spaced, compact);
        if (containsAny(compact, deboli) && hasEventoContext(n)) return buildPark(spaced, compact);
        return null;
    }

    /** Contesto evento: ordinante (persona fisica) presente o data nella descrizione. */
    private boolean hasEventoContext(RawMovimento n) {
        if (n.entita() != null && n.entita().ordinante() != null) return true;
        return extractEventoDate(n.descrizione()) != null;
    }

    private ParkEvento buildPark(String spaced, String compact) {
        String tipo;
        String keyword;
        if (compact.contains("CAPARRA")) { tipo = "CAPARRA"; keyword = "CAPARRA"; }
        else if (compact.contains("ACCONTO")) { tipo = "ACCONTO"; keyword = "ACCONTO"; }
        else if (compact.contains("AFFITTOSALA") || compact.contains("AFFITTO")) { tipo = "AFFITTO_SALA"; keyword = "AFFITTO"; }
        else if (compact.contains("SALDO")) { tipo = "SALDO"; keyword = "SALDO"; }
        else { tipo = null; keyword = firstMatch(compact, keywordEngine.eventiForti()); }
        return new ParkEvento(tipo, keyword, extractEventoDate(spaced));
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

    private LocalDate extractEventoDate(String descrizione) {
        if (descrizione == null) return null;
        Matcher m = EVENTO_DATE.matcher(descrizione);
        if (m.find()) {
            try {
                int d = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int y = Integer.parseInt(m.group(3));
                if (y < 100) y += 2000;
                return LocalDate.of(y, mo, d);
            } catch (Exception ignored) {
                // formato numerico non valido (es. 32/13/2026): provo quello testuale
            }
        }
        Matcher mt = EVENTO_DATE_TESTUALE.matcher(descrizione);
        if (mt.find()) {
            try {
                int d = Integer.parseInt(mt.group(1));
                int mo = MESI_IT.indexOf(mt.group(2)) + 1;
                int y = Integer.parseInt(mt.group(3));
                return LocalDate.of(y, mo, d);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
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
                } else {
                    cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
                }
                return cl;
            }

            if ("SATISPAY".equals(n.metodoPagamentoCodice())) {
                if (altro) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; }
                else { cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00; }
                return cl;
            }
            if (altro && carne) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; return cl; }
            if (altro && orto) { cl.cogeId = coge(COGE_ORTOFRUTTA_4); cl.bu = 3; cl.aliquota = IVA_04; return cl; }
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
            return cl;
        }

        // C4 — partite speciali (entrate non operative)
        if (desc.contains("ORGANISMO PAGATORE") || desc.contains("AGEA")
                || desc.contains("REGIME DI PAGAMENTO UNICO")) {
            cl.cogeId = coge(COGE_CONTRIBUTI); cl.bu = 1; cl.aliquota = IVA_00;
            return cl;
        }
        if (desc.contains("VERSAMENTO SOCIO") || desc.contains("VERSAMENTO SOCI")) {
            cl.cogeId = coge(COGE_VERSAMENTO_SOCI); cl.bu = 5; cl.aliquota = IVA_00;
            return cl;
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
        cl.cogeId = cogeId;
        cl.bu = m.bu();
        cl.fornitoreId = m.fornitoreId();
        if ("ENTRATA".equals(n.tipo())) cl.aliquota = IVA_00;
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
            return cl;
        }

        boolean sdd = causale.contains("SDD") || "PAGAMENTO UTENZE".equals(causale);
        if (desc.contains("NEXI") && (sdd || "COMMISSIONI/SPESE".equals(causale))) {
            cl.cogeId = coge(COGE_COMMISSIONI_POS);
            cl.bu = 5;
            AliasRule nexi = matchAlias(desc);
            if (nexi != null) cl.fornitoreId = nexi.fornitoreId();
            return cl;
        }
        if (desc.contains("BOLLO E/C") || desc.contains("CANONE") || desc.contains("COMMISSIONI")) {
            cl.cogeId = coge(COGE_SPESE_BANCA);
            cl.bu = 5;
            return cl;
        }
        if ("COMMISSIONI/SPESE".equals(causale)) {
            cl.cogeId = coge(COGE_SPESE_BANCA);
            cl.bu = 5;
            return cl;
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
            return cl;
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
