package com.agostinelli.gestionale.reporting.resource;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.*;
import com.agostinelli.gestionale.reporting.service.ReportJobService;
import com.agostinelli.gestionale.reporting.service.ForecastingService;
import com.agostinelli.gestionale.reporting.service.ReportingService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Path("/api/reporting")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class ReportingResource {

    @Inject ReportingService reportingService;
    @Inject ForecastingService forecastingService;
    @Inject ReportJobService jobService;

    // ── P&L ───────────────────────────────────────────────────────────────────

    @GET
    @Path("/pl")
    public Response pl(
            @QueryParam("buId") Short buId,
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to,
            @Context SecurityContext ctx) {

        validateRange(from, to);

        // Range > 12 mesi → pattern async
        if (ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1)) > 12) {
            UUID jobId = jobService.submitJob(() -> reportingService.computePl(buId, from, to));
            return Response.accepted(Map.of("jobId", jobId.toString())).build();
        }

        PlDTO result = reportingService.computePl(buId, from, to);
        return Response.ok(result).build();
    }

    @GET
    @Path("/pl/status/{jobId}")
    public JobStatusDTO plStatus(@PathParam("jobId") UUID jobId) {
        return jobService.getJobStatus(jobId);
    }

    @GET
    @Path("/pl/tutte-bu")
    public PlComparativoDTO plTutteBu(
            @QueryParam("from") LocalDate from,
            @QueryParam("to")   LocalDate to) {

        validateRange(from, to);
        return reportingService.computePlComparativo(from, to);
    }

    // ── Cash Flow ─────────────────────────────────────────────────────────────

    @GET
    @Path("/cashflow/storico")
    public List<CashFlowPeriodoDTO> cashFlowStorico(
            @QueryParam("from")         LocalDate from,
            @QueryParam("to")           LocalDate to,
            @QueryParam("granularity")  @DefaultValue("MONTH") String granularity) {

        validateRange(from, to);
        return reportingService.getCashFlowStorico(from, to, granularity);
    }

    @GET
    @Path("/cashflow/forecast")
    public List<ForecastPointDTO> cashFlowForecast(
            @QueryParam("giorni") @DefaultValue("90") int giorni) {

        if (giorni > 365) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARAM_OUT_OF_RANGE", "giorni max 365");
        }
        return reportingService.getCashFlowForecast(giorni);
    }

    // ── Forecasting ───────────────────────────────────────────────────────────

    @GET
    @Path("/forecasting")
    public ForecastingRispostaDTO forecasting(
            @QueryParam("horizon") @DefaultValue("90") String horizon) {

        return forecastingService.computeForecasting(horizon);
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "MISSING_RANGE",
                    "I parametri from e to sono obbligatori");
        }
        if (from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_RANGE",
                    "from non può essere successivo a to");
        }
        if (ChronoUnit.YEARS.between(from, to) >= 5) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_TOO_LARGE",
                    "Range massimo consentito: 5 anni");
        }
    }
}
