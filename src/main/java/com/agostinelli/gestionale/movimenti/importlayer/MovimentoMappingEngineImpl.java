package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
import com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
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
public class MovimentoMappingEngineImpl implements MovimentoMappingEngine {

    @Inject EntityManager em;
    @Inject RegoleClassificazioneEngine regoleEngine;

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

    // Gate B — keyword evento (ETL v2 §5), cercate sulla vista COMPACT.
    private static final String[] EVENTO_FORTI = {
            "MATRIMONIO", "BATTESIMO", "CRESIMA", "COMUNIONE", "COMPLEANNO", "ANNIVERSARIO",
            "CERIMONIA", "DICIOTTESIMO", "18ESIMO", "18ANNI", "GENDER", "LAUREA",
            "AFFITTOSALA", "CAPARRA"
    };
    private static final String[] EVENTO_DEBOLI = { "ACCONTO", "SALDO", "AFFITTO", "EVENTO", "FESTA" };

    // Data evento best-effort: prima occorrenza gg/mm/aaaa (o gg.mm.aaaa) nella descrizione.
    private static final Pattern EVENTO_DATE = Pattern.compile(
            "\\b(\\d{1,2})[/.](\\d{1,2})[/.](\\d{2,4})\\b");

    private volatile boolean loaded = false;
    private final Map<String, Integer> cogeByCode = new HashMap<>();
    private final Map<String, Integer> metodiByCode = new HashMap<>();
    private final List<AliasRule> aliasRules = new ArrayList<>();
    // Rubrica controparti (§7): IBAN forte + lista per match a token sul nome normalizzato.
    private final Map<String, Controparte> contropartiByIban = new HashMap<>();
    private final List<Controparte> contropartiList = new ArrayList<>();

    private record AliasRule(String pattern, String matchType, UUID fornitoreId,
                             Integer cogeDefaultId, Short buDefaultId) {}

    private record Controparte(String iban, String nomeNorm, UUID fornitoreId,
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
    }

