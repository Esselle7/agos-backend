package com.agostinelli.gestionale.personale.service;

import com.agostinelli.gestionale.anagrafica.domain.CentroDiCostoCoan;
import com.agostinelli.gestionale.anagrafica.repository.BusinessUnitRepository;
import com.agostinelli.gestionale.anagrafica.repository.CentroDiCostoCoanRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.personale.domain.Personale;
import com.agostinelli.gestionale.personale.dto.*;
import com.agostinelli.gestionale.personale.repository.PersonaleRepository;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class PersonaleService {

    @Inject PersonaleRepository repo;
    @Inject BusinessUnitRepository buRepo;
    @Inject CentroDiCostoCoanRepository cdcRepo;
    @Inject EntityManager em;

    public PagedResponse<PersonaleSummaryDTO> search(String query, Short buId, String mansione, Boolean activeOnly, int page, int size) {
        List<PersonaleSummaryDTO> content = repo.search(query, buId, mansione, activeOnly, page, size);
        long total = repo.countSearch(query, buId, mansione, activeOnly);
        return PagedResponse.of(content, page, size, total);
    }

    public PersonaleDTO findById(UUID id) {
        Personale p = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Dipendente non trovato: " + id));
        return toDTO(p);
    }

    @Transactional
    public PersonaleDTO create(CreatePersonaleRequest req) {
        Personale p = new Personale();
        applyRequest(p, req);
        repo.persist(p);
        return toDTO(p);
    }

    @Transactional
    public PersonaleDTO update(UUID id, CreatePersonaleRequest req) {
        Personale p = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Dipendente non trovato: " + id));
        applyRequest(p, req);
        return toDTO(p);
    }

    @SuppressWarnings("unchecked")
    public PersonaleCostoSummaryDTO getCostoSummary() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT p.business_unit_id, b.nome,
                       COUNT(*) as cnt,
                       COALESCE(SUM(p.costo_aziendale_mensile), 0) as totale
                FROM personale p
                LEFT JOIN business_units b ON b.id = p.business_unit_id
                WHERE p.is_active = true
                GROUP BY p.business_unit_id, b.nome
                ORDER BY p.business_unit_id
                """)
                .getResultList();

        BigDecimal costoTotale = BigDecimal.ZERO;
        long totaleAttivi = 0;

        List<PersonaleCostoSummaryDTO.BuCosto> perBu = new java.util.ArrayList<>();
        for (Object[] r : rows) {
            Short buId = r[0] != null ? ((Number) r[0]).shortValue() : null;
            String buNome = (String) r[1];
            long cnt = ((Number) r[2]).longValue();
            BigDecimal costo = (BigDecimal) r[3];
            totaleAttivi += cnt;
            costoTotale = costoTotale.add(costo);
            perBu.add(new PersonaleCostoSummaryDTO.BuCosto(buId, buNome, cnt, costo));
        }

        return new PersonaleCostoSummaryDTO(totaleAttivi, costoTotale, perBu);
    }

    public List<String> getMansioni() {
        return repo.findDistinctMansioni();
    }

    private void applyRequest(Personale p, CreatePersonaleRequest req) {
        p.nome = req.nome();
        p.cognome = req.cognome();
        p.mansione = req.mansione();
        p.businessUnitId = req.businessUnitId();
        p.centroDiCostoId = req.centroDiCostoId();
        p.costoAziendaleMensile = req.costoAziendaleMensile();
        if (req.isActive() != null) p.isActive = req.isActive();
    }

    private PersonaleDTO toDTO(Personale p) {
        String buNome = null;
        if (p.businessUnitId != null) {
            buNome = buRepo.findByIdOptional(p.businessUnitId)
                    .map(bu -> bu.nome)
                    .orElse(null);
        }

        String cdcCodice = null;
        String cdcDescrizione = null;
        if (p.centroDiCostoId != null) {
            CentroDiCostoCoan cdc = cdcRepo.findByIdOptional(p.centroDiCostoId).orElse(null);
            if (cdc != null) {
                cdcCodice = cdc.codice;
                cdcDescrizione = cdc.descrizione;
            }
        }

        return new PersonaleDTO(
                p.id, p.nome, p.cognome, p.mansione,
                p.businessUnitId, buNome,
                p.centroDiCostoId, cdcCodice, cdcDescrizione,
                p.costoAziendaleMensile, p.isActive);
    }
}
