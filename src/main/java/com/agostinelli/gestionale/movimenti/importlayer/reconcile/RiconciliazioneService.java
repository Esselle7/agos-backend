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
        List<RawMovimento> coreBpm = new ArrayList<>();
        List<RawMovimento> coreCa = new ArrayList<>();
        long testaCents = 0;
        int testaCount = 0;
        LocalDate maxDel = null;
        for (RawMovimento r : posRows) {
            LocalDate del = r.dataIncassoPos();
            if (del != null && del.getYear() != anno) { // periodo precedente
                testaCents += cents(r.importo());
                testaCount++;
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

        // ── Step 4 — ripartizione PROPORZIONALE deterministica dei ricavi sui conti BPM/CA ──
        // Non sappiamo quale carta/banca abbia incassato quale scontrino (le righe banca sono
        // aggregate per circuito e non mappabili al singolo scontrino): distribuiamo i ricavi Billy
        // così che ciascuna banca riceva la sua QUOTA del totale POS. Il totale ricavi da ripartire
        // è < del totale POS banca (per il non-spaccio: agriturismo a POS, Satispay, storni): con la
        // ripartizione proporzionale quello scarto si SPALMA su entrambe le banche in proporzione
        // (ognuna ~uguale % sotto il suo POS lordo), invece di cadere tutto sull'ultima riempita.
        // Regola deterministica e stabile: scontrini per importo decrescente (poi rif), si riempie
        // BPM fino al suo TARGET PROPORZIONALE, il resto a CA. Tutto in centesimi interi (no double).
        // L'attribuzione categoria↔banca resta CONVENZIONALE (non reale): documentato in UI.
        bookable.sort(Comparator
                .comparingLong((RawMovimento s) -> cents(s.importo())).reversed()
                .thenComparing(s -> rif(s)));
        boolean bpmDisponibile = sigmaBpmCents > 0;
        boolean caDisponibile = sigmaCaCents > 0;
        long sigmaTotCents = sigmaBpmCents + sigmaCaCents;
        long bookCents = somma(bookable);
        // Quota BPM del totale POS, applicata al totale ricavi da ripartire. Prodotto entro long
        // (≈2,5e12 sul reale, max teorico molto sotto 9,2e18): nessun overflow, nessun double.
        long targetBpmCents = sigmaTotCents == 0 ? 0 : (bookCents * sigmaBpmCents) / sigmaTotCents;
        long assBpm = 0, assCa = 0;
        int nBpm = 0, nCa = 0;
        List<RawMovimentoArricchito> daMappare = new ArrayList<>();
        for (RawMovimento s : bookable) {
            long c = cents(s.importo());
            boolean toBpm;
            if (!caDisponibile) toBpm = true;             // nessun POS CA → tutto su BPM
            else if (!bpmDisponibile) toBpm = false;      // nessun POS BPM → tutto su CA
            else toBpm = (assBpm + c <= targetBpmCents);   // riempi BPM fino al target proporzionale, poi CA
            if (toBpm) { assBpm += c; nBpm++; } else { assCa += c; nCa++; }
            daMappare.add(RawMovimentoArricchito.arricchito(s, ricavoPos(s, toBpm)));
        }

        // ── Contanti Billy → Cassa (unico ricavo creato direttamente da Billy) ──
        for (RawMovimento c : contanti) {
            daMappare.add(RawMovimentoArricchito.arricchito(c, cassa(c)));
        }

        // ── Banche NON-POS → passthrough ai gate AS-IS (costi/eventi/SDD/Stripe/giroconti/Satispay) ──
        int nonPos = 0;
        for (RawMovimento r : bpm) if (!isPos(r)) { daMappare.add(RawMovimentoArricchito.passthrough(r)); nonPos++; }
        for (RawMovimento r : ca) if (!isPos(r)) { daMappare.add(RawMovimentoArricchito.passthrough(r)); nonPos++; }

        // ── Step 5 — quadratura di periodo (informativa) ──
        long billyNonAgriCents = somma(spaccioElettronico);
        long billyBookCents = assBpm + assCa;
        long posCoreCents = sigmaBpmCents + sigmaCaCents;
        long posTotCents = posCoreCents + testaCents;
        long fondoCents = somma(inAttesa);
        long residuoCents = posCoreCents - billyBookCents;

        List<String> note = new ArrayList<>();
        if (testaCount > 0) {
            note.add("Coda testa esclusa: " + testaCount + " riga/e POS con DEL dell'anno precedente ("
                    + euro(testaCents).toPlainString() + " €), periodo precedente.");
        }
        if (!inAttesa.isEmpty()) {
            note.add("Coda fondo in attesa di accredito: " + inAttesa.size() + " scontrino/i venduti dopo l'ultima DEL banca ("
                    + euro(fondoCents).toPlainString() + " €) — non contabilizzati, al prossimo import.");
        }
        if (!eventiAttesi.isEmpty()) {
            note.add("Eventi (agriturismo): " + eventiAttesi.size() + " scontrino/i Billy agriturismo esclusi dalla "
                    + "contabilità import (li gestisce il modulo Eventi). I relativi incassi in banca sono per la "
                    + "gran parte BONIFICI evento (parcheggiati), non incassi POS: NON inquinano i ricavi spaccio.");
        }
        note.add("Residuo core = POS banca core − Billy spaccio contabilizzato. Lo compongono: la quota di "
                + "agriturismo effettivamente incassata al TERMINALE POS (piccola), Satispay (netto banca vs "
                + "lordo Billy ~1%) e storni. È piccolo e atteso, non è un errore.");

        QuadraturaPeriodo quadratura = new QuadraturaPeriodo(
                anno,
                euro(billyNonAgriCents), euro(billyBookCents),
                euro(posTotCents), euro(posCoreCents),
                euro(sigmaBpmCents), euro(sigmaCaCents),
                euro(assBpm), euro(assCa),
                euro(testaCents), euro(fondoCents), euro(residuoCents),
                maxDel, note);

        List<RawMovimento> contabilizzati = new ArrayList<>(bookable.size() + contanti.size());
        contabilizzati.addAll(bookable);
        contabilizzati.addAll(contanti);

        DatasetRiconciliato.Statistiche stat = new DatasetRiconciliato.Statistiche(
                posRows.size(), testaCount, bookable.size(), contanti.size(),
                eventiAttesi.size(), inAttesa.size(), nBpm, nCa, nonPos);

        return new DatasetRiconciliato(daMappare, contabilizzati, inAttesa, eventiAttesi, quadratura, stat);
    }

    // ── costruzione DettagliBilly ──────────────────────────────────────────────────

    /** Ricavo POS spaccio: categoria da Billy, conto/metodo dalla ripartizione (BPM o CA). */
    private DettagliBilly ricavoPos(RawMovimento scontrino, boolean bpm) {
        BillyCategoria.Esito cat = BillyCategoria.classifica(scontrino);
        String metodo = bpm ? METODO_BPM : METODO_CA;
        short conto = bpm ? CONTO_BPM : CONTO_CA;
        return new DettagliBilly(
                EsitoMatch.RICAVO_POS,
                cat == null ? null : cat.cogeCodice(),
                cat == null ? null : cat.bu(),
                cat == null ? null : cat.aliquotaIva(),
                metodo, conto, refs(scontrino));
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
