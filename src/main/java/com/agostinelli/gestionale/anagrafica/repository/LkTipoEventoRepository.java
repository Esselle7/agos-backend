package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.LkTipoEvento;
import com.agostinelli.gestionale.anagrafica.dto.LkTipoEventoDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class LkTipoEventoRepository implements PanacheRepositoryBase<LkTipoEvento, String> {

    @Inject
    EntityManager em;

    public List<LkTipoEventoDTO> findAllTipi() {
        return em.createQuery(
                "SELECT new com.agostinelli.gestionale.anagrafica.dto.LkTipoEventoDTO(t.codice, t.descrizione) " +
                "FROM LkTipoEvento t ORDER BY t.codice",
                LkTipoEventoDTO.class)
                .getResultList();
    }
}
