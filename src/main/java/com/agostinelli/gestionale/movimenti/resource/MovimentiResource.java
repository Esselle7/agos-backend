package com.agostinelli.gestionale.movimenti.resource;

import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.importlayer.ImportLogService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import com.agostinelli.gestionale.movimenti.service.ReconciliazioneService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Path("/api/movimenti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MovimentiResource {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    @Inject MovimentiService service;
    @Inject ReconciliazioneService reconciliazioneService;
    @Inject MovimentoImportService importService;
    @Inject ImportLogService importLogService;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public PagedResponse<MovimentoDTO> list(
            @QueryParam("from")           LocalDate from,
            @QueryParam("to")             LocalDate to,
            @QueryParam("tipo")           String tipo,
            @QueryParam("buId")           Short buId,
            @QueryParam("categoriaId")    Long categoriaId,
            @QueryParam("metodoPagamentoId") Integer metodoPagamentoId,
            @QueryParam("stato")          String stato,
            @QueryParam("fornitoreId")    UUID fornitoreId,
            @QueryParam("eventoId")       UUID eventoId,
            @QueryParam("search")         String search,
            @QueryParam("page")  @DefaultValue("0")  int page,
            @QueryParam("size")  @DefaultValue("20") int size,
            @QueryParam("sort")           String sort
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return service.findWithFilters(tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search, page, safeSize, sort);
    }

    @GET
    @Path("/sommario")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentiSommarioDTO sommario(
            @QueryParam("from")              LocalDate from,
            @QueryParam("to")                LocalDate to,
            @QueryParam("tipo")              String tipo,
            @QueryParam("buId")              Short buId,
            @QueryParam("categoriaId")       Long categoriaId,
            @QueryParam("metodoPagamentoId") Integer metodoPagamentoId,
            @QueryParam("stato")             String stato,
            @QueryParam("fornitoreId")       UUID fornitoreId,
            @QueryParam("eventoId")          UUID eventoId,
            @QueryParam("search")            String search
    ) {
        return service.getSommario(tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentoDTO findById(@PathParam("id") UUID id) {
        return service.findById(id);
    }

    @POST
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public Response create(@Valid MovimentoCreateRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        MovimentoDTO dto = service.createMovimento(req, userId);
        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentoDTO update(
            @PathParam("id") UUID id,
            @Valid MovimentoUpdateRequest req,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        boolean isAdmin = ctx.isUserInRole("ADMIN");
        return service.updateMovimento(id, req, userId, isAdmin);
    }

    @PATCH
    @Path("/{id}/liquida")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentoDTO liquida(@PathParam("id") UUID id, @Valid LiquidaRequest req) {
        return service.liquidaMovimento(id, req);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Response annulla(@PathParam("id") UUID id) {
        service.annullaMovimento(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/bulk")
    @RolesAllowed("ADMIN")
    public BulkImportResponse bulkImport(@Valid BulkImportRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.bulkImport(req, userId);
    }

    // ── Riconciliazione ────────────────────────────────────────────────────────

    @GET
    @Path("/riconciliazione/non-riconciliati")
    @RolesAllowed("ADMIN")
    public List<MovimentoDTO> nonRiconciliati() {
        return reconciliazioneService.getMovimentiNonRiconciliati();
    }

    @POST
    @Path("/riconciliazione/{id}/riconcilia")
    @RolesAllowed("ADMIN")
    public Response riconcilia(@PathParam("id") UUID id, @QueryParam("note") String note) {
        reconciliazioneService.segnaRiconciliato(id, note);
        return Response.noContent().build();
    }

    @POST
    @Path("/riconciliazione/match-automatico")
    @RolesAllowed("ADMIN")
    public Response matchAutomatico() {
        int matched = reconciliazioneService.matchAutomatico();
        return Response.ok().entity("{\"matched\":" + matched + "}").build();
    }

    // ── Import ETL (Billy / BPM / CA) ─────────────────────────────────────────

    @POST
    @Path("/import/billy")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("ADMIN")
    public EtlImportResponse importBilly(
            @RestForm("file") java.io.InputStream fileStream,
            @RestForm("filename") String filename,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return importService.importFile(fileStream, filename, "IMPORT_BILLY", userId);
    }

    @POST
    @Path("/import/bpm")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("ADMIN")
    public EtlImportResponse importBpm(
            @RestForm("file") java.io.InputStream fileStream,
            @RestForm("filename") String filename,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return importService.importFile(fileStream, filename, "IMPORT_BANCA_BPM", userId);
    }

    @POST
    @Path("/import/ca")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("ADMIN")
    public EtlImportResponse importCa(
            @RestForm("file") java.io.InputStream fileStream,
            @RestForm("filename") String filename,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return importService.importFile(fileStream, filename, "IMPORT_BANCA_CA", userId);
    }

    @GET
    @Path("/import/history")
    @RolesAllowed("ADMIN")
    public PagedResponse<ImportLogDTO> importHistory(
            @QueryParam("fonte") String fonte,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return importLogService.findHistory(fonte, page, safeSize);
    }

    @GET
    @Path("/import/{importLogId}/ambiguita")
    @RolesAllowed("ADMIN")
    public PagedResponse<AmbiguitaDTO> getAmbiguita(
            @PathParam("importLogId") UUID importLogId,
            @QueryParam("stato") String stato,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return importLogService.getAmbiguita(importLogId, stato, page, safeSize);
    }

    @PUT
    @Path("/import/ambiguita/{id}/classifica")
    @RolesAllowed("ADMIN")
    public Response classificaAmbiguita(
            @PathParam("id") UUID id,
            @Valid ClassificaAmbiguitaRequest req,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        importLogService.classificaAmbiguita(id, req, userId);
        return Response.noContent().build();
    }
}
