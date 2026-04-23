package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.ContoBancarioDTO;
import com.agostinelli.gestionale.anagrafica.repository.ContiBancariRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/conti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContiResource {

    @Inject
    ContiBancariRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "conti-list")
    public List<ContoBancarioDTO> listAll() {
        return repo.findAllAttiviConSaldo();
    }
}
