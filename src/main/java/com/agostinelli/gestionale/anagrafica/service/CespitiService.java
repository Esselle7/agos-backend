package com.agostinelli.gestionale.anagrafica.service;

import com.agostinelli.gestionale.anagrafica.domain.Cespite;
import com.agostinelli.gestionale.anagrafica.dto.CespiteDTO;
import com.agostinelli.gestionale.anagrafica.dto.CespiteRequest;
import com.agostinelli.gestionale.anagrafica.repository.CespitiRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
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
    @Inject EntityManager em;

    public List<CespiteDTO> listAll() {
        // mappa id_conto -> [codice, descrizione] per arricchire il DTO senza N query
        @SuppressWarnings("unchecked")
        List<Object[]> conti = em.createNativeQuery(
                "SELECT id, codice, descrizione FROM piano_dei_conti_coge").getResultList();
        Map<Integer, String[]> contoMap = conti.stream().collect(Collectors.toMap(
                r -> ((Number) r[0]).intValue(),
                r -> new String[]{(String) r[1], (String) r[2]}));

        return repo.findAllOrdered().stream().map(c -> toDTO(c, contoMap)).toList();
    }

    @Transactional
    public CespiteDTO create(CespiteRequest req) {
        Cespite c = new Cespite();
        apply(c, req);
        repo.persist(c);
        return toDTO(c, null);
    }

    @Transactional
    public CespiteDTO update(UUID id, CespiteRequest req) {
        Cespite c = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Cespite non trovato"));
        apply(c, req);
        return toDTO(c, null);
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.deleteById(id)) {
            throw new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Cespite non trovato");
        }
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

    private CespiteDTO toDTO(Cespite c, Map<Integer, String[]> contoMap) {
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
                mensile, annuo, gia, residuo);
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
