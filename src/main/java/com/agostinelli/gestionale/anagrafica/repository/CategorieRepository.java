package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.Categoria;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
public class CategorieRepository implements PanacheRepository<Categoria> {

    @Inject
    EntityManager em;

    // Projection record usata per caricare la lista flat senza accedere alle relazioni JPA
    public record CategoriaFlat(Long id, String nome, String tipo, Long parentId, Short buId, int ordinamento) {}

    public List<CategoriaFlat> findFlatByTipoAndBuId(String tipo, Short buId) {
        return em.createQuery("""
                SELECT new com.agostinelli.gestionale.anagrafica.repository.CategorieRepository$CategoriaFlat(
                    c.id, c.nome, c.tipo, c.parentId, c.buId, c.ordinamento)
                FROM Categoria c
                WHERE c.tipo = :tipo AND c.buId = :buId AND c.isActive = true
                ORDER BY c.ordinamento, c.id
                """, CategoriaFlat.class)
                .setParameter("tipo", tipo)
                .setParameter("buId", buId)
                .getResultList();
    }

    public List<CategoriaFlat> findFlatSottocategorie(Long parentId) {
        return em.createQuery("""
                SELECT new com.agostinelli.gestionale.anagrafica.repository.CategorieRepository$CategoriaFlat(
                    c.id, c.nome, c.tipo, c.parentId, c.buId, c.ordinamento)
                FROM Categoria c
                WHERE c.parentId = :parentId AND c.isActive = true
                ORDER BY c.ordinamento, c.id
                """, CategoriaFlat.class)
                .setParameter("parentId", parentId)
                .getResultList();
    }
}
