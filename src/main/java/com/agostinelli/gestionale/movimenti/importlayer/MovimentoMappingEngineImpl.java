package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.importlayer.model.MappingResult;
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

    // COGE per codice (mai hardcodare l'ID)
    private static final String COGE_CAPARRA = "30.02.001";
    private static final String COGE_SALDO = "30.02.002";
    private static final String COGE_CARNE_10 = "30.03.001";
    private static final String COGE_ORTOFRUTTA_4 = "30.03.002";
    private static final String COGE_ALVEARE_STRIPE = "30.03.003";
    private static final String COGE_AGRITURISMO = "30.01.001";
    private static final String COGE_COMMISSIONI_POS = "40.02.001";
    private static final String COGE_SPESE_BANCA = "40.02.002";

    private static final BigDecimal IVA_10 = new BigDecimal("0.10");
    private static final BigDecimal IVA_04 = new BigDecimal("0.04");
    private static final BigDecimal IVA_00 = new BigDecimal("0.00");

    private static final Pattern EVENTO_DATE = Pattern.compile(
            "(?:EVENTO|MATRIMONIO|CERIMONIA)\\s+(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})");

    private volatile boolean loaded = false;
    private final Map<String, Integer> cogeByCode = new HashMap<>();
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
        String motivo; // se valorizzato → AMBIGUOUS
    }

    @Override
    public MappingResult map(RawMovimento n) {
        ensureLoaded();

        if (n.girosalto() != null) {
            return MappingResult.giroconto(n);
        }

        String sorgente = n.rawOriginale().campi().get(Sorgente.KEY);
        Classify cl = "ENTRATA".equals(n.tipo())
                ? classifyEntrata(n, sorgente)
                : classifyUscita(n, sorgente);

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
                n.descrizione(),
                null,                      // note
                n.riferimentoEsterno(),
                n.fonte(),
                null                       // allegatoPath
        );
        return MappingResult.success(req, n);
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
            boolean eventoKw = desc.contains("CAPARRA") || desc.contains("SALDO EVENTO") || desc.contains("ACCONTO EVENTO");

            // Riga mista (agriturismo + evento): split non automatizzabile → ambigua
            if (eventoKw && agri) {
                cl.motivo = "BU_AMBIGUA";
                return cl;
            }

            if (eventoKw) {
                if (desc.contains("CAPARRA")) { cl.cogeId = coge(COGE_CAPARRA); cl.tipoEvento = "CAPARRA"; }
                else if (desc.contains("ACCONTO EVENTO")) { cl.cogeId = coge(COGE_CAPARRA); cl.tipoEvento = "ACCONTO"; }
                else { cl.cogeId = coge(COGE_SALDO); cl.tipoEvento = "SALDO"; }
                cl.bu = 2;
                cl.aliquota = IVA_10;
                UUID evento = findEvento(desc);
                if (evento == null) { cl.motivo = "EVENTO_NON_TROVATO"; return cl; }
                cl.eventoId = evento;
                return cl;
            }

            if ("SATISPAY".equals(n.metodoPagamentoCodice())) {
                if (agri) { cl.cogeId = coge(COGE_AGRITURISMO); cl.bu = 1; cl.aliquota = IVA_10; }
                else if (altro) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; }
                else cl.motivo = "BU_AMBIGUA";
                return cl;
            }
            if (agri) { cl.cogeId = coge(COGE_AGRITURISMO); cl.bu = 1; cl.aliquota = IVA_10; return cl; }
            if (altro && carne) { cl.cogeId = coge(COGE_CARNE_10); cl.bu = 3; cl.aliquota = IVA_10; return cl; }
            if (altro && orto) { cl.cogeId = coge(COGE_ORTOFRUTTA_4); cl.bu = 3; cl.aliquota = IVA_04; return cl; }
            cl.motivo = "BU_AMBIGUA";
            return cl;
        }

        // ── Banca (BPM / CA) in entrata ──
        if (desc.contains("STRIPE")) {
            cl.cogeId = coge(COGE_ALVEARE_STRIPE);
            cl.bu = 3;
            cl.aliquota = IVA_00;
            cl.metodoCodiceOverride = "ALVEARE_STRIPE";
            return cl;
        }

        // Per gli incassi bancari NON applichiamo l'alias dei fornitori (orientato ai costi):
        // mapperebbe un ricavo su un conto di costo (es. "INCASSO POS NEXI" → Nexi). Oltre a
        // Stripe non c'è regola deterministica → revisione manuale (regola conservativa).
        String causale = upperCausale(n);
        cl.motivo = "ZI0".equals(causale) ? "FORNITORE_NON_RICONOSCIUTO" : "COGE_NON_DETERMINABILE";
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

        // EFFETTI RITIRATI/RICHIAMATI (RIBA) e fallback: matching alias fornitore
        AliasRule alias = matchAlias(desc);
        if (alias != null && alias.buDefaultId() != null && alias.cogeDefaultId() != null) {
            cl.cogeId = alias.cogeDefaultId();
            cl.bu = alias.buDefaultId();
            cl.fornitoreId = alias.fornitoreId();
            return cl;
        }
        cl.motivo = "FORNITORE_NON_RICONOSCIUTO";
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

    // ── Abbinamento evento (BU2) ──
    private UUID findEvento(String descrizione) {
        Matcher m = EVENTO_DATE.matcher(descrizione);
        if (!m.find()) return null;
        LocalDate data;
        try {
            int d = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int y = Integer.parseInt(m.group(3));
            if (y < 100) y += 2000;
            data = LocalDate.of(y, mo, d);
        } catch (Exception e) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery(
                        "SELECT id FROM eventi WHERE data_evento = :d AND stato = 'CONFERMATO'")
                .setParameter("d", data)
                .getResultList();
        if (ids.size() != 1) return null; // 0 o >1 → non disambiguabile
        Object id = ids.get(0);
        return id instanceof UUID u ? u : UUID.fromString(id.toString());
    }

    private Integer coge(String codice) {
        return cogeByCode.get(codice);
    }

    private boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
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

    /** Ricarica le lookup (chiamato a inizio import: recepisce alias aggiunti dalle classificazioni). */
    public synchronized void refreshLookups() {
        cogeByCode.clear();
        metodiByCode.clear();
        aliasRules.clear();
        loadLookups();
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
    }
}
