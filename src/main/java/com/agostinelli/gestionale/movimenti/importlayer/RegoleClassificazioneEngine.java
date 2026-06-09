package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Interprete delle regole data-driven (ETL_CLASSIFICAZIONE_v2 §9). Carica le righe
 * di {@code regole_classificazione} (attive, per priorità) e le valuta contro una
 * riga normalizzata. La PRIMA regola che matcha vince.
 *
 * <p>È consultato dal {@link MovimentoMappingEngineImpl} PRIMA dei gate hardcoded:
 * permette di aggiungere/modificare regole senza redeploy, lasciando ai gate la
 * logica speciale non esprimibile a tabella (tag Alveare, carve-out Billy, ecc.).
 */
@ApplicationScoped
public class RegoleClassificazioneEngine {

    @Inject EntityManager em;

    private volatile boolean loaded = false;
    private final List<Regola> regole = new ArrayList<>();

    /** Esito di un match di regola (i campi MAP sono null per le azioni SKIP/PARK). */
    public record Match(String azione, String cogeCodice, Short buId, String metodoCodice) {}

    private record Regola(String sorgente, String tipoMovimento, String campo, String matchType,
                          String pattern, String[] patternList, String azione,
                          String cogeCodice, Short buId, String metodoCodice) {}

    public synchronized void refresh() {
        regole.clear();
        load();
        loaded = true;
    }

    private synchronized void ensureLoaded() {
        if (!loaded) { load(); loaded = true; }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        for (Object[] r : (List<Object[]>) em.createNativeQuery(
                "SELECT sorgente, tipo_movimento, campo, match_type, pattern, azione, " +
                "coge_codice, bu_id, metodo_codice FROM regole_classificazione " +
                "WHERE attivo = true ORDER BY priorita, id").getResultList()) {
            String matchType = ((String) r[3]).toUpperCase();
            String pattern = ((String) r[4]).toUpperCase();
            String[] list = "IN_LIST".equals(matchType)
                    ? java.util.Arrays.stream(pattern.split(",")).map(String::trim).toArray(String[]::new)
                    : null;
            Short bu = r[7] == null ? null : ((Number) r[7]).shortValue();
            regole.add(new Regola((String) r[0], (String) r[1], (String) r[2], matchType,
                    pattern, list, (String) r[5], (String) r[6], bu, (String) r[8]));
        }
    }

    /** Valuta le regole per priorità; ritorna il primo match o null. */
    public Match evaluate(RawMovimento n, String sorgente) {
        ensureLoaded();
        for (Regola r : regole) {
            if (!"*".equals(r.sorgente()) && !r.sorgente().equals(sorgente)) continue;
            if (!"*".equals(r.tipoMovimento()) && !r.tipoMovimento().equals(n.tipo())) continue;
            String value = fieldValue(n, r.campo());
            if (value == null) continue;
            if (matches(r, value)) {
                return new Match(r.azione(), r.cogeCodice(), r.buId(), r.metodoCodice());
            }
        }
        return null;
    }

    private String fieldValue(RawMovimento n, String campo) {
        return switch (campo) {
            case "CAUSALE" -> upper(n.rawOriginale().campi().get("CAUSALE"));
            case "DESC_SPACED" -> n.descrizione();
            case "DESC_COMPACT" -> n.descCompact();
            case "IBAN" -> n.entita() == null ? null : n.entita().ibanControparte();
            default -> null;
        };
    }

    private boolean matches(Regola r, String value) {
        return switch (r.matchType()) {
            case "EQUALS" -> value.equals(r.pattern());
            case "CONTAINS" -> value.contains(r.pattern());
            case "STARTS_WITH" -> value.startsWith(r.pattern());
            case "REGEX" -> {
                try { yield value.matches(r.pattern()); } catch (Exception e) { yield false; }
            }
            case "IN_LIST" -> {
                for (String tok : r.patternList()) if (!tok.isEmpty() && value.contains(tok)) yield true;
                yield false;
            }
            default -> false;
        };
    }

    private String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}
