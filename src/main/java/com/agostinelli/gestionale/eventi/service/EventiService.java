package com.agostinelli.gestionale.eventi.service;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.eventi.domain.EventoPartecipante;
import com.agostinelli.gestionale.eventi.dto.*;
import com.agostinelli.gestionale.eventi.mapper.EventoMapper;
import com.agostinelli.gestionale.eventi.repository.EventiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoPartecipantiRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.ForbiddenException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class EventiService {

    @Inject EventiRepository repo;
    @Inject EventoPartecipantiRepository partecipantiRepo;
    @Inject MovimentiRepository movimentiRepo;
    @Inject EventoMapper mapper;
    @Inject EntityManager em;

    // ── CRUD EVENTI ────────────────────────────────────────────────────────────

    @Transactional
    public EventoDTO createEvento(EventoCreateRequest req, UUID userId, boolean isAdmin) {
        if (req.dataEvento().isBefore(LocalDate.now())) {
            throw new ApiException(Response.Status.BAD_REQUEST, "DATA_NEL_PASSATO",
                    "dataEvento deve essere oggi o una data futura");
        }

        Evento e = mapper.fromRequest(req);
        e.stato = "PREVENTIVO";
        e.createdBy = userId;
        // Default BU2 (Cerimonie ed Eventi) se non specificata esplicitamente
        if (e.businessUnitId == null) {
            e.businessUnitId = 2;
        }
        repo.persist(e);
        return buildEventoDTO(e, isAdmin);
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

        // Evento completato è immutabile: nessuna modifica consentita
        if ("COMPLETATO".equals(e.stato)) {
            throw new ForbiddenException("Evento completato non modificabile");
        }

        // Transizione di stato: deve precedere l'aggiornamento degli altri campi
        if (req.stato() != null && !req.stato().equals(e.stato)) {
            EventoStatoMacchina.valida(e, req.stato(), isAdmin, req.noteAnnullamento());
            e.stato = req.stato();
            if ("ANNULLATO".equals(req.stato())) {
                e.noteAnnullamento = req.noteAnnullamento();
            }
        }

        // Non permettere la modifica di dataEvento se l'evento è già COMPLETATO
        if ("COMPLETATO".equals(e.stato) && req.dataEvento() != null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CAMPO_NON_MODIFICABILE",
                    "Non è possibile modificare dataEvento di un evento completato");
        }

        mapper.updateFromRequest(e, req);
        return buildEventoDTO(e, isAdmin);
    }

    @Transactional
    public void deleteEvento(UUID id) {
        Evento e = findOrThrow(id);

        if (!"PREVENTIVO".equals(e.stato)) {
            throw new ForbiddenException(
                    "Eliminazione consentita solo per eventi in stato PREVENTIVO");
        }

        long movCollegati = movimentiRepo.count(
                "eventoId = ?1 AND stato != ?2", id, "ANNULLATO");
        if (movCollegati > 0) {
            throw new ForbiddenException(
                    "Impossibile eliminare: l'evento ha " + movCollegati + " movimento/i collegato/i");
        }

        repo.delete(e);
    }

    // ── PAGAMENTI ─────────────────────────────────────────────────────────────

    @Transactional
    public RegistraPagamentoResult registraPagamento(UUID eventoId, PagamentoRequest req, UUID userId) {
        Evento e = findOrThrow(eventoId);

        // 1. Verifica stato
        if ("ANNULLATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Non è possibile registrare pagamenti su un evento annullato");
        }

        // 2 & 3. Importo: RIMBORSO diventa negativo (ENTRATA con importo negativo
        // → il trigger DB riduce importoIncassato correttamente via fn_ricalcola_evento)
        BigDecimal importo;
        if ("RIMBORSO".equals(req.tipo())) {
            importo = req.importo().negate();
        } else {
            if (req.importo().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_NON_VALIDO",
                        "L'importo deve essere maggiore di zero");
            }
            importo = req.importo();

            // 4. Importo non supera il residuo (solo per pagamenti positivi)
            if (e.importoTotalePreviventivato != null) {
                BigDecimal residuo = e.importoTotalePreviventivato.subtract(e.importoIncassato);
                if (importo.compareTo(residuo) > 0) {
                    throw new ApiException(Response.Status.CONFLICT, "IMPORTO_SUPERA_RESIDUO",
                            "L'importo EUR " + importo + " supera il residuo da incassare EUR " + residuo);
                }
            }
        }

        // 5. Crea il Movimento collegato
        Integer cogeId = req.contoCoge() != null ? req.contoCoge() : lookupCogeRicavi();

        Movimento m = new Movimento();
        m.tipo = "ENTRATA";
        m.importo = importo;
        m.importoCommissione = BigDecimal.ZERO;
        m.dataMovimento = req.data();
        m.eventoId = eventoId;
        m.tipoEventoMovimento = req.tipo();
        m.businessUnitId = e.businessUnitId != null ? e.businessUnitId : 2;
        m.contoBancarioId = req.contoBancarioId();
        m.metodoPagamentoId = req.metodoPagamentoId();
        m.contoCoge = cogeId;
        m.descrizione = "[EVENTO] " + e.nome + " - " + req.tipo();
        m.note = req.note();
        m.fonte = "MANUALE";
        m.stato = "REGISTRATO";
        m.createdBy = userId;
        movimentiRepo.persist(m);

        // 6. Il trigger DB (trg_z_aggiorna_totali_evento) aggiornerà importoIncassato
        // automaticamente dopo il flush. Ricarica l'evento per leggere i valori aggiornati.
        em.flush();
        em.refresh(e);

        // 7. Suggerisci completamento se saldo azzerato
        boolean suggerisci = false;
        if ("SALDO".equals(req.tipo()) && e.importoTotalePreviventivato != null) {
            BigDecimal nuovoResiduo = e.importoTotalePreviventivato.subtract(e.importoIncassato);
            suggerisci = nuovoResiduo.compareTo(BigDecimal.ZERO) <= 0;
        }

        PagamentoEventoDTO dto = new PagamentoEventoDTO(
                m.id, m.tipoEventoMovimento, m.importo, m.dataMovimento, m.note, m.stato);
        return new RegistraPagamentoResult(dto, suggerisci);
    }

    // ── CALENDARIO ────────────────────────────────────────────────────────────

    public List<EventoCalendarioDTO> getCalendario(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now();
        LocalDate t = to   != null ? to   : f.plusDays(90);

        return repo.findCalendario(f, t).stream().map(e -> {
            BigDecimal residuo = calcImportoResiduo(e);
            return new EventoCalendarioDTO(
                    e.id, e.nome, e.dataEvento, e.stato,
                    e.importoTotalePreviventivato,
                    residuo,
                    EventoCalendarioDTO.colorePerStato(e.stato));
        }).toList();
    }

    // ── DASHBOARD ─────────────────────────────────────────────────────────────

    public DashboardDTO getDashboard(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate t = to   != null ? to   : f.plusMonths(1).minusDays(1);

        Long totaleEventi = em.createQuery(
                "SELECT COUNT(e) FROM Evento e WHERE e.dataEvento >= :from AND e.dataEvento <= :to",
                Long.class)
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();

        Object incassatoRaw = em.createQuery(
                "SELECT SUM(e.importoIncassato) FROM Evento e " +
                "WHERE e.dataEvento >= :from AND e.dataEvento <= :to")
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();
        BigDecimal totaleIncassato = incassatoRaw != null ? (BigDecimal) incassatoRaw : BigDecimal.ZERO;

        // Costi reali = somma movimenti USCITA collegati agli eventi del periodo
        Object costiRaw = em.createQuery(
                "SELECT SUM(m.importo) FROM Movimento m " +
                "WHERE m.tipo = 'USCITA' AND m.stato != 'ANNULLATO' " +
                "AND m.eventoId IN " +
                "  (SELECT e.id FROM Evento e WHERE e.dataEvento >= :from AND e.dataEvento <= :to)")
                .setParameter("from", f).setParameter("to", t)
                .getSingleResult();
        BigDecimal totaleCosti = costiRaw != null ? (BigDecimal) costiRaw : BigDecimal.ZERO;

        return new DashboardDTO(
                totaleEventi != null ? totaleEventi : 0L,
                totaleIncassato,
                totaleCosti,
                totaleIncassato.subtract(totaleCosti),
                f, t);
    }

    // ── PARTECIPANTI ──────────────────────────────────────────────────────────

    @Transactional
    public EventoPartecipanteDTO aggiungiPartecipante(UUID eventoId, AggiungiPartecipanteRequest req) {
        findOrThrow(eventoId);

        // Verifica che il personale esista
        if (!personaleExists(req.personaleId())) {
            throw new ApiException(Response.Status.NOT_FOUND, "PERSONALE_NOT_FOUND",
                    "Record personale non trovato: " + req.personaleId());
        }

        // Evita duplicati (evento_id + personale_id UNIQUE in DB, ma intercettiamo prima)
        if (partecipantiRepo.existsByEventoIdAndPersonaleId(eventoId, req.personaleId())) {
            throw new ApiException(Response.Status.CONFLICT, "PARTECIPANTE_DUPLICATO",
                    "Il personale " + req.personaleId() + " è già associato a questo evento");
        }

        EventoPartecipante p = new EventoPartecipante();
        p.eventoId = eventoId;
        p.personaleId = req.personaleId();
        p.ruolo = req.ruolo();
        p.costo = req.costo();
        p.note = req.note();
        partecipantiRepo.persist(p);

        return toPartecipanteDTO(p);
    }

    @Transactional
    public void rimuoviPartecipante(Long id) {
        EventoPartecipante p = partecipantiRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Partecipante non trovato: " + id));
        partecipantiRepo.delete(p);
    }

    public List<EventoPartecipanteDTO> getPartecipantiEvento(UUID eventoId) {
        findOrThrow(eventoId);
        return partecipantiRepo.findByEventoId(eventoId).stream()
                .map(this::toPartecipanteDTO)
                .toList();
    }

    // ── COSTI REALI ───────────────────────────────────────────────────────────

    public BigDecimal calcolaCostiReali(UUID eventoId) {
        Object result = em.createQuery(
                "SELECT SUM(m.importo) FROM Movimento m " +
                "WHERE m.eventoId = :eid AND m.tipo = 'USCITA' AND m.stato != 'ANNULLATO'")
                .setParameter("eid", eventoId)
                .getSingleResult();
        return result != null ? (BigDecimal) result : BigDecimal.ZERO;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private Evento findOrThrow(UUID id) {
        return repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Evento non trovato: " + id));
    }

    /**
     * Costruisce l'EventoDTO arricchito con campi calcolati e lista pagamenti.
     * noteAnnullamento è inclusa solo per le chiamate con isAdmin=true.
     */
    private EventoDTO buildEventoDTO(Evento e, boolean isAdmin) {
        List<PagamentoEventoDTO> pagamenti = movimentiRepo.findByEventoId(e.id).stream()
                .map(m -> new PagamentoEventoDTO(
                        m.id, m.tipoEventoMovimento, m.importo, m.dataMovimento, m.note, m.stato))
                .toList();

        BigDecimal residuo = calcImportoResiduo(e);
        BigDecimal perc = calcPercentualeIncassata(e);
        BigDecimal costiReali = calcolaCostiReali(e.id);
        BigDecimal profitto = e.importoIncassato.subtract(costiReali);

        return new EventoDTO(
                e.id, e.nome, e.tipo, e.dataEvento, e.dataPreventivo,
                e.importoTotalePreviventivato, e.importoIncassato, e.caparreIncassate,
                e.costiDirettiImputati, e.stato, e.businessUnitId,
                e.contattoNome, e.contattoTelefono, e.contattoEmail, e.nOspiti,
                e.note,
                isAdmin ? e.noteAnnullamento : null,
                residuo, perc, costiReali, profitto,
                pagamenti, e.createdAt, e.createdBy);
    }

    private BigDecimal calcImportoResiduo(Evento e) {
        if (e.importoTotalePreviventivato == null) return null;
        return e.importoTotalePreviventivato.subtract(e.importoIncassato);
    }

    private BigDecimal calcPercentualeIncassata(Evento e) {
        if (e.importoTotalePreviventivato == null
                || e.importoTotalePreviventivato.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return e.importoIncassato
                .divide(e.importoTotalePreviventivato, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private EventoPartecipanteDTO toPartecipanteDTO(EventoPartecipante p) {
        return new EventoPartecipanteDTO(p.id, p.eventoId, p.personaleId, p.ruolo, p.costo, p.note);
    }

    /** Verifica l'esistenza di un record in personale tramite query nativa. */
    private boolean personaleExists(UUID personaleId) {
        Long count = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM personale WHERE id = :id")
                .setParameter("id", personaleId)
                .getSingleResult()).longValue();
        return count > 0;
    }

    /**
     * Recupera il primo conto COGE di tipo ricavi (codice 30.xx) come fallback
     * quando non viene specificato nel PagamentoRequest.
     */
    private Integer lookupCogeRicavi() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '30.%' ORDER BY codice LIMIT 1")
                .getSingleResult()).intValue();
    }
}
