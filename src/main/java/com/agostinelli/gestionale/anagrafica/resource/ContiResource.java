package com.agostinelli.gestionale.anagrafica.resource;

import com.agostinelli.gestionale.anagrafica.domain.ContoBancario;
import com.agostinelli.gestionale.anagrafica.dto.ContoBancarioDTO;
import com.agostinelli.gestionale.anagrafica.dto.SaldoInizialeRequest;
import com.agostinelli.gestionale.anagrafica.repository.ContiBancariRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/api/conti")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ContiResource {

    @Inject
    ContiBancariRepository repo;

    @Inject
    MvRefreshService mvRefresh;

    @GET
    @RolesAllowed({"ADMIN", "DIPENDENTE"})
    @CacheResult(cacheName = "conti-list")
    public List<ContoBancarioDTO> listAll() {
        return repo.findAllAttiviConSaldo();
    }

    /** Imposta il saldo di apertura del conto (es. al 31/12/2025). Invalida cache e rinfresca i saldi. */
    @PUT
    @Path("/{id}/saldo-iniziale")
    @RolesAllowed("ADMIN")
    @Transactional
    @CacheInvalidateAll(cacheName = "conti-list")
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    public ContoBancarioDTO updateSaldoIniziale(@PathParam("id") Short id, @Valid SaldoInizialeRequest req) {
        ContoBancario c = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Conto non trovato"));
        c.saldoIniziale = req.saldoIniziale();
        c.dataSaldoIniziale = req.dataSaldoIniziale();
        mvRefresh.requestRefreshAfterCommit();
        return repo.findAllAttiviConSaldo().stream()
                .filter(d -> d.id().equals(id)).findFirst().orElseThrow();
    }
}
