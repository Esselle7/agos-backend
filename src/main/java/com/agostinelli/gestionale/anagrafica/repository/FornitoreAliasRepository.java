package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.FornitoreAlias;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FornitoreAliasRepository implements PanacheRepositoryBase<FornitoreAlias, Integer> {

    public List<FornitoreAlias> findByFornitoreId(UUID fornitoreId) {
        return list("fornitoreId", fornitoreId);
    }
}
