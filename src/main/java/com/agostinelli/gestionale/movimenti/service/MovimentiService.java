package com.agostinelli.gestionale.movimenti.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.*;
import com.agostinelli.gestionale.movimenti.importlayer.CogeRiservatoEventi;
import com.agostinelli.gestionale.movimenti.importlayer.CogeTransitorio;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

@ApplicationScoped
public class MovimentiService {

    @Inject MovimentiRepository repo;
    @Inject MovimentoMapper mapper;
    @Inject Validator validator;
    @Inject MvRefreshService mvRefresh;
    @Inject EntityManager em;

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

        // Stessa trappola su dataCompetenza, ma silenziosa: mv_conto_economico_mensile
        // filtra "data_competenza IS NOT NULL", quindi un PUT che la omette faceva
        // sparire il movimento dal Conto Economico lasciandolo nei saldi. @PrePersist
        // copre solo la creazione: qui si replica lo stesso default.
        if (m.dataCompetenza == null) {
            m.dataCompetenza = m.dataMovimento;
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

    // ── CESTINA (purga fisica di un movimento ANNULLATO) ──────────────────────
    // Spec: docs/specs/movimento-cestina-fisica.md

    /**
     * Toglie DAVVERO la riga da {@code movimenti}. Irreversibile in tabella, ma non nella storia:
     * il trigger {@code trg_audit_movimenti} scrive l'intera riga in {@code audit_log.dati_precedenti}.
     *
     * <p><b>Perché serve l'annullamento prima (I1):</b> {@code mv_saldi_conti} somma solo i
     * movimenti con {@code stato <> 'ANNULLATO'}. Su una riga già annullata la cancellazione fisica
     * vale zero euro su ogni saldo — il denaro si è mosso quando è stata annullata. Il due-passi non
     * è burocrazia: è ciò che rende l'operazione priva di effetti contabili.
     *
     * <p><b>Perché la guardia sui riferimenti (I2):</b> {@code movimenti} è partizionata e <b>non ha
     * FK in ingresso</b> (verificato: 0 righe in {@code pg_constraint}), quindi il database non
     * protegge niente. Dieci colonne di nove tabelle puntano ai movimenti per id: se una di loro
     * punta a questa riga, cancellarla lascerebbe un orfano silenzioso — una rata PAID senza la sua
     * scrittura, una riga d'import che dice «già a libro» indicando il vuoto.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public void cestinaMovimento(UUID id) {
        Movimento m = repo.findById(id);
        if (m == null) {
            throw new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Movimento non trovato");
        }
        if (!"ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_NON_ANNULLATO",
                    "La cestina è consentita solo su un movimento ANNULLATO (stato attuale: "
                    + m.stato + "): annullalo prima, così il saldo si muove una volta sola.");
        }
        List<String> refs = riferimentiA(m);
        if (!refs.isEmpty()) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_REFERENZIATO",
                    "Questo movimento è ancora collegato a: " + String.join(", ", refs)
                    + ". Sciogli il collegamento prima di cestinarlo.");
        }
        repo.delete(m);
        mvRefresh.requestRefreshAfterCommit();
    }

    /** Chi punta a questo movimento, detto in italiano. Vuoto = si può cestinare. */
    @SuppressWarnings("unchecked")
    private List<String> riferimentiA(Movimento m) {
        List<String> refs = new ArrayList<>();
        // Questi due li porta il movimento stesso: nessuna query serve.
        if (m.eventoId != null)  refs.add("un evento");
        if (m.cespiteId != null) refs.add("un cespite");
        // Le altre nove colonne stanno su altre tabelle e nessuna FK le difende: una query sola.
        refs.addAll(em.createNativeQuery(
                "SELECT 'una rata di un piano ricorrente' WHERE EXISTS (SELECT 1 FROM recurring_expense_installment WHERE movimento_id = :id OR movimento_interessi_id = :id)" +
                " UNION ALL SELECT 'la penale di un piano ricorrente' WHERE EXISTS (SELECT 1 FROM recurring_expense_plan WHERE movimento_penale_id = :id)" +
                " UNION ALL SELECT 'un costo diretto di evento'       WHERE EXISTS (SELECT 1 FROM evento_costi_diretti WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'un pagamento di evento'           WHERE EXISTS (SELECT 1 FROM evento_partecipanti WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'una riga della coda ricorrenti'   WHERE EXISTS (SELECT 1 FROM ricorrenti_da_riconciliare WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'una riga ambigua dell''import'    WHERE EXISTS (SELECT 1 FROM import_ambiguita WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'una riga scartata dell''import'   WHERE EXISTS (SELECT 1 FROM import_scartati WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'un matching differito'            WHERE EXISTS (SELECT 1 FROM matching_differiti WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'un conflitto di keyword'          WHERE EXISTS (SELECT 1 FROM keyword_conflitto WHERE movimento_id = :id)" +
                " UNION ALL SELECT 'una firma di keyword appresa'     WHERE EXISTS (SELECT 1 FROM keyword_firma WHERE movimento_origine_id = :id)")
                .setParameter("id", m.id).getResultList());
        return refs;
    }


    // ── DIVISIONE DI UN MOVIMENTO CUMULATIVO ──────────────────────────────────
    // Spec: docs/specs/debug-con-cliente/riba-split-importo.md

    /**
     * Divide un movimento (tipicamente una RiBa/effetti cumulativa) in N quote con conto CoGe e
     * Business Unit propri. Il padre passa ad ANNULLATO, i figli nascono con le sue stesse
     * coordinate finanziarie.
     *
     * <p><b>Il denaro non si crea e non si distrugge (I1):</b> la somma delle quote deve fare
     * l'importo del padre al centesimo, e la verifica sta QUI — non solo nell'interfaccia — perché
     * è l'unico punto da cui si scrive. Niente arrotondamenti d'ufficio sull'ultima quota: uno
     * scarto si mostra, non si aggiusta.
     *
     * <p><b>Il saldo non si muove (I2):</b> nessun figlio cambia conto, data o segno rispetto al
     * padre. In particolare {@code dataFinanziaria} si propaga identica (I4): è il campo su cui
     * {@code mv_saldi_conti} decide se il movimento sta dentro o fuori dal saldo iniziale
     * (filtro strict {@code >}), e un figlio senza quella data ricadrebbe su {@code dataMovimento}.
     *
     * <p><b>Una riga bancaria resta importata una volta sola (I3):</b> il
     * {@code riferimentoEsterno} resta sul padre annullato e i figli nascono senza. Due ragioni:
     * {@code idx_movimenti_dedup_import} è UNIQUE su (fonte, riferimento_esterno, data_movimento)
     * e N figli lo violerebbero; e la dedup del prossimo import legge i riferimenti senza filtrare
     * lo stato, quindi il padre annullato continua a fare da scudo al ri-import.
     */
    @CacheInvalidateAll(cacheName = "dashboard-kpi")
    @CacheInvalidateAll(cacheName = "dashboard-andamento")
    @CacheInvalidateAll(cacheName = "dashboard-bufatturato")
    @Transactional
    public List<MovimentoDTO> dividiMovimento(UUID id, DividiMovimentoRequest req, UUID userId) {
        // Lock pessimistico: due divisioni in parallelo si mettono in fila, la seconda trova il
        // padre già ANNULLATO e si ferma (R6). Con un UPDATE condizionato il controllo sarebbe
        // altrettanto atomico, ma lascerebbe in sessione un'entità con lo stato vecchio.
        Movimento padre = repo.findByIdOptional(id, LockModeType.PESSIMISTIC_WRITE)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento non trovato: " + id));

        if ("ANNULLATO".equals(padre.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_GIA_DIVISO",
                    "Il movimento è annullato: se è già stato diviso, i figli sono a libro. "
                    + "Dividerlo di nuovo creerebbe il doppio delle righe.");
        }
        if (padre.eventoId != null) {
            throw new ApiException(Response.Status.CONFLICT, "SPLIT_SU_EVENTO_NON_SUPPORTATO",
                    "Questo movimento è collegato a un evento: i totali dell'evento sono "
                    + "denormalizzati e dividerlo qui li falserebbe. Scollegalo prima, o dividi "
                    + "un movimento non legato a eventi.");
        }

        // R3 — quadratura al centesimo. Prima di scrivere qualunque cosa (I5).
        BigDecimal somma = req.quote().stream()
                .map(DividiMovimentoRequest.Quota::importo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (somma.compareTo(padre.importo) != 0) {
            BigDecimal scarto = somma.subtract(padre.importo);
            throw new ApiException(Response.Status.BAD_REQUEST, "SPLIT_NON_QUADRA",
                    "Le quote sommano EUR " + somma + " contro EUR " + padre.importo
                    + " del movimento: scarto di EUR " + scarto.abs()
                    + (scarto.signum() > 0 ? " in più." : " in meno.")
                    + " La divisione non può creare né perdere denaro.");
        }

        // Ogni quota deve avere una destinazione vera: le stesse guardie della catalogazione.
        for (DividiMovimentoRequest.Quota q : req.quote()) {
            String codice = cogeCodiceOrThrow(q.contoCogeId());
            CogeTransitorio.vieta(codice);      // dividere per lasciare i pezzi «da classificare» non è dividere
            CogeRiservatoEventi.vieta(codice);  // un ricavo-evento senza evento non compare in nessun bilancio
        }

        List<Movimento> figli = new ArrayList<>();
        for (int i = 0; i < req.quote().size(); i++) {
            DividiMovimentoRequest.Quota q = req.quote().get(i);
            Movimento f = new Movimento();
            // Coordinate finanziarie: identiche al padre, o il saldo si muove.
            f.tipo                = padre.tipo;
            f.dataMovimento       = padre.dataMovimento;
            f.dataCompetenza      = padre.dataCompetenza;
            f.dataFinanziaria     = padre.dataFinanziaria;
            f.dataLiquidita       = padre.dataLiquidita;
            f.contoBancarioId     = padre.contoBancarioId;
            f.metodoPagamentoId   = padre.metodoPagamentoId;
            f.fonte               = padre.fonte;
            f.fonteImportazioneId = padre.fonteImportazioneId;
            f.stato               = padre.stato;
            f.controparte         = padre.controparte;
            // Per quota: il perché della divisione.
            f.importo             = q.importo();
            f.importoCommissione  = BigDecimal.ZERO;
            f.contoCoge           = q.contoCogeId();
            f.businessUnitId      = q.businessUnitId();
            f.fornitoreId         = q.fornitoreId();
            f.descrizione         = q.descrizione() != null && !q.descrizione().isBlank()
                    ? q.descrizione()
                    : padre.descrizione + " — quota " + (i + 1) + "/" + req.quote().size();
            f.riferimentoEsterno  = null;   // I3: il riferimento resta sul padre
            f.note                = "Quota " + (i + 1) + "/" + req.quote().size()
                                    + " della divisione del movimento " + padre.id;
            f.createdBy           = userId;
            repo.persist(f);
            figli.add(f);
        }
        em.flush();   // servono gli id dei figli per la traccia sul padre (R7)

        padre.stato = "ANNULLATO";
        padre.note = (padre.note == null || padre.note.isBlank() ? "" : padre.note + " | ")
                + "Diviso in " + figli.size() + " quote: "
                + figli.stream().map(f -> f.id.toString()).collect(java.util.stream.Collectors.joining(", "));

        mvRefresh.requestRefreshAfterCommit();
        return figli.stream().map(mapper::toDTO).toList();
    }

    /** Codice del conto CoGe, o 400 se l'id non esiste: le guardie ragionano sul codice, non sull'id. */
    private String cogeCodiceOrThrow(Integer cogeId) {
        List<?> r = em.createNativeQuery("SELECT codice FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId).getResultList();
        if (r.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_TROVATO",
                    "Conto CoGe inesistente: " + cogeId);
        }
        return (String) r.get(0);
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

    /** Crediti (ENTRATA) / debiti (USCITA) di apertura pre-2026 ancora da liquidare. */
    public List<MovimentoDTO> listPartiteApertura(String tipo) {
        return repo.findPartiteApertura(tipo).stream().map(mapper::toDTO).toList();
    }

    /**
     * Attribuisce SOLO il conto/cassa al movimento, senza toccare gli altri campi.
     * L'update generale è full-overwrite (azzererebbe dataFinanziaria e de-liquiderebbe il
     * movimento): per il popup "Senza banca" serve questo percorso mirato.
     */
    @Transactional
    public MovimentoDTO assegnaContoBancario(UUID id, Short contoBancarioId) {
        Movimento m = findActiveOrThrow(id);
        // ponytail: guardia money, non rimuovere. Invariante I2: una riga di competenza evento
        // non è denaro entrato, e con un conto valorizzato entra in mv_saldi_conti via
        // COALESCE(data_finanziaria, data_movimento).
        if ("COMPETENZA".equals(m.tipoEventoMovimento)) {
            throw new ApiException(Response.Status.CONFLICT, "COMPETENZA_SENZA_BANCA",
                    "Una riga di competenza evento non è denaro entrato: non può avere un conto bancario.");
        }
        m.contoBancarioId = contoBancarioId;
        mvRefresh.requestRefreshAfterCommit();
        return mapper.toDTO(m);
    }

    public PagedResponse<MovimentoDTO> findWithFilters(
            MovimentiFilterQuery filter, int page, int size, String sort) {

        List<MovimentoDTO> content = repo.findWithFilters(filter, page, size, sort)
                .stream().map(mapper::toDTO).toList();

        long total = repo.countWithFilters(filter);

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

    public MovimentiSommarioDTO getSommario(MovimentiFilterQuery filter) {

        List<Object[]> rows = repo.sommarioByStatoTipo(filter);

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
     *
     * <p><b>Scorporo, non sottrazione.</b> {@code m.importo} è il LORDO IVA inclusa
     * (colonna {@code importo_lordo}: corrispettivo Billy, totale bonifico), quindi
     * l'imponibile è {@code lordo / (1 + aliquota)}. Il vecchio calcolo
     * {@code iva = lordo * aliquota} trattava lo stesso campo come imponibile nella
     * prima riga e come lordo nella seconda: su 400,70 al 10% scriveva 40,07 di IVA
     * invece di 36,43, cioè un'aliquota implicita dell'11,11%, e sottostimava i
     * ricavi del P&L (che legge importo_imponibile) di ~0,9%.
     * Invariante: {@code imponibile + iva = lordo} e {@code iva / imponibile = aliquota}.
     */
    private void applyDerivedAmounts(Movimento m, BigDecimal importoLordo, BigDecimal aliquotaIva) {
        if (importoLordo != null && importoLordo.compareTo(m.importo) > 0) {
            m.importoCommissione = importoLordo.subtract(m.importo);
        }
        if (aliquotaIva != null && aliquotaIva.compareTo(BigDecimal.ZERO) > 0) {
            m.importoImponibile = m.importo.divide(
                    BigDecimal.ONE.add(aliquotaIva), 2, RoundingMode.HALF_UP);
            m.importoIva = m.importo.subtract(m.importoImponibile);
        }
    }

}
