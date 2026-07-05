package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.EventoVoce;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventoVociRepository implements PanacheRepositoryBase<EventoVoce, Long> {

    public List<EventoVoce> findByEventoId(UUID eventoId) {
        return list("eventoId = ?1 ORDER BY id", eventoId);
    }

    /** Rimuove le voci di ricarico collegate a un costo diretto. */
    public long deleteByCostoDirettoId(Long costoDirettoId) {
        return delete("costoDirettoId = ?1", costoDirettoId);
    }
}
