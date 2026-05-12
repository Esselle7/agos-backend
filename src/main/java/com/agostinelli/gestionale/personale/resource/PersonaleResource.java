package com.agostinelli.gestionale.personale.resource;

import com.agostinelli.gestionale.personale.dto.*;
import com.agostinelli.gestionale.personale.service.PersonaleService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@Path("/api/personale")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class PersonaleResource {

    private static final int MAX_SIZE = 100;

    @Inject
    PersonaleService service;

    @GET
    public PagedResponse<PersonaleSummaryDTO> search(
            @QueryParam("search")     String search,
            @QueryParam("buId")       Short buId,
            @QueryParam("mansione")   String mansione,
            @QueryParam("activeOnly") @DefaultValue("false") boolean activeOnly,
            @QueryParam("page")       @DefaultValue("0")  int page,
            @QueryParam("size")       @DefaultValue("20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return service.search(search, buId, mansione, activeOnly ? Boolean.TRUE : null, page, safeSize);
    }

    @GET
    @Path("/costo-summary")
    public PersonaleCostoSummaryDTO getCostoSummary() {
        return service.getCostoSummary();
    }

    @GET
    @Path("/mansioni")
    public List<String> getMansioni() {
        return service.getMansioni();
    }

    @GET
    @Path("/{id}")
    public PersonaleDTO findById(@PathParam("id") UUID id) {
        return service.findById(id);
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid CreatePersonaleRequest req) {
        PersonaleDTO dto = service.create(req);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public PersonaleDTO update(@PathParam("id") UUID id, @Valid CreatePersonaleRequest req) {
        return service.update(id, req);
    }
}
