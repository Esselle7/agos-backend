package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.LkTipoEventoDTO;
import com.agostinelli.gestionale.anagrafica.repository.LkTipoEventoRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/lookup/tipi-evento")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LkTipoEventoResource {

    @Inject
    LkTipoEventoRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "lk-tipi-evento")
    public List<LkTipoEventoDTO> listAll() {
        return repo.findAllTipi();
    }
}
