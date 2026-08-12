package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Riconciliazione a periodo, <b>Billy = verità</b> (PROMPT-RICONCILIAZIONE-PERIODO §4).
 * Funzione pura (nessuno stato fuori dal metodo, nessun DB): trasforma i 3 file in un dataset
 * pronto per il mapping/persistenza più la quadratura informativa.
 *
 * <p>Idea: i ricavi elettronici nascono da Billy (un movimento per scontrino, categoria da Billy);
 * le banche servono solo a (i) ripartire l'incasso sui conti (BPM/CA) e (ii) fare da controllo di
 * quadratura di periodo. La quadratura NON è un cancello: i ricavi sono comunque contabilizzati.
 *
 * <p>Perché a periodo e non per-giorno: {@code Billy.Data} ≠ {@code banca.DEL} nel ~56% dei giorni
 * (Billy chiude il corrispettivo 1–2 giorni dopo la carta e a volte accorpa più giorni), quindi il
 * match per-giorno è strutturalmente inaffidabile; a livello di periodo torna (Δ ~1%, cause note).
 *
 * <p>Confronti sempre in <b>centesimi interi</b> (mai double/float).
 */
@ApplicationScoped
public class RiconciliazioneService {

    static final short CONTO_BPM = 1;
    static final short CONTO_CA = 2;
    static final short CONTO_CASSA = 3;
    static final String METODO_BPM = "POS_BPM";
    static final String METODO_CA = "POS_CA_NEXI";
    static final String METODO_CONTANTI = "CONTANTI";

