package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.MatchingDifferitoDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.dto.RisolviMatchingDifferitoRequest;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service per il "Matching differiti" (V11): riconciliazione tra righe bancarie intercettate
 * dall'import e movimenti MANUALI già presenti in stato DA_LIQUIDARE (non ancora liquidi).
 *
 * <p>Meccanismo:
 * <ul>
 *   <li>Fase di import ({@link MovimentoImportService}): prima di persistere una riga banca come
 *       nuovo movimento, si cerca un movimento DA_LIQUIDARE con stesso importo (al centesimo) e
 *       stessa descrizione (normalizzata LOWER+TRIM). Se trovato, la riga banca NON viene
 *       persistita: si scrive una riga in {@code matching_differiti} con il movimento abbinato e
 *       il {@link MovimentoCreateRequest} serializzato in JSONB (per poterlo ricostruire su IGNORA).
 *       Vedi {@link #trovaMatch} e {@link #salvaMatch}.</li>
 *   <li>Fase di smistamento (questo service): l'utente risolve il match dalla UI:
 *       COLLEGA liquida il movimento esistente con i dati della riga banca;
 *       IGNORA crea comunque un nuovo movimento dalla riga banca.</li>
 * </ul>
 *
 * <p>Le rate dei piani di spesa ricorrente NON finiscono qui: lo scheduler le converte in
 * movimenti REGISTRATI alla scadenza (dataFinanziaria sempre valorizzata), quindi non sono MAI
 * DA_LIQUIDARE al momento dell'import.
 *
 * <p>Il matching è efficiente e prestazionale perché pre-carica in memoria una sola volta,
 * all'inizio di ogni import, tutti i movimenti DA_LIQUIDARE aperti (tipicamente poche decine):
 * si costruisce una mappa chiave → lista di UUID dove la chiave è
 * {@code importoCents + "|" + descrizioneNormalizzata}. Ogni riga banca fa una lookup O(1)
 * nella mappa: nessuna query DB per riga. Il costo è una singola SELECT iniziale su una
 * tabella piccola (i movimenti DA_LIQUIDARE aperti).
 */
@ApplicationScoped
public class MatchingDifferitiService {

    @Inject EntityManager em;
    @Inject MovimentiRepository movimentiRepo;
    @Inject MovimentiService movimentiService;
    @Inject MvRefreshService mvRefresh;
    @Inject ObjectMapper objectMapper;

    // ── Fase di import: lookup O(1) e persistenza del match ──────────────────────

    /** Un movimento DA_LIQUIDARE candidato: id + token significativi della descrizione. */
    public record Candidato(UUID id, java.util.Set<String> tokens) {}

    /**
     * Costruisce l'indice in memoria di tutti i movimenti DA_LIQUIDARE aperti (non liquidi),
     * raggruppati per importo al centesimo. Una sola SELECT a inizio import.
     *
     * Il match NON usa l'uguaglianza esatta della descrizione (le banche scrivono
     * "VOSTRA DISPOSIZIONE - VS.DISP. A LEONE S.R.L CARNI", l'utente "Pagamento fornitore LEONE"):
     * non combacerebbero mai. Si confrontano i token significativi (nome controparte), scartando
     * il rumore bancario. È comunque un suggerimento che l'utente conferma dallo smistamento.
     */
    public Map<Long, List<Candidato>> buildIndiceDifferitiAperti() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT id, importo_lordo, COALESCE(descrizione, '') " +
                        "FROM movimenti WHERE stato = 'DA_LIQUIDARE' AND data_finanziaria IS NULL " +
                        "AND data_liquidita IS NOT NULL")
                .getResultList();
        Map<Long, List<Candidato>> idx = new LinkedHashMap<>();
        for (Object[] r : rows) {
            UUID id = toUuid(r[0]);
            long cents = cents((BigDecimal) r[1]);
            idx.computeIfAbsent(cents, k -> new ArrayList<>())
               .add(new Candidato(id, tokens((String) r[2])));
        }
        return idx;
    }

    /**
     * Cerca un movimento DA_LIQUIDARE che combaci con la riga banca: stesso importo al centesimo
     * e almeno un token significativo condiviso (la controparte). Fra più candidati con lo stesso
     * importo, vince quello con più token in comune. Ritorna null se nessuno condivide token.
     *
     * @param idx        indice costruito da {@link #buildIndiceDifferitiAperti}
     * @param importo    importo della riga banca (sempre > 0, abs value dal normalizzatore)
     * @param descrizione descrizione della riga banca
     */
    public UUID trovaMatch(Map<Long, List<Candidato>> idx, BigDecimal importo, String descrizione) {
        if (importo == null) return null;
        List<Candidato> candidati = idx.get(cents(importo));
        if (candidati == null || candidati.isEmpty()) return null;
        java.util.Set<String> tk = tokens(descrizione);
        if (tk.isEmpty()) return null;
        UUID best = null;
        long bestOverlap = 0;
        for (Candidato c : candidati) {
            long overlap = c.tokens().stream().filter(tk::contains).count();
            if (overlap > bestOverlap) { bestOverlap = overlap; best = c.id(); }
        }
        return bestOverlap >= 1 ? best : null;
    }

    /**
     * Persiste una riga in matching_differiti: la riga banca non diventa movimento (evita doppia
     * registrazione), l'utente risolverà dallo smistamento. Salva il MovimentoCreateRequest
     * serializzato in JSONB così su IGNORA può essere ricostruito senza perdere i dati risolti
     * dal mapping engine (coge/BU/fornitore/metodo/riferimento esterno).
     */
    public void salvaMatch(UUID importLogId, UUID movimentoId, MovimentoCreateRequest req,
                           String fonte, int rigaNumero) {
        em.createNativeQuery(
                        "INSERT INTO matching_differiti (id, import_log_id, movimento_id, fonte, riga_numero, " +
                        "data_banca, importo, descrizione, conto_bancario_id, stato, raw_request) " +
                        "VALUES (:id, :logId, :movId, :fonte, :riga, :data, :importo, :descr, :conto, " +
                        "'DA_RICONCILIARE', CAST(:raw AS jsonb))")
                .setParameter("id", UUID.randomUUID())
                .setParameter("logId", importLogId)
                .setParameter("movId", movimentoId)
                .setParameter("fonte", fonte)
                .setParameter("riga", rigaNumero)
                .setParameter("data", req.dataFinanziaria() != null ? req.dataFinanziaria() : req.dataMovimento())
                .setParameter("importo", req.importo())
                .setParameter("descr", req.descrizione())
                .setParameter("conto", req.contoBancarioId())
                .setParameter("raw", toJson(req))
                .executeUpdate();
    }

    /** Importo scalato al centesimo (10.00 == 10.0 == 10.001→10.00). */
    private static long cents(BigDecimal importo) {
        return importo == null ? 0L
                : importo.setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /**
     * Rumore bancario/contabile da ignorare: non identifica la controparte. Lista derivata dalle
     * descrizioni reali BPM/CA ("bonif. vs. favore - bon.da …", "addebito diretto sdd - sdd b2b : …").
     * ponytail: euristica a lista fissa; passare a pesatura per rarità (TF-IDF) solo se emergono
     * falsi match — finora il gate sull'importo esatto + conferma utente lo rendono superfluo.
     */
    private static final java.util.Set<String> STOP = java.util.Set.of(
            "vostra", "vostro", "disposizione", "disposiz", "disp", "pagamento", "pagam",
            "fornitore", "bonifico", "bonif", "favore", "diretto", "addebito", "accredito",
            "mandato", "fattura", "fatt", "causale", "riferimento", "valuta", "conto", "banca",
            "sepa", "sdd", "b2b", "core", "ricevuta", "importo", "saldo", "spett", "spettabile",
            "ditta", "carta", "versamento", "socio", "spa", "srl", "tid", "pos", "iban", "rif",
            "lordo", "netto", "anticipo");

    /**
     * Token significativi di una descrizione: parole ≥3 caratteri (recupera nomi corti come "tim",
     * "eni"), minuscole, escluso il rumore. I numeri puri valgono solo da 4 cifre (così tengo
     * mandati/numeri fattura ma scarto frammenti come "001"/"33").
     */
    private static java.util.Set<String> tokens(String s) {
        if (s == null || s.isBlank()) return java.util.Set.of();
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String t : s.toLowerCase().split("[^a-zà-ÿ0-9]+")) {
            if (t.length() < 3 || STOP.contains(t)) continue;
            if (t.length() < 4 && t.chars().allMatch(Character::isDigit)) continue; // numeri corti = rumore
            out.add(t);
        }
        return out;
    }

    // ── Fase di smistamento: lista e risoluzione ────────────────────────────────

    /** Lista paginata dei matching differiti, opzionalmente filtrata per stato. */
    @SuppressWarnings("unchecked")
    public PagedResponse<MatchingDifferitoDTO> list(String stato, int page, int size) {
        String where = "FROM matching_differiti md " +
                "LEFT JOIN movimenti m ON m.id = md.movimento_id " +
                "WHERE (CAST(:stato AS VARCHAR) IS NULL OR md.stato = :stato)";
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT md.id, md.import_log_id, md.movimento_id, " +
                        "m.tipo, m.data_movimento, m.data_liquidita, m.importo_lordo, " +
                        "m.descrizione, m.stato, m.fonte, " +
                        "md.fonte, md.riga_numero, md.data_banca, md.importo, md.descrizione, " +
                        "md.conto_bancario_id, md.stato, md.note, md.risolto_at, md.risolto_by, md.created_at " +
                        where + " ORDER BY md.created_at DESC, md.id LIMIT :size OFFSET :offset")
                .setParameter("stato", stato)
                .setParameter("size", size)
                .setParameter("offset", (long) page * size)
                .getResultList();

        List<MatchingDifferitoDTO> content = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            content.add(new MatchingDifferitoDTO(
                    toUuid(r[0]), toUuid(r[1]), toUuid(r[2]),
                    (String) r[3],
                    r[4] == null ? null : ((Date) r[4]).toLocalDate(),
                    r[5] == null ? null : ((Date) r[5]).toLocalDate(),
                    (BigDecimal) r[6], (String) r[7], (String) r[8], (String) r[9],
                    (String) r[10], r[11] == null ? null : ((Number) r[11]).intValue(),
                    r[12] == null ? null : ((Date) r[12]).toLocalDate(),
                    (BigDecimal) r[13], (String) r[14],
                    r[15] == null ? null : ((Number) r[15]).shortValue(),
                    (String) r[16], (String) r[17],
                    tsToLocalDate(r[18]),
                    r[19] == null ? null : toUuid(r[19]),
                    tsToLocalDate(r[20])));
        }

        long total = ((Number) em.createNativeQuery("SELECT COUNT(*) " + where)
                .setParameter("stato", stato).getSingleResult()).longValue();
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Risolve un matching differito:
     *  - COLLEGA: liquida il movimento Da Liquidare esistente con i dati della riga banca
     *    (dataFinanziaria = dataBanca, contoBancarioId, metodoPagamentoId, stato = REGISTRATO).
     *    Il movimento riceve la data finanziaria effettiva della riga banca (il giorno in cui i
     *    soldi sono passati davvero sul conto), NON la data di oggi, perché il pagamento è già
     *    avvenuto (lo attesta la riga bancare). Il movimento esce così dallo stato DA_LIQUIDARE
     *    in modo coerente con la liquidazione manuale (vedi MovimentiService.liquidaMovimento).
     *  - IGNORA: crea un nuovo movimento dalla riga banca (falso positivo del match); il
     *    movimento Da Liquidare originale resta aperto. Si usa il MovimentoCreateRequest
     *    serializzato al momento dell'import, così non si perde nulla (coge/BU/fornitore/...).
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @CacheInvalidateAll(cacheName = "import-kpi")
    @Transactional
    public void risolvi(UUID id, RisolviMatchingDifferitoRequest req, UUID userId) {
        List<Object[]> found = em.createNativeQuery(
                        "SELECT movimento_id, data_banca, conto_bancario_id, raw_request, stato, import_log_id " +
                        "FROM matching_differiti WHERE id = :id")
                .setParameter("id", id).getResultList();
        if (found.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "MATCHING_NON_TROVATO",
                    "Matching differito non trovato: " + id);
        }
        Object[] r = found.get(0);
        if (!"DA_RICONCILIARE".equals((String) r[4])) {
            throw new ApiException(Response.Status.CONFLICT, "MATCHING_GIA_RISOLTO",
                    "Il matching è già stato risolto");
        }

        UUID movimentoId = toUuid(r[0]);
        LocalDate dataBanca = r[1] == null ? null : ((Date) r[1]).toLocalDate();
        Short contoBancarioId = null; // r[2] è smallint → Short dal driver? può essere Short o Integer
        if (r[2] != null) contoBancarioId = ((Number) r[2]).shortValue();
        String rawRequestJson = (String) r[3];
        UUID importLogId = toUuid(r[5]);

        String azione = req.azione() == null ? "" : req.azione().toUpperCase();
        switch (azione) {
            case "COLLEGA" -> {
                Movimento m = movimentiRepo.findByIdOptional(movimentoId)
                        .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND,
                                "MOVIMENTO_NON_TROVATO",
                                "Movimento Da Liquidare non trovato: " + movimentoId));
                if (!"DA_LIQUIDARE".equals(m.stato)) {
                    throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_NON_DA_LIQUIDARE",
                            "Il movimento abbinato non è più in stato DA_LIQUIDARE (stato attuale: "
                                    + m.stato + "). Forse è stato liquidato a mano nel frattempo.");
                }
                // Liquida il movimento con i dati della riga banca: la data finanziaria effettiva
                // è la data della riga banca (il giorno in cui i soldi sono passati sul conto),
                // NON oggi. Stato → REGISTRATO.
                m.dataFinanziaria = dataBanca != null ? dataBanca : LocalDate.now();
                m.dataLiquidita = m.dataFinanziaria;
                m.stato = "REGISTRATO";
                m.contoBancarioId = contoBancarioId;
                if (req.metodoPagamentoId() != null) {
                    m.metodoPagamentoId = req.metodoPagamentoId();
                }
                em.merge(m);
                mvRefresh.requestRefreshAfterCommit();
                segnaRisolto(id, "COLLEGATO", req.nota(), userId);
            }
            case "IGNORA" -> {
                // Ricostruisce il MovimentoCreateRequest dal JSONB e crea il movimento.
                MovimentoCreateRequest createReq = fromJson(rawRequestJson, MovimentoCreateRequest.class);
                if (createReq == null) {
                    throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR,
                            "RAW_REQUEST_NON_DECODIFICABILE",
                            "Impossibile ricostruire la richiesta di creazione movimento dal match");
                }
                movimentiService.createMovimentoImport(createReq, userId, importLogId);
                mvRefresh.requestRefreshAfterCommit();
                segnaRisolto(id, "IGNORATO", req.nota(), userId);
            }
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "AZIONE_NON_VALIDA",
                    "Azione non valida: " + req.azione() + " (COLLEGA | IGNORA)");
        }
    }

    private void segnaRisolto(UUID id, String stato, String nota, UUID userId) {
        em.createNativeQuery(
                        "UPDATE matching_differiti SET stato = :stato, note = :nota, " +
                        "risolto_at = now(), risolto_by = :uid WHERE id = :id")
                .setParameter("stato", stato)
                .setParameter("nota", nota)
                .setParameter("uid", userId)
                .setParameter("id", id)
                .executeUpdate();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    /** timestamptz → LocalDate. Il driver può restituire Instant o Timestamp a seconda del tipo. */
    private static LocalDate tsToLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.Instant i) return i.atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private UUID toUuid(Object o) {
        if (o == null) return null;
        return o instanceof UUID u ? u : UUID.fromString(o.toString());
    }
}
