package com.agostinelli.gestionale.movimenti.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.mapper.MovimentoMapper;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class MovimentiService {

    @Inject MovimentiRepository repo;
    @Inject MovimentoMapper mapper;
    @Inject Validator validator;

    /**
     * Crea un movimento con validazione cross-field e calcolo dei campi derivati.
     *
     * LOGICA DI LIQUIDAZIONE:
     *   dataFinanziaria != null → REGISTRATO (liquidato); dataLiquidita = dataFinanziaria
     *   dataFinanziaria == null → DA_LIQUIDARE; dataLiquidita (scadenzaFinanziaria) obbligatoria
     *
     * I totali sull'evento vengono aggiornati automaticamente dal trigger DB.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public MovimentoDTO createMovimento(MovimentoCreateRequest req, UUID userId) {
        validateCrossFields(req);

        Movimento m = mapper.fromRequest(req);
        m.createdBy = userId;
        m.fonte = req.fonte() != null ? req.fonte() : "MANUALE";

        if (req.dataFinanziaria() != null) {
            m.stato = "REGISTRATO";
            m.dataLiquidita = req.dataFinanziaria(); // scadenzaFinanziaria = dataFinanziaria
        } else {
            m.stato = "DA_LIQUIDARE";
        }

        applyDerivedAmounts(m, req.importoLordo(), req.aliquotaIva());

        repo.persist(m);
        return mapper.toDTO(m);
    }

    /**
     * Aggiorna parzialmente un movimento (PATCH semantics).
     * Solo l'autore originale o un ADMIN possono modificare.
     *
     * Se la richiesta imposta dataFinanziaria su un DA_LIQUIDARE, il movimento
     * viene promosso a REGISTRATO e dataLiquidita viene sincronizzata.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public MovimentoDTO updateMovimento(UUID id, MovimentoUpdateRequest req, UUID userId, boolean isAdmin) {
        Movimento m = findActiveOrThrow(id);

        if (!isAdmin && !userId.equals(m.createdBy)) {
            throw new ApiException(Response.Status.FORBIDDEN, "FORBIDDEN",
                    "Solo l'autore o un ADMIN può modificare questo movimento");
        }

        mapper.updateFromRequest(m, req);

        if (req.importoLordo() != null || req.aliquotaIva() != null) {
            applyDerivedAmounts(m, req.importoLordo(), req.aliquotaIva());
        }

        // Sincronizza stato e scadenzaFinanziaria in base alla presenza di dataFinanziaria
        if (m.dataFinanziaria != null && !"ANNULLATO".equals(m.stato) && !"RICONCILIATO".equals(m.stato)) {
            m.stato = "REGISTRATO";
            m.dataLiquidita = m.dataFinanziaria;
        } else if (m.dataFinanziaria == null && !"ANNULLATO".equals(m.stato) && !"RICONCILIATO".equals(m.stato)) {
            m.stato = "DA_LIQUIDARE";
        }

        return mapper.toDTO(m);
    }

    @Transactional
    public void annullaMovimento(UUID id) {
        Movimento m = findActiveOrThrow(id);
        m.stato = "ANNULLATO";
    }

    public MovimentoDTO findById(UUID id) {
        Movimento m = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento non trovato: " + id));
        return mapper.toDTO(m);
    }

    public PagedResponse<MovimentoDTO> findWithFilters(
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search, int page, int size, String sort) {

        List<MovimentoDTO> content = repo.findWithFilters(tipo, buId, categoriaId, metodoPagamentoId,
                        stato, fornitoreId, eventoId, from, to, search, page, size, sort)
                .stream().map(mapper::toDTO).toList();

        long total = repo.countWithFilters(tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);

        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Import massivo con deduplication O(1) tramite HashSet pre-caricato.
     * Errori di validazione non bloccano l'intera transazione – solo gli errori
     * DB critici causano rollback.
     */
    @Transactional
    public BulkImportResponse bulkImport(BulkImportRequest request, UUID userId) {
        List<MovimentoCreateRequest> lista = request.movimenti();
        if (lista.size() > 500) {
            throw new ApiException(Response.Status.BAD_REQUEST, "BULK_LIMIT_EXCEEDED",
                    "Il bulk import accetta al massimo 500 movimenti per richiesta");
        }

        // Pre-carica i riferimenti esterni esistenti per evitare N query DB nel loop
        String fonteDefault = lista.stream()
                .map(MovimentoCreateRequest::fonte)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("MANUALE");
        Set<String> esistenti = repo.findRifimentiEsterniByFonte(fonteDefault);

        int importati = 0, duplicati = 0, errori = 0;
        List<ImportError> dettaglioErrori = new ArrayList<>();

        for (int i = 0; i < lista.size(); i++) {
            MovimentoCreateRequest req = lista.get(i);
            int riga = i + 1;

            // Validazione Bean Validation programmatica
            Set<ConstraintViolation<MovimentoCreateRequest>> violations = validator.validate(req);
            if (!violations.isEmpty()) {
                ConstraintViolation<MovimentoCreateRequest> v = violations.iterator().next();
                dettaglioErrori.add(new ImportError(riga,
                        v.getPropertyPath().toString(), v.getMessage()));
                errori++;
                continue;
            }

            // Deduplication O(1)
            String rif = req.riferimentoEsterno();
            if (rif != null && !rif.isBlank() && esistenti.contains(rif)) {
                duplicati++;
                continue;
            }

            try {
                MovimentoDTO dto = createMovimento(req, userId);
                if (rif != null && !rif.isBlank()) {
                    esistenti.add(rif);
                }
                importati++;
            } catch (Exception e) {
                dettaglioErrori.add(new ImportError(riga, "-", e.getMessage()));
                errori++;
            }
        }

        return new BulkImportResponse(importati, duplicati, errori, dettaglioErrori);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Movimento findActiveOrThrow(UUID id) {
        Movimento m = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento non trovato: " + id));
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Il movimento " + id + " è già annullato e non può essere modificato");
        }
        return m;
    }

    /**
     * Validazione cross-field per create.
     *
     * Regola 1 — dataFinanziaria presente (LIQUIDATO):
     *   - contoBancarioId e metodoPagamentoId obbligatori
     *   - dataLiquidita facoltativa (verrà auto-impostata = dataFinanziaria)
     *
     * Regola 2 — dataFinanziaria assente (DA_LIQUIDARE):
     *   - dataLiquidita (scadenzaFinanziaria) obbligatoria
     *   - contoBancarioId e metodoPagamentoId devono essere assenti
     */
    private void validateCrossFields(MovimentoCreateRequest req) {
        if (req.tipoEventoMovimento() != null && req.eventoId() == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "EVENTO_MANCANTE",
                    "tipoEventoMovimento richiede la presenza di eventoId");
        }

        boolean isLiquidato = req.dataFinanziaria() != null;

        if (isLiquidato) {
            if (req.contoBancarioId() == null || req.metodoPagamentoId() == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "LIQUIDATO_INCOMPLETO",
                        "Conto bancario e metodo pagamento sono obbligatori per movimenti liquidati (dataFinanziaria valorizzata)");
            }
            if (req.dataFinanziaria().isAfter(LocalDate.now())) {
                throw new ApiException(Response.Status.BAD_REQUEST, "DATA_FINANZIARIA_FUTURA",
                        "La data di liquidazione effettiva non può essere nel futuro");
            }
        } else {
            if (req.dataLiquidita() == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "SCADENZA_FINANZIARIA_MANCANTE",
                        "La scadenza finanziaria (dataLiquidita) è obbligatoria per movimenti non ancora liquidati");
            }
            if (req.contoBancarioId() != null || req.metodoPagamentoId() != null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "LIQUIDITA_INCONSISTENTE",
                        "Non puoi specificare conto bancario o metodo pagamento per movimenti non ancora liquidati (dataFinanziaria assente)");
            }
        }
    }

    /**
     * Calcola importoCommissione e importoIva dai valori opzionali forniti.
     * commissione: solo se importoLordo > importo (scenario POS/Satispay).
     * IVA: solo se aliquotaIva è presente (es. 0.10 per il 10%).
     */
    private void applyDerivedAmounts(Movimento m, BigDecimal importoLordo, BigDecimal aliquotaIva) {
        if (importoLordo != null && importoLordo.compareTo(m.importo) > 0) {
            m.importoCommissione = importoLordo.subtract(m.importo);
        }
        if (aliquotaIva != null && aliquotaIva.compareTo(BigDecimal.ZERO) > 0) {
            m.importoIva = m.importo.multiply(aliquotaIva);
            m.importoImponibile = m.importo.subtract(m.importoIva);
        }
    }
}
