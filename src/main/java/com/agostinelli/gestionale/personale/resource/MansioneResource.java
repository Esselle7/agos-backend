package com.agostinelli.gestionale.personale.resource;

import com.agostinelli.gestionale.personale.dto.MansioneDTO;
import com.agostinelli.gestionale.personale.service.MansioneService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/mansioni")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class MansioneResource {

    @Inject MansioneService service;

    @GET
    public List<MansioneDTO> getAll() {
        return service.getAll();
    }
}
