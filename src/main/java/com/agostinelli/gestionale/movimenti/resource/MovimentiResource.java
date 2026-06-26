package com.agostinelli.gestionale.movimenti.resource;

import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.importlayer.ImportLogService;
import com.agostinelli.gestionale.movimenti.importlayer.MovimentoImportService;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
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
    /**
     * Cap più alto per le liste del centro di smistamento (transitori/eventi): sono code di
     * lavorazione interne che vanno mostrate INTERE, non paginate a 100 (altrimenti restano
     * righe non catalogabili dalla UI). Vedi ANALISI-IMPORT "troncamento lista transitori".
     */
    private static final int MAX_TRIAGE_SIZE = 2000;

    @Inject MovimentiService service;
    @Inject MovimentoImportService importService;
    @Inject ImportLogService importLogService;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.ImportTriageService triageService;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordLearningService keywordService;
    @Inject com.agostinelli.gestionale.movimenti.importlayer.MatchingDifferitiService matchingDifferitiService;

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

    // Movimenti attivi senza conto/cassa: da attribuire a mano (popup "Situazione Finanziaria").
    // Path literale prima di /{id} per non farlo intercettare dal template.
    @GET
    @Path("/senza-banca")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public java.util.List<MovimentoDTO> senzaBanca() {
        return service.listSenzaBanca();
    }

    @GET
    @Path("/{id}")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentoDTO findById(@PathParam("id") UUID id) {
        return service.findById(id);
    }

    // ── Feature 1: movimenti "Da liquidare" in ritardo ──────────────────────────
    // Lista paginata dei movimenti manuali in stato DA_LIQUIDARE con scadenza passata.
    // Il campo derivato giorniAllaScadenza (sul MovimentoDTO) è negativo e rappresenta
    // il ritardo in giorni: per USCITA = "sei in ritardo sul pagamento",
    // per ENTRATA = "qualcuno è in ritardo nel pagarmi".
    // Le rate dei piani di spesa ricorrente NON compaiono qui (lo scheduler le liquida
    // alla scadenza, quindi sono sempre REGISTRATE e non DA_LIQUIDARE).
    @GET
    @Path("/da-liquidare-in-ritardo")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public PagedResponse<MovimentoDTO> daLiquidareInRitardo(
            @QueryParam("tipo") String tipo,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") String sort) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return service.findDaLiquidareInRitardo(tipo, page, safeSize, sort);
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

    // ── Import ETL CONGIUNTO (Billy + BPM + CA) ───────────────────────────────────────
    // L'import single-file (billy/bpm/ca) è stato RIMOSSO (PROMPT-KEYWORD-LEARNING.md §4.9):
    // si importa solo congiunto. Il metodo interno importFile resta per i test (non più REST).

    /**
     * Import ETL CONGIUNTO: i 3 file (Billy + BPM + CA) dello stesso periodo, OBBLIGATORI,
     * caricati e riconciliati insieme (REFACTOR-IMPORT-CONGIUNTO). Una sola operazione di
     * import (rollback atomico). Unica modalità di import disponibile.
     */
    @POST
    @Path("/import/congiunto")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @RolesAllowed("ADMIN")
    public EtlImportResponse importCongiunto(
            @RestForm("billy") java.io.InputStream billy,
            @RestForm("bpm") java.io.InputStream bpm,
            @RestForm("ca") java.io.InputStream ca,
            @RestForm("filenameBilly") String fnBilly,
            @RestForm("filenameBpm") String fnBpm,
            @RestForm("filenameCa") String fnCa,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return importService.importCongiunto(billy, bpm, ca, fnBilly, fnBpm, fnCa, userId);
    }

    @DELETE
    @Path("/import/{importLogId}/rollback")
    @RolesAllowed("ADMIN")
    public java.util.Map<String, Object> rollbackImport(@PathParam("importLogId") UUID importLogId) {
        return importService.rollbackImport(importLogId);
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

    // ── Triage assistito / KPI / regole data-driven (ETL v2 §8/§9/§13) ──────────

    @GET
    @Path("/import/kpi")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO importKpi() {
        return triageService.getKpi();
    }

    @GET
    @Path("/import/eventi/analisi-duplicati")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.AnalisiDuplicatiDTO analisiDuplicati() {
        return triageService.analisiDuplicati();
    }

    @GET
    @Path("/import/regole")
    @RolesAllowed("ADMIN")
    public List<com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO> listRegole() {
        return triageService.listRegole();
    }

    @POST
    @Path("/import/regole")
    @RolesAllowed("ADMIN")
    public Response createRegola(com.agostinelli.gestionale.movimenti.dto.RegolaClassificazioneDTO regola) {
        Integer id = triageService.createRegola(regola);
        return Response.status(Response.Status.CREATED).entity(java.util.Map.of("id", id)).build();
    }

    @PUT
    @Path("/import/regole/{id}/attiva")
    @RolesAllowed("ADMIN")
    public Response setRegolaAttiva(@PathParam("id") int id, @QueryParam("attiva") @DefaultValue("true") boolean attiva) {
        triageService.setRegolaAttiva(id, attiva);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/import/regole/{id}")
    @RolesAllowed("ADMIN")
    public Response deleteRegola(@PathParam("id") int id) {
        triageService.deleteRegola(id);
        return Response.noContent().build();
    }

    // ── Centro smistamento: movimenti transitori (da catalogare) ────────────────

    @GET
    @Path("/import/transitori")
    @RolesAllowed("ADMIN")
    public PagedResponse<TransitorioDTO> listTransitori(
            @QueryParam("tipo") String tipo,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return triageService.listTransitori(tipo, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    @PUT
    @Path("/import/transitori/{movimentoId}/classifica")
    @RolesAllowed("ADMIN")
    public Response classificaTransitorio(
            @PathParam("movimentoId") UUID movimentoId,
            @Valid ClassificaTransitorioRequest req) {
        triageService.classificaTransitorio(movimentoId, req);
        return Response.noContent().build();
    }

    // ── Centro smistamento: eventi parcheggiati ──────────────────────────────────

    @GET
    @Path("/import/eventi")
    @RolesAllowed("ADMIN")
    public PagedResponse<EventoParcheggiatoDTO> listEventi(
            @QueryParam("stato") @DefaultValue("DA_RICONCILIARE") String stato,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return triageService.listEventi(stato, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    @PUT
    @Path("/import/eventi/{id}/risolvi")
    @RolesAllowed("ADMIN")
    public Response risolviEvento(
            @PathParam("id") UUID id,
            @Valid RisolviEventoRequest req,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        triageService.risolviEvento(id, req, userId);
        return Response.noContent().build();
    }

    // ── Parcheggio spese ricorrenti / finanziamenti (V9) ──────────────────────────────

    @GET
    @Path("/import/ricorrenti")
    @RolesAllowed("ADMIN")
    public PagedResponse<RicorrenteParcheggiataDTO> listRicorrenti(
            @QueryParam("stato") @DefaultValue("DA_RICONCILIARE") String stato,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return triageService.listRicorrenti(stato, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    @PUT
    @Path("/import/ricorrenti/{id}/risolvi")
    @RolesAllowed("ADMIN")
    public Response risolviRicorrente(@PathParam("id") UUID id, RisolviRicorrenteRequest req,
                                      @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        triageService.risolviRicorrente(id, req, userId);
        return Response.noContent().build();
    }

    // ── Feature 2 — Matching differiti (V11): righe banca che combaciano con ────
    //    movimenti DA_LIQUIDARE già presenti in gestionale (match su importo al centesimo +
    //    descrizione uguale). Evita la doppia registrazione: la riga banca NON diventa movimento
    //    in fase di import, viene parcheggiata qui. L'utente risolve dallo smistamento scegliendo
    //    COLLEGA (liquida il movimento esistente con i dati della riga banca) oppure
    //    IGNORA (crea comunque un nuovo movimento dalla riga banca — falso positivo del match).

    @GET
    @Path("/import/matching-differiti")
    @RolesAllowed("ADMIN")
    public PagedResponse<MatchingDifferitoDTO> listMatchingDifferiti(
            @QueryParam("stato") @DefaultValue("DA_RICONCILIARE") String stato,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return matchingDifferitiService.list(stato, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    @PUT
    @Path("/import/matching-differiti/{id}/risolvi")
    @RolesAllowed("ADMIN")
    public Response risolviMatchingDifferito(@PathParam("id") UUID id,
                                             @Valid RisolviMatchingDifferitoRequest req,
                                             @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        matchingDifferitiService.risolvi(id, req, userId);
        return Response.noContent().build();
    }

    // ── Vista Effetti / RiBa da catalogare (transitori filtrati) ──────────────────────

    @GET
    @Path("/import/transitori/riba")
    @RolesAllowed("ADMIN")
    public PagedResponse<TransitorioDTO> listRibaTransitori(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return triageService.listRibaTransitori(page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    // ── Pannello di quadratura di periodo (sostituisce "Incassi POS da ripartire") ──────
    // PROMPT-RICONCILIAZIONE-PERIODO §5: i ricavi POS nascono da Billy; qui si mostra solo il
    // controllo di quadratura Σ Billy ↔ Σ POS banca dell'ultimo import congiunto (o di un import
    // specifico via ?importLogId=). Restituisce 204 se non c'è ancora nessuna quadratura.
    @GET
    @Path("/import/quadratura")
    @RolesAllowed("ADMIN")
    public Response getQuadratura(@QueryParam("importLogId") UUID importLogId) {
        QuadraturaPeriodoDTO q = triageService.getQuadratura(importLogId);
        return q == null ? Response.noContent().build() : Response.ok(q).build();
    }

    // ── Gestione Keyword (pagina dedicata, PROMPT-KEYWORD-LEARNING.md §4.8) ──────────────

    @GET
    @Path("/keyword")
    @RolesAllowed("ADMIN")
    public List<KeywordFirmaDTO> listKeyword(@QueryParam("natura") String natura,
                                             @QueryParam("stato") String stato) {
        return keywordService.listFirme(natura, stato);
    }

    @POST
    @Path("/keyword")
    @RolesAllowed("ADMIN")
    public Response createKeyword(@Valid KeywordFirmaDTO d) {
        UUID id = keywordService.createFirma(d);
        return Response.status(Response.Status.CREATED).entity(java.util.Map.of("id", id)).build();
    }

    @PUT
    @Path("/keyword/{id}")
    @RolesAllowed("ADMIN")
    public Response updateKeyword(@PathParam("id") UUID id, @Valid KeywordFirmaDTO d) {
        keywordService.updateFirma(id, d);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/keyword/{id}")
    @RolesAllowed("ADMIN")
    public Response deleteKeyword(@PathParam("id") UUID id) {
        keywordService.deleteFirma(id);
        return Response.noContent().build();
    }

    @POST
    @Path("/keyword/anteprima")
    @RolesAllowed("ADMIN")
    public KeywordAnteprimaDTO anteprimaKeyword(KeywordAnteprimaRequest req) {
        return keywordService.anteprima(req.descrizione(), req.sorgente());
    }

    @GET
    @Path("/keyword/conflitti")
    @RolesAllowed("ADMIN")
    public List<KeywordConflittoDTO> listConflittiKeyword(@QueryParam("stato") String stato) {
        return keywordService.listConflitti(stato);
    }

    @PUT
    @Path("/keyword/conflitti/{id}/risolvi")
    @RolesAllowed("ADMIN")
    public Response risolviConflittoKeyword(@PathParam("id") UUID id,
                                            RisolviConflittoKeywordRequest req,
                                            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        keywordService.risolviConflitto(id, req, userId);
        return Response.noContent().build();
    }
}
