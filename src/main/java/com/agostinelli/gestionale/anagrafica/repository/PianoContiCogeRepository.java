package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.PianoContiCoge;
import com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;

@ApplicationScoped
public class PianoContiCogeRepository implements PanacheRepositoryBase<PianoContiCoge, Integer> {

    @Inject
    EntityManager em;

    public List<PianoContiCogeDTO> findAllAttivi() {
        return em.createQuery(
                "SELECT p FROM PianoContiCoge p WHERE p.isActive = true ORDER BY p.codice",
                PianoContiCoge.class)
                .getResultList()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<PianoContiCogeDTO> findByTipo(String tipo) {
        return em.createQuery(
                "SELECT p FROM PianoContiCoge p WHERE p.isActive = true AND p.tipo = :tipo ORDER BY p.codice",
                PianoContiCoge.class)
                .setParameter("tipo", tipo)
                .getResultList()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    private PianoContiCogeDTO toDTO(PianoContiCoge p) {
        int livello = (int) p.codice.chars().filter(c -> c == '.').count() + 1;
        return new PianoContiCogeDTO(p.id, p.codice, p.descrizione, p.tipo, p.parentId, livello);
    }
}
