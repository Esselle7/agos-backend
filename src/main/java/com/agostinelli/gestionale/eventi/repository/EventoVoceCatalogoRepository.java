package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.EventoVoceCatalogo;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EventoVoceCatalogoRepository implements PanacheRepository<EventoVoceCatalogo> {

    public List<EventoVoceCatalogo> findAttivi() {
        return list("attivo = true ORDER BY isDefault DESC, ordine, label");
    }

    /** Match case-insensitive per l'upsert idempotente delle label. */
    public Optional<EventoVoceCatalogo> findByLabelIgnoreCase(String label) {
        return find("lower(label) = lower(?1)", label).firstResultOptional();
    }
}
