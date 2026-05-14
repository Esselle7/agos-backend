package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.AliquotaIvaDTO;
import com.agostinelli.gestionale.anagrafica.repository.AliquotaIvaRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/aliquote-iva")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AliquotaIvaResource {

    @Inject
    AliquotaIvaRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "aliquote-iva")
    public List<AliquotaIvaDTO> listAll() {
        return repo.findAllAttive();
    }
}
