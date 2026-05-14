package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.BusinessUnit;
import com.agostinelli.gestionale.anagrafica.dto.BusinessUnitDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class BusinessUnitRepository implements PanacheRepositoryBase<BusinessUnit, Short> {

    @Inject
    EntityManager em;

    public List<BusinessUnitDTO> findAllAttive() {
        return em.createQuery("""
                SELECT new com.agostinelli.gestionale.anagrafica.dto.BusinessUnitDTO(
                    b.id, b.codice, b.nome, b.coloreHex, b.descrizione)
                FROM BusinessUnit b
                WHERE b.isActive = true
                ORDER BY b.id
                """, BusinessUnitDTO.class)
                .getResultList();
    }
}