    public DatasetRiconciliato riconcilia(List<RawMovimento> billy,
                                          List<RawMovimento> bpm,
                                          List<RawMovimento> ca) {
        // ── Step 1 — Billy → ricavi (split per metodo/natura) ──
        List<RawMovimento> contanti = new ArrayList<>();
        List<RawMovimento> spaccioElettronico = new ArrayList<>(); // elettronico NON-agriturismo
        List<RawMovimento> eventiAttesi = new ArrayList<>();        // elettronico agriturismo → evento
        for (RawMovimento b : billy) {
            if (b.importo() == null || b.dataMovimento() == null) continue;
            // Agriturismo = EVENTO: lo gestisce il modulo Eventi (decisione cliente) → escluso dalla
            // contabilità import a PRESCINDERE dal metodo di pagamento (contanti, POS, ecc.). Il check
            // precede lo split contanti/elettronico: anche un corrispettivo agriturismo in CONTANTI non
            // va in Cassa, è materia del modulo Eventi (come i relativi bonifici/POS lato banca).
            if (positive(b.billyAgriturismo())) { eventiAttesi.add(b); continue; }
            if (isContanti(b)) { contanti.add(b); continue; }
            spaccioElettronico.add(b);
        }

        // ── Step 2 — banche → righe POS del periodo ──
        List<RawMovimento> posRows = new ArrayList<>();
        for (RawMovimento r : bpm) if (isPos(r)) posRows.add(r);
        for (RawMovimento r : ca) if (isPos(r)) posRows.add(r);

        int anno = determinaAnno(billy, posRows);

        // ── Step 3.1 — coda testa (DEL di anno diverso) → esclusa, NON contabilizzata ──
        // Le righe escluse escono da qui NOMINATE (non solo contate): l'orchestratore le scrive in
        // import_scartati con motivo SKIP_CODA_TESTA. Fino al 2026-08-11 sparivano lasciando come
        // unica traccia una nota nella quadratura: 230,00 € di accredito bancario vero fuori dai
        // conti e da ogni coda (audit-catena-import-2026-08-11.md §2, FINDING F1). Il criterio
        // «anno solare del DEL» resta, ma ora chi importa gennaio VEDE la coda di dicembre.
        List<RawMovimento> coreBpm = new ArrayList<>();
        List<RawMovimento> coreCa = new ArrayList<>();
        List<RawMovimento> codaTesta = new ArrayList<>();
        long testaCents = 0;
        LocalDate maxDel = null;
        for (RawMovimento r : posRows) {
            LocalDate del = r.dataIncassoPos();
            if (del != null && del.getYear() != anno) { // periodo precedente
                testaCents += cents(r.importo());
                codaTesta.add(r);
                continue;
            }
            if (del != null && (maxDel == null || del.isAfter(maxDel))) maxDel = del;
            if (contoDi(r) == CONTO_CA) coreCa.add(r); else coreBpm.add(r);
        }
        long sigmaBpmCents = somma(coreBpm);
        long sigmaCaCents = somma(coreCa);

        // ── Step 3.2 — coda fondo (vendite Billy dopo l'ultima DEL) → in attesa di accredito ──
        List<RawMovimento> inAttesa = new ArrayList<>();
        List<RawMovimento> bookable = new ArrayList<>();
        for (RawMovimento s : spaccioElettronico) {
            if (maxDel != null && s.dataMovimento().isAfter(maxDel)) inAttesa.add(s);
            else bookable.add(s);
        }

        // ── Step 4 — LE BANCHE SONO L'OSSATURA: ogni riga POS bancaria diventa un movimento ──
        // Il movimento nasce dalla RIGA BANCA (conto, data di accredito e importo sono quelli veri,
        // per costruzione); Billy arricchisce solo la CATEGORIA contabile (coge/BU/IVA) dello
        // scontrino agganciato. L'aggancio è DETERMINISTICO e già presente nel dato: la descrizione
        // bancaria porta "DEL gg/mm/aa" (estratta in dataIncassoPos dal normalizzatore) e l'importo.
        //
        // Perché non serve più l'euristica: sui dati di luglio il join (importo, DEL) risolve 7 righe
        // POS su 13 — 5 esatte 1:1 e 2 come somma di più circuiti dello stesso DEL (755,20 + 117,30
        // = scontrino 872,50). Le orfane restano contabilizzate come ricavo POS generico: il denaro
        // non si perde MAI, al massimo perde la categoria di dettaglio.
        //
        // Storia: fino al 2026-08-09 questo passo faceva il contrario (ricavi creati dagli scontrini
        // Billy, banca/metodo assegnati per riempimento proporzionale, righe banca POS scartate).
        // Produceva banca e data sbagliate, 14 righe BPM sparite (4.371,33 €) e movimenti inesistenti
        // in estratto conto. Vedi docs/specs/piano-attacco-import-2026-08-09.md scheda A2.
        Map<String, RawMovimento> billyPerChiave = indicizzaBilly(spaccioElettronico);

        long assBpm = 0, assCa = 0;
        int nBpm = 0, nCa = 0;
        List<RawMovimentoArricchito> daMappare = new ArrayList<>();
        List<RawMovimento> posContabilizzate = new ArrayList<>();
        for (RawMovimento r : posRows) {
            LocalDate del = r.dataIncassoPos();
            if (del != null && del.getYear() != anno) continue; // coda testa: periodo precedente
            RawMovimento scontrino = agganciaScontrino(r, posRows, billyPerChiave);
            boolean toBpm = contoDi(r) != CONTO_CA;
            if (toBpm) { assBpm += cents(r.importo()); nBpm++; } else { assCa += cents(r.importo()); nCa++; }
            posContabilizzate.add(r);
            daMappare.add(RawMovimentoArricchito.arricchito(r, ricavoPosBanca(r, scontrino)));
        }

        // ── Contanti Billy → Cassa: unico caso in cui Billy crea un movimento, perché il
        //    contante non ha (per definizione) nessuna riga bancaria che lo rappresenti. ──
        for (RawMovimento c : contanti) {
            daMappare.add(RawMovimentoArricchito.arricchito(c, cassa(c)));
        }

        // ── Banche NON-POS → passthrough ai gate AS-IS (costi/eventi/SDD/Stripe/giroconti/Satispay) ──
        int nonPos = 0;
        for (RawMovimento r : bpm) if (!isPos(r)) { daMappare.add(RawMovimentoArricchito.passthrough(r)); nonPos++; }
        for (RawMovimento r : ca) if (!isPos(r)) { daMappare.add(RawMovimentoArricchito.passthrough(r)); nonPos++; }

        // ── Step 5 — quadratura di periodo (informativa) ──
        long billyNonAgriCents = somma(spaccioElettronico);
        long posBookCents = assBpm + assCa;          // POS bancario effettivamente contabilizzato
        long posCoreCents = sigmaBpmCents + sigmaCaCents;
        long posTotCents = posCoreCents + testaCents;
        long fondoCents = somma(inAttesa);

        List<String> note = new ArrayList<>();
        if (!codaTesta.isEmpty()) {
            note.add("Coda testa esclusa: " + codaTesta.size() + " riga/e POS con DEL dell'anno precedente ("
                    + euro(testaCents).toPlainString() + " €), periodo precedente. Tracciate in "
                    + "import_scartati (motivo SKIP_CODA_TESTA): vanno guardate una per una.");
        }
        if (!inAttesa.isEmpty()) {
            note.add("Coda fondo: " + inAttesa.size() + " scontrino/i venduti dopo l'ultima DEL banca ("
                    + euro(fondoCents).toPlainString() + " €) — l'accredito arriverà nel prossimo estratto conto.");
        }
        if (!eventiAttesi.isEmpty()) {
            note.add("Eventi (agriturismo): " + eventiAttesi.size() + " scontrino/i Billy agriturismo esclusi dalla "
                    + "contabilità import (li gestisce il modulo Eventi). I relativi incassi in banca sono per la "
                    + "gran parte BONIFICI evento (parcheggiati), non incassi POS: NON inquinano i ricavi spaccio.");
        }
        // NB: qui NON c'è più il confronto posCore − posBook. Era una guardia vacua: i due addendi
        // nascevano dallo stesso loop con lo stesso filtro, quindi il residuo era ≡ 0 per costruzione
        // — un controllo che non poteva fallire (audit §2, intervento §8 #9). Il confronto che può
        // davvero divergere è lo «scarto informativo» qui sotto (POS banca − Billy spaccio).
        note.add("Quadratura POS: tutte le righe POS del periodo sono contabilizzate dalla riga bancaria "
                + "(importo, conto e data di accredito sono quelli dell'estratto conto). Billy fornisce "
                + "solo la categoria contabile dello scontrino agganciato.");
        long scarto = posCoreCents - (billyNonAgriCents - fondoCents);
        if (scarto != 0) {
            note.add("Scarto informativo POS banca − Billy spaccio del periodo: " + euro(scarto).toPlainString()
                    + " €. Non incide sui saldi (contano le righe banca); misura quanto del POS bancario non "
                    + "trova uno scontrino spaccio: agriturismo incassato al terminale, Satispay, storni.");
        }

        QuadraturaPeriodo quadratura = new QuadraturaPeriodo(
                anno,
                euro(billyNonAgriCents), euro(posBookCents),
                euro(posTotCents), euro(posCoreCents),
                euro(sigmaBpmCents), euro(sigmaCaCents),
                euro(assBpm), euro(assCa),
                euro(testaCents), euro(fondoCents),
                maxDel, note);

        List<RawMovimento> contabilizzati = new ArrayList<>(posContabilizzate.size() + contanti.size());
        contabilizzati.addAll(posContabilizzate);
        contabilizzati.addAll(contanti);

        DatasetRiconciliato.Statistiche stat = new DatasetRiconciliato.Statistiche(
                posRows.size(), codaTesta.size(), posContabilizzate.size(), contanti.size(),
                eventiAttesi.size(), inAttesa.size(), nBpm, nCa, nonPos);

        return new DatasetRiconciliato(daMappare, contabilizzati, inAttesa, eventiAttesi, codaTesta,
                quadratura, stat);
    }

