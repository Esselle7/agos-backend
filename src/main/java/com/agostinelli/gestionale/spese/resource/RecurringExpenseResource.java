package com.agostinelli.gestionale.spese.resource;

import com.agostinelli.gestionale.spese.dto.*;
import com.agostinelli.gestionale.spese.service.RecurringExpenseService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.util.List;
import java.util.UUID;

@Path("/api/spese-ricorrenti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("ADMIN")
public class RecurringExpenseResource {

    @Inject RecurringExpenseService service;
    @Inject EntityManager em;

    // ── Piani ─────────────────────────────────────────────────────────────────

    @GET
    @Path("/piani")
    public List<RecurringExpensePlanSummaryDTO> list() {
        return service.listPlans();
    }

    @POST
    @Path("/piani")
    public Response create(@Valid RecurringExpensePlanCreateRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return Response.status(Response.Status.CREATED)
                .entity(service.createPlan(req, userId))
                .build();
    }

    @GET
    @Path("/piani/{id}")
    public RecurringExpensePlanDetailDTO detail(@PathParam("id") UUID id) {
        return service.getPlanDetail(id);
    }

    @POST
    @Path("/piani/{id}/liquida")
    public RecurringExpensePlanDetailDTO liquidate(@PathParam("id") UUID id,
                                                    LiquidatePlanRequest req,
                                                    @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.liquidatePlan(id, req != null ? req : new LiquidatePlanRequest(null, null), userId);
    }

    @POST
    @Path("/piani/{id}/annulla")
    public RecurringExpensePlanDetailDTO cancel(@PathParam("id") UUID id,
                                                 CancelPlanRequest req,
                                                 @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.cancelPlan(id, req != null ? req : new CancelPlanRequest(null, null), userId);
    }

    // ── Rate ──────────────────────────────────────────────────────────────────

    @PUT
    @Path("/piani/{pianoId}/rate/{rataId}")
    public RecurringExpenseInstallmentDTO updateInstallment(
            @PathParam("pianoId")  UUID pianoId,
            @PathParam("rataId")   UUID rataId,
            @Valid UpdateInstallmentRequest req) {
        return service.updateInstallment(pianoId, rataId, req);
    }

    @POST
    @Path("/piani/{pianoId}/rate/{rataId}/paga")
    public RecurringExpensePlanDetailDTO paga(@PathParam("pianoId") UUID pianoId,
                                               @PathParam("rataId")  UUID rataId,
                                               @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.payInstallment(pianoId, rataId, userId);
    }

    @POST
    @Path("/piani/{pianoId}/rate/{rataId}/skip")
    public Response skip(@PathParam("pianoId") UUID pianoId,
                          @PathParam("rataId")  UUID rataId,
                          @Valid SkipInstallmentRequest req) {
        service.skipInstallment(pianoId, rataId, req);
        return Response.noContent().build();
    }

    // ── Lookup: conti COGE PASSIVITA per il form ───────────────────────────────

    @GET
    @Path("/conti-coge")
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Object[]> contiCogePassivita() {
        return em.createNativeQuery(
                "SELECT id, codice, descrizione FROM piano_dei_conti_coge " +
                "WHERE tipo = 'PASSIVITA' AND is_active = true ORDER BY codice")
                .getResultList();
    }

    // ── Lookup: conti COGE ONERE_FINANZIARIO per il form finanziamento ─────────

    @GET
    @Path("/conti-coge-interessi")
    @Transactional
    @SuppressWarnings("unchecked")
    public List<Object[]> contiCogeInteressi() {
        return em.createNativeQuery(
                "SELECT id, codice, descrizione FROM piano_dei_conti_coge " +
                "WHERE tipo = 'ONERE_FINANZIARIO' AND is_active = true ORDER BY codice")
                .getResultList();
    }
}
