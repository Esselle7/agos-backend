package com.agostinelli.gestionale.eventi.resource;

import com.agostinelli.gestionale.eventi.dto.*;
import com.agostinelli.gestionale.eventi.service.EventiService;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/eventi")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EventiResource {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE     = 100;

    @Inject EventiService service;

    // ── CRUD EVENTI ────────────────────────────────────────────────────────────

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public PagedResponse<EventoDTO> list(
            @QueryParam("stato")  String stato,
            @QueryParam("buId")   Short buId,
            @QueryParam("from")   LocalDate from,
            @QueryParam("to")     LocalDate to,
            @QueryParam("search") String search,
            @QueryParam("page")   @DefaultValue("0")  int page,
            @QueryParam("size")   @DefaultValue("20") int size,
            @Context SecurityContext ctx) {

        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        boolean isAdmin = ctx.isUserInRole("ADMIN");
        return service.findWithFilters(stato, buId, from, to, search, page, safeSize, isAdmin);
    }

    @GET
    @Path("/calendario")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<EventoCalendarioDTO> calendario(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to) {
        return service.getCalendario(from, to);
    }

    @GET
    @Path("/dashboard")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public DashboardDTO dashboard(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to) {
        return service.getDashboard(from, to);
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public EventoDTO findById(@PathParam("id") UUID id, @Context SecurityContext ctx) {
        return service.findById(id, ctx.isUserInRole("ADMIN"));
    }

    @POST
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public Response create(@Valid EventoCreateRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        EventoDTO dto = service.createEvento(req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public Response update(
            @PathParam("id") UUID id,
            @Valid EventoUpdateRequest req,
            @Context SecurityContext ctx) {

        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        boolean isAdmin = ctx.isUserInRole("ADMIN");
        EventoDTO dto = service.updateEvento(id, req, userId, isAdmin);
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Response delete(@PathParam("id") UUID id) {
        service.deleteEvento(id);
        return Response.noContent().build();
    }

    // ── PAGAMENTI ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/pagamenti")
    @RolesAllowed("ADMIN")
    public Response registraPagamento(
            @PathParam("id") UUID id,
            @Valid PagamentoRequest req,
            @Context SecurityContext ctx) {

        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        RegistraPagamentoResult result = service.registraPagamento(id, req, userId);
        Response.ResponseBuilder rb = Response.status(Response.Status.CREATED).entity(result.dto());
        if (result.suggestCompletamento()) {
            rb.header("X-Suggest-Completamento", "true");
        }
        return rb.build();
    }

    // ── PDF PREVENTIVO ────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/pdf-preventivo")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public Response pdfPreventivo(@PathParam("id") UUID id) {
        // TODO [v1.1]: implementare generazione PDF con:
        //   - Intestazione con logo Agostinelli e dati evento (nome, data, contatto)
        //   - Tabella pagamenti (tipo, data, importo, metodo, stato)
        //   - Sezione totali (importoTotalePreviventivato, incassato, residuo)
        //   - Sezione partecipanti con costi
        //   - Firma e condizioni contrattuali
        //   Dipendenza suggerita: iText 7 o Apache PDFBox
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(Map.of(
                        "message", "PDF export non ancora implementato",
                        "plannedRelease", "v1.1"))
                .build();
    }

    // ── PARTECIPANTI ──────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/partecipanti")
    @RolesAllowed("ADMIN")
    public Response aggiungiPartecipante(
            @PathParam("id") UUID id,
            @Valid AggiungiPartecipanteRequest req) {

        EventoPartecipanteDTO dto = service.aggiungiPartecipante(id, req);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @GET
    @Path("/{id}/partecipanti")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<EventoPartecipanteDTO> getPartecipanti(@PathParam("id") UUID id) {
        return service.getPartecipantiEvento(id);
    }

    @DELETE
    @Path("/partecipanti/{id}")
    @RolesAllowed("ADMIN")
    public Response rimuoviPartecipante(@PathParam("id") Long id) {
        service.rimuoviPartecipante(id);
        return Response.noContent().build();
    }
}