    @Override
    public MappingResult map(RawMovimento n) {
        ensureLoaded();

        String sorgente = n.rawOriginale().campi().get(Sorgente.KEY);

        // ── REGOLE DATA-DRIVEN (priorità) — ETL v2 §9: valutate PRIMA dei gate ──
        RegoleClassificazioneEngine.Match rule = regoleEngine.evaluate(n, sorgente);
        Classify cl;
        if (rule != null && !"MAP".equals(rule.azione())) {
            return switch (rule.azione()) {
                case "SKIP_POS" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_POS, n);
                case "SKIP_GIROCONTO" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_GIROCONTO, n);
                case "SKIP_RICORRENTE" -> MappingResult.skip(MappingResult.MappingOutcome.SKIP_RICORRENTE, n);
                case "PARK_EVENTO" -> MappingResult.parkEvento(
                        buildPark(safe(n.descrizione()), safe(n.descCompact())), n);
                default -> MappingResult.ambiguous("AZIONE_REGOLA_SCONOSCIUTA", n);
            };
        }
        if (rule != null) { // azione MAP
            cl = new Classify();
            cl.cogeId = coge(rule.cogeCodice());
            cl.bu = rule.buId();
            cl.metodoCodiceOverride = rule.metodoCodice();
        } else {
            // ── GATE A — esclusioni deterministiche (ETL v2 §4) ──
            MappingResult.MappingOutcome skip = gateA(n, sorgente);
            if (skip != null) {
                return MappingResult.skip(skip, n);
            }
            // ── GATE B — parcheggio eventi (ETL v2 §5) ──
            ParkEvento park = gateB(n, sorgente);
            if (park != null) {
                return MappingResult.parkEvento(park, n);
            }
            cl = "ENTRATA".equals(n.tipo())
                    ? classifyEntrata(n, sorgente)
                    : classifyUscita(n, sorgente);
        }

        if (cl.motivo != null) {
            return MappingResult.ambiguous(cl.motivo, n);
        }

        // Metodo di pagamento: override (es. Stripe) o codice dal normalizzatore
        String metodoCodice = cl.metodoCodiceOverride != null ? cl.metodoCodiceOverride : n.metodoPagamentoCodice();
        Integer metodoId = metodoCodice == null ? null : metodiByCode.get(metodoCodice);

        // ── Validazione invarianti (§6.1) ──
        String motivo = validate(n, metodoCodice, metodoId, cl);
        if (motivo != null) {
            return MappingResult.ambiguous(motivo, n);
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
        return MappingResult.success(req, n);
    }

    // ── GATE A — esclusioni deterministiche (SKIP, ETL v2 §4) ──────────────────
    /**
     * Restituisce l'esito di scarto se la riga va esclusa a monte, altrimenti null.
     * POS e ricorrenti si valutano solo sulle banche: Billy è la fonte originale
     * degli incassi POS e non contiene spese ricorrenti.
     */
    private MappingResult.MappingOutcome gateA(RawMovimento n, String sorgente) {
        // A2 — giroconto interno (rilevato dal normalizzatore, simmetrico CA↔BPM)
        if (n.girosalto() != null) return MappingResult.MappingOutcome.SKIP_GIROCONTO;

        if (Sorgente.BILLY.equals(sorgente)) return null;

        String desc = n.descrizione() == null ? "" : n.descrizione();
        String causale = upperCausale(n);

        // A1 — POS / Satispay (duplicati di Billy)
        if (desc.contains("SATISPAY EUROPE")) return MappingResult.MappingOutcome.SKIP_POS;
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

        if (Sorgente.BILLY.equals(sorgente)) {
            if (!positive(n.billyAgriturismo())) {
                // Billy non-agri: parcheggia solo su keyword evento esplicita (forte)
                return (!fatturaCtx && containsAny(compact, EVENTO_FORTI)) ? buildPark(spaced, compact) : null;
            }
            // Agriturismo>0: evento salvo carve-out (gestiti in classifyEntrata)
            if (isPosIncasso(spaced) || "SATISPAY".equals(n.metodoPagamentoCodice())) return null;
            if (compact.contains("KAIROS")) return null;
            return buildPark(spaced, compact);
        }

        // Banca (CA / BPM)
        if (fatturaCtx) return null;
        if (containsAny(compact, EVENTO_FORTI)) return buildPark(spaced, compact);
        if (containsAny(compact, EVENTO_DEBOLI) && hasEventoContext(n)) return buildPark(spaced, compact);
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
        else { tipo = null; keyword = firstMatch(compact, EVENTO_FORTI); }
        return new ParkEvento(tipo, keyword, extractEventoDate(spaced));
    }

    private boolean isPosIncasso(String spaced) {
        return spaced.contains("INCASSO POS") || spaced.contains("NUMIA")
                || spaced.contains("ACCREDITO POS") || spaced.contains("INC.POS")
                || spaced.contains("INCAS. TRAMITE P.O.S");
    }

    private boolean containsAny(String s, String[] keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    private String firstMatch(String s, String[] keys) {
        for (String k : keys) if (s.contains(k)) return k;
        return null;
    }

    private LocalDate extractEventoDate(String descrizione) {
        if (descrizione == null) return null;
        Matcher m = EVENTO_DATE.matcher(descrizione);
        if (!m.find()) return null;
        try {
            int d = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int y = Integer.parseInt(m.group(3));
            if (y < 100) y += 2000;
            return LocalDate.of(y, mo, d);
        } catch (Exception e) {
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

        // Fornitore/COGE non bloccante (ETL v2 §6 C3): entrata non riconosciuta →
        // transitorio "Ricavi da classificare" + monitoraggio triage (niente più scarto).
        cl.cogeId = coge(COGE_RICAVI_DACLASS); cl.bu = 5; cl.aliquota = IVA_00;
        return cl;
    }

    // ── USCITE (solo CA) ─────────────────────────────────────────────────────────
    private Classify classifyUscita(RawMovimento n, String sorgente) {
        Classify cl = new Classify();
        String desc = n.descrizione() == null ? "" : n.descrizione();
        String causale = upperCausale(n);

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

        // Rubrica controparti (§7.1): IBAN forte → token sul nome. Arricchisce il fornitore
        // e, se la controparte ha COGE/BU di default, mappa direttamente.
        Controparte cp = matchControparte(desc, n.entita());
        if (cp != null) {
            cl.fornitoreId = cp.fornitoreId();
            if (cp.cogeDefaultId() != null && cp.buDefaultId() != null) {
                cl.cogeId = cp.cogeDefaultId();
                cl.bu = cp.buDefaultId();
                return cl;
            }
        }

        // EFFETTI RITIRATI/RICHIAMATI (RIBA) e fallback: matching alias fornitore storico
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
        metodiByCode.clear();
        aliasRules.clear();
        contropartiByIban.clear();
        contropartiList.clear();
        loadLookups();
        regoleEngine.refresh();
        loaded = true;
    }

    @SuppressWarnings("unchecked")
    private void loadLookups() {
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT codice, id FROM piano_dei_conti_coge").getResultList()) {
            cogeByCode.put((String) r[0], ((Number) r[1]).intValue());
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
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT iban, nome_normalizzato, fornitore_id, coge_default_id, bu_default_id FROM controparti")
                .getResultList()) {
            String iban = (String) r[0];
            String nomeNorm = DescNormalizer.normalizeToken((String) r[1]);
            UUID fid = r[2] == null ? null : (r[2] instanceof UUID u ? u : UUID.fromString(r[2].toString()));
            Integer cogeDef = r[3] == null ? null : ((Number) r[3]).intValue();
            Short buDef = r[4] == null ? null : ((Number) r[4]).shortValue();
            Controparte c = new Controparte(iban, nomeNorm, fid, cogeDef, buDef);
            if (iban != null && !iban.isBlank()) contropartiByIban.put(iban, c);
            if (nomeNorm != null && nomeNorm.length() >= 4) contropartiList.add(c);
        }
    }

    /**
     * Matching controparte (§7.1): IBAN forte → token sul nome normalizzato.
     * Il fuzzy (trigrammi) è riservato ai suggerimenti del triage (F6), non all'auto-book.
     */
    private Controparte matchControparte(String desc, EntitaEstratte ent) {
        if (ent != null && ent.ibanControparte() != null) {
            Controparte byIban = contropartiByIban.get(ent.ibanControparte());
            if (byIban != null) return byIban;
        }
        String descNorm = DescNormalizer.normalizeToken(desc);
        if (descNorm == null || descNorm.isBlank()) return null;
        for (Controparte c : contropartiList) {
            if (descNorm.contains(c.nomeNorm())) return c;
        }
        return null;
    }
}
