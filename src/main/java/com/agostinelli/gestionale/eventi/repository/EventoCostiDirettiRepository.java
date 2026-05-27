package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.EventoCostoDiretto;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventoCostiDirettiRepository implements PanacheRepositoryBase<EventoCostoDiretto, Long> {

    public List<EventoCostoDiretto> findByEventoId(UUID eventoId) {
        return list("eventoId = ?1 ORDER BY id", eventoId);
    }
}
