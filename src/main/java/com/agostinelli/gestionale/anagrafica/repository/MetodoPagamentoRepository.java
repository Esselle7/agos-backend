package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.MetodoPagamento;
import com.agostinelli.gestionale.anagrafica.dto.MetodoPagamentoDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class MetodoPagamentoRepository implements PanacheRepositoryBase<MetodoPagamento, Integer> {

    @Inject
    EntityManager em;

    public List<MetodoPagamentoDTO> findAllAttivi() {
        return em.createQuery(
                "SELECT new com.agostinelli.gestionale.anagrafica.dto.MetodoPagamentoDTO(m.id, m.codice, m.descrizione) " +
                "FROM MetodoPagamento m WHERE m.isActive = true ORDER BY m.id",
                MetodoPagamentoDTO.class)
                .getResultList();
    }
}
