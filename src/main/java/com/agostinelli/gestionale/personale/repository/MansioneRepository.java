package com.agostinelli.gestionale.personale.repository;

import com.agostinelli.gestionale.personale.domain.Mansione;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MansioneRepository implements PanacheRepositoryBase<Mansione, UUID> {

    public Optional<Mansione> findByNomeIgnoreCase(String nome) {
        return find("LOWER(nome) = LOWER(?1)", nome.trim()).firstResultOptional();
    }

    public List<Mansione> findAllActive() {
        return list("isActive = true ORDER BY nome");
    }
}
