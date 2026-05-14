package com.agostinelli.gestionale.personale.service;

import com.agostinelli.gestionale.anagrafica.domain.CentroDiCostoCoan;
import com.agostinelli.gestionale.anagrafica.repository.BusinessUnitRepository;
import com.agostinelli.gestionale.anagrafica.repository.CentroDiCostoCoanRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.personale.domain.Mansione;
import com.agostinelli.gestionale.personale.domain.Personale;
import com.agostinelli.gestionale.personale.dto.*;
import com.agostinelli.gestionale.personale.repository.MansioneRepository;
import com.agostinelli.gestionale.personale.repository.PersonaleRepository;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class PersonaleService {

    @Inject PersonaleRepository repo;
    @Inject MansioneRepository mansioneRepo;
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

    /** Lista mansioni attive dalla tabella centralizzata. */
    public List<String> getMansioni() {
        return mansioneRepo.findAllActive().stream()
                .map(m -> m.nome)
                .toList();
    }

    // ── Helpers privati ───────────────────────────────────────────────────────

    private void applyRequest(Personale p, CreatePersonaleRequest req) {
        p.nome = req.nome();
        p.cognome = req.cognome();
        p.businessUnitId = req.businessUnitId();

        // Mansione: find-or-create nella tabella centralizzata
        if (req.mansione() != null && !req.mansione().isBlank()) {
            Mansione m = findOrCreateMansione(req.mansione().trim());
            p.mansioneId = m.id;
        } else {
            p.mansioneId = null;
        }

        // Centro di costo: derivato automaticamente dalla BU
        p.centroDiCostoId = resolveCentroDiCosto(req.businessUnitId());

        p.costoAziendaleMensile = req.costoAziendaleMensile();
        if (req.isActive() != null) p.isActive = req.isActive();
    }

    /**
     * Trova una mansione per nome (case-insensitive) oppure la crea.
     * Deve essere chiamato all'interno di una transazione già attiva su applyRequest.
     */
    private Mansione findOrCreateMansione(String nome) {
        return mansioneRepo.findByNomeIgnoreCase(nome).orElseGet(() -> {
            Mansione m = new Mansione();
            m.nome = nome;
            mansioneRepo.persist(m);
            return m;
        });
    }

    /**
     * Deriva il primo centro di costo attivo associato alla BU indicata.
     * Restituisce null se la BU è nulla o non ha CDC associati.
     */
    private Integer resolveCentroDiCosto(Short businessUnitId) {
        if (businessUnitId == null) return null;
        @SuppressWarnings("unchecked")
        List<Object[]> result = em.createNativeQuery(
                "SELECT id FROM centri_di_costo_coan WHERE business_unit_id = :buId AND is_active = true ORDER BY id LIMIT 1")
                .setParameter("buId", businessUnitId.intValue())
                .getResultList();
        if (result.isEmpty()) return null;
        Object row = result.get(0);
        // single-column native query returns the scalar directly (not Object[])
        Number val = (row instanceof Object[] arr) ? (Number) arr[0] : (Number) row;
        return val.intValue();
    }

    private PersonaleDTO toDTO(Personale p) {
        String buNome = null;
        if (p.businessUnitId != null) {
            buNome = buRepo.findByIdOptional(p.businessUnitId)
                    .map(bu -> bu.nome)
                    .orElse(null);
        }

        String mansioneNome = null;
        if (p.mansioneId != null) {
            mansioneNome = mansioneRepo.findByIdOptional(p.mansioneId)
                    .map(m -> m.nome)
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
                p.id, p.nome, p.cognome,
                p.mansioneId, mansioneNome,
                p.businessUnitId, buNome,
                p.centroDiCostoId, cdcCodice, cdcDescrizione,
                p.costoAziendaleMensile, p.isActive);
    }
}
