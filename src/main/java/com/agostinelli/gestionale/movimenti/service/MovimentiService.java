package com.agostinelli.gestionale.movimenti.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.mapper.MovimentoMapper;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
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
    @Inject MvRefreshService mvRefresh;

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

        mvRefresh.requestRefreshAfterCommit();
        return mapper.toDTO(m);
    }

    /**
     * Crea un movimento proveniente dall'ETL (Billy / BPM / CA). I movimenti import
     * sono SEMPRE liquidati (dataFinanziaria valorizzata = dataMovimento, stato REGISTRATO).
     *
     * Differenze rispetto a {@link #createMovimento}:
     *  - NESSUN @CacheInvalidateAll: l'invalidazione cache e il refresh MV vengono fatti
     *    una sola volta dal MovimentoImportService al termine del loop (non per riga);
     *  - collega il movimento all'import_log tramite fonteImportazioneId.
     *
     * Riusa la stessa validateCrossFields e il mapper di createMovimento.
     */
    @Transactional
    public MovimentoDTO createMovimentoImport(MovimentoCreateRequest req, UUID userId, UUID importLogId) {
        validateCrossFields(req);

        Movimento m = mapper.fromRequest(req);
        m.createdBy = userId;
        m.fonte = req.fonte() != null ? req.fonte() : "MANUALE";
        m.fonteImportazioneId = importLogId;
        m.stato = "REGISTRATO";
        m.dataLiquidita = req.dataFinanziaria();

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

        // Stato di liquidazione PRIMA del mapping: serve per impedire la de-liquidazione.
        boolean eraLiquidato = m.dataFinanziaria != null;

        mapper.updateFromRequest(m, req);

        // Un movimento già liquidato non può tornare DA_LIQUIDARE tramite update.
        if (eraLiquidato && m.dataFinanziaria == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "DELIQUIDAZIONE_NON_CONSENTITA",
                    "Un movimento già liquidato non può essere riportato a DA_LIQUIDARE. Usa l'annullamento.");
        }

        // fonte è NOT NULL: con la semantica full-overwrite un client che la omette
        // la azzererebbe. Default a MANUALE come in createMovimento.
        if (m.fonte == null) {
            m.fonte = "MANUALE";
        }

        if (req.importoLordo() != null || req.aliquotaIva() != null) {
            applyDerivedAmounts(m, req.importoLordo(), req.aliquotaIva());
        }

        validateConsistency(m);

        // Sincronizza stato e scadenzaFinanziaria in base alla presenza di dataFinanziaria
        if (m.dataFinanziaria != null && !"ANNULLATO".equals(m.stato)) {
            m.stato = "REGISTRATO";
            m.dataLiquidita = m.dataFinanziaria;
        } else if (m.dataFinanziaria == null && !"ANNULLATO".equals(m.stato)) {
            m.stato = "DA_LIQUIDARE";
        }

        mvRefresh.requestRefreshAfterCommit();
        return mapper.toDTO(m);
    }

    /**
     * Liquidazione rapida: imposta dataFinanziaria = oggi, contoBancarioId e (opzionalmente)
     * metodoPagamentoId, portando il movimento in stato REGISTRATO.
     * Non esegue la validazione completa di validateConsistency (metodoPagamentoId opzionale
     * per permettere una liquidazione veloce; il campo può essere completato tramite update).
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public MovimentoDTO liquidaMovimento(UUID id, LiquidaRequest req) {
        Movimento m = findActiveOrThrow(id);
        if (!"DA_LIQUIDARE".equals(m.stato)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "GIA_LIQUIDATO",
                    "Il movimento non è in stato DA_LIQUIDARE");
        }
        LocalDate oggi = LocalDate.now();
        m.dataFinanziaria   = oggi;
        m.dataLiquidita     = oggi;
        m.stato             = "REGISTRATO";
        m.contoBancarioId   = req.contoBancarioId();
        m.metodoPagamentoId = req.metodoPagamentoId(); // può essere null
        mvRefresh.requestRefreshAfterCommit();
        return mapper.toDTO(m);
    }

    @Transactional
    public void annullaMovimento(UUID id) {
        Movimento m = findActiveOrThrow(id);
        m.stato = "ANNULLATO";
        mvRefresh.requestRefreshAfterCommit();
    }

    public MovimentoDTO findById(UUID id) {
        Movimento m = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento non trovato: " + id));
        return mapper.toDTO(m);
    }

    /** Movimenti attivi non ancora attribuiti a un conto/cassa, da catalogare a mano. */
    public List<MovimentoDTO> listSenzaBanca() {
        return repo.findSenzaBanca().stream().map(mapper::toDTO).toList();
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

    // ── Feature 1: movimenti "Da liquidare" in ritardo ──────────────────────────
    //
    // Movimenti con stato DA_LIQUIDARE, dataFinanziaria IS NULL e dataLiquidita < oggi.
    // La Mapper.toDTO calcola automaticamente giorniAllaScadenza (negativo = ritardo).
    // Per le USCITE: "sei in ritardo di |gg| giorni sul pagamento";
    // per le ENTRATE: "qualcuno è in ritardo di |gg| giorni nel pagarmi".
    // Le rate ricorrenti NON compaiono qui perché lo scheduler le liquida alla scadenza.
    public PagedResponse<MovimentoDTO> findDaLiquidareInRitardo(String tipo, int page, int size, String sort) {
        LocalDate oggi = LocalDate.now();
        List<MovimentoDTO> content = repo.findDaLiquidareInRitardo(tipo, oggi, page, size, sort)
                .stream().map(mapper::toDTO).toList();
        long total = repo.countDaLiquidareInRitardo(tipo, oggi);
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

    public MovimentiSommarioDTO getSommario(
            String tipo, Short buId, Long categoriaId, Integer metodoPagamentoId,
            String stato, UUID fornitoreId, UUID eventoId,
            LocalDate from, LocalDate to, String search) {

        List<Object[]> rows = repo.sommarioByStatoTipo(tipo, buId, categoriaId, metodoPagamentoId,
                stato, fornitoreId, eventoId, from, to, search);

        Map<String, BigDecimal[]> byStato = new LinkedHashMap<>();
        Map<String, long[]> countByStato = new LinkedHashMap<>();
        BigDecimal totEntrate = BigDecimal.ZERO;
        BigDecimal totUscite  = BigDecimal.ZERO;
        long totCount = 0;

        for (Object[] row : rows) {
            String statoVal = (String) row[0];
            String tipoVal  = (String) row[1];
            BigDecimal sum  = row[2] != null ? (BigDecimal) row[2] : BigDecimal.ZERO;
            long cnt        = row[3] != null ? ((Number) row[3]).longValue() : 0L;

            byStato.putIfAbsent(statoVal, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            countByStato.putIfAbsent(statoVal, new long[]{0L, 0L});

            if ("ENTRATA".equals(tipoVal)) {
                byStato.get(statoVal)[0] = byStato.get(statoVal)[0].add(sum);
                countByStato.get(statoVal)[0] += cnt;
                totEntrate = totEntrate.add(sum);
            } else {
                byStato.get(statoVal)[1] = byStato.get(statoVal)[1].add(sum);
                countByStato.get(statoVal)[1] += cnt;
                totUscite = totUscite.add(sum);
            }
            totCount += cnt;
        }

        List<MovimentiSommarioDTO.StatoSomma> perStato = byStato.entrySet().stream()
                .map(e -> new MovimentiSommarioDTO.StatoSomma(
                        e.getKey(),
                        e.getValue()[0],
                        e.getValue()[1],
                        e.getValue()[0].subtract(e.getValue()[1]),
                        countByStato.get(e.getKey())[0],
                        countByStato.get(e.getKey())[1]
                ))
                .toList();

        return new MovimentiSommarioDTO(perStato, totEntrate, totUscite,
                totEntrate.subtract(totUscite), totCount);
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
     * Validazione di consistenza sullo stato finale dell'entity (usata in update,
     * dove le regole vanno verificate dopo il mapping full-overwrite).
     * Specchia le regole di validateCrossFields ma opera sul Movimento già mappato.
     */
    private void validateConsistency(Movimento m) {
        if (m.tipoEventoMovimento != null && m.eventoId == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "EVENTO_MANCANTE",
                    "tipoEventoMovimento richiede la presenza di eventoId");
        }

        boolean isLiquidato = m.dataFinanziaria != null;

        if (isLiquidato) {
            if (m.contoBancarioId == null || m.metodoPagamentoId == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "LIQUIDATO_INCOMPLETO",
                        "Conto bancario e metodo pagamento sono obbligatori per movimenti liquidati (dataFinanziaria valorizzata)");
            }
            if (m.dataFinanziaria.isAfter(LocalDate.now())) {
                throw new ApiException(Response.Status.BAD_REQUEST, "DATA_FINANZIARIA_FUTURA",
                        "La data di liquidazione effettiva non può essere nel futuro");
            }
        } else {
            if (m.dataLiquidita == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "SCADENZA_FINANZIARIA_MANCANTE",
                        "La scadenza finanziaria (dataLiquidita) è obbligatoria per movimenti non ancora liquidati");
            }
            if (m.contoBancarioId != null || m.metodoPagamentoId != null) {
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
