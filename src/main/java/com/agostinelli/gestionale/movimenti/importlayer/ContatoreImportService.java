package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.ContatoreImportDTO;
import com.agostinelli.gestionale.movimenti.dto.RegistroImportDTO;
import com.agostinelli.gestionale.movimenti.dto.RigaImportDTO;
import com.agostinelli.gestionale.movimenti.importlayer.model.Proposta;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dove si trova, adesso, ogni euro estratto dai due file banca (SPEC import-v2 §5, R7–R10, R21).
 * Sola lettura, nessuno stato nuovo (ADR 008: la coda resta DERIVATA).
 *
 * <p><b>Una lettura sola per due viste.</b> Il contatore (§6.1) e il registro (R21) escono dallo
 * stesso {@link #righe(UUID)}: se derivassero da due query diverse potrebbero raccontare due storie
 * diverse della stessa riga, ed è esattamente il tipo di bug che il contatore esiste per impedire.
 * Il costo è materializzare le righe di UN import (~10²–10³) invece di aggregare in SQL: irrilevante
 * a questi volumi, e in cambio l'invariante di R7 copre anche il registro.
 *
 * <h2>Perché non basta sommare «movimenti dell'import» + «righe di coda» (R10a)</h2>
 * Due percorsi su quattro creano il movimento <b>con</b> {@code fonte_importazione_id} <b>e</b>
 * lasciano viva la riga di coda ({@code ImportTriageService.confermaRicorrente} e
 * {@code contabilizzaScartato}): sommarli conta quei soldi due volte. La regola qui è una sola,
 * per (tabella, stato): <b>una riga di coda risolta si conta SOLO se la sua risoluzione ha creato
 * il movimento FUORI dall'insieme dei movimenti di questo import</b>. Gli stati che invece bookano
 * dentro l'import ({@code CONTABILIZZATA}, {@code CLASSIFICATO}, {@code CONFERMATA}, e
 * {@code IGNORATO} dei differiti) sono marcati {@link Casa#GIA_CONTATO}.
 *
 * <p>Nota su {@code matching_differiti}: l'anti-join su {@code movimento_id} — la guardia che la
 * SPEC propone in R10a — <b>non</b> funziona lì, perché quella colonna punta al movimento
 * DA_LIQUIDARE <i>preesistente</i>, non a quello creato: su {@code IGNORA}
 * ({@code MatchingDifferitiService:301}) il movimento nuovo non viene mai scritto in tabella.
 * La regola per stato copre entrambi i casi senza migration.
 *
 * <h2>Perché conta RIGHE bancarie, non movimenti (R10b)</h2>
 * Una rata di FINANZIAMENTO collegata a un piano genera <b>due</b> movimenti (quota capitale +
 * quota interessi, {@code RecurringExpenseService.collegaRataDaImport}) da una sola riga bancaria.
 * La riga porta il proprio importo, non la somma dei movimenti: gli euro tornano, il conteggio
 * righe resta 1.
 *
 * <h2>Limite dichiarato</h2>
 * Un movimento d'import portato ad {@code ANNULLATO} esce da ogni bucket e l'invariante NON chiude
 * più: è voluto. Quel denaro ha lasciato i libri senza una casa, e il contatore deve gridarlo
 * invece di far quadrare i conti con un bucket-discarica.
 */
@ApplicationScoped
public class ContatoreImportService {

    private static final String COGE_RICAVI_DACLASS = "39.99.999";
    private static final String COGE_COSTI_DACLASS = "49.99.999";

    /** Contropartita cassa del versamento ATM: rovescio contabile della stessa riga banca (§5). */
    private static final String SUFFISSO_CASSA = "%:CASSA";

    @Inject EntityManager em;
    @Inject MovimentoNormalizer normalizer;
    @Inject ObjectMapper objectMapper;

    /** Le case possibili di una riga bancaria. GIA_CONTATO non è una casa: è «già nel primo termine». */
    public enum Casa {
        A_LIBRO("a libro"),
        DA_CATALOGARE("da catalogare"),
        FUORI_DAI_CONTI("fuori dai conti"),
        ESCLUSO("escluso"),
        DUPLICATA("duplicata"),
        PARTITA_DI_GIRO("partita di giro"),
        GIA_CONTATO(null);

        /** R22: la parola del badge. Mai solo il colore. */
        public final String parola;
        Casa(String parola) { this.parola = parola; }
    }

    // ── il contatore (§6.1) ───────────────────────────────────────────────────────

    public ContatoreImportDTO calcola(UUID importLogId) {
        Object[] log = universoMisurato(importLogId);

        Map<Casa, long[]> acc = new EnumMap<>(Casa.class);   // [righe, entrateCents, usciteCents]
        for (RigaImportDTO r : righe(importLogId)) {
            long[] a = acc.computeIfAbsent(Casa.valueOf(r.stato()), k -> new long[3]);
            a[0]++;
            if ("ENTRATA".equals(r.tipo())) a[1] += cents(r.importo()); else a[2] += cents(r.importo());
        }

        long letteEntrate = cents((BigDecimal) log[1]);
        long letteUscite = cents((BigDecimal) log[2]);
        long sommaEntrate = 0, sommaUscite = 0;
        for (Map.Entry<Casa, long[]> e : acc.entrySet()) {
            if (e.getKey() == Casa.GIA_CONTATO) continue;
            sommaEntrate += e.getValue()[1];
            sommaUscite += e.getValue()[2];
        }
        // Gli import caricati PRIMA di V32 non hanno l'universo misurato: il termine sinistro
        // dell'invariante non esiste. Dichiararlo «non quadrato» sarebbe un falso allarme — il
        // denaro non manca, manca la misura. Si dice quello, e si dice come si ottiene: rifare
        // l'import (la dedup su riferimento_esterno regge, R8) oppure aspettare il prossimo.
        boolean misurato = log[0] != null;
        long scartoEntrate = misurato ? letteEntrate - sommaEntrate : 0;
        long scartoUscite = misurato ? letteUscite - sommaUscite : 0;

        return new ContatoreImportDTO(
                importLogId,
                new ContatoreImportDTO.Bucket(
                        misurato ? ((Number) log[0]).longValue() : 0,
                        euro(letteEntrate), euro(letteUscite)),
                misurato,
                bucket(acc, Casa.A_LIBRO),
                bucket(acc, Casa.DA_CATALOGARE),
                bucket(acc, Casa.FUORI_DAI_CONTI),
                bucket(acc, Casa.ESCLUSO),
                bucket(acc, Casa.DUPLICATA),
                bucket(acc, Casa.PARTITA_DI_GIRO),
                misurato && scartoEntrate == 0 && scartoUscite == 0,
                euro(scartoEntrate), euro(scartoUscite),
                fuoriUniverso(importLogId));
    }

    // ── il registro (R21/R22) ─────────────────────────────────────────────────────

    /**
     * Tutte le righe bancarie dell'import, filtrabili e paginate.
     *
     * <p>L'ordine è {@code (data, importo)} e non quello del file di export: {@code import_scartati}
     * e {@code import_ambiguita} hanno {@code riga_numero}, ma {@code eventi_da_riconciliare} e
     * {@code ricorrenti_da_riconciliare} no — verificato a schema. Ricostruire l'ordine originale
     * costerebbe due ALTER TABLE per un guadagno che nessuno ha chiesto (SPEC §7).
     */
    public RegistroImportDTO registro(UUID importLogId, String stato, Short conto,
                                      LocalDate da, LocalDate a, String cerca,
                                      int page, int size) {
        String q = cerca == null ? null : cerca.trim().toLowerCase(Locale.ROOT);
        List<RigaImportDTO> tutte = righe(importLogId).stream()
                .filter(r -> !Casa.GIA_CONTATO.name().equals(r.stato()))
                .filter(r -> stato == null || stato.isBlank() || stato.equals(r.stato()))
                .filter(r -> conto == null || conto.equals(r.contoBancarioId()))
                .filter(r -> da == null || (r.data() != null && !r.data().isBefore(da)))
                .filter(r -> a == null || (r.data() != null && !r.data().isAfter(a)))
                .filter(r -> q == null || q.isEmpty() || contiene(r, q))
                .sorted(Comparator.comparing(RigaImportDTO::data,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RigaImportDTO::importo,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // I totali sono di TUTTO l'insieme filtrato, non della pagina: è la domanda che si fa
        // chi filtra («quanto vale questa coda?»), e qui l'insieme intero c'è già.
        long entrateCents = 0, usciteCents = 0, nEntrate = 0, nUscite = 0;
        for (RigaImportDTO r : tutte) {
            if ("ENTRATA".equals(r.tipo())) { entrateCents += cents(r.importo()); nEntrate++; }
            else { usciteCents += cents(r.importo()); nUscite++; }
        }

        int from = Math.min((int) Math.min((long) page * size, Integer.MAX_VALUE), tutte.size());
        int to = Math.min(from + size, tutte.size());
        return new RegistroImportDTO(
                PagedResponse.of(tutte.subList(from, to), page, size, tutte.size()),
                nEntrate, euro(entrateCents), nUscite, euro(usciteCents));
    }

    private boolean contiene(RigaImportDTO r, String q) {
        return (r.causale() != null && r.causale().toLowerCase(Locale.ROOT).contains(q))
                || (r.dettaglio() != null && r.dettaglio().toLowerCase(Locale.ROOT).contains(q))
                || (r.importo() != null && r.importo().toPlainString().contains(q));
    }

    // ── la lettura unica: ogni riga banca, con la sua casa ────────────────────────

    /** Ogni riga bancaria di questo import, una volta e una sola. Include le GIA_CONTATO. */
    public List<RigaImportDTO> righe(UUID importLogId) {
        List<RigaImportDTO> out = new ArrayList<>();
        Map<Short, String> conti = contiBancariNomi();
        movimenti(importLogId, conti, out);
        eventi(importLogId, conti, out);
        ricorrenti(importLogId, conti, out);
        differiti(importLogId, conti, out);
        scartati(importLogId, conti, out);
        ambiguita(importLogId, conti, out);
        return out;
    }

    /**
     * Primo termine: i movimenti nati da una riga banca di QUESTO import. Il transitorio
     * (39/49.99.999) è «da catalogare» — è la derivazione già viva in ADR 008 — tutto il resto
     * è «a libro».
     */
    private void movimenti(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT m.id, m.data_movimento, m.conto_bancario_id, m.tipo, m.importo_lordo, " +
                "       m.descrizione, p.codice, m.note " +
                "FROM movimenti m JOIN piano_dei_conti_coge p ON p.id = m.conto_coge_id " +
                "WHERE m.fonte_importazione_id = :id AND m.fonte = 'IMPORT_BANCA' " +
                "  AND m.stato <> 'ANNULLATO' " +
                "  AND COALESCE(m.riferimento_esterno,'') NOT LIKE :cassa",
                Map.of("id", importLogId, "cassa", SUFFISSO_CASSA))) {
            String coge = (String) r[6];
            boolean transitorio = COGE_RICAVI_DACLASS.equals(coge) || COGE_COSTI_DACLASS.equals(coge);
            Proposta p = Proposta.leggi((String) r[7]);
            out.add(riga("MOVIMENTO", r[0], r[1], r[2], conti, (String) r[3], (BigDecimal) r[4],
                    (String) r[5],
                    transitorio ? Casa.DA_CATALOGARE : Casa.A_LIBRO,
                    p != null ? "proposto " + p.cogeCodice() + ": " + p.perche()
                              : transitorio ? "nessun segnale: il motore non sa che voce sia" : null));
        }
    }

    private void eventi(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT id, data_movimento, conto_bancario_id, tipo, importo, descrizione_norm, stato, " +
                "       tipo_evento_presunto, CAST(raw_data ->> '_nota_triage' AS text) " +
                "FROM eventi_da_riconciliare WHERE import_log_id = :id AND fonte = 'IMPORT_BANCA'",
                Map.of("id", importLogId))) {
            String stato = (String) r[6];
            // RICONCILIATO: il movimento nasce in EventiService con fonte MANUALE e senza
            // fonte_importazione_id → è FUORI dal primo termine, quindi qui va contato.
            Casa casa = switch (stato) {
                case "DA_RICONCILIARE" -> Casa.DA_CATALOGARE;
                case "SCARTATO" -> Casa.ESCLUSO;
                default -> Casa.A_LIBRO;
            };
            String dettaglio = "SCARTATO".equals(stato) ? (String) r[8]
                    : "incasso evento" + (r[7] == null ? "" : " (" + r[7] + " presunto)");
            out.add(riga("EVENTO", r[0], r[1], r[2], conti, (String) r[3], (BigDecimal) r[4],
                    (String) r[5], casa, dettaglio));
        }
    }

    private void ricorrenti(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT id, data_movimento, conto_bancario_id, tipo, importo, descrizione_norm, stato, " +
                "       tipo_presunto, note " +
                "FROM ricorrenti_da_riconciliare WHERE import_log_id = :id AND fonte = 'IMPORT_BANCA'",
                Map.of("id", importLogId))) {
            String stato = (String) r[6];
            Casa casa = switch (stato) {
                case "DA_RICONCILIARE" -> Casa.DA_CATALOGARE;
                case "IGNORATA" -> Casa.ESCLUSO;
                // COLLEGA → movimenti con fonte RICORRENTE, fuori dall'import (R10b: sono due,
                // la riga bancaria una sola — conta la riga).
                case "RICONCILIATA" -> Casa.A_LIBRO;
                // CONFERMA → createMovimentoImport: già nel primo termine.
                default -> Casa.GIA_CONTATO;
            };
            String dettaglio = "IGNORATA".equals(stato) ? (String) r[8]
                    : "rata" + (r[7] == null ? "" : " (" + r[7] + ")");
            out.add(riga("RICORRENTE", r[0], r[1], r[2], conti, (String) r[3], (BigDecimal) r[4],
                    (String) r[5], casa, dettaglio));
        }
    }

    private void differiti(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT id, data_banca, conto_bancario_id, raw_request->>'tipo', importo, descrizione, stato " +
                "FROM matching_differiti WHERE import_log_id = :id AND fonte = 'IMPORT_BANCA'",
                Map.of("id", importLogId))) {
            String stato = (String) r[6];
            Casa casa = switch (stato) {
                case "DA_RICONCILIARE" -> Casa.DA_CATALOGARE;
                // COLLEGATO: liquida un movimento DA_LIQUIDARE preesistente (fonte MANUALE) → fuori
                // dal primo termine. IGNORATO: crea un movimento d'import → già contato.
                case "COLLEGATO" -> Casa.A_LIBRO;
                default -> Casa.GIA_CONTATO;
            };
            out.add(riga("DIFFERITO", r[0], r[1], r[2], conti, (String) r[3], (BigDecimal) r[4],
                    (String) r[5], casa, "combacia con un movimento già a libro, da liquidare"));
        }
    }

    /**
     * import_scartati non porta la direzione: si ri-deriva dal grezzo col normalizzatore
     * dell'import (stesso principio di {@code ImportTriageService.listScartati}, I3).
     */
    private void scartati(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT id, data_movimento, importo, causale, stato, motivo, riga_numero, " +
                "       CAST(raw_data AS text), note " +
                "FROM import_scartati WHERE import_log_id = :id AND fonte = 'IMPORT_BANCA'",
                Map.of("id", importLogId))) {
            String stato = (String) r[4];
            String motivo = (String) r[5];
            Casa casa = switch (stato) {
                case "DA_VEDERE" -> "SKIP_GIROCONTO".equals(motivo) ? Casa.PARTITA_DI_GIRO : Casa.FUORI_DAI_CONTI;
                case "IGNORATA" -> Casa.ESCLUSO;
                case "DUPLICATA" -> Casa.DUPLICATA;
                default -> Casa.GIA_CONTATO;   // CONTABILIZZATA
            };
            RawMovimento n = rinormalizza((String) r[7], ((Number) r[6]).intValue());
            String dettaglio = "IGNORATA".equals(stato) && r[8] != null ? (String) r[8] : motivoLeggibile(motivo);
            out.add(riga("SCARTATO", r[0], r[1], n == null ? null : n.contoBancarioId(), conti,
                    n == null ? "USCITA" : n.tipo(), (BigDecimal) r[2],
                    n != null && n.descrizione() != null ? n.descrizione() : (String) r[3],
                    casa, dettaglio));
        }
    }

    /** import_ambiguita non porta né direzione né importo: entrambi si ri-derivano dal grezzo. */
    private void ambiguita(UUID importLogId, Map<Short, String> conti, List<RigaImportDTO> out) {
        for (Object[] r : query(
                "SELECT id, stato, riga_numero, CAST(raw_data AS text), motivo, note_operatore " +
                "FROM import_ambiguita WHERE import_log_id = :id AND fonte = 'IMPORT_BANCA'",
                Map.of("id", importLogId))) {
            String stato = (String) r[1];
            Casa casa = switch (stato) {
                case "DA_CLASSIFICARE" -> Casa.DA_CATALOGARE;
                case "SCARTATO" -> Casa.ESCLUSO;
                default -> Casa.GIA_CONTATO;   // CLASSIFICATO → createMovimentoImport
            };
            RawMovimento n = rinormalizza((String) r[3], ((Number) r[2]).intValue());
            String dettaglio = "SCARTATO".equals(stato) && r[5] != null ? (String) r[5] : (String) r[4];
            out.add(riga("AMBIGUITA", r[0],
                    n == null ? null : n.dataMovimento(),
                    n == null ? null : n.contoBancarioId(), conti,
                    n == null ? "USCITA" : n.tipo(),
                    n == null ? BigDecimal.ZERO : n.importo(),
                    n == null ? null : n.descrizione(), casa, dettaglio));
        }
    }

    private RigaImportDTO riga(String origine, Object id, Object data, Object contoRaw,
                               Map<Short, String> conti, String tipo, BigDecimal importo,
                               String causale, Casa casa, String dettaglio) {
        Short conto = contoRaw == null ? null
                : (contoRaw instanceof Short s ? s : ((Number) contoRaw).shortValue());
        return new RigaImportDTO(origine, toUuid(id), toLocalDate(data), conto,
                conto == null ? null : conti.get(conto),
                tipo, importo == null ? BigDecimal.ZERO : importo, causale,
                casa.name(), casa.parola, dettaglio);
    }

    /** Il «perché» detto in italiano: il codice motivo non spiega niente a chi deve decidere. */
    private String motivoLeggibile(String motivo) {
        return switch (motivo == null ? "" : motivo) {
            case "SKIP_CODA_TESTA" -> "incasso POS dell'anno prima: la banca lo accredita adesso, "
                    + "ma la vendita è del periodo precedente";
            case "SKIP_POS" -> "presa per un incasso già registrato da Billy — ma in Billy non c'è";
            case "SKIP_GIROCONTO" -> "trasferimento fra due conti tuoi: né ricavo né costo";
            case "DUPLICATA" -> "già importata da un import precedente";
            default -> "esclusa dall'import con motivo «" + motivo + "»";
        };
    }

    // ── il termine sinistro: misurato all'import, non derivato ────────────────────

    private Object[] universoMisurato(UUID importLogId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT righe_banca, COALESCE(banca_entrate,0), COALESCE(banca_uscite,0) " +
                        "FROM import_log WHERE id = :id")
                .setParameter("id", importLogId).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "IMPORT_NON_TROVATO",
                    "Import log non trovato: " + importLogId);
        }
        return rows.get(0);
    }

    // ── fuori dall'universo: si dichiarano, non si sommano (§5) ───────────────────

    private List<ContatoreImportDTO.VoceFuoriUniverso> fuoriUniverso(UUID importLogId) {
        List<ContatoreImportDTO.VoceFuoriUniverso> out = new ArrayList<>(3);

        for (Object[] r : query(
                "SELECT COUNT(*), COALESCE(SUM(importo_lordo),0) FROM movimenti " +
                "WHERE fonte_importazione_id = :id AND fonte = 'IMPORT_BILLY' AND stato <> 'ANNULLATO'",
                Map.of("id", importLogId))) {
            if (num(r[0]) > 0) out.add(new ContatoreImportDTO.VoceFuoriUniverso(
                    "Contanti Billy", num(r[0]), (BigDecimal) r[1],
                    "creano un movimento in Cassa, ma non sono denaro passato dalle banche"));
        }

        for (Object[] r : query(
                "SELECT COUNT(*), COALESCE(SUM(importo_lordo),0) FROM movimenti " +
                "WHERE fonte_importazione_id = :id AND fonte = 'IMPORT_BANCA' " +
                "  AND riferimento_esterno LIKE :cassa AND stato <> 'ANNULLATO'",
                Map.of("id", importLogId, "cassa", SUFFISSO_CASSA))) {
            if (num(r[0]) > 0) out.add(new ContatoreImportDTO.VoceFuoriUniverso(
                    "Contropartita cassa dei versamenti", num(r[0]), (BigDecimal) r[1],
                    "è il rovescio contabile della stessa riga banca: contarla sarebbe contarla due volte"));
        }

        for (Object[] r : query(
                "SELECT coda_fondo, jsonb_array_length(in_attesa) FROM quadratura_periodo " +
                "WHERE import_log_id = :id",
                Map.of("id", importLogId))) {
            BigDecimal fondo = (BigDecimal) r[0];
            if (fondo != null && fondo.signum() != 0) out.add(new ContatoreImportDTO.VoceFuoriUniverso(
                    "Coda fondo (venduto, non ancora accreditato)", num(r[1]), fondo,
                    "scontrini Billy dopo l'ultima data di accredito: l'incasso arriva nel prossimo estratto conto"));
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Ricostruisce i dati contabili dal grezzo con lo stesso normalizzatore dell'import
     * (DRY: le code non duplicano tipo/importo in colonne). null se il grezzo non è più leggibile.
     */
    public RawMovimento rinormalizza(String rawJson, int riga) {
        try {
            Map<String, String> campi = objectMapper.readValue(rawJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
            return normalizer.normalize(new RawRow(riga, campi));
        } catch (Exception e) {
            return null;
        }
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

    @SuppressWarnings("unchecked")
    private List<Object[]> query(String sql, Map<String, Object> params) {
        var q = em.createNativeQuery(sql);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    private ContatoreImportDTO.Bucket bucket(Map<Casa, long[]> acc, Casa casa) {
        long[] a = acc.get(casa);
        return a == null ? ContatoreImportDTO.Bucket.ZERO
                : new ContatoreImportDTO.Bucket(a[0], euro(a[1]), euro(a[2]));
    }

    private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    private static UUID toUuid(Object o) {
        return o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    /** Centesimi interi, mai float: è la regola di §5 e di RiconciliazioneService. */
    static long cents(BigDecimal v) {
        return v == null ? 0 : v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    static BigDecimal euro(long cents) { return BigDecimal.valueOf(cents, 2); }
}
