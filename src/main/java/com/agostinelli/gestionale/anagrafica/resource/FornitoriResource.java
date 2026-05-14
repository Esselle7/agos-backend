package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.*;
import com.agostinelli.gestionale.anagrafica.service.FornitoriService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.UUID;

@Path("/api/fornitori")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class FornitoriResource {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @Inject
    FornitoriService service;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public PagedResponse<FornitoreSummaryDTO> search(
            @QueryParam("search") @DefaultValue("") String search,
            @QueryParam("page")   @DefaultValue("0")  int page,
            @QueryParam("size")   @DefaultValue("20") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return service.search(search, page, safeSize);
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public FornitoreDTO findById(@PathParam("id") UUID id) {
        return service.findById(id);
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid CreateFornitoreRequest req) {
        FornitoreDTO dto = service.create(req);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public FornitoreDTO update(@PathParam("id") UUID id, @Valid CreateFornitoreRequest req) {
        return service.update(id, req);
    }

    @POST
    @Path("/{id}/alias")
    @RolesAllowed("ADMIN")
    public Response addAlias(@PathParam("id") UUID id, @Valid CreateAliasRequest req) {
        AliasDTO dto = service.addAlias(id, req);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @DELETE
    @Path("/{id}/alias/{aliasId}")
    @RolesAllowed("ADMIN")
    public Response deleteAlias(@PathParam("id") UUID id, @PathParam("aliasId") Integer aliasId) {
        service.deleteAlias(id, aliasId);
        return Response.noContent().build();
    }
}
