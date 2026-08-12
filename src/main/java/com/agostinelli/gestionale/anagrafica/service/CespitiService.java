package com.agostinelli.gestionale.anagrafica.service;

import com.agostinelli.gestionale.anagrafica.domain.Cespite;
import com.agostinelli.gestionale.anagrafica.dto.CespiteAcquistoRequest;
import com.agostinelli.gestionale.anagrafica.dto.CespiteDTO;
import com.agostinelli.gestionale.anagrafica.dto.CespiteRequest;
import com.agostinelli.gestionale.anagrafica.repository.CespitiRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class CespitiService {

    @Inject CespitiRepository repo;
    @Inject MovimentiRepository movimentiRepo;
    @Inject MvRefreshService mvRefresh;
    @Inject EntityManager em;

    public List<CespiteDTO> listAll() {
        // mappa id_conto -> [codice, descrizione] per arricchire il DTO senza N query
        @SuppressWarnings("unchecked")
        List<Object[]> conti = em.createNativeQuery(
                "SELECT id, codice, descrizione FROM piano_dei_conti_coge").getResultList();
        Map<Integer, String[]> contoMap = conti.stream().collect(Collectors.toMap(
                r -> ((Number) r[0]).intValue(),
                r -> new String[]{(String) r[1], (String) r[2]}));

        // mappa cespite_id -> [movimento_id, stato] del movimento di acquisto (una sola query)
        @SuppressWarnings("unchecked")
        List<Object[]> movs = em.createNativeQuery(
                "SELECT CAST(cespite_id AS text), CAST(id AS text), stato FROM movimenti " +
                "WHERE cespite_id IS NOT NULL AND stato <> 'ANNULLATO'").getResultList();
        Map<UUID, String[]> movMap = movs.stream().collect(Collectors.toMap(
                r -> UUID.fromString((String) r[0]),
                r -> new String[]{(String) r[1], (String) r[2]},
                (a, b) -> a));

        return repo.findAllOrdered().stream().map(c -> toDTO(c, contoMap, movMap.get(c.id))).toList();
    }

    @Transactional
    public CespiteDTO create(CespiteRequest req) {
        Cespite c = new Cespite();
        apply(c, req);
        repo.persist(c);
        return toDTO(c, null, null);
    }

    @Transactional
    public CespiteDTO update(UUID id, CespiteRequest req) {
        Cespite c = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Cespite non trovato"));
        apply(c, req);
        return toDTO(c, null, null);
    }

    /**
     * Acquisto operativo: crea il cespite E il movimento di acquisto CAPEX collegato, in una
     * sola transazione. L'acquisto è capex (immobilizzazione), NON un costo operativo: l'unico
     * impatto P&L è l'ammortamento nel tempo (computeAmmortamenti). Nessun doppio conteggio.
     */
    @Transactional
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-kpi")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-andamento")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    public CespiteDTO registraAcquisto(CespiteAcquistoRequest req, UUID userId) {
        // I1: l'acquisto deve capitalizzarsi su un conto CAPEX, altrimenti diventerebbe un costo
        // operativo (doppio conteggio con l'ammortamento).
        Boolean isCapex = (Boolean) em.createNativeQuery(
                "SELECT is_capex FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", req.contoCogeId())
                .getResultStream().findFirst().orElse(null);
        if (isCapex == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_NON_TROVATO",
                    "Conto COGE non trovato: " + req.contoCogeId());
        }
        if (!isCapex) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_NON_CAPEX",
                    "Il conto dell'acquisto deve essere di investimento (CAPEX), non un costo operativo");
        }

        // Durata (anni) → aliquota a quote costanti
        BigDecimal aliquota = BigDecimal.valueOf(100)
                .divide(BigDecimal.valueOf(req.vitaAnni()), 2, RoundingMode.HALF_UP);

        Cespite c = new Cespite();
        c.descrizione          = req.descrizione();
        c.contoCogeId          = req.contoCogeId();
        c.costoStorico         = req.costoStorico();
        c.aliquotaAmmortamento = aliquota;
        c.dataAcquisto         = req.dataAcquisto();
        c.isActive             = true;
        repo.persist(c);
        em.flush();

        boolean pagato = req.dataPagamento() != null;
        Integer metodoId = req.metodoPagamentoId();
        if (pagato) {
            if (req.contoBancarioId() == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_PAGAMENTO_MANCANTE",
                        "Indica con quale conto/cassa è stato pagato l'acquisto");
            }
            metodoId = verificaFondiEDerivaMetodo(req.contoBancarioId(), req.costoStorico(), metodoId);
        }

        Movimento m = new Movimento();
        m.tipo               = "USCITA";
        m.importo            = req.costoStorico();
        m.importoCommissione = BigDecimal.ZERO;
        m.dataMovimento      = req.dataAcquisto();           // competenza = acquisto
        m.contoCoge          = req.contoCogeId();            // conto CAPEX
        m.businessUnitId     = req.businessUnitId();
        m.cespiteId          = c.id;
        m.fonte              = "MANUALE";
        m.descrizione        = "[CESPITE] " + req.descrizione();
        m.createdBy          = userId;
        if (pagato) {
            m.stato           = "REGISTRATO";
            m.dataFinanziaria = req.dataPagamento();
            m.dataLiquidita   = req.dataPagamento();
            m.contoBancarioId = req.contoBancarioId();
            m.metodoPagamentoId = metodoId;
        } else {
            m.stato           = "DA_LIQUIDARE";
            m.dataFinanziaria = null;
            m.dataLiquidita   = req.dataScadenza() != null ? req.dataScadenza() : req.dataAcquisto();
        }
        movimentiRepo.persist(m);
        em.flush();

        mvRefresh.requestRefreshAfterCommit();
        return toDTO(c, null, new String[]{m.id.toString(), m.stato});
    }

    /**
     * Liquidazione differita di un acquisto rimasto DA_LIQUIDARE (R13): paga ora il movimento di
     * acquisto collegato al cespite, portandolo a REGISTRATO. Stessa guardia fondi di registraAcquisto
     * (fail closed) e stesse invalidazioni cache + refresh MV, così il KPI si aggiorna subito.
     */
    @Transactional
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-kpi")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-andamento")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    public CespiteDTO liquidaAcquisto(UUID cespiteId, com.agostinelli.gestionale.anagrafica.dto.CespiteLiquidazioneRequest req) {
        Cespite c = repo.findByIdOptional(cespiteId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Cespite non trovato"));

        // Movimento di acquisto collegato ancora attivo (cespite_id, non annullato).
        List<Movimento> attivi = movimentiRepo.list("cespiteId = ?1 AND stato <> ?2", cespiteId, "ANNULLATO");
        if (attivi.isEmpty()) {
            throw new ApiException(Response.Status.NOT_FOUND, "MOVIMENTO_ACQUISTO_ASSENTE",
                    "Nessun movimento di acquisto da liquidare per questo cespite");
        }
        Movimento m = attivi.get(0);
        if ("REGISTRATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "ACQUISTO_GIA_LIQUIDATO",
                    "L'acquisto di questo cespite è già stato liquidato");
        }
        if (!"DA_LIQUIDARE".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "STATO_NON_LIQUIDABILE",
                    "Il movimento di acquisto non è in stato DA_LIQUIDARE");
        }
        if (req.contoBancarioId() == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_PAGAMENTO_MANCANTE",
                    "Indica con quale conto/cassa liquidare l'acquisto");
        }

        int metodoId = verificaFondiEDerivaMetodo(req.contoBancarioId(), m.importo, req.metodoPagamentoId());
        LocalDate dataPagamento = req.dataPagamento() != null ? req.dataPagamento() : LocalDate.now();

        m.stato             = "REGISTRATO";
        m.dataFinanziaria   = dataPagamento;
        m.dataLiquidita     = dataPagamento;
        m.contoBancarioId   = req.contoBancarioId();
        m.metodoPagamentoId = metodoId;
        em.flush();

        mvRefresh.requestRefreshAfterCommit();
        return toDTO(c, null, new String[]{m.id.toString(), m.stato});
    }

    /**
     * Guardia fondi condivisa (registraAcquisto + liquidaAcquisto, DRY): verifica che il conto esista
     * e abbia saldo live ≥ importo — fail closed, calcolato al volo con la stessa formula di
     * mv_saldi_conti perché la MV è async e non vede le scritture recenti — poi deriva il metodo dal
     * tipo conto se non indicato (CASSA → CONTANTI, altrimenti BONIFICO). Ritorna il metodo risolto.
     */
    private int verificaFondiEDerivaMetodo(Short contoBancarioId, BigDecimal importo, Integer metodoId) {
        List<?> saldoRows = em.createNativeQuery("""
                SELECT cb.tipo,
                       cb.saldo_iniziale + COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' THEN m.importo_lordo
                                                             WHEN m.tipo='USCITA'  THEN -m.importo_lordo
                                                             ELSE 0 END), 0)
                FROM conti_bancari cb
                LEFT JOIN movimenti m ON m.conto_bancario_id = cb.id AND m.stato <> 'ANNULLATO'
                  AND (cb.data_saldo_iniziale IS NULL
                       OR COALESCE(m.data_finanziaria, m.data_movimento) > cb.data_saldo_iniziale)
                WHERE cb.id = :id
                GROUP BY cb.tipo, cb.saldo_iniziale
                """).setParameter("id", contoBancarioId).getResultList();
        if (saldoRows.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_NON_TROVATO",
                    "Conto di pagamento non trovato: " + contoBancarioId);
        }
        Object[] saldoRow = (Object[]) saldoRows.get(0);
        BigDecimal saldo = (BigDecimal) saldoRow[1];
        if (saldo.compareTo(importo) < 0) {
            throw new ApiException(Response.Status.CONFLICT, "FONDI_INSUFFICIENTI",
                    "Fondi insufficienti sul conto selezionato: saldo EUR " + saldo
                            + ", richiesto EUR " + importo);
        }
        if (metodoId != null) return metodoId;
        String codiceMetodo = "CASSA".equals(saldoRow[0]) ? "CONTANTI" : "BONIFICO";
        return ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = :c")
                .setParameter("c", codiceMetodo).getSingleResult()).intValue();
    }

    /**
     * Elimina un cespite gestendo il movimento di acquisto collegato (I5):
     * - acquisto già liquidato (REGISTRATO) → 409, va disattivato non eliminato;
     * - acquisto DA_LIQUIDARE → annullato (audit preservato) e sganciato, poi elimina;
     * - cespite legacy senza movimento → elimina.
     */
    @Transactional
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-kpi")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-andamento")
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    public void delete(UUID id) {
        Cespite c = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Cespite non trovato"));

        List<Movimento> attivi = movimentiRepo.list("cespiteId = ?1 AND stato <> ?2", id, "ANNULLATO");
        boolean liquidato = attivi.stream().anyMatch(m -> "REGISTRATO".equals(m.stato));
        if (liquidato) {
            throw new ApiException(Response.Status.CONFLICT, "CESPITE_ACQUISTO_LIQUIDATO",
                    "Acquisto già liquidato: disattiva il cespite invece di eliminarlo");
        }
        // Annulla gli acquisti pendenti (DA_LIQUIDARE)
        attivi.forEach(m -> m.stato = "ANNULLATO");
        em.flush();
        // Sgancia la FK (RESTRICT) su tutti i movimenti collegati, inclusi quelli già annullati
        movimentiRepo.update("cespiteId = null WHERE cespiteId = ?1", id);

        repo.delete(c);
        if (!attivi.isEmpty()) mvRefresh.requestRefreshAfterCommit();
    }

    /**
     * Crea al volo una nuova categoria investimento (conto COGE CAPEX) sotto 50.01, così l'utente
     * può aggiungerne una mentre inserisce un cespite. Imposta is_capex=true e tipo COSTO (il CRUD
     * piano-conti generale non lo fa). Ritorna {id, codice, descrizione} del nuovo conto.
     */
    @Transactional
    @io.quarkus.cache.CacheInvalidateAll(cacheName = "piano-dei-conti")
    public com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeDTO creaCategoria(String descrizione) {
        List<?> parentRows = em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = '50.01'")
                .getResultList();
        if (parentRows.isEmpty()) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "NO_PARENT",
                    "Categoria padre 50.01 mancante");
        }
        int parentId = ((Number) parentRows.get(0)).intValue();

        // prossimo codice 50.01.0NN
        List<?> lastRows = em.createNativeQuery(
                "SELECT codice FROM piano_dei_conti_coge WHERE codice LIKE '50.01.%' ORDER BY codice DESC LIMIT 1")
                .getResultList();
        String lastCodice = lastRows.isEmpty() ? null : lastRows.get(0).toString();
        int next = lastCodice == null ? 1
                : Integer.parseInt(lastCodice.substring(lastCodice.lastIndexOf('.') + 1)) + 1;
        String codice = "50.01.%03d".formatted(next);
        String descr = descrizione.trim();

        int id = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id),0)+1 FROM piano_dei_conti_coge")
                .getSingleResult()).intValue();
        em.createNativeQuery(
                "INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, is_capex, parent_id, is_active) " +
                "VALUES (:id, :codice, :descr, 'COSTO', true, :parent, true)")
                .setParameter("id", id)
                .setParameter("codice", codice)
                .setParameter("descr", descr)
                .setParameter("parent", parentId)
                .executeUpdate();
        return new com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeDTO(
                id, codice, descr, "COSTO", parentId, 3);
    }

    private void apply(Cespite c, CespiteRequest req) {
        c.descrizione          = req.descrizione();
        c.contoCogeId          = req.contoCogeId();
        c.costoStorico         = req.costoStorico();
        c.aliquotaAmmortamento = req.aliquotaAmmortamento();
        c.dataAcquisto         = req.dataAcquisto();
        if (req.isActive() != null) c.isActive = req.isActive();
    }

    /** {@code mov} = [movimentoId, stato] del movimento di acquisto, o null se assente. */
    private CespiteDTO toDTO(Cespite c, Map<Integer, String[]> contoMap, String[] mov) {
        String[] conto = contoMap != null ? contoMap.get(c.contoCogeId) : lookupConto(c.contoCogeId);

        BigDecimal mensile = c.costoStorico.multiply(c.aliquotaAmmortamento)
                .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
        BigDecimal annuo = mensile.multiply(BigDecimal.valueOf(12));

        // già ammortizzato a oggi, a quote costanti, capato al costo
        long mesiTrascorsi = Math.max(0, ChronoUnit.MONTHS.between(
                c.dataAcquisto.withDayOfMonth(1), LocalDate.now().withDayOfMonth(1)));
        BigDecimal gia = mensile.multiply(BigDecimal.valueOf(mesiTrascorsi));
        if (gia.compareTo(c.costoStorico) > 0) gia = c.costoStorico;
        BigDecimal residuo = c.costoStorico.subtract(gia);

        return new CespiteDTO(
                c.id, c.descrizione, c.contoCogeId,
                conto != null ? conto[0] : null,
                conto != null ? conto[1] : null,
                c.costoStorico, c.aliquotaAmmortamento, c.dataAcquisto, c.isActive,
                mensile, annuo, gia, residuo,
                mov != null ? UUID.fromString(mov[0]) : null,
                mov != null ? mov[1] : null);
    }

    private String[] lookupConto(Integer contoId) {
        if (contoId == null) return null;
        @SuppressWarnings("unchecked")
        List<Object[]> r = em.createNativeQuery(
                        "SELECT codice, descrizione FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", contoId).getResultList();
        return r.isEmpty() ? null : new String[]{(String) r.get(0)[0], (String) r.get(0)[1]};
    }
}
