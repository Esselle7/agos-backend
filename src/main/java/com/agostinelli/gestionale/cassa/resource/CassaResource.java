package com.agostinelli.gestionale.cassa.resource;

import com.agostinelli.gestionale.cassa.dto.*;
import com.agostinelli.gestionale.cassa.service.CassaService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.LocalDate;
import java.util.UUID;

@Path("/api/cassa")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class CassaResource {

    private static final int MAX_SIZE = 100;

    @Inject CassaService service;

    @GET
    @Path("/saldo")
    public SaldoResponse getSaldo() {
        return service.getSaldo();
    }

    @GET
    @Path("/movimenti")
    public PagedResponse<CassaMovimentoDTO> list(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to,
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("20") int size
    ) {
        return service.getMovimenti(from, to, page, Math.min(Math.max(size, 1), MAX_SIZE));
    }

    @GET
    @Path("/movimenti/{id}")
    public CassaMovimentoDTO findById(@PathParam("id") UUID id) {
        return service.findById(id);
    }

    @POST
    @Path("/movimenti")
    public Response create(@Valid CreateCassaMovimentoRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        CassaMovimentoDTO dto = service.createMovimentoCassa(req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/movimenti/{id}")
    public CassaMovimentoDTO update(
            @PathParam("id") UUID id,
            @Valid CreateCassaMovimentoRequest req,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.updateMovimentoCassa(id, req, userId);
    }

    @DELETE
    @Path("/movimenti/{id}")
    @RolesAllowed("ADMIN")
    public Response annulla(@PathParam("id") UUID id) {
        service.annullaMovimentoCassa(id);
        return Response.noContent().build();
    }
}
