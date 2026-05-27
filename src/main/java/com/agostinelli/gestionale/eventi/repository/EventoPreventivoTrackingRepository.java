package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.EventoPreventivoTracking;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class EventoPreventivoTrackingRepository implements PanacheRepositoryBase<EventoPreventivoTracking, Long> {

    public List<EventoPreventivoTracking> findByEventoId(UUID eventoId) {
        return list("eventoId = ?1 ORDER BY tipo", eventoId);
    }

    public Optional<EventoPreventivoTracking> findByEventoIdAndTipo(UUID eventoId, String tipo) {
        return find("eventoId = ?1 AND tipo = ?2", eventoId, tipo).firstResultOptional();
    }
}
