package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeDTO;
import com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeUpsertRequest;
import com.agostinelli.gestionale.anagrafica.repository.PianoContiCogeRepository;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/piano-dei-conti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PianoContiCogeResource {

    @Inject
    PianoContiCogeRepository repo;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "piano-dei-conti")
    public List<PianoContiCogeDTO> listAll(@QueryParam("tipo") @DefaultValue("all") String tipo) {
        if ("all".equals(tipo)) {
            return repo.findAllAttivi();
        }
        return repo.findByTipo(tipo.toUpperCase());
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid PianoContiCogeUpsertRequest req) {
        return Response.status(Response.Status.CREATED).entity(repo.create(req)).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public PianoContiCogeDTO update(@PathParam("id") Integer id, @Valid PianoContiCogeUpsertRequest req) {
        return repo.update(id, req);
    }
}
