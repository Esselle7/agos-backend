package com.agostinelli.gestionale.eventi.service;

import com.agostinelli.gestionale.anagrafica.domain.AliquotaIva;
import com.agostinelli.gestionale.anagrafica.domain.PianoContiCoge;
import com.agostinelli.gestionale.anagrafica.repository.AliquotaIvaRepository;
import com.agostinelli.gestionale.anagrafica.repository.PianoContiCogeRepository;
import com.agostinelli.gestionale.auth.domain.User;
import com.agostinelli.gestionale.auth.domain.UserRepository;
import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.eventi.domain.EventoCostoDiretto;
import com.agostinelli.gestionale.eventi.domain.EventoPartecipante;
import com.agostinelli.gestionale.eventi.domain.EventoPreventivoTracking;
import com.agostinelli.gestionale.eventi.dto.*;
import com.agostinelli.gestionale.eventi.mapper.EventoMapper;
import com.agostinelli.gestionale.eventi.repository.EventiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoCostiDirettiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoPartecipantiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoPreventivoTrackingRepository;
import com.agostinelli.gestionale.personale.domain.Personale;
import com.agostinelli.gestionale.personale.repository.PersonaleRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.ForbiddenException;
import com.agostinelli.gestionale.infrastructure.storage.R2StorageService;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventiService {

    private static final BigDecimal SOGLIA_SALDO = new BigDecimal("0.01");

    /**
     * Timezone di riferimento per le validazioni di date che derivano da
     * input umano (oggi/ieri). Usare la TZ del business (Italia) evita che
     * un server UTC respinga eventi "domani Italia" creati dopo mezzanotte
     * UTC ma prima di mezzanotte locale.
     */
    private static final ZoneId ITALY = ZoneId.of("Europe/Rome");

    @Inject EventiRepository repo;
    @Inject EventoPartecipantiRepository partecipantiRepo;
    @Inject EventoCostiDirettiRepository costiRepo;
    @Inject EventoPreventivoTrackingRepository trackingRepo;
    @Inject PersonaleRepository personaleRepo;
    @Inject MovimentiRepository movimentiRepo;
    @Inject PianoContiCogeRepository pianoContiRepo;
    @Inject AliquotaIvaRepository aliquotaRepo;
    @Inject EventoMapper mapper;
    @Inject EntityManager em;
    @Inject UserRepository userRepository;
    @Inject MvRefreshService mvRefresh;
    @Inject R2StorageService r2Storage;

    // ── CRUD EVENTI ────────────────────────────────────────────────────────────

    @Transactional
    public EventoDTO createEvento(EventoCreateRequest req, UUID userId) {
        if (req.dataEvento() != null && req.dataEvento().isBefore(LocalDate.now(ITALY))) {
            throw new ApiException(Response.Status.BAD_REQUEST, "DATA_NEL_PASSATO",
                    "La data evento non può essere nel passato");
        }
        Evento e = mapper.fromRequest(req);
        e.stato    = "PREVENTIVATO";
        e.createdBy = userId;
        e.nOspiti  = req.numeroTotalePartecipanti();
        e.numeroTotalePartecipanti = req.numeroTotalePartecipanti();
        e.numeroBambini = req.numeroBambini();
        if (e.businessUnitId == null) {
            e.businessUnitId = 2;
        }
        repo.persist(e);
        salvaAllergie(e.id, req.allergie());
        bulkAssegnaPersonale(e.id, req.personaleIds());
        return buildEventoDTO(e, true);
    }

    public EventoDTO findById(UUID id, boolean isAdmin) {
        return buildEventoDTO(findOrThrow(id), isAdmin);
    }

    public PagedResponse<EventoDTO> findWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to,
            String search, int page, int size, boolean isAdmin) {

        List<EventoDTO> content = repo.findWithFilters(stato, buId, from, to, search, page, size)
                .stream().map(e -> buildEventoDTO(e, isAdmin)).toList();
        long total = repo.countWithFilters(stato, buId, from, to, search);
        return PagedResponse.of(content, page, size, total);
    }

    @Transactional
    public EventoDTO updateEvento(UUID id, EventoUpdateRequest req, UUID userId, boolean isAdmin) {
        Evento e = findOrThrow(id);

        if ("SALDATO".equals(e.stato)) {
            throw new ForbiddenException("Evento saldato: non modificabile");
        }

        // Transizione di stato manuale
        if (req.stato() != null && !req.stato().equals(e.stato)) {
            EventoStatoMacchina.valida(e, req.stato(), isAdmin, req.noteAnnullamento());
            e.stato = req.stato();
            if ("ANNULLATO".equals(req.stato())) {
                e.noteAnnullamento = req.noteAnnullamento();
            }
        }

        mapper.updateFromRequest(e, req);

        // Aggiorna i campi non gestiti dal mapper (PATCH semantics)
        if (req.numeroTotalePartecipanti() != null) {
            e.numeroTotalePartecipanti = req.numeroTotalePartecipanti();
            e.nOspiti = req.numeroTotalePartecipanti();
        }
        if (req.numeroBambini() != null) {
            e.numeroBambini = req.numeroBambini();
        }
        if (req.allergie() != null) {
            salvaAllergie(e.id, req.allergie());
        }

        // Se personaleIds presente, sostituisce integralmente i partecipanti
        if (req.personaleIds() != null) {
            em.createQuery("DELETE FROM EventoPartecipante ep WHERE ep.eventoId = :eid")
                    .setParameter("eid", e.id)
                    .executeUpdate();
            bulkAssegnaPersonale(e.id, req.personaleIds());
        }

        return buildEventoDTO(e, isAdmin);
    }

    @Transactional
    public void deleteEvento(UUID id) {
        Evento e = findOrThrow(id);

        if (!"PREVENTIVATO".equals(e.stato)) {
            throw new ForbiddenException("Eliminazione consentita solo per eventi in stato PREVENTIVATO");
        }

        long movAttivi = movimentiRepo.count(
                "eventoId = ?1 AND stato != ?2", id, "ANNULLATO");
        if (movAttivi > 0) {
            throw new ForbiddenException(
                    "Impossibile eliminare: l'evento ha " + movAttivi + " movimento/i attivo/i collegato/i");
        }

        // La FK movimenti.evento_id è RESTRICT: anche i movimenti ANNULLATO bloccano il delete.
        // Li scolleghiamo prima di eliminare l'evento (il record del movimento resta per audit).
        movimentiRepo.update("eventoId = null WHERE eventoId = ?1 AND stato = ?2", id, "ANNULLATO");

        repo.delete(e);
    }

    // ── MENU PDF ──────────────────────────────────────────────────────────────

    /**
     * Carica il menu PDF su R2 e salva l'URL pubblica sull'evento.
     * Lancia 404 se l'evento non esiste, 400 (da {@link R2StorageService})
     * se il file non è un PDF valido o supera 10 MB.
     */
    @Transactional
    public String uploadMenuPdf(UUID eventoId, java.io.InputStream content, long size, String mimeType) {
        Evento e = findOrThrow(eventoId);
        String url = r2Storage.uploadMenuPdf(eventoId, content, size, mimeType);
        e.menuPdfUrl = url;
        return url;
    }

    /**
     * Apre un InputStream sul menu PDF dell'evento letto direttamente da R2.
     * Lancia 404 se l'evento non esiste o non ha un PDF caricato.
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public InputStream getMenuPdfStream(UUID eventoId) {
        Evento e = findOrThrow(eventoId);
        if (e.menuPdfUrl == null) {
            throw new ApiException(Response.Status.NOT_FOUND, "MENU_PDF_NON_TROVATO",
                    "Nessun menu PDF per questo evento");
        }
        return r2Storage.getMenuPdf(eventoId);
    }

    /** Rimuove il menu PDF da R2 e azzera l'URL sull'evento (404 se non esiste). */
    @Transactional
    public void deleteMenuPdf(UUID eventoId) {
        Evento e = findOrThrow(eventoId);
        r2Storage.deleteMenuPdf(eventoId);
        e.menuPdfUrl = null;
    }

    // ── PAGAMENTI ─────────────────────────────────────────────────────────────

    @Transactional
    public RegistraPagamentoResult registraPagamento(UUID eventoId, PagamentoRequest req, UUID userId) {
        Evento e = findOrThrow(eventoId);

        // SALDATO: nessun pagamento aggiuntivo
        if ("SALDATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_SALDATO",
                    "Evento già saldato: nessun pagamento aggiuntivo consentito");
        }

        // ANNULLATO: solo PENALE consentita
        if ("ANNULLATO".equals(e.stato) && !"PENALE".equals(req.tipo())) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Su un evento annullato è consentita solo la registrazione di una PENALE");
        }

        // Importo > 0
        if (req.importo().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_NON_VALIDO",
                    "L'importo deve essere maggiore di zero");
        }

        // Vincolo unicità: max 1 CAPARRA, 1 ACCONTO, 1 SALDO
        if (List.of("CAPARRA", "ACCONTO", "SALDO").contains(req.tipo())) {
            long contaEsistenti = movimentiRepo.count(
                    "eventoId = ?1 AND tipoEventoMovimento = ?2 AND stato != ?3",
                    eventoId, req.tipo(), "ANNULLATO");
            if (contaEsistenti > 0) {
                throw new ApiException(Response.Status.CONFLICT, "PAGAMENTO_GIA_PRESENTE",
                        "Esiste già un pagamento di tipo " + req.tipo() + " per questo evento");
            }
        }

        // Importo non supera il residuo (solo per CAPARRA/ACCONTO/SALDO — non per PENALE/RIMBORSO)
        if (!"PENALE".equals(req.tipo()) && !"RIMBORSO".equals(req.tipo())
                && e.importoTotalePreviventivato != null) {
            BigDecimal residuo = e.importoTotalePreviventivato.subtract(e.importoIncassato);
            if (req.importo().compareTo(residuo) > 0) {
                throw new ApiException(Response.Status.CONFLICT, "IMPORTO_SUPERA_RESIDUO",
                        "L'importo EUR " + req.importo() + " supera il residuo da incassare EUR " + residuo);
            }
        }

        // Crea il Movimento: competenza economica = data evento, data finanziaria = data pagamento
        Integer cogeId = req.contoCoge() != null ? req.contoCoge() : lookupCogeRicavi();
        // RIMBORSO: importo negativo riduce importoIncassato via ricalcolaIncassi
        BigDecimal importoMovimento = "RIMBORSO".equals(req.tipo())
                ? req.importo().negate()
                : req.importo();

        Movimento m = new Movimento();
        m.tipo                  = "ENTRATA";
        m.importo               = importoMovimento;
        m.importoCommissione    = BigDecimal.ZERO;
        m.dataMovimento         = e.dataEvento;       // competenza economica = data evento
        m.dataFinanziaria       = req.data();          // data effettiva pagamento
        m.dataLiquidita         = req.data();
        m.stato                 = "ATTIVO";
        m.eventoId              = eventoId;
        m.tipoEventoMovimento   = req.tipo();
        m.contoBancarioId       = req.contoBancarioId();
        m.metodoPagamentoId     = req.metodoPagamentoId();
        m.businessUnitId        = e.businessUnitId != null ? e.businessUnitId : 2;
        m.contoCoge             = cogeId;
        m.descrizione           = "[EVENTO] " + e.nome + " – " + req.tipo();
        m.note                  = req.note();
        m.fonte                 = "MANUALE";
        m.createdBy             = userId;
        movimentiRepo.persist(m);

        // Ricalcola incassi in Java (trigger rimosso in V20)
        em.flush();
        ricalcolaIncassi(e);

        // ── Auto-transizioni di stato ──────────────────────────────────────────

        // PREVENTIVATO → CONFERMATO alla prima caparra o acconto
        if ("PREVENTIVATO".equals(e.stato)
                && List.of("CAPARRA", "ACCONTO").contains(req.tipo())) {
            e.stato = "CONFERMATO";
        }

        // CONFERMATO → SALDATO quando residuo ≤ €0.01
        boolean suggestCompletamento = false;
        if ("CONFERMATO".equals(e.stato) && e.importoTotalePreviventivato != null) {
            BigDecimal residuoAggiornato = e.importoTotalePreviventivato.subtract(e.importoIncassato);
            if (residuoAggiornato.compareTo(SOGLIA_SALDO) <= 0) {
                e.stato = "SALDATO";
                suggestCompletamento = true;
            }
        }

        mvRefresh.requestRefreshAfterCommit();
        return new RegistraPagamentoResult(
                new PagamentoEventoDTO(m.id, m.tipoEventoMovimento, m.importo,
                        m.dataFinanziaria, m.note, m.stato),
                suggestCompletamento);
    }

    // ── CALENDARIO ────────────────────────────────────────────────────────────

    public List<EventoCalendarioDTO> getCalendario(LocalDate from, LocalDate to, boolean isAdmin) {
        LocalDate f = from != null ? from : LocalDate.now(ITALY);
        LocalDate t = to   != null ? to   : f.plusDays(90);

        return repo.findCalendario(f, t).stream().map(e -> {
            BigDecimal residuo = isAdmin ? calcImportoResiduo(e) : null;
            return new EventoCalendarioDTO(
                    e.id, e.nome, e.dataEvento, e.stato,
                    isAdmin ? e.importoTotalePreviventivato : null,
                    residuo,
                    EventoCalendarioDTO.colorePerStato(e.stato));
        }).toList();
    }

    /**
     * Lista degli eventi a cui l'utente DIPENDENTE è assegnato come partecipante.
     * Restituisce lista vuota se l'utente non è collegato a un record personale
     * (campo {@code users.personale_id} null).
     *
     * I dati restituiti seguono la visibility policy non-ADMIN: nessun dato
     * finanziario, ma date di journey visibili.
     */
    public PagedResponse<EventoDTO> getMieiEventi(UUID userId, int page, int size) {
        User user = userRepository.findById(userId);
        if (user == null || user.personaleId == null) {
            return PagedResponse.of(List.of(), page, size, 0L);
        }
        List<EventoDTO> content = repo.findByPersonaleId(user.personaleId, page, size)
                .stream().map(e -> buildEventoDTO(e, false)).toList();
        long total = repo.countByPersonaleId(user.personaleId);
        return PagedResponse.of(content, page, size, total);
    }

    // ── DASHBOARD ─────────────────────────────────────────────────────────────

    /**
     * KPI dashboard. {@code isAdmin=false} nasconde i campi finanziari
     * (totaleIncassato, totaleCosti, profittoTotale).
     */
    public DashboardDTO getDashboard(LocalDate from, LocalDate to, boolean isAdmin) {
        LocalDate f = from != null ? from : LocalDate.now(ITALY).withDayOfMonth(1);
        LocalDate t = to   != null ? to   : f.plusMonths(1).minusDays(1);

        Long totaleEventi = em.createQuery(
                "SELECT COUNT(e) FROM Evento e WHERE e.dataEvento >= :from AND e.dataEvento <= :to",
                Long.class)
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();

        if (!isAdmin) {
            return new DashboardDTO(
                    totaleEventi != null ? totaleEventi : 0L,
                    null, null, null, f, t);
        }

        Object incassatoRaw = em.createQuery(
                "SELECT SUM(e.importoIncassato) FROM Evento e " +
                "WHERE e.dataEvento >= :from AND e.dataEvento <= :to " +
                "AND e.stato != 'ANNULLATO'")
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();
        BigDecimal totaleIncassato = incassatoRaw != null ? (BigDecimal) incassatoRaw : BigDecimal.ZERO;

        Object costiRaw = em.createQuery(
                "SELECT SUM(m.importo) FROM Movimento m " +
                "WHERE m.tipo = 'USCITA' AND m.stato != 'ANNULLATO' " +
                "AND m.eventoId IN " +
                "  (SELECT e.id FROM Evento e WHERE e.dataEvento >= :from AND e.dataEvento <= :to AND e.stato != 'ANNULLATO')")
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();
        BigDecimal totaleCosti = costiRaw != null ? (BigDecimal) costiRaw : BigDecimal.ZERO;

        return new DashboardDTO(
                totaleEventi != null ? totaleEventi : 0L,
                totaleIncassato, totaleCosti,
                totaleIncassato.subtract(totaleCosti), f, t);
    }

    // ── PARTECIPANTI ──────────────────────────────────────────────────────────

    @Transactional
    public EventoPartecipanteDTO aggiungiPartecipante(UUID eventoId, AggiungiPartecipanteRequest req) {
        findOrThrow(eventoId);

        if (!personaleExists(req.personaleId())) {
            throw new ApiException(Response.Status.NOT_FOUND, "PERSONALE_NOT_FOUND",
                    "Record personale non trovato: " + req.personaleId());
        }
        if (partecipantiRepo.existsByEventoIdAndPersonaleId(eventoId, req.personaleId())) {
            throw new ApiException(Response.Status.CONFLICT, "PARTECIPANTE_DUPLICATO",
                    "Il personale " + req.personaleId() + " è già associato a questo evento");
        }

        EventoPartecipante p = new EventoPartecipante();
        p.eventoId    = eventoId;
        p.personaleId = req.personaleId();
        p.ruolo       = req.ruolo();
        p.costo       = req.costo();
        p.note        = req.note();
        partecipantiRepo.persist(p);
        em.flush();

        // L'endpoint che chiama questo metodo è ADMIN-only, quindi costo è sempre visibile
        return toPartecipanteDTOEnriched(p.id, true);
    }

    @Transactional
    public void rimuoviPartecipante(Long id) {
        EventoPartecipante p = partecipantiRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Partecipante non trovato: " + id));

        // Se c'è un costo orario collegato, annulla il movimento e ricalcola i costi
        if (p.movimentoId != null) {
            movimentiRepo.findByIdOptional(p.movimentoId).ifPresent(m -> m.stato = "ANNULLATO");
            em.flush();
            repo.findByIdOptional(p.eventoId).ifPresent(e -> {
                ricalcolaIncassi(e);
                mvRefresh.requestRefreshAfterCommit();
            });
        }

        partecipantiRepo.delete(p);
    }

    /**
     * Alloca ore a un partecipante con retribuzione ORARIA: calcola
     * ore * pagaOraria, genera un movimento USCITA DA_LIQUIDARE collegato
     * all'evento (impatta i costi diretti) e salva il riferimento sul
     * partecipante. Se esiste già un'allocazione, il movimento precedente
     * viene annullato (ri-allocazione).
     */
    @Transactional
    public EventoPartecipanteDTO allocaOre(Long partecipanteId, BigDecimal ore, UUID userId) {
        EventoPartecipante p = partecipantiRepo.findByIdOptional(partecipanteId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Partecipante non trovato: " + partecipanteId));
        Evento e = findOrThrow(p.eventoId);
        if ("ANNULLATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Impossibile allocare ore su un evento annullato");
        }
        if (ore == null || ore.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "ORE_NON_VALIDE",
                    "Le ore devono essere maggiori di zero");
        }

        Personale per = personaleRepo.findByIdOptional(p.personaleId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Dipendente non trovato: " + p.personaleId));
        if (!"ORARIA".equals(per.tipoRetribuzione)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "NON_ORARIA",
                    "Il dipendente non è retribuito a ore");
        }
        if (per.pagaOraria == null || per.pagaOraria.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PAGA_ORARIA_MANCANTE",
                    "Paga oraria non impostata per il dipendente");
        }

        BigDecimal totale = per.pagaOraria.multiply(ore).setScale(2, RoundingMode.HALF_UP);

        // Ri-allocazione: annulla il movimento precedente
        if (p.movimentoId != null) {
            movimentiRepo.findByIdOptional(p.movimentoId).ifPresent(m -> m.stato = "ANNULLATO");
            em.flush();
        }

        PianoContiCoge conto = pianoContiRepo.find("codice", "40.13.006").firstResult();
        if (conto == null) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "CONTO_NON_TROVATO",
                    "Conto CoGe non trovato: 40.13.006");
        }

        Movimento m = new Movimento();
        m.tipo                = "USCITA";
        m.importo             = totale;
        m.importoCommissione  = BigDecimal.ZERO;
        m.dataMovimento       = e.dataEvento;
        m.dataFinanziaria     = null;
        m.dataLiquidita       = null;
        m.stato               = "DA_LIQUIDARE";
        m.fonte               = "MANUALE";
        m.eventoId            = e.id;
        m.tipoEventoMovimento = null;
        m.contoCoge           = conto.id;
        m.businessUnitId      = e.businessUnitId != null ? e.businessUnitId : 2;
        m.descrizione         = "[COSTO EVENTO] Personale a ore – " + per.nome + " " + per.cognome + " – " + e.nome;
        m.createdBy           = userId;
        movimentiRepo.persist(m);
        em.flush();

        p.ore           = ore;
        p.costo         = totale;
        p.movimentoId   = m.id;
        p.movimentoData = m.dataMovimento;

        ricalcolaIncassi(e);
        mvRefresh.requestRefreshAfterCommit();
        return toPartecipanteDTOEnriched(p.id, true);
    }

    /** Rimuove l'allocazione ore: annulla il movimento e azzera ore/costo del partecipante. */
    @Transactional
    public EventoPartecipanteDTO rimuoviOre(Long partecipanteId) {
        EventoPartecipante p = partecipantiRepo.findByIdOptional(partecipanteId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Partecipante non trovato: " + partecipanteId));
        Evento e = findOrThrow(p.eventoId);

        if (p.movimentoId != null) {
            movimentiRepo.findByIdOptional(p.movimentoId).ifPresent(m -> m.stato = "ANNULLATO");
            em.flush();
        }
        p.ore           = null;
        p.costo         = null;
        p.movimentoId   = null;
        p.movimentoData = null;

        ricalcolaIncassi(e);
        mvRefresh.requestRefreshAfterCommit();
        return toPartecipanteDTOEnriched(p.id, true);
    }

    /**
     * Restituisce i partecipanti di un evento applicando la visibility policy
     * sul campo {@code costo}: ADMIN vede il valore reale, DIPENDENTE riceve
     * {@code null} per non esporre il costo di colleghi.
     */
    @SuppressWarnings("unchecked")
    public List<EventoPartecipanteDTO> getPartecipantiEvento(UUID eventoId, boolean isAdmin) {
        findOrThrow(eventoId);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ep.id, CAST(ep.evento_id AS text), CAST(ep.personale_id AS text),
                       per.nome, per.cognome, man.nome as mansione,
                       ep.ruolo, ep.costo,
                       per.tipo_retribuzione, per.paga_oraria, ep.ore, ep.movimento_id,
                       ep.note
                FROM evento_partecipanti ep
                JOIN personale per ON per.id = ep.personale_id
                LEFT JOIN mansioni man ON man.id = per.mansione_id
                WHERE ep.evento_id = :eid
                ORDER BY man.nome NULLS LAST, per.cognome, per.nome
                """)
                .setParameter("eid", eventoId)
                .getResultList();

        return rows.stream().map(r -> new EventoPartecipanteDTO(
                ((Number) r[0]).longValue(),
                UUID.fromString((String) r[1]),
                UUID.fromString((String) r[2]),
                (String) r[3],
                (String) r[4],
                (String) r[5],
                (String) r[6],
                isAdmin ? (BigDecimal) r[7] : null,
                (String) r[8],
                isAdmin ? (BigDecimal) r[9] : null,
                isAdmin ? (BigDecimal) r[10] : null,
                r[11] != null,
                (String) r[12]
        )).toList();
    }

    // ── COSTI DIRETTI EVENTO ────────────────────────────────────────────────────

    private static final BigDecimal IVA_ORDINARIA = new BigDecimal("22.0");

    /**
     * Registra un costo diretto sull'evento. Genera un movimento USCITA
     * DA_LIQUIDARE (competenza = data evento, nessuna data di liquidazione)
     * collegato all'evento, esattamente come {@link #registraPagamento} ma sul
     * lato uscite. Aggiorna {@code costiDirettiImputati} via ricalcolaIncassi.
     */
    @Transactional
    public EventoCostoDirettoDTO aggiungiCostoDiretto(UUID eventoId, EventoCostoDirettoRequest req, UUID userId) {
        Evento e = findOrThrow(eventoId);
        if ("ANNULLATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Impossibile aggiungere costi a un evento annullato");
        }

        String etichetta = etichettaPerVoce(req.voce(), req.etichetta());
        String codiceConto = codiceContoPerVoce(req.voce());

        BigDecimal importoEffettivo = req.importo();
        if (importoEffettivo == null || importoEffettivo.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_NON_VALIDO",
                    "L'importo del costo deve essere maggiore di zero");
        }

        PianoContiCoge conto = pianoContiRepo.find("codice", codiceConto).firstResult();
        if (conto == null) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "CONTO_NON_TROVATO",
                    "Conto CoGe non trovato: " + codiceConto);
        }

        // Movimento USCITA, DA_LIQUIDARE: competenza = data evento, nessuna liquidazione
        Movimento m = new Movimento();
        m.tipo                = "USCITA";
        m.importo             = importoEffettivo;
        m.importoCommissione  = BigDecimal.ZERO;
        m.dataMovimento       = e.dataEvento;
        m.dataFinanziaria     = null;
        m.dataLiquidita       = null;
        m.stato               = "DA_LIQUIDARE";
        m.fonte               = "MANUALE";
        m.eventoId            = e.id;
        m.tipoEventoMovimento = null;
        m.contoCoge           = conto.id;
        m.businessUnitId      = e.businessUnitId != null ? e.businessUnitId : 2;
        m.descrizione         = "[COSTO EVENTO] " + etichetta + " – " + e.nome;
        m.note                = req.note();
        m.createdBy           = userId;
        AliquotaIva iva = aliquotaRepo.find("aliquota", IVA_ORDINARIA).firstResult();
        if (iva != null) {
            m.aliquotaIvaId = iva.id;
        }
        movimentiRepo.persist(m);
        em.flush();

        EventoCostoDiretto costo = new EventoCostoDiretto();
        costo.eventoId        = e.id;
        costo.tipoCosto       = req.tipoCosto();
        costo.voce            = req.voce();
        costo.etichetta       = etichetta;
        costo.importo         = importoEffettivo;
        costo.movimentoId     = m.id;
        costo.movimentoData   = m.dataMovimento;
        costo.contoCogeId     = conto.id;
        costo.note            = req.note();
        costo.createdBy       = userId;
        costiRepo.persist(costo);
        em.flush();

        ricalcolaIncassi(e);
        mvRefresh.requestRefreshAfterCommit();
        return toCostoDTO(costo, conto.codice);
    }

    /**
     * Rimuove un costo diretto: annulla il movimento collegato (stato=ANNULLATO,
     * preservando l'audit trail) ed elimina il record del costo, poi ricalcola
     * i costi imputati dell'evento.
     */
    @Transactional
    public void rimuoviCostoDiretto(Long costoId, UUID userId) {
        EventoCostoDiretto costo = costiRepo.findByIdOptional(costoId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Costo diretto non trovato: " + costoId));
        Evento e = findOrThrow(costo.eventoId);
        if ("ANNULLATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Impossibile rimuovere costi da un evento annullato");
        }

        if (costo.movimentoId != null) {
            movimentiRepo.findByIdOptional(costo.movimentoId)
                    .ifPresent(m -> m.stato = "ANNULLATO");
            em.flush();
        }

        costiRepo.delete(costo);
        em.flush();

        ricalcolaIncassi(e);
        mvRefresh.requestRefreshAfterCommit();
    }

    /** Lista dei costi diretti di un evento con i campi derivati del catering. */
    public List<EventoCostoDirettoDTO> getCostiDiretti(UUID eventoId) {
        findOrThrow(eventoId);
        return costiRepo.findByEventoId(eventoId).stream()
                .map(c -> {
                    String codice = c.contoCogeId != null
                            ? pianoContiRepo.findById(c.contoCogeId).codice
                            : null;
                    return toCostoDTO(c, codice);
                })
                .toList();
    }

    private String etichettaPerVoce(String voce, String etichettaCustom) {
        return switch (voce) {
            case "DJ"    -> "DJ e Intrattenimento";
            case "TORTA" -> "Torta";
            case "CUSTOM" -> {
                if (etichettaCustom == null || etichettaCustom.isBlank()) {
                    throw new ApiException(Response.Status.BAD_REQUEST, "ETICHETTA_MANCANTE",
                            "L'etichetta è obbligatoria per un costo personalizzato");
                }
                yield etichettaCustom.trim();
            }
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "VOCE_NON_VALIDA",
                    "Voce di costo non valida: " + voce);
        };
    }

    private String codiceContoPerVoce(String voce) {
        return switch (voce) {
            case "DJ"     -> "40.13.002";
            case "TORTA"  -> "40.13.004";
            case "CUSTOM" -> "40.13.005";
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "VOCE_NON_VALIDA",
                    "Voce di costo non valida: " + voce);
        };
    }

    private EventoCostoDirettoDTO toCostoDTO(EventoCostoDiretto c, String contoCodice) {
        return new EventoCostoDirettoDTO(
                c.id, c.tipoCosto, c.voce, c.etichetta, c.importo,
                c.movimentoId, c.movimentoData, contoCodice, c.note, c.createdAt);
    }

    // ── MONITORING PREVENTIVATO (no contabilità) ────────────────────────────────

    /** Lista delle voci di tracciamento (AFFITTO/CATERING) del preventivato. */
    public List<EventoPreventivoTrackingDTO> getPreventivoTracking(UUID eventoId) {
        findOrThrow(eventoId);
        return trackingRepo.findByEventoId(eventoId).stream().map(this::toTrackingDTO).toList();
    }

    /**
     * Upsert di una voce di tracciamento (una sola AFFITTO e una CATERING per evento).
     * NON genera movimenti e NON tocca i KPI contabili dell'evento.
     */
    @Transactional
    public EventoPreventivoTrackingDTO salvaPreventivoTracking(UUID eventoId, EventoPreventivoTrackingRequest req, UUID userId) {
        Evento e = findOrThrow(eventoId);
        if (!"AFFITTO".equals(req.tipo()) && !"CATERING".equals(req.tipo())) {
            throw new ApiException(Response.Status.BAD_REQUEST, "TIPO_NON_VALIDO",
                    "Tipo tracciamento non valido: " + req.tipo());
        }

        EventoPreventivoTracking t = trackingRepo.findByEventoIdAndTipo(eventoId, req.tipo()).orElse(null);
        boolean nuovo = t == null;
        if (nuovo) {
            t = new EventoPreventivoTracking();
            t.eventoId  = e.id;
            t.tipo      = req.tipo();
            t.createdBy = userId;
        }

        if ("AFFITTO".equals(req.tipo())) {
            t.importoIncasso    = req.importoIncasso();
            t.costoPerPersona   = null;
            t.prezzoPerPersona  = null;
            t.numPersone        = null;
        } else {
            t.importoIncasso    = null;
            t.costoPerPersona   = req.costoPerPersona();
            t.prezzoPerPersona  = req.prezzoPerPersona();
            t.numPersone        = req.numPersone() != null ? req.numPersone() : e.numeroTotalePartecipanti;
        }
        t.note = req.note();

        if (nuovo) {
            trackingRepo.persist(t);
        }
        em.flush();
        return toTrackingDTO(t);
    }

    @Transactional
    public void rimuoviPreventivoTracking(Long trackingId) {
        EventoPreventivoTracking t = trackingRepo.findByIdOptional(trackingId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Voce di tracciamento non trovata: " + trackingId));
        trackingRepo.delete(t);
    }

    private EventoPreventivoTrackingDTO toTrackingDTO(EventoPreventivoTracking t) {
        BigDecimal costoTotale = null, ricavo = null, margine = null, marginePerc = null;
        if ("CATERING".equals(t.tipo) && t.numPersone != null) {
            BigDecimal n = BigDecimal.valueOf(t.numPersone);
            if (t.costoPerPersona != null)  costoTotale = t.costoPerPersona.multiply(n);
            if (t.prezzoPerPersona != null) ricavo      = t.prezzoPerPersona.multiply(n);
            if (costoTotale != null && ricavo != null) {
                margine = ricavo.subtract(costoTotale);
                if (ricavo.compareTo(BigDecimal.ZERO) > 0) {
                    marginePerc = margine
                            .divide(ricavo, 4, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return new EventoPreventivoTrackingDTO(
                t.id, t.tipo, t.importoIncasso,
                t.costoPerPersona, t.prezzoPerPersona, t.numPersone,
                costoTotale, ricavo, margine, marginePerc, t.note);
    }

    // ── HELPERS PRIVATI ───────────────────────────────────────────────────────

    private Evento findOrThrow(UUID id) {
        return repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Evento non trovato: " + id));
    }

    /**
     * Associa in bulk un elenco di personale a un evento.
     * Ignora silenziosamente id non esistenti e duplicati.
     */
    private void bulkAssegnaPersonale(UUID eventoId, List<UUID> personaleIds) {
        if (personaleIds == null || personaleIds.isEmpty()) return;
        for (UUID pid : personaleIds) {
            if (!personaleExists(pid)) continue;
            if (partecipantiRepo.existsByEventoIdAndPersonaleId(eventoId, pid)) continue;
            EventoPartecipante ep = new EventoPartecipante();
            ep.eventoId    = eventoId;
            ep.personaleId = pid;
            partecipantiRepo.persist(ep);
        }
    }

    /**
     * Ricalcola importoIncassato, caparreIncassate e costiDirettiImputati direttamente
     * in Java tramite query JPQL aggregata — sostituisce il trigger trg_z_aggiorna_totali_evento
     * rimosso in V20. Deve essere chiamato dopo ogni flush() che coinvolge movimenti dell'evento.
     */
    @SuppressWarnings("unchecked")
    private void ricalcolaIncassi(Evento evento) {
        Object[] r = (Object[]) em.createQuery(
                "SELECT " +
                "COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' THEN m.importo ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN m.tipoEventoMovimento='CAPARRA' AND m.tipo='ENTRATA' THEN m.importo ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN m.tipo='USCITA' THEN m.importo ELSE 0 END), 0) " +
                "FROM Movimento m WHERE m.eventoId = :eid AND m.stato != 'ANNULLATO'")
                .setParameter("eid", evento.id)
                .getSingleResult();

        evento.importoIncassato       = (BigDecimal) r[0];
        evento.caparreIncassate       = (BigDecimal) r[1];
        evento.costiDirettiImputati   = (BigDecimal) r[2];
    }

    /**
     * Costruisce l'{@link EventoDTO} applicando la visibility policy del ruolo.
     *
     * I campi ADMIN-only (importi, percentuali, profitto, costi reali, note di
     * annullamento, dettaglio importo/note dei pagamenti) sono nascosti per i
     * non-ADMIN. Le date di journey ({@code dataConferma}, {@code dataSaldo})
     * e la lista pagamenti sanitizzata sono visibili anche ai DIPENDENTE per
     * permettere al frontend di renderizzare lo stato di avanzamento.
     */
    private EventoDTO buildEventoDTO(Evento e, boolean isAdmin) {
        List<Movimento> movimenti = movimentiRepo.findByEventoId(e.id);

        List<PagamentoEventoDTO> pagamenti = movimenti.stream()
                .map(m -> new PagamentoEventoDTO(
                        m.id,
                        m.tipoEventoMovimento,
                        isAdmin ? m.importo : null,
                        m.dataFinanziaria,
                        isAdmin ? m.note : null,
                        m.stato))
                .toList();

        LocalDate dataConferma = computeDataConferma(movimenti);
        LocalDate dataSaldo    = computeDataSaldo(movimenti);

        @SuppressWarnings("unchecked")
        List<String> allergie = em.createNativeQuery(
                "SELECT descrizione FROM evento_allergie WHERE evento_id = :eid ORDER BY id")
                .setParameter("eid", e.id)
                .getResultList();

        BigDecimal residuo    = isAdmin ? calcImportoResiduo(e) : null;
        BigDecimal perc       = isAdmin ? calcPercentualeIncassata(e) : null;
        BigDecimal costiReali = isAdmin ? calcolaCostiReali(e.id) : null;
        BigDecimal profitto   = isAdmin ? safeProfitto(e, costiReali) : null;

        return new EventoDTO(
                e.id, e.nome, e.tipo, e.dataEvento, e.dataPreventivo,
                isAdmin ? e.importoTotalePreviventivato : null,
                isAdmin ? e.importoIncassato : null,
                isAdmin ? e.caparreIncassate : null,
                isAdmin ? e.costiDirettiImputati : null,
                e.stato, e.businessUnitId,
                e.contattoNome, e.contattoTelefono, e.contattoEmail,
                e.numeroTotalePartecipanti, e.numeroBambini, allergie,
                e.note,
                e.menuPdfUrl,
                isAdmin ? e.noteAnnullamento : null,
                residuo, perc, costiReali, profitto,
                dataConferma, dataSaldo,
                pagamenti,
                e.createdAt, e.createdBy);
    }

    /**
     * Data del primo movimento non annullato di tipo CAPARRA o ACCONTO
     * (ENTRATA), o {@code null} se nessun pagamento di conferma è stato
     * registrato.
     */
    private LocalDate computeDataConferma(List<Movimento> movimenti) {
        return movimenti.stream()
                .filter(m -> !"ANNULLATO".equals(m.stato))
                .filter(m -> "CAPARRA".equals(m.tipoEventoMovimento)
                          || "ACCONTO".equals(m.tipoEventoMovimento))
                .map(m -> m.dataFinanziaria)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Data del SALDO non annullato, o {@code null} se non saldato. */
    private LocalDate computeDataSaldo(List<Movimento> movimenti) {
        return movimenti.stream()
                .filter(m -> !"ANNULLATO".equals(m.stato))
                .filter(m -> "SALDO".equals(m.tipoEventoMovimento))
                .map(m -> m.dataFinanziaria)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private BigDecimal safeProfitto(Evento e, BigDecimal costiReali) {
        BigDecimal incassato = e.importoIncassato != null ? e.importoIncassato : BigDecimal.ZERO;
        BigDecimal costi     = costiReali        != null ? costiReali        : BigDecimal.ZERO;
        return incassato.subtract(costi);
    }

    private BigDecimal calcImportoResiduo(Evento e) {
        if (e.importoTotalePreviventivato == null) return null;
        BigDecimal incassato = e.importoIncassato != null ? e.importoIncassato : BigDecimal.ZERO;
        return e.importoTotalePreviventivato.subtract(incassato);
    }

    private BigDecimal calcPercentualeIncassata(Evento e) {
        if (e.importoTotalePreviventivato == null
                || e.importoTotalePreviventivato.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal incassato = e.importoIncassato != null ? e.importoIncassato : BigDecimal.ZERO;
        return incassato
                .divide(e.importoTotalePreviventivato, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calcolaCostiReali(UUID eventoId) {
        Object result = em.createQuery(
                "SELECT SUM(m.importo) FROM Movimento m " +
                "WHERE m.eventoId = :eid AND m.tipo = 'USCITA' AND m.stato != 'ANNULLATO'")
                .setParameter("eid", eventoId)
                .getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    /** Sostituisce integralmente le allergie dell'evento. */
    private void salvaAllergie(UUID eventoId, List<String> allergie) {
        em.createNativeQuery("DELETE FROM evento_allergie WHERE evento_id = :eid")
          .setParameter("eid", eventoId)
          .executeUpdate();
        if (allergie == null || allergie.isEmpty()) return;
        for (String a : allergie) {
            if (a != null && !a.isBlank()) {
                em.createNativeQuery(
                        "INSERT INTO evento_allergie (evento_id, descrizione) VALUES (:eid, :desc)")
                  .setParameter("eid", eventoId)
                  .setParameter("desc", a.trim())
                  .executeUpdate();
            }
        }
    }

    /**
     * Restituisce un DTO arricchito per un EventoPartecipante appena persistito.
     * Il flag {@code isAdmin} controlla la visibilità del campo {@code costo}.
     */
    @SuppressWarnings("unchecked")
    private EventoPartecipanteDTO toPartecipanteDTOEnriched(Long partecipanteId, boolean isAdmin) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ep.id, CAST(ep.evento_id AS text), CAST(ep.personale_id AS text),
                       per.nome, per.cognome, man.nome as mansione,
                       ep.ruolo, ep.costo,
                       per.tipo_retribuzione, per.paga_oraria, ep.ore, ep.movimento_id,
                       ep.note
                FROM evento_partecipanti ep
                JOIN personale per ON per.id = ep.personale_id
                LEFT JOIN mansioni man ON man.id = per.mansione_id
                WHERE ep.id = :pid
                """)
                .setParameter("pid", partecipanteId)
                .getResultList();

        if (rows.isEmpty()) throw new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND", "Partecipante non trovato");
        Object[] r = rows.get(0);
        return new EventoPartecipanteDTO(
                ((Number) r[0]).longValue(),
                UUID.fromString((String) r[1]),
                UUID.fromString((String) r[2]),
                (String) r[3],
                (String) r[4],
                (String) r[5],
                (String) r[6],
                isAdmin ? (BigDecimal) r[7] : null,
                (String) r[8],
                isAdmin ? (BigDecimal) r[9] : null,
                isAdmin ? (BigDecimal) r[10] : null,
                r[11] != null,
                (String) r[12]
        );
    }

    private boolean personaleExists(UUID personaleId) {
        Long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM personale WHERE id = :id")
                .setParameter("id", personaleId)
                .getSingleResult()).longValue();
        return count > 0;
    }

    private Integer lookupCogeRicavi() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '30.%' ORDER BY codice LIMIT 1")
                .getSingleResult()).intValue();
    }

}
