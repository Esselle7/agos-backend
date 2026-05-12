package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.CentroDiCostoDTO;
import com.agostinelli.gestionale.anagrafica.repository.CentroDiCostoCoanRepository;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/lookup/centri-di-costo")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CentroDiCostoResource {

    @Inject
    CentroDiCostoCoanRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<CentroDiCostoDTO> listAll() {
        return repo.findAllAttivi();
    }
}
