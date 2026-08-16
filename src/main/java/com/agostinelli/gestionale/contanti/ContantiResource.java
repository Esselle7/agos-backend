package com.agostinelli.gestionale.contanti;

import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.UUID;

/**
 * Modulo «Contanti»: un endpoint per operazione, non un CRUD generico (R2).
 *
 * <p>Lista e annullamento <b>non</b> sono qui: {@code GET /api/movimenti?contoId=<cassa>} filtra già
 * per conto (R10, {@code MovimentiFilterQuery.contoId}) e {@code DELETE /api/movimenti/{id}} annulla
 * senza cancellare (R9). Duplicarli sarebbe una seconda strada verso lo stesso dato.
 */
@Path("/api/contanti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContantiResource {

    @Inject ContantiService service;

    /** R1 — saldo calcolato al volo: vede la scrittura appena fatta, la MV asincrona no. */
    @GET
    @Path("/saldo")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public ContantiService.SaldoContanti saldo() {
        return service.saldo();
    }

    @POST
    @Path("/prelievo")
    @RolesAllowed("ADMIN")
    public Response prelievo(@Valid ContantiRequests.Prelievo req, @Context SecurityContext ctx) {
        return creato(service.prelievo(req, utente(ctx)));
    }

    @POST
    @Path("/deposito")
    @RolesAllowed("ADMIN")
    public Response deposito(@Valid ContantiRequests.Deposito req, @Context SecurityContext ctx) {
        return creato(service.deposito(req, utente(ctx)));
    }

    @POST
    @Path("/incasso")
    @RolesAllowed("ADMIN")
    public Response incasso(@Valid ContantiRequests.Incasso req, @Context SecurityContext ctx) {
        return creato(service.incasso(req, utente(ctx)));
    }

    @POST
    @Path("/spesa")
    @RolesAllowed("ADMIN")
    public Response spesa(@Valid ContantiRequests.Spesa req, @Context SecurityContext ctx) {
        return creato(service.spesa(req, utente(ctx)));
    }

    /** 200 e non 201: col contato uguale al teorico non nasce nessuna risorsa (R6). */
    @POST
    @Path("/conta")
    @RolesAllowed("ADMIN")
    public ContantiService.EsitoConta conta(@Valid ContantiRequests.Conta req, @Context SecurityContext ctx) {
        return service.conta(req, utente(ctx));
    }

    private static UUID utente(SecurityContext ctx) {
        return UUID.fromString(ctx.getUserPrincipal().getName());
    }

    private static Response creato(MovimentoDTO dto) {
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }
}
