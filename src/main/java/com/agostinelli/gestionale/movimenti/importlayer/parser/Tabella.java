package com.agostinelli.gestionale.movimenti.importlayer.parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Risultato del parsing tabellare generico (CSV o XLSX) prodotto da
 * {@link TabularReader}: un'intestazione + righe dati, indicizzate per
 * <b>nome di colonna normalizzato</b> e non per posizione.
 *
 * <p>La normalizzazione header ({@link #norm(String)}) rende equivalenti varianti
 * tipografiche ("Importo (€)" ≡ "Importo", "Carne 10%" ≡ "Carne 10") così che gli
 * export nativi delle banche / del registratore di cassa e le versioni
 * "addomesticate" in Excel siano interscambiabili purché contengano le colonne
 * attese.
 */
public final class Tabella {

    /** Riga dati grezza: numero progressivo (post-header) + celle in ordine di colonna. */
    public record Riga(int numero, List<String> celle) {}

    private final boolean excel;
    private final List<String> intestazioni;          // normalizzate, in ordine
    private final Map<String, Integer> indicePerNome; // nome normalizzato → primo indice
    private final List<Riga> righe;

    Tabella(boolean excel, List<String> intestazioniNorm, List<Riga> righe) {
        this.excel = excel;
        this.intestazioni = List.copyOf(intestazioniNorm);
        this.righe = List.copyOf(righe);
        Map<String, Integer> idx = new LinkedHashMap<>();
        for (int i = 0; i < intestazioniNorm.size(); i++) {
            idx.putIfAbsent(intestazioniNorm.get(i), i);
        }
        this.indicePerNome = idx;
    }

    /** True se la fonte era un foglio Excel (origine di date/numeri canonici). */
    public boolean isExcel() {
        return excel;
    }

    public List<String> intestazioni() {
        return intestazioni;
    }

    public List<Riga> righe() {
        return righe;
    }

    /** Indice della prima colonna che corrisponde a uno degli alias, o -1. */
    public int colonna(String... alias) {
        for (String a : alias) {
            Integer i = indicePerNome.get(norm(a));
            if (i != null) return i;
        }
        return -1;
    }

    public boolean haColonna(String... alias) {
        return colonna(alias) >= 0;
    }

    /** Valore (trim) della riga per la prima colonna che matcha un alias; null se assente/vuoto. */
    public String valore(Riga r, String... alias) {
        return cella(r, colonna(alias));
    }

    /** Valore (trim) per indice di colonna; null se fuori range o vuoto. */
    public String cella(Riga r, int idx) {
        if (idx < 0 || idx >= r.celle().size()) return null;
        String v = r.celle().get(idx);
        if (v == null) return null;
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    // ── normalizzazione header (condivisa con il reader) ───────────────────────

    /**
     * Forma normalizzata di un'intestazione/alias: minuscolo, senza accenti, senza
     * contenuto tra parentesi né '%', soli alfanumerici e spazi singoli.
     */
    public static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        t = t.toLowerCase(Locale.ROOT);
        t = t.replaceAll("\\([^)]*\\)", " ");   // rimuove "(€)", "(No Agriturismo)", "(Pagamento)"
        t = t.replace('%', ' ');
        t = t.replaceAll("[^a-z0-9 ]", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** Insieme normalizzato delle celle di una riga (per il riconoscimento header). */
    static List<String> normCells(List<String> celle) {
        List<String> out = new ArrayList<>(celle.size());
        for (String c : celle) out.add(norm(c));
        return out;
    }
}