    // ── costruzione DettagliBilly ──────────────────────────────────────────────────

    /**
     * Ricavo POS: il movimento è la RIGA BANCA (conto/metodo/data/importo suoi), la categoria
     * contabile viene dallo scontrino Billy agganciato. Scontrino null (riga orfana) → categoria
     * lasciata a null: la riga si contabilizza comunque come ricavo POS generico.
     */
    private DettagliBilly ricavoPosBanca(RawMovimento rigaBanca, RawMovimento scontrino) {
        BillyCategoria.Esito cat = scontrino == null ? null : BillyCategoria.classifica(scontrino);
        short conto = contoDi(rigaBanca);
        String metodo = conto == CONTO_CA ? METODO_CA : METODO_BPM;
        List<String> refs = new ArrayList<>(refs(rigaBanca));
        if (scontrino != null) refs.addAll(refs(scontrino)); // traccia lo scontrino agganciato
        return new DettagliBilly(
                EsitoMatch.RICAVO_POS,
                cat == null ? null : cat.cogeCodice(),
                cat == null ? null : cat.bu(),
                cat == null ? null : cat.aliquotaIva(),
                metodo, conto, refs);
    }

    /** Indice degli scontrini Billy per chiave "data|centesimi" (aggancio deterministico). */
    private Map<String, RawMovimento> indicizzaBilly(List<RawMovimento> scontrini) {
        Map<String, RawMovimento> idx = new HashMap<>();
        for (RawMovimento s : scontrini) {
            if (s.dataMovimento() == null || s.importo() == null) continue;
            idx.putIfAbsent(s.dataMovimento() + "|" + cents(s.importo()), s);
        }
        return idx;
    }

