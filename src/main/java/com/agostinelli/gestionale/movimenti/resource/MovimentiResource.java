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
    @Inject com.agostinelli.gestionale.movimenti.importlayer.ContatoreImportService contatoreService;
    // AUDIT-TEMP: registro decisionale dell'import su file. Vedi ImportAuditLog per come si spegne
    // e si cancella tutto (questi 3 endpoint spariscono insieme alla classe).
    @Inject com.agostinelli.gestionale.movimenti.importlayer.ImportAuditLog auditLog;

    /**
     * Filtri avanzati (docs/specs/movimenti-filtri-avanzati.md).
     * Ogni dimensione accetta il parametro RIPETUTO (?stato=REGISTRATO&stato=DA_LIQUIDARE):
     * valori della stessa dimensione in OR, dimensioni diverse in AND.
     * {@code contoId=0} = movimenti senza banca. {@code dateField} sceglie a quale delle tre
     * date del modello applicare from/to (default: data movimento).
     */
    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public PagedResponse<MovimentoDTO> list(
            @BeanParam MovimentiFilterParams filtri,
            @QueryParam("page")  @DefaultValue("0")  int page,
            @QueryParam("size")  @DefaultValue("20") int size,
            @QueryParam("sort")           String sort
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        return service.findWithFilters(filtri.toQuery(), page, safeSize, sort);
    }

    /**
     * Riepilogo per stato/tipo sullo STESSO insieme filtrato della lista: accetta gli stessi
     * identici parametri (invariante di spec — un riepilogo che non rispecchia la lista è un bug).
     */
    @GET
    @Path("/sommario")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentiSommarioDTO sommario(@BeanParam MovimentiFilterParams filtri) {
        return service.getSommario(filtri.toQuery());
    }

    // Movimenti attivi senza conto/cassa: da attribuire a mano (popup "Situazione Finanziaria").
    // Path literale prima di /{id} per non farlo intercettare dal template.
    @GET
    @Path("/senza-banca")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public java.util.List<MovimentoDTO> senzaBanca() {
        return service.listSenzaBanca();
    }

    // Partite di apertura (crediti ENTRATA / debiti USCITA pre-2026 da liquidare). Literal prima di /{id}.
    @GET
    @Path("/partite-apertura")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public java.util.List<MovimentoDTO> partiteApertura(@QueryParam("tipo") String tipo) {
        return service.listPartiteApertura(tipo);
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

    // Attribuzione mirata del conto/cassa (popup "Senza banca"): tocca solo conto_bancario_id,
    // senza il full-overwrite del PUT (che de-liquiderebbe il movimento).
    @PATCH
    @Path("/{id}/conto-bancario")
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    public MovimentoDTO assegnaConto(@PathParam("id") UUID id, @Valid AssegnaContoRequest req) {
        return service.assegnaContoBancario(id, req.contoBancarioId());
    }

    /**
     * Divide un movimento cumulativo (RiBa/effetti) in N quote — spec riba-split-importo.
     * ADMIN-only: sposta euro veri fra conti CoGe e business unit.
     */
    @POST
    @Path("/{id}/dividi")
    @RolesAllowed("ADMIN")
    public java.util.List<MovimentoDTO> dividi(
            @PathParam("id") UUID id,
            @Valid DividiMovimentoRequest req,
            @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return service.dividiMovimento(id, req, userId);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("ADMIN")
    public Response annulla(@PathParam("id") UUID id) {
        service.annullaMovimento(id);
        return Response.noContent().build();
    }

    /**
     * Cestina: toglie fisicamente dal database un movimento GIÀ ANNULLATO e non referenziato.
     * Spec: docs/specs/movimento-cestina-fisica.md. La riga resta in audit_log (trigger DB).
     */
    @DELETE
    @Path("/{id}/cestina")
    @RolesAllowed("ADMIN")
    public Response cestina(@PathParam("id") UUID id) {
        service.cestinaMovimento(id);
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
    // si importa solo congiunto. Anche il metodo interno importFile è stato cancellato il
    // 2026-08-11 (104 righe vive per un solo test) — audit-catena-import §6.2/5.

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

    /** "Va che è un evento": sposta la riga ambigua nella coda degli incassi-evento. */
    @PUT
    @Path("/import/ambiguita/{id}/e-un-evento")
    @RolesAllowed("ADMIN")
    public Response ambiguitaEUnEvento(@PathParam("id") UUID id, @Context SecurityContext ctx) {
        importLogService.promuoviAEvento(id, UUID.fromString(ctx.getUserPrincipal().getName()));
        return Response.noContent().build();
    }

    // ── Triage assistito / KPI / regole data-driven (ETL v2 §8/§9/§13) ──────────

    @GET
    @Path("/import/kpi")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.ImportKpiDTO importKpi() {
        return triageService.getKpi();
    }

    /** I badge della console Import in una risposta sola: vedi il javadoc di ImportBadgeDTO. */
    @GET
    @Path("/import/badge")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.ImportBadgeDTO importBadge() {
        return triageService.getBadge();
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

    /** Rami storicamente usati per ogni conto: il wizard chiede la BU solo quando ce n'è più d'uno. */
    // «Non è una spesa»: rimanda la riga alla coda che sa lavorarla (incassi evento / rate).
    // Serve perché per un incasso-evento la risposta giusta non è un conto: il ricavo nasce dal
    // modulo Eventi, e qui non si può (né si deve) aggirare quell'invariante.
    @PUT
    @Path("/import/transitori/{movimentoId}/sposta")
    @RolesAllowed("ADMIN")
    public Response spostaInCoda(@PathParam("movimentoId") UUID movimentoId,
                                 SpostaRigaRequest req, @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        triageService.spostaInCoda(movimentoId, req, userId);
        return Response.noContent().build();
    }

    @GET
    @Path("/import/bu-per-coge")
    @RolesAllowed("ADMIN")
    public java.util.Map<Integer, java.util.List<Short>> buPerCoge() {
        return triageService.buPerCoge();
    }

    // ── Pannello BU dell'import: rifinitura manuale della classificazione analitica ──
    // Gli "incerti" (conto CoGe transitorio) arrivano in un gruppo a parte: sono il lavoro.

    @GET
    @Path("/import/{importLogId}/bu")
    @RolesAllowed("ADMIN")
    public BuPanelDTO buPanel(@PathParam("importLogId") UUID importLogId) {
        return triageService.getBuPanel(importLogId);
    }

    /** Sposta un movimento dell'import su un'altra BU. Dimensione analitica: nessun saldo si muove. */
    @PUT
    @Path("/import/{importLogId}/bu/{movimentoId}")
    @RolesAllowed("ADMIN")
    public Response cambiaBu(@PathParam("importLogId") UUID importLogId,
                             @PathParam("movimentoId") UUID movimentoId,
                             @Valid CambiaBusinessUnitRequest req) {
        triageService.cambiaBusinessUnit(importLogId, movimentoId, req.businessUnitId());
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

    // ── AUDIT-TEMP: traccia decisionale dell'import (file .txt) ───────────────────────

    /** Elenco dei file di audit prodotti dagli import. */
    @GET
    @Path("/import/audit-log")
    public List<com.agostinelli.gestionale.movimenti.importlayer.ImportAuditLog.FileAudit> auditLogList() {
        return auditLog.elenco();
    }

    /** Contenuto di un file di audit, da leggere così com'è. */
    @GET
    @Path("/import/audit-log/{nome}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response auditLogFile(@PathParam("nome") String nome) {
        String testo = auditLog.contenuto(nome);
        return testo == null ? Response.status(Response.Status.NOT_FOUND).build()
                             : Response.ok(testo).build();
    }

    /** Cancella TUTTI i file di audit prodotti: è la pulizia di fine indagine. */
    @DELETE
    @Path("/import/audit-log")
    public Response auditLogPulisci() {
        int n = auditLog.cancellaTutto();
        return Response.ok(java.util.Map.of("cancellati", n)).build();
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

    // ── Coda «Righe fuori dai conti» (audit §7.4): righe bancarie escluse dalla pipeline ──
    //    (SKIP_POS, SKIP_CODA_TESTA, SKIP_GIROCONTO). Sono denaro fuori dai conti finché
    //    qualcuno non le guarda: «Mettila nei conti» crea il movimento, «Lasciala fuori» chiude.

    @GET
    @Path("/import/scartati")
    @RolesAllowed("ADMIN")
    public PagedResponse<ScartatoDTO> listScartati(
            @QueryParam("stato") @DefaultValue("DA_VEDERE") String stato,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        return triageService.listScartati(stato, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
    }

    @PUT
    @Path("/import/scartati/{id}/risolvi")
    @RolesAllowed("ADMIN")
    public Response risolviScartato(@PathParam("id") UUID id, RisolviScartatoRequest req,
                                    @Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        triageService.risolviScartato(id, req, userId);
        return Response.noContent().build();
    }

    // La vista Effetti/RiBa separata è cancellata (audit §7.7): quelle righe sono uscite da
    // catalogare come le altre e ora compaiono in /import/transitori.

    // ── Il contatore dell'import (SPEC import-v2 §5/§6, R7–R10) ─────────────────────────
    // Quanto è uscito dalle banche e dove si trova adesso, al centesimo e per direzione.
    // È l'oracolo contro cui il titolare verifica l'estratto conto: sola lettura, nessuno stato.
    @GET
    @Path("/import/{importLogId}/contatore")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.ContatoreImportDTO contatore(
            @PathParam("importLogId") UUID importLogId) {
        return contatoreService.calcola(importLogId);
    }

    // ── Registro di TUTTE le righe dell'import (R21/R22) ────────────────────────────────
    // Sola lettura: nessuna azione inline. Il denaro si muove da una strada sola, il wizard
    // (principio 6 di PRODUCT.md). Cliccando una riga la UI apre il wizard su QUELLA riga.
    @GET
    @Path("/import/{importLogId}/righe")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.RegistroImportDTO righeImport(
            @PathParam("importLogId") UUID importLogId,
            @QueryParam("stato") String stato,
            @QueryParam("conto") Short conto,
            @QueryParam("da") String da,
            @QueryParam("a") String a,
            @QueryParam("q") String cerca,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size) {
        return contatoreService.registro(importLogId, stato, conto,
                da == null || da.isBlank() ? null : java.time.LocalDate.parse(da),
                a == null || a.isBlank() ? null : java.time.LocalDate.parse(a),
                cerca, page, Math.min(Math.max(size, 1), MAX_TRIAGE_SIZE));
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

    /** Firme attive che si contendono la riga di un conflitto MATCH ("In import"); vuoto per APPRENDIMENTO. */
    @GET
    @Path("/keyword/conflitti/{id}/firme")
    @RolesAllowed("ADMIN")
    public List<KeywordFirmaDTO> firmeConflittoKeyword(@PathParam("id") UUID id) {
        return keywordService.firmeConflittoMatch(id);
    }

    /** Chiude da soli i conflitti MATCH non più ambigui e ri-cataloga i movimenti incastrati. */
    @POST
    @Path("/keyword/conflitti/rivaluta")
    @RolesAllowed("ADMIN")
    public com.agostinelli.gestionale.movimenti.dto.RivalutazioneConflittiDTO rivalutaConflitti(@Context SecurityContext ctx) {
        UUID userId = UUID.fromString(ctx.getUserPrincipal().getName());
        return keywordService.rivalutaConflittiMatch(userId);
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
