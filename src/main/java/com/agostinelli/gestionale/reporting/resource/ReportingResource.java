package com.agostinelli.gestionale.reporting.resource;

import com.agostinelli.gestionale.cassa.dto.CassaMovimentoDTO;
import com.agostinelli.gestionale.cassa.mapper.CassaMovimentoMapper;
import com.agostinelli.gestionale.cassa.repository.CassaMovimentiRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.*;
import com.agostinelli.gestionale.reporting.service.ExportService;
import com.agostinelli.gestionale.reporting.service.ReportJobService;
import com.agostinelli.gestionale.reporting.service.ReportingService;
import com.agostinelli.gestionale.shared.dto.MovimentoDTO;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Path("/api/reporting")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "DIPENDENTE"})
public class ReportingResource {

    private static final Logger log = Logger.getLogger(ReportingResource.class);
    private static final DateTimeFormatter FNAME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Inject ReportingService reportingService;
    @Inject ReportJobService jobService;
    @Inject ExportService exportService;
    @Inject CassaMovimentiRepository cassaRepo;
    @Inject CassaMovimentoMapper cassaMapper;
    @Inject jakarta.persistence.EntityManager em;

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

    // ── Export ────────────────────────────────────────────────────────────────

    @GET
    @Path("/export/movimenti")
    public Response exportMovimenti(
            @QueryParam("from")   LocalDate from,
            @QueryParam("to")     LocalDate to,
            @QueryParam("format") @DefaultValue("csv") String format,
            @Context SecurityContext ctx) {

        validateRange(from, to);
        String userId = ctx.getUserPrincipal().getName();

        List<MovimentoDTO> data = fetchMovimentiForExport(from, to);
        log.infof("Export movimenti: userId=%s format=%s periodo=%s/%s righe=%d",
                userId, format, from, to, data.size());

        if ("xlsx".equalsIgnoreCase(format)) {
            String filename = "movimenti_" + from.format(FNAME_FMT) + "_" + to.format(FNAME_FMT) + ".xlsx";
            StreamingOutput stream = out -> {
                exportService.streamXlsxMovimenti(data, out);
                out.flush();
            };
            return Response.ok(stream)
                    .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .build();
        }

        // Default: CSV
        byte[] csv = exportService.buildCsvMovimenti(data);
        String filename = "movimenti_" + from.format(FNAME_FMT) + "_" + to.format(FNAME_FMT) + ".csv";
        return Response.ok(csv)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @GET
    @Path("/export/commercialista")
    public Response exportCommercialista(
            @QueryParam("mese") int mese,
            @QueryParam("anno") int anno,
            @Context SecurityContext ctx) {

        if (mese < 1 || mese > 12) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARAM_OUT_OF_RANGE", "mese deve essere tra 1 e 12");
        }
        String userId = ctx.getUserPrincipal().getName();
        LocalDate from = LocalDate.of(anno, mese, 1);
        LocalDate to   = from.withDayOfMonth(from.lengthOfMonth());

        List<MovimentoDTO> movimenti = fetchMovimentiForExport(from, to);
        List<RiepilogoCategoriaDTO> riepilogo = calcolaRiepilogo(movimenti);
        List<CassaMovimentoDTO> cassa = cassaRepo
                .findByPeriodo(from, to, 0, Integer.MAX_VALUE)
                .stream().map(cassaMapper::toDTO).toList();

        log.infof("Export commercialista: userId=%s anno=%d mese=%d movimenti=%d cassa=%d",
                userId, anno, mese, movimenti.size(), cassa.size());

        String filename = String.format("commercialista_%d_%02d.xlsx", anno, mese);
        StreamingOutput stream = out -> {
            exportService.streamXlsxCommercialista(movimenti, riepilogo, cassa, out);
            out.flush();
        };
        return Response.ok(stream)
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @GET
    @Path("/export/pl-bu")
    public Response exportPlBu(
            @QueryParam("buId")   Short buId,
            @QueryParam("from")   LocalDate from,
            @QueryParam("to")     LocalDate to,
            @QueryParam("format") @DefaultValue("xlsx") String format) {

        if ("pdf".equalsIgnoreCase(format)) {
            return Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(Map.of("message", "PDF export non ancora disponibile"))
                    .build();
        }

        validateRange(from, to);
        PlDTO plData = reportingService.computePl(buId, from, to);

        String filename = "pl_bu" + (buId != null ? buId : "all") + "_"
                + from.format(FNAME_FMT) + "_" + to.format(FNAME_FMT) + ".xlsx";

        StreamingOutput stream = out -> {
            exportService.streamXlsxPlBu(plData, out);
            out.flush();
        };
        return Response.ok(stream)
                .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private List<MovimentoDTO> fetchMovimentiForExport(LocalDate from, LocalDate to) {
        List<MovimentoDTO> all = new ArrayList<>();
        int page = 0;
        final int PAGE_SIZE = 500;
        while (true) {
            @SuppressWarnings("unchecked")
            List<Object[]> batch = em.createNativeQuery(
                    "SELECT m.id, m.data_movimento, m.tipo, m.importo_lordo, m.importo_imponibile, " +
                    "m.importo_iva, m.importo_commissione, m.descrizione, m.stato, m.fonte, " +
                    "COALESCE(c.nome,'') AS categoria_nome, COALESCE(f.ragione_sociale,'') AS fornitore_nome, " +
                    "bu.nome AS bu_nome, cb.nome AS conto_nome " +
                    "FROM movimenti m " +
                    "LEFT JOIN categorie c ON c.id = m.categoria_id " +
                    "LEFT JOIN fornitori f ON f.id = m.fornitore_id " +
                    "JOIN business_units bu ON bu.id = m.business_unit_id " +
                    "JOIN conti_bancari cb ON cb.id = m.conto_bancario_id " +
                    "WHERE m.stato != 'ANNULLATO' AND m.data_movimento BETWEEN :from AND :to " +
                    "ORDER BY m.data_movimento DESC " +
                    "LIMIT :size OFFSET :offset")
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .setParameter("size", PAGE_SIZE)
                    .setParameter("offset", page * PAGE_SIZE)
                    .getResultList();

            for (Object[] r : batch) {
                all.add(mapExportRow(r));
            }
            if (batch.size() < PAGE_SIZE) break;
            page++;
        }
        return all;
    }

    private MovimentoDTO mapExportRow(Object[] r) {
        // r[0]=id, r[1]=data_movimento, r[2]=tipo, r[3]=importo_lordo,
        // r[4]=importo_imponibile, r[5]=importo_iva, r[6]=importo_commissione,
        // r[7]=descrizione, r[8]=stato, r[9]=fonte,
        // r[10]=categoria_nome, r[11]=fornitore_nome, r[12]=bu_nome, r[13]=conto_nome
        BigDecimal importoLordo = toBD(r[3]);
        return new MovimentoDTO(
                toUUID(r[0]),
                (String) r[2],
                importoLordo,
                toLocalDate(r[1]),
                null, null, null,
                null, (String) r[13],
                null, (String) r[12],
                null, (String) r[10],
                null, null,
                null, (String) r[11],
                null, null, null,
                (String) r[7],
                null,
                importoLordo,
                toBD(r[6]),
                null,
                toBD(r[5]),
                (String) r[8],
                (String) r[9],
                null, null, null
        );
    }

    private List<RiepilogoCategoriaDTO> calcolaRiepilogo(List<MovimentoDTO> movimenti) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal[]> importi = new LinkedHashMap<>();

        for (MovimentoDTO m : movimenti) {
            String cat = m.categoriaNome() != null ? m.categoriaNome() : "(nessuna)";
            counts.computeIfAbsent(cat, k -> new long[]{0})[0]++;
            BigDecimal[] imp = importi.computeIfAbsent(cat, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if ("ENTRATA".equals(m.tipo())) imp[0] = imp[0].add(m.importo());
            else                            imp[1] = imp[1].add(m.importo());
        }

        return counts.entrySet().stream()
                .map(e -> {
                    BigDecimal[] imp = importi.get(e.getKey());
                    return new RiepilogoCategoriaDTO(e.getKey(), e.getValue()[0], imp[0], imp[1]);
                })
                .toList();
    }

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

    // ── type-cast helpers ─────────────────────────────────────────────────────

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    private UUID toUUID(Object o) {
        if (o == null) return null;
        if (o instanceof UUID u) return u;
        return UUID.fromString(o.toString());
    }
}
