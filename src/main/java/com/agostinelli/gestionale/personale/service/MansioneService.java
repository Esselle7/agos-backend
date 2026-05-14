package com.agostinelli.gestionale.personale.service;

import com.agostinelli.gestionale.personale.domain.Mansione;
import com.agostinelli.gestionale.personale.dto.MansioneDTO;
import com.agostinelli.gestionale.personale.repository.MansioneRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;

@ApplicationScoped
public class MansioneService {

    @Inject MansioneRepository repo;

    public List<MansioneDTO> getAll() {
        return repo.findAllActive().stream()
                .map(m -> new MansioneDTO(m.id, m.nome, m.isActive))
                .toList();
    }

    /**
     * Trova una mansione per nome (case-insensitive) oppure la crea se non esiste.
     * Deve essere chiamato all'interno di una transazione attiva.
     */
    @Transactional
    public Mansione findOrCreate(String nome) {
        if (nome == null || nome.isBlank()) return null;
        String trimmed = nome.trim();
        return repo.findByNomeIgnoreCase(trimmed).orElseGet(() -> {
            Mansione m = new Mansione();
            m.nome = trimmed;
            repo.persist(m);
            return m;
        });
    }
}
