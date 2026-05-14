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

    private static final BigDecimal SOGLIA_SALDO = new BigDecimal("0.01");

    @Inject EventiRepository repo;
    @Inject EventoPartecipantiRepository partecipantiRepo;
    @Inject MovimentiRepository movimentiRepo;
    @Inject EventoMapper mapper;
    @Inject EntityManager em;

    // ── CRUD EVENTI ────────────────────────────────────────────────────────────

    @Transactional
    public EventoDTO createEvento(EventoCreateRequest req, UUID userId) {
        if (req.dataEvento() != null && req.dataEvento().isBefore(LocalDate.now())) {
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

        return new RegistraPagamentoResult(
                new PagamentoEventoDTO(m.id, m.tipoEventoMovimento, m.importo,
                        m.dataFinanziaria, m.note, m.stato),
                suggestCompletamento);
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

        return toPartecipanteDTOEnriched(p.id);
    }

    @Transactional
    public void rimuoviPartecipante(Long id) {
        EventoPartecipante p = partecipantiRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Partecipante non trovato: " + id));
        partecipantiRepo.delete(p);
    }

    @SuppressWarnings("unchecked")
    public List<EventoPartecipanteDTO> getPartecipantiEvento(UUID eventoId) {
        findOrThrow(eventoId);
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ep.id, CAST(ep.evento_id AS text), CAST(ep.personale_id AS text),
                       per.nome, per.cognome, man.nome as mansione,
                       ep.ruolo, ep.costo, ep.note
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
                (BigDecimal) r[7],
                (String) r[8]
        )).toList();
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

    private EventoDTO buildEventoDTO(Evento e, boolean isAdmin) {
        List<PagamentoEventoDTO> pagamenti = movimentiRepo.findByEventoId(e.id).stream()
                .map(m -> new PagamentoEventoDTO(
                        m.id, m.tipoEventoMovimento, m.importo, m.dataFinanziaria, m.note, m.stato))
                .toList();

        @SuppressWarnings("unchecked")
        List<String> allergie = em.createNativeQuery(
                "SELECT descrizione FROM evento_allergie WHERE evento_id = :eid ORDER BY id")
                .setParameter("eid", e.id)
                .getResultList();

        BigDecimal residuo     = calcImportoResiduo(e);
        BigDecimal perc        = calcPercentualeIncassata(e);
        BigDecimal costiReali  = calcolaCostiReali(e.id);
        BigDecimal profitto    = e.importoIncassato.subtract(costiReali);

        return new EventoDTO(
                e.id, e.nome, e.tipo, e.dataEvento, e.dataPreventivo,
                e.importoTotalePreviventivato, e.importoIncassato, e.caparreIncassate,
                e.costiDirettiImputati, e.stato, e.businessUnitId,
                e.contattoNome, e.contattoTelefono, e.contattoEmail,
                e.numeroTotalePartecipanti, e.numeroBambini, allergie,
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
                || e.importoTotalePreviventivato.compareTo(BigDecimal.ZERO) == 0) return null;
        return e.importoIncassato
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
    @SuppressWarnings("unchecked")
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

    /** Restituisce un DTO arricchito per un EventoPartecipante appena persistito. */
    @SuppressWarnings("unchecked")
    private EventoPartecipanteDTO toPartecipanteDTOEnriched(Long partecipanteId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT ep.id, CAST(ep.evento_id AS text), CAST(ep.personale_id AS text),
                       per.nome, per.cognome, man.nome as mansione,
                       ep.ruolo, ep.costo, ep.note
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
                (BigDecimal) r[7],
                (String) r[8]
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
