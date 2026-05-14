package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.CentroDiCostoCoan;
import com.agostinelli.gestionale.anagrafica.dto.CentroDiCostoDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class CentroDiCostoCoanRepository implements PanacheRepositoryBase<CentroDiCostoCoan, Integer> {

    @Inject
    EntityManager em;

    public List<CentroDiCostoDTO> findAllAttivi() {
        return em.createQuery("""
                SELECT new com.agostinelli.gestionale.anagrafica.dto.CentroDiCostoDTO(
                    c.id, c.codice, c.descrizione, c.businessUnitId)
                FROM CentroDiCostoCoan c
                WHERE c.isActive = true
                ORDER BY c.id
                """, CentroDiCostoDTO.class)
                .getResultList();
    }
}
