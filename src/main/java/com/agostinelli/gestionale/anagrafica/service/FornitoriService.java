package com.agostinelli.gestionale.anagrafica.service;

import com.agostinelli.gestionale.anagrafica.domain.Fornitore;
import com.agostinelli.gestionale.anagrafica.domain.FornitoreAlias;
import com.agostinelli.gestionale.anagrafica.dto.*;
import com.agostinelli.gestionale.anagrafica.mapper.FornitoreMapper;
import com.agostinelli.gestionale.anagrafica.repository.FornitoreAliasRepository;
import com.agostinelli.gestionale.anagrafica.repository.FornitoriRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FornitoriService {

    @Inject FornitoriRepository fornitoriRepo;
    @Inject FornitoreAliasRepository aliasRepo;
    @Inject FornitoreMapper mapper;

    public PagedResponse<FornitoreSummaryDTO> search(String query, int page, int size) {
        List<FornitoreSummaryDTO> content = fornitoriRepo.searchFullText(query, page, size);
        long total = fornitoriRepo.countFullText(query);
        return PagedResponse.of(content, page, size, total);
    }

    public FornitoreDTO findById(UUID id) {
        Fornitore f = fornitoriRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Fornitore non trovato: " + id));
        List<FornitoreAlias> aliases = aliasRepo.findByFornitoreId(id);
        return mapper.toDTO(f, aliases);
    }

    @Transactional
    public FornitoreDTO create(CreateFornitoreRequest req) {
        Fornitore f = mapper.fromRequest(req);
        fornitoriRepo.persist(f);
        return mapper.toDTO(f, List.of());
    }

    @Transactional
    public FornitoreDTO update(UUID id, CreateFornitoreRequest req) {
        Fornitore f = fornitoriRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Fornitore non trovato: " + id));
        mapper.updateFromRequest(f, req);
        List<FornitoreAlias> aliases = aliasRepo.findByFornitoreId(id);
        return mapper.toDTO(f, aliases);
    }

    @Transactional
    public AliasDTO addAlias(UUID fornitoreId, CreateAliasRequest req) {
        fornitoriRepo.findByIdOptional(fornitoreId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Fornitore non trovato: " + fornitoreId));
        FornitoreAlias alias = new FornitoreAlias();
        alias.fornitoreId = fornitoreId;
        alias.pattern = req.pattern();
        alias.matchType = req.matchType();
        aliasRepo.persist(alias);
        return new AliasDTO(alias.id, alias.pattern, alias.matchType);
    }

    @Transactional
    public void deleteAlias(UUID fornitoreId, Integer aliasId) {
        FornitoreAlias alias = aliasRepo.findByIdOptional(aliasId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Alias non trovato: " + aliasId));
        if (!alias.fornitoreId.equals(fornitoreId)) {
            throw new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Alias non appartiene al fornitore specificato");
        }
        aliasRepo.delete(alias);
    }
}
