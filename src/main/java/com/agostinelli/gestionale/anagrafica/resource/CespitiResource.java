package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.CategoriaCespiteRequest;
import com.agostinelli.gestionale.anagrafica.dto.CespiteAcquistoRequest;
import com.agostinelli.gestionale.anagrafica.dto.CespiteDTO;
import com.agostinelli.gestionale.anagrafica.dto.CespiteLiquidazioneRequest;
import com.agostinelli.gestionale.anagrafica.dto.CespiteRequest;
import com.agostinelli.gestionale.anagrafica.service.CespitiService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

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

    /** Acquisto operativo: crea il cespite + il movimento di acquisto CAPEX collegato. */
    @POST
    @Path("/acquisto")
    @RolesAllowed("ADMIN")
    public Response registraAcquisto(@Valid CespiteAcquistoRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return Response.status(Response.Status.CREATED).entity(service.registraAcquisto(req, userId)).build();
    }

    /** Liquidazione differita: paga ora l'acquisto rimasto DA_LIQUIDARE, portandolo a REGISTRATO. */
    @POST
    @Path("/{id}/liquidazione")
    @RolesAllowed("ADMIN")
    public CespiteDTO liquidaAcquisto(@PathParam("id") UUID id, @Valid CespiteLiquidazioneRequest req) {
        return service.liquidaAcquisto(id, req);
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
