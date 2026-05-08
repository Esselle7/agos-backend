package com.agostinelli.gestionale.spese.repository;

import com.agostinelli.gestionale.spese.domain.RecurringExpensePlan;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RecurringExpensePlanRepository implements PanacheRepositoryBase<RecurringExpensePlan, UUID> {

    public List<RecurringExpensePlan> findAllOrderedByCreatedAt() {
        return findAll(Sort.descending("createdAt")).list();
    }
}
