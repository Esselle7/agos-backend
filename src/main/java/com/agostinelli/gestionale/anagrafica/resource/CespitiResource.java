package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.CategoriaCespiteRequest;
import com.agostinelli.gestionale.anagrafica.dto.CespiteDTO;
import com.agostinelli.gestionale.anagrafica.dto.CespiteRequest;
import com.agostinelli.gestionale.anagrafica.service.CespitiService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/cespiti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CespitiResource {

    @Inject CespitiService service;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<CespiteDTO> listAll() {
        return service.listAll();
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid CespiteRequest req) {
        return Response.status(Response.Status.CREATED).entity(service.create(req)).build();
    }

    /** Crea al volo una nuova categoria investimento (conto CAPEX 50.01.x) per i cespiti. */
    @POST
    @Path("/categoria")
    @RolesAllowed("ADMIN")
    public Response creaCategoria(@Valid CategoriaCespiteRequest req) {
        return Response.status(Response.Status.CREATED).entity(service.creaCategoria(req.descrizione())).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public CespiteDTO update(@PathParam("id") UUID id, @Valid CespiteRequest req) {
        return service.update(id, req);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Response delete(@PathParam("id") UUID id) {
        service.delete(id);
        return Response.noContent().build();
    }
}
