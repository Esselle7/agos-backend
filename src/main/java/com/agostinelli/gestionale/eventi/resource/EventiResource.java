package com.agostinelli.gestionale.eventi.resource;

import com.agostinelli.gestionale.eventi.dto.*;
import com.agostinelli.gestionale.eventi.service.EventiService;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
    private static final String ROLE_ADMIN = "ADMIN";

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

        int safeSize = clampSize(size);
        return service.findWithFilters(stato, buId, from, to, search, page, safeSize, isAdmin(ctx));
    }

    @GET
    @Path("/calendario")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<EventoCalendarioDTO> calendario(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to,
            @Context SecurityContext ctx) {
        return service.getCalendario(from, to, isAdmin(ctx));
    }

    /**
     * KPI dashboard. Visibile a entrambi i ruoli, ma il service nullifica
     * i campi finanziari (totaleIncassato, totaleCosti, profittoTotale)
     * per i DIPENDENTE.
     */
    @GET
    @Path("/dashboard")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public DashboardDTO dashboard(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to,
            @Context SecurityContext ctx) {
        return service.getDashboard(from, to, isAdmin(ctx));
    }

    /**
     * Lista degli eventi a cui il DIPENDENTE è assegnato come partecipante.
     * Riservato al ruolo DIPENDENTE (gli ADMIN usano l'endpoint generico).
     *
     * NOTA path routing: in JAX-RS i segmenti statici hanno priorità sui
     * path parameter, quindi {@code /miei} viene risolto qui e non in
     * {@link #findById(UUID, SecurityContext)}.
     */
    @GET
    @Path("/miei")
    @RolesAllowed("DIPENDENTE")
    public PagedResponse<EventoDTO> getMiei(
            @QueryParam("page") @DefaultValue("0")  int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        int safeSize = clampSize(size);
        return service.getMieiEventi(userId, page, safeSize);
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public EventoDTO findById(@PathParam("id") UUID id, @Context SecurityContext ctx) {
        return service.findById(id, isAdmin(ctx));
    }

    @POST
    @RolesAllowed(ROLE_ADMIN)
    public Response create(@Valid EventoCreateRequest req, @Context SecurityContext ctx) {
        UUID userId = currentUserId(ctx);
        EventoDTO dto = service.createEvento(req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed(ROLE_ADMIN)
    public Response update(
            @PathParam("id") UUID id,
            @Valid EventoUpdateRequest req,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        EventoDTO dto = service.updateEvento(id, req, userId, isAdmin(ctx));
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed(ROLE_ADMIN)
    public Response delete(@PathParam("id") UUID id) {
        service.deleteEvento(id);
        return Response.noContent().build();
    }

    // ── PAGAMENTI ─────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/pagamenti")
    @RolesAllowed(ROLE_ADMIN)
    public Response registraPagamento(
            @PathParam("id") UUID id,
            @Valid PagamentoRequest req,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        RegistraPagamentoResult result = service.registraPagamento(id, req, userId);
        Response.ResponseBuilder rb = Response.status(Response.Status.CREATED).entity(result.dto());
        if (result.suggestCompletamento()) {
            rb.header("X-Suggest-Completamento", "true");
        }
        return rb.build();
    }

    // ── COSTI DIRETTI ─────────────────────────────────────────────────────────

    @GET
    @Path("/{id}/costi-diretti")
    @RolesAllowed(ROLE_ADMIN)
    public List<EventoCostoDirettoDTO> getCostiDiretti(@PathParam("id") UUID id) {
        return service.getCostiDiretti(id);
    }

    @POST
    @Path("/{id}/costi-diretti")
    @RolesAllowed(ROLE_ADMIN)
    public Response aggiungiCostoDiretto(
            @PathParam("id") UUID id,
            @Valid EventoCostoDirettoRequest req,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        EventoCostoDirettoDTO dto = service.aggiungiCostoDiretto(id, req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @DELETE
    @Path("/{id}/costi-diretti/{costoId}")
    @RolesAllowed(ROLE_ADMIN)
    public Response rimuoviCostoDiretto(
            @PathParam("id") UUID id,
            @PathParam("costoId") Long costoId,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        service.rimuoviCostoDiretto(costoId, userId);
        return Response.noContent().build();
    }

    // ── MONITORING PREVENTIVATO ─────────────────────────────────────────────────

    @GET
    @Path("/{id}/preventivo-tracking")
    @RolesAllowed(ROLE_ADMIN)
    public List<EventoPreventivoTrackingDTO> getPreventivoTracking(@PathParam("id") UUID id) {
        return service.getPreventivoTracking(id);
    }

    @POST
    @Path("/{id}/preventivo-tracking")
    @RolesAllowed(ROLE_ADMIN)
    public Response salvaPreventivoTracking(
            @PathParam("id") UUID id,
            @Valid EventoPreventivoTrackingRequest req,
            @Context SecurityContext ctx) {

        UUID userId = currentUserId(ctx);
        EventoPreventivoTrackingDTO dto = service.salvaPreventivoTracking(id, req, userId);
        return Response.ok(dto).build();
    }

    @DELETE
    @Path("/{id}/preventivo-tracking/{trackingId}")
    @RolesAllowed(ROLE_ADMIN)
    public Response rimuoviPreventivoTracking(
            @PathParam("id") UUID id,
            @PathParam("trackingId") Long trackingId) {
        service.rimuoviPreventivoTracking(trackingId);
        return Response.noContent().build();
    }

    // ── MENU PDF ──────────────────────────────────────────────────────────────

    @POST
    @Path("/{id}/menu-pdf")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed(ROLE_ADMIN)
    public Response uploadMenuPdf(
            @PathParam("id") UUID id,
            @RestForm("file") FileUpload file) {

        if (file == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "FILE_MANCANTE",
                    "Nessun file caricato nel campo 'file'");
        }
        try (InputStream in = Files.newInputStream(file.uploadedFile())) {
            String url = service.uploadMenuPdf(id, in, file.size(), file.contentType());
            return Response.ok(Map.of("menuPdfUrl", url)).build();
        } catch (IOException ex) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "UPLOAD_ERROR",
                    "Errore durante la lettura del file caricato");
        }
    }

    @GET
    @Path("/{id}/menu-pdf")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public Response getMenuPdf(@PathParam("id") UUID id) {
        EventiService.MenuResult result = service.getMenuStream(id);
        return Response.ok(result.stream(), result.mimeType())
                .header("Content-Disposition", "inline; filename=\"" + result.filename() + "\"")
                .build();
    }

    @DELETE
    @Path("/{id}/menu-pdf")
    @RolesAllowed(ROLE_ADMIN)
    public Response deleteMenuPdf(@PathParam("id") UUID id) {
        service.deleteMenuPdf(id);
        return Response.noContent().build();
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
    @RolesAllowed(ROLE_ADMIN)
    public Response aggiungiPartecipante(
            @PathParam("id") UUID id,
            @Valid AggiungiPartecipanteRequest req) {

        EventoPartecipanteDTO dto = service.aggiungiPartecipante(id, req);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    /**
     * Lista partecipanti di un evento. Il campo {@code costo} è nascosto
     * ai DIPENDENTE (impostato a {@code null}) per non esporre informazioni
     * di costo del personale a colleghi.
     */
    @GET
    @Path("/{id}/partecipanti")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public List<EventoPartecipanteDTO> getPartecipanti(
            @PathParam("id") UUID id,
            @Context SecurityContext ctx) {
        return service.getPartecipantiEvento(id, isAdmin(ctx));
    }

    @DELETE
    @Path("/partecipanti/{id}")
    @RolesAllowed(ROLE_ADMIN)
    public Response rimuoviPartecipante(@PathParam("id") Long id) {
        service.rimuoviPartecipante(id);
        return Response.noContent().build();
    }

    /** Alloca/aggiorna le ore di un partecipante ORARIA → genera il movimento di costo. */
    @POST
    @Path("/partecipanti/{partecipanteId}/ore")
    @RolesAllowed(ROLE_ADMIN)
    public EventoPartecipanteDTO allocaOre(
            @PathParam("partecipanteId") Long partecipanteId,
            @Valid AllocaOreRequest req,
            @Context SecurityContext ctx) {
        return service.allocaOre(partecipanteId, req.ore(), currentUserId(ctx));
    }

    /** Rimuove l'allocazione ore → annulla il movimento di costo. */
    @DELETE
    @Path("/partecipanti/{partecipanteId}/ore")
    @RolesAllowed(ROLE_ADMIN)
    public EventoPartecipanteDTO rimuoviOre(@PathParam("partecipanteId") Long partecipanteId) {
        return service.rimuoviOre(partecipanteId);
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private static boolean isAdmin(SecurityContext ctx) {
        return ctx.isUserInRole(ROLE_ADMIN);
    }

    private static UUID currentUserId(SecurityContext ctx) {
        return UUID.fromString(ctx.getUserPrincipal().getName());
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_SIZE);
    }
}
