package com.agostinelli.gestionale.reporting.resource;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.*;
import com.agostinelli.gestionale.reporting.service.DashboardService;
import com.agostinelli.gestionale.shared.dto.DateRangeFilter;
import com.agostinelli.gestionale.shared.dto.MovimentoDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import java.time.LocalDate;
import java.util.List;

@Path("/api/dashboard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class DashboardResource {

    @Inject
    DashboardService service;

    @GET
    @Path("/kpi")
    public DashboardKpiDTO kpi(
            @QueryParam("from")    LocalDate from,
            @QueryParam("to")      LocalDate to,
            @QueryParam("period")  @DefaultValue("MTD") String period,
            @Context SecurityContext ctx) {

        DateRangeFilter range = resolveRange(period, from, to);
        String userId = ctx.getUserPrincipal().getName();
        return service.getKpi(range.from(), range.to(), userId);
    }

    @GET
    @Path("/andamento-mensile")
    public List<AndamentoMensileDTO> andamentoMensile(
            @QueryParam("anni") @DefaultValue("2") int anni,
            @Context SecurityContext ctx) {

        if (anni > 5) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARAM_OUT_OF_RANGE", "anni max 5");
        }
        String userId = ctx.getUserPrincipal().getName();
        return service.getAndamentoMensile(anni, userId);
    }

    @GET
    @Path("/fatturato-per-bu")
    public List<FatturatoPerBuDTO> fatturatoPerBu(
            @QueryParam("from")   LocalDate from,
            @QueryParam("to")     LocalDate to,
            @QueryParam("period") @DefaultValue("YTD") String period,
            @Context SecurityContext ctx) {

        DateRangeFilter range = resolveRange(period, from, to);
        String userId = ctx.getUserPrincipal().getName();
        return service.getFatturatoPerBu(range.from(), range.to(), userId);
    }

    @GET
    @Path("/ultime-transazioni")
    public List<MovimentoDTO> ultimeTransazioni(
            @QueryParam("limit") @DefaultValue("10") int limit) {

        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return service.getUltimeTransazioni(safeLimit);
    }

    @GET
    @Path("/scadenze-imminenti")
    public ScadenzeImminentiDTO scadenzeImminenti(
            @QueryParam("from")    LocalDate from,
            @QueryParam("to")      LocalDate to,
            @QueryParam("period")  @DefaultValue("MTD") String period) {

        DateRangeFilter.Period p;
        try {
            p = DateRangeFilter.Period.valueOf(period);
        } catch (IllegalArgumentException e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_PERIOD",
                    "Periodo non valido: " + period + ". Usare MTD, QTD, YTD o CUSTOM");
        }
        if (p == DateRangeFilter.Period.CUSTOM && (from == null || to == null)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "MISSING_RANGE",
                    "from e to sono obbligatori per period=CUSTOM");
        }
        DateRangeFilter range = DateRangeFilter.resolveFullPeriod(p, from, to);
        return service.getScadenzeImminenti(range.from(), range.to());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DateRangeFilter resolveRange(String period, LocalDate from, LocalDate to) {
        DateRangeFilter.Period p;
        try {
            p = DateRangeFilter.Period.valueOf(period);
        } catch (IllegalArgumentException e) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_PERIOD",
                    "Periodo non valido: " + period + ". Usare MTD, QTD, YTD o CUSTOM");
        }
        if (p != DateRangeFilter.Period.CUSTOM) {
            return DateRangeFilter.resolveRange(p);
        }
        if (from == null || to == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "MISSING_RANGE",
                    "from e to sono obbligatori per period=CUSTOM");
        }
        if (from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_RANGE",
                    "from non può essere successivo a to");
        }
        return new DateRangeFilter(from, to, p);
    }
}
