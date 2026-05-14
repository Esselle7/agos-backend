package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.dto.CategoriaNode;
import com.agostinelli.gestionale.anagrafica.dto.CreateCategoriaRequest;
import com.agostinelli.gestionale.anagrafica.service.CategorieService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/categorie")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CategorieResource {

    @Inject
    CategorieService service;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<CategoriaNode> list(
            @QueryParam("tipo") @DefaultValue("ENTRATA") String tipo,
            @QueryParam("buId") Short buId
    ) {
        if (buId == null) {
            throw new BadRequestException("Il parametro 'buId' è obbligatorio");
        }
        return service.buildTree(tipo, buId);
    }

    @POST
    @RolesAllowed("ADMIN")
    public Response create(@Valid CreateCategoriaRequest req) {
        CategoriaNode node = service.create(req);
        return Response.status(Response.Status.CREATED).entity(node).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public CategoriaNode update(@PathParam("id") Long id, @Valid CreateCategoriaRequest req) {
        return service.update(id, req);
    }
}
