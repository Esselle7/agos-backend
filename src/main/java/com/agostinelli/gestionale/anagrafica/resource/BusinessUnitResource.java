package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.BusinessUnitDTO;
import com.agostinelli.gestionale.anagrafica.repository.BusinessUnitRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/bu")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BusinessUnitResource {

    @Inject
    BusinessUnitRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "bu-list")
    public List<BusinessUnitDTO> listAll() {
        return repo.findAllAttive();
    }
}