    /**
     * Aggancia una riga POS bancaria al suo scontrino Billy usando (data DEL, importo).
     * Due tentativi, in ordine: (1) 1:1 sull'importo della riga; (2) N:1, cioè la somma di tutte
     * le righe POS della stessa banca con lo stesso DEL (più circuiti per lo stesso scontrino).
     * Nessun match → null (riga orfana, contabilizzata comunque).
     */
    private RawMovimento agganciaScontrino(RawMovimento rigaBanca, List<RawMovimento> posRows,
                                           Map<String, RawMovimento> billyPerChiave) {
        LocalDate del = rigaBanca.dataIncassoPos();
        if (del == null) return null;
        RawMovimento esatto = billyPerChiave.get(del + "|" + cents(rigaBanca.importo()));
        if (esatto != null) return esatto;
        long somma = 0;
        for (RawMovimento altra : posRows) {
            if (del.equals(altra.dataIncassoPos()) && contoDi(altra) == contoDi(rigaBanca)) {
                somma += cents(altra.importo());
            }
        }
        return billyPerChiave.get(del + "|" + somma);
    }

    /** Scontrino contante → Cassa (conto 3), categoria da Billy. */
    private DettagliBilly cassa(RawMovimento contante) {
        BillyCategoria.Esito cat = BillyCategoria.classifica(contante);
        return new DettagliBilly(
                EsitoMatch.CONTANTI,
                cat == null ? null : cat.cogeCodice(),
                cat == null ? null : cat.bu(),
                cat == null ? null : cat.aliquotaIva(),
                METODO_CONTANTI, CONTO_CASSA, refs(contante));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /** Anno del periodo: il più frequente fra gli scontrini Billy; fallback DEL banca o anno corrente. */
    private int determinaAnno(List<RawMovimento> billy, List<RawMovimento> posRows) {
        Map<Integer, Integer> freq = new HashMap<>();
        for (RawMovimento b : billy) {
            if (b.dataMovimento() != null) freq.merge(b.dataMovimento().getYear(), 1, Integer::sum);
        }
        if (freq.isEmpty()) {
            for (RawMovimento r : posRows) {
                if (r.dataIncassoPos() != null) freq.merge(r.dataIncassoPos().getYear(), 1, Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(LocalDate.now().getYear());
    }

    private long somma(List<RawMovimento> rows) {
        long c = 0;
        for (RawMovimento r : rows) c += cents(r.importo());
        return c;
    }

    private short contoDi(RawMovimento r) {
        return r.contoBancarioId() != null ? r.contoBancarioId() : CONTO_BPM;
    }

    private List<String> refs(RawMovimento r) {
        String rif = r.riferimentoEsterno();
        return rif == null ? List.of() : List.of(rif);
    }

    private String rif(RawMovimento r) {
        return r.riferimentoEsterno() == null ? "" : r.riferimentoEsterno();
    }

    private boolean isPos(RawMovimento r) {
        return r.circuitoPos() != null;
    }

    private boolean isContanti(RawMovimento b) {
        return "C".equals(pagamento(b));
    }

    private String pagamento(RawMovimento b) {
        return b.rawOriginale() == null ? null : b.rawOriginale().campi().get("PAGAMENTO");
    }

    private boolean positive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    /** Importo in centesimi interi (mai double/float per evitare falsi mismatch). */
    static long cents(BigDecimal v) {
        return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Centesimi interi → euro BigDecimal scala 2. */
    static BigDecimal euro(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
