package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.Cespite;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CespitiRepository implements PanacheRepositoryBase<Cespite, UUID> {

    /** Tutti i cespiti, attivi prima, poi per data di acquisto decrescente. */
    public List<Cespite> findAllOrdered() {
        return list("ORDER BY isActive DESC, dataAcquisto DESC");
    }
}
