package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.MetodoPagamentoDTO;
import com.agostinelli.gestionale.anagrafica.repository.MetodoPagamentoRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/metodi-pagamento")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MetodoPagamentoResource {

    @Inject
    MetodoPagamentoRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "metodi-pagamento")
    public List<MetodoPagamentoDTO> listAll() {
        return repo.findAllAttivi();
    }
}
