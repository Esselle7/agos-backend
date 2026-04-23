package com.agostinelli.gestionale.anagrafica.service;

import com.agostinelli.gestionale.anagrafica.domain.Categoria;
import com.agostinelli.gestionale.anagrafica.dto.CategoriaNode;
import com.agostinelli.gestionale.anagrafica.dto.CreateCategoriaRequest;
import com.agostinelli.gestionale.anagrafica.repository.CategorieRepository;
import com.agostinelli.gestionale.anagrafica.repository.CategorieRepository.CategoriaFlat;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.util.*;

@ApplicationScoped
public class CategorieService {

    @Inject
    CategorieRepository repo;

    @CacheResult(cacheName = "categorie-tree")
    public List<CategoriaNode> buildTree(String tipo, Short buId) {
        List<CategoriaFlat> flat = repo.findFlatByTipoAndBuId(tipo, buId);
        return buildTreeFromFlat(flat);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "categorie-tree")
    public CategoriaNode create(CreateCategoriaRequest req) {
        validateCategoria(req.tipo(), req.buId(), req.parentId());
        Categoria cat = new Categoria();
        applyRequest(cat, req);
        repo.persist(cat);
        return toNode(cat);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "categorie-tree")
    public CategoriaNode update(Long id, CreateCategoriaRequest req) {
        Categoria cat = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Categoria non trovata: " + id));
        validateCategoria(req.tipo(), req.buId(), req.parentId());
        applyRequest(cat, req);
        return toNode(cat);
    }

    // Valida che il parentId (se presente) abbia stesso tipo e stessa BU
    private void validateCategoria(String tipo, Short buId, Long parentId) {
        if (parentId == null) return;
        repo.findByIdOptional(parentId).ifPresentOrElse(parent -> {
            if (!parent.tipo.equals(tipo)) {
                throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_PARENT",
                        "Il parent deve avere lo stesso tipo (" + tipo + ")");
            }
            if (!parent.buId.equals(buId)) {
                throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_PARENT",
                        "Il parent deve appartenere alla stessa BU (" + buId + ")");
            }
        }, () -> {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_PARENT", "Parent categoria non trovata: " + parentId);
        });
    }

    private void applyRequest(Categoria cat, CreateCategoriaRequest req) {
        cat.nome = req.nome();
        cat.tipo = req.tipo();
        cat.parentId = req.parentId();
        cat.buId = req.buId();
        cat.ordinamento = req.ordinamento();
    }

    private CategoriaNode toNode(Categoria cat) {
        return new CategoriaNode(cat.id, cat.nome, cat.tipo, cat.buId, cat.ordinamento);
    }

    // Costruisce l'albero in memoria: zero accessi JPA dopo il caricamento flat
    static List<CategoriaNode> buildTreeFromFlat(List<CategoriaFlat> flat) {
        Map<Long, CategoriaNode> nodeMap = new LinkedHashMap<>();
        for (CategoriaFlat f : flat) {
            nodeMap.put(f.id(), new CategoriaNode(f.id(), f.nome(), f.tipo(), f.buId(), f.ordinamento()));
        }
        List<CategoriaNode> roots = new ArrayList<>();
        for (CategoriaFlat f : flat) {
            CategoriaNode node = nodeMap.get(f.id());
            if (f.parentId() == null || !nodeMap.containsKey(f.parentId())) {
                roots.add(node);
            } else {
                nodeMap.get(f.parentId()).sottocategorie.add(node);
            }
        }
        return roots;
    }
}
