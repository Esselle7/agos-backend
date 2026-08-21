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
import com.agostinelli.gestionale.eventi.domain.EventoVoce;
import com.agostinelli.gestionale.eventi.domain.EventoVoceCatalogo;
import com.agostinelli.gestionale.eventi.dto.*;
import com.agostinelli.gestionale.eventi.mapper.EventoMapper;
import com.agostinelli.gestionale.eventi.repository.EventiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoCostiDirettiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoPartecipantiRepository;
import com.agostinelli.gestionale.eventi.repository.EventoPreventivoTrackingRepository;
import com.agostinelli.gestionale.eventi.repository.EventoVoceCatalogoRepository;
import com.agostinelli.gestionale.eventi.repository.EventoVociRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class EventiService {

    private static final BigDecimal SOGLIA_SALDO = new BigDecimal("0.01");

    /** Foglie di ricavo della BU "Cerimonie ed Eventi" (piano dei conti, mastro 30.02). */
    private static final String COGE_CAPARRE_EVENTI = "30.02.001";
    private static final String COGE_SALDI_EVENTI   = "30.02.002";

    /**
     * Fase 4 — SPEC {@code docs/specs/competenza-ricavo-evento.md}.
     * Marcatore della riga di ricavo maturato e non ancora incassato. NON è un pagamento:
     * non ha banca né data finanziaria, quindi il conto economico la vede e la vista
     * finanziaria no. I filtri esistenti su CAPARRA/ACCONTO/SALDO non la intercettano.
     */
    static final String TIPO_COMPETENZA = "COMPETENZA";

    /** Data di apertura del gestionale: prima non esiste un conto economico (reset go-live). */
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 7, 1);

    /**
     * Timezone di riferimento per le validazioni di date che derivano da
     * input umano (oggi/ieri). Usare la TZ del business (Italia) evita che
     * un server UTC respinga eventi "domani Italia" creati dopo mezzanotte
     * UTC ma prima di mezzanotte locale.
     */
    private static final ZoneId ITALY = EventiRepository.FUSO;   // un solo fuso per il modulo

    @Inject EventiRepository repo;
    @Inject EventoPartecipantiRepository partecipantiRepo;
    @Inject EventoCostiDirettiRepository costiRepo;
    @Inject EventoPreventivoTrackingRepository trackingRepo;
    @Inject EventoVociRepository vociRepo;
    @Inject EventoVoceCatalogoRepository catalogoRepo;
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

        // Se in creazione è indicato un totale previsto, lo materializzo come voce iniziale:
        // mantiene l'invariante totale = Σ voci e permette la conferma immediata. Il frontend
        // voci-first non lo invia (compone le voci nel dettaglio) → nessun doppio conteggio.
        if (req.importoTotalePreviventivato() != null
                && req.importoTotalePreviventivato().compareTo(BigDecimal.ZERO) > 0) {
            EventoVoce v = new EventoVoce();
            v.eventoId           = e.id;
            v.label              = "Preventivo iniziale";
            v.prezzoUnitario     = req.importoTotalePreviventivato();
            v.quantitaPreventivo = BigDecimal.ONE;
            v.origine            = "MANUALE";
            v.createdBy          = userId;
            ricalcolaImporti(v);
            vociRepo.persist(v);
            em.flush();
            ricalcolaPreventivato(e);
        }
        return buildEventoDTO(e, true);
    }

    public EventoDTO findById(UUID id, boolean isAdmin) {
        return buildEventoDTO(findOrThrow(id), isAdmin);
    }

    /**
     * @param vista {@code LISTA} (eventi da lavorare, prima i più vicini), {@code STORICO}
     *              (solo i saldati, dal più recente) oppure {@code null} = tutti, come da sempre.
     *              Il default resta «tutti» perché altre maschere (filtri movimenti, wizard)
     *              chiedono l'elenco intero: restringerlo qui le romperebbe in silenzio.
     */
    public PagedResponse<EventoDTO> findWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to,
            String search, String vista, int page, int size, boolean isAdmin) {

        List<Evento> eventi = repo.findWithFilters(stato, buId, from, to, search, vista, page, size);
        Prefetch pre = prefetch(eventi);
        List<EventoDTO> content = eventi.stream().map(e -> buildEventoDTO(e, isAdmin, pre)).toList();
        long total = repo.countWithFilters(stato, buId, from, to, search, vista);
        return PagedResponse.of(content, page, size, total);
    }

    /**
     * Figli di un'intera pagina di eventi, caricati in 3 query invece che 3 per evento.
     *
     * PERCHE': {@link #buildEventoDTO} nasce per il singolo evento e interroga i figli uno alla
     * volta; usato dentro il map di {@link #findWithFilters} diventava un N+1 (misurato il
     * 15/08/2026: ~274 transazioni e 489 ms per 37 eventi, costo lineare a ~13 ms/evento).
     * Il CLAUDE.md di progetto tratta l'N+1 Panache come correttezza, non come ottimizzazione.
     *
     * Le mappe usano gli STESSI predicati dei metodi singoli, così la strada lista e la strada
     * dettaglio non possono divergere.
     */
    private record Prefetch(Map<UUID, List<Movimento>> movimenti,
                            Map<UUID, List<String>> allergie,
                            Map<UUID, List<EventoVoce>> voci) {}

    @SuppressWarnings("unchecked")
    private Prefetch prefetch(List<Evento> eventi) {
        List<UUID> ids = eventi.stream().map(e -> e.id).toList();
        if (ids.isEmpty()) return new Prefetch(Map.of(), Map.of(), Map.of());

        Map<UUID, List<Movimento>> mov = movimentiRepo.findByEventoIds(ids).stream()
                .collect(Collectors.groupingBy(m -> m.eventoId));
        Map<UUID, List<EventoVoce>> voci = vociRepo.findByEventoIds(ids).stream()
                .collect(Collectors.groupingBy(v -> v.eventoId));

        // Le allergie restano native come nel metodo singolo (stesso ORDER BY id).
        Map<UUID, List<String>> allergie = new HashMap<>();
        List<Object[]> righe = em.createNativeQuery(
                "SELECT evento_id, descrizione FROM evento_allergie "
                + "WHERE evento_id IN (:ids) ORDER BY id")
                .setParameter("ids", ids)
                .getResultList();
        for (Object[] r : righe) {
            allergie.computeIfAbsent((UUID) r[0], k -> new ArrayList<>()).add((String) r[1]);
        }
        return new Prefetch(mov, allergie, voci);
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

    // ── MENU (PDF / Word) ─────────────────────────────────────────────────────

    /** Risultato di {@link #getMenuStream}: stream + metadati HTTP per la risposta. */
    public record MenuResult(InputStream stream, String mimeType, String filename) {}

    /**
     * Carica il menu (PDF o Word) su R2 e salva l'URL pubblica sull'evento.
     * Lancia 404 se l'evento non esiste, 400 (da {@link R2StorageService})
     * se il tipo file non è supportato o la size supera 10 MB.
     */
    @Transactional
    public String uploadMenuPdf(UUID eventoId, java.io.InputStream content, long size, String mimeType) {
        Evento e = findOrThrow(eventoId);
        String url = r2Storage.uploadMenuPdf(eventoId, content, size, mimeType);
        e.menuPdfUrl = url;
        return url;
    }

    /**
     * Apre un InputStream sul menu dell'evento letto direttamente da R2.
     * Ritorna anche il mimeType e il filename corretti per l'header HTTP.
     * Lancia 404 se l'evento non esiste o non ha un file caricato.
     */
    @Transactional(Transactional.TxType.REQUIRED)
    public MenuResult getMenuStream(UUID eventoId) {
        Evento e = findOrThrow(eventoId);
        if (e.menuPdfUrl == null) {
            throw new ApiException(Response.Status.NOT_FOUND, "MENU_NON_TROVATO",
                    "Nessun menu caricato per questo evento");
        }
        InputStream stream = r2Storage.getMenuPdf(e.menuPdfUrl);
        String ext      = e.menuPdfUrl.substring(e.menuPdfUrl.lastIndexOf('.') + 1);
        String mimeType = switch (ext) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc"  -> "application/msword";
            default     -> "application/pdf";
        };
        return new MenuResult(stream, mimeType, "menu." + ext);
    }

    /** Rimuove il menu da R2 e azzera l'URL sull'evento (404 se l'evento non esiste). */
    @Transactional
    public void deleteMenuPdf(UUID eventoId) {
        Evento e = findOrThrow(eventoId);
        if (e.menuPdfUrl != null) {
            r2Storage.deleteMenuPdf(e.menuPdfUrl);
        }
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

        // Importo > 0. Difesa in profondità su percorso-soldi: la REST API ha già @Positive
        // su PagamentoRequest.importo, ma il controllo resta a protezione di eventuali altri
        // chiamatori del service (scheduler/import) che bypassino la bean-validation.
        // ponytail: guardia money intenzionale, non rimuovere.
        if (req.importo().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_NON_VALIDO",
                    "L'importo deve essere maggiore di zero");
        }

        // RIMBORSO non può superare quanto effettivamente incassato: altrimenti importoIncassato
        // diventerebbe negativo (ricalcolaIncassi somma il movimento negativo) corrompendo i KPI.
        if ("RIMBORSO".equals(req.tipo()) && e.importoIncassato != null
                && req.importo().compareTo(e.importoIncassato) > 0) {
            throw new ApiException(Response.Status.CONFLICT, "RIMBORSO_SUPERA_INCASSATO",
                    "Il rimborso EUR " + req.importo() + " supera l'incassato EUR " + e.importoIncassato);
        }

        // NESSUN vincolo di unicità sul tipo: più pagamenti dello stesso tipo sullo stesso
        // evento sono legittimi (una caparra pagata in due tranche è il caso normale del
        // cliente: CELLA ERIKA, 18/09/2026, "CAPARRA 3" 320,00 + "CAPARRA 3 BIS" 20,00).
        // Il doppio inserimento dello STESSO bonifico resta escluso a monte, non qui:
        // dedup dell'import (chiave_aggancio / eventoDuplicatoFallback) e invariante I2
        // "una riga parcheggiata si risolve una volta sola" (EVENTO_GIA_RISOLTO).
        // Vedi docs/specs/import-eventi-attribuzione.md (I3) e docs/adr/003.

        // RIMBORSO: importo negativo riduce importoIncassato via ricalcolaIncassi.
        // Il calcolo sta QUI, sopra la guardia anti-doppione (A1): il confronto col gemello deve
        // usare l'importo col segno con cui il movimento è finito a libro, altrimenti un rimborso
        // ripetuto verrebbe cercato col segno sbagliato e non troverebbe mai il suo gemello.
        BigDecimal importoMovimento = "RIMBORSO".equals(req.tipo())
                ? req.importo().negate()
                : req.importo();

        // Stesso evento + stesso tipo + stesso importo + stessa data finanziaria + stesso conto
        // (+ controparte, A4) = lo STESSO bonifico registrato due volte. Non è il caso dell'ADR 003
        // (più pagamenti dello stesso tipo sono legittimi: due tranche differiscono per data o
        // per importo).
        //
        // La guardia sta QUI e non nel wizard perché questo è il punto comune alle due strade che
        // hanno prodotto il doppione reale su «Greg»: registrazione manuale dalla scheda evento
        // (10/08) e conferma dal wizard incassi-evento (14/08). Le tre guardie dichiarate in
        // docs/adr/003 guardano tutte altrove — il dedup dell'import confronta le righe
        // parcheggiate fra loro, EVENTO_GIA_RISOLTO protegge la singola riga, e il tetto sul
        // residuo non scatta finché c'è capienza. Vedi docs/adr/009.
        //
        // ORDINE (A1): questa guardia corre PRIMA del tetto sul residuo. Su un evento senza
        // capienza i due controlli scattano entrambi, e quello che parla per primo detta la
        // diagnosi: col vecchio ordine il doppione veniva rifiutato come IMPORTO_SUPERA_RESIDUO,
        // che manda a correggere il preventivo — cioè a fare spazio a un incasso che non esiste.
        // Caso reale: «18esimo Erica balzaretti», 550 € su 1.050 preventivati già incassati per 550.
        movimentiRepo.findPagamentoEventoGemello(
                eventoId, req.tipo(), importoMovimento, req.data(), req.contoBancarioId(),
                req.controparte())
            .ifPresent(gemello -> {
                // Il messaggio lo legge il titolare, non un log: importi e date all'italiana.
                throw new ApiException(Response.Status.CONFLICT, "PAGAMENTO_DUPLICATO",
                        "Su questo evento è già registrato un " + req.tipo().toLowerCase()
                        + " di " + euro(req.importo()) + " del " + giorno(req.data())
                        + ", inserito il " + giorno(gemello.createdAt.atZone(ITALY).toLocalDate())
                        + ": sembra lo stesso pagamento, non un secondo incasso.");
            });

        // Importo non supera il residuo (solo per CAPARRA/ACCONTO/SALDO — non per PENALE/RIMBORSO)
        if (!"PENALE".equals(req.tipo()) && !"RIMBORSO".equals(req.tipo())
                && e.importoTotalePreviventivato != null) {
            // A3 criterio 2 — il tetto si ALLARGA, mai si stringe: è il maggiore fra il pattuito
            // (Σ preventivo, la colonna denormalizzata) e il reale (Σ consuntivo, con fallback sul
            // preventivo voce per voce). Serve all'«extra a consuntivo»: chi incassa più del
            // pattuito registra l'eccedenza come voce con preventivo 0, e il pagamento entra —
            // senza gonfiare il preventivo, che è il pattuito e non deve mentire (A2).
            // Il MAX, e non il consuntivo secco: un evento con consuntivo MINORE del preventivo
            // (voci non erogate) continuerebbe altrimenti a rifiutare pagamenti legittimi.
            BigDecimal tetto = e.importoTotalePreviventivato.max(calcolaTotaleConsuntivato(eventoId));
            BigDecimal residuo = tetto.subtract(e.importoIncassato);
            if (req.importo().compareTo(residuo) > 0) {
                // A2 — la prima domanda è sul doppione, non sul preventivo. Il messaggio arriva
                // qui solo se la guardia sopra NON ha riconosciuto un gemello, ma il pagamento
                // può essere lo stesso bonifico con un dato diverso (data o importo ritoccati):
                // mandare a "correggere il preventivo" produrrebbe preventivo falso E ricavo
                // fantasma. La seconda via è l'extra a consuntivo, che lascia intatto il pattuito.
                throw new ApiException(Response.Status.CONFLICT, "IMPORTO_SUPERA_RESIDUO",
                        "È lo stesso pagamento già registrato? " + euro(req.importo())
                        + " del " + giorno(req.data()) + " superano di "
                        + euro(req.importo().subtract(residuo)) + " il residuo da incassare ("
                        + euro(residuo) + "). Se invece è un incasso in più rispetto al pattuito, "
                        + "registra l'eccedenza come extra a consuntivo sull'evento.");
            }
        }

        // Crea il Movimento: competenza economica = data evento, data finanziaria = data pagamento
        Integer cogeId = req.contoCoge() != null ? req.contoCoge() : lookupCogeRicavi(req.tipo());

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
        // Il nome dei segnaposto porta già il marcatore "[DA ATTRIBUIRE] " (ImportTriageService:520):
        // concatenarlo a "[EVENTO] " produceva descrizioni con due prefissi in fila, del tipo
        // «[EVENTO] [DA ATTRIBUIRE] GIOVACCHINI MATTIA – SALDO». Il fatto che sia un segnaposto è
        // già portato dalla colonna is_segnaposto: nella descrizione basta il nome della controparte.
        m.descrizione           = "[EVENTO] " + spogliaSegnaposto(e.nome) + " – " + req.tipo();
        m.note                  = req.note();
        m.controparte           = req.controparte();   // A4: null se registrato dalla scheda evento
        m.fonte                 = "MANUALE";
        m.createdBy             = userId;
        movimentiRepo.persist(m);

        // Ricalcola incassi in Java (trigger rimosso in V20)
        em.flush();
        ricalcolaIncassi(e);

        // ── Auto-transizioni di stato ──────────────────────────────────────────

        // PREVENTIVATO → CONFERMATO alla prima caparra/acconto/saldo. Anche un pagamento
        // in un'unica soluzione (SALDO) conferma l'evento: senza, un SALDO pieno su un
        // PREVENTIVATO lo lasciava bloccato pur essendo incassato al 100% (il blocco
        // SALDATO sotto scatta solo da CONFERMATO).
        if ("PREVENTIVATO".equals(e.stato)
                && List.of("CAPARRA", "ACCONTO", "SALDO").contains(req.tipo())) {
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
        // BU del costo: quella indicata nel request (può differire dalla BU evento),
        // altrimenti la BU dell'evento. Il profitto dell'evento netta comunque il costo
        // (vedi calcolaCostiReali su eventoId); la BU instrada solo il movimento nel P&L.
        m.businessUnitId      = req.businessUnitId() != null ? req.businessUnitId()
                : (e.businessUnitId != null ? e.businessUnitId : 2);
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

        // Ricarico opzionale verso cliente → voce di preventivo collegata (NON genera movimento).
        // Es. DJ pagato 200 (movimento USCITA) e addebitato 250 al cliente (voce di ricavo).
        if (req.importoAddebitoCliente() != null
                && req.importoAddebitoCliente().compareTo(BigDecimal.ZERO) > 0) {
            EventoVoce voce = new EventoVoce();
            voce.eventoId           = e.id;
            voce.catalogoId         = null;
            voce.label              = etichetta;
            voce.prezzoUnitario     = req.importoAddebitoCliente();
            voce.quantitaPreventivo = BigDecimal.ONE;
            voce.origine            = "COSTO_DIRETTO";
            voce.costoDirettoId     = costo.id;
            voce.createdBy          = userId;
            ricalcolaImporti(voce);
            vociRepo.persist(voce);
            em.flush();
            ricalcolaPreventivato(e);
        }

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

        // Rimuove l'eventuale voce di ricarico collegata (evita il residuo del ON DELETE CASCADE
        // nella sessione Hibernate) e ricalcola il preventivato.
        vociRepo.deleteByCostoDirettoId(costo.id);
        costiRepo.delete(costo);
        em.flush();

        ricalcolaPreventivato(e);
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

    // ── VOCI PREVENTIVO / CONSUNTIVO (no contabilità) ────────────────────────────

    /** Listino delle voci riutilizzabili (default guidati per primi). */
    public List<EventoVoceCatalogoDTO> getCatalogoVoci() {
        return catalogoRepo.findAttivi().stream()
                .map(c -> new EventoVoceCatalogoDTO(c.id, c.label, c.isDefault, c.prezzoDefault, c.unita))
                .toList();
    }

    /** Voci di preventivo/consuntivo di un evento. */
    public List<EventoVoceDTO> getVoci(UUID eventoId) {
        findOrThrow(eventoId);
        return vociRepo.findByEventoId(eventoId).stream().map(this::toVoceDTO).toList();
    }

    /**
     * Aggiunge una voce di preventivo (riga quantità × prezzo unitario). Fornire catalogoId
     * (voce di listino, prezzo precompilato) OPPURE label (voce nuova → registrata nel listino).
     * NON genera movimenti.
     */
    @Transactional
    public EventoVoceDTO aggiungiVoce(UUID eventoId, EventoVoceRequest req, UUID userId) {
        Evento e = findOrThrow(eventoId);
        assertVociModificabili(e);

        EventoVoceCatalogo cat = risolviCatalogo(req);

        EventoVoce v = new EventoVoce();
        v.eventoId           = e.id;
        v.catalogoId         = cat.id;
        v.label              = cat.label;
        v.prezzoUnitario     = req.prezzoUnitario() != null ? req.prezzoUnitario()
                : (cat.prezzoDefault != null ? cat.prezzoDefault : BigDecimal.ZERO);
        v.quantitaPreventivo = req.quantitaPreventivo() != null ? req.quantitaPreventivo() : BigDecimal.ONE;
        v.origine            = "MANUALE";
        v.note               = req.note();
        v.createdBy          = userId;
        if (req.quantitaConsuntivo() != null) {
            assertConsuntivabile(e);
            v.quantitaConsuntivo = req.quantitaConsuntivo();
        }
        ricalcolaImporti(v);
        vociRepo.persist(v);
        em.flush();

        ricalcolaPreventivato(e);
        mvRefresh.requestRefreshAfterCommit();
        return toVoceDTO(v);
    }

    /** Aggiorna prezzo/quantità/note di una voce (PATCH: null = invariato). */
    @Transactional
    public EventoVoceDTO updateVoce(Long voceId, EventoVoceRequest req) {
        EventoVoce v = vociRepo.findByIdOptional(voceId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Voce non trovata: " + voceId));
        Evento e = findOrThrow(v.eventoId);
        assertVociModificabili(e);

        if (req.prezzoUnitario() != null)     v.prezzoUnitario     = req.prezzoUnitario();
        if (req.quantitaPreventivo() != null) v.quantitaPreventivo = req.quantitaPreventivo();
        if (req.quantitaConsuntivo() != null) {
            assertConsuntivabile(e);
            v.quantitaConsuntivo = req.quantitaConsuntivo();
        }
        if (req.note() != null) v.note = req.note();
        ricalcolaImporti(v);
        em.flush();

        ricalcolaPreventivato(e);
        mvRefresh.requestRefreshAfterCommit();
        return toVoceDTO(v);
    }

    /** Rimuove una voce MANUALE. Le voci COSTO_DIRETTO si rimuovono col relativo costo. */
    @Transactional
    public void rimuoviVoce(Long voceId) {
        EventoVoce v = vociRepo.findByIdOptional(voceId)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Voce non trovata: " + voceId));
        Evento e = findOrThrow(v.eventoId);
        assertVociModificabili(e);
        if ("COSTO_DIRETTO".equals(v.origine)) {
            throw new ApiException(Response.Status.CONFLICT, "VOCE_DA_COSTO_DIRETTO",
                    "Questa voce deriva da un costo diretto: rimuovi il costo diretto collegato");
        }
        vociRepo.delete(v);
        em.flush();

        ricalcolaPreventivato(e);
        mvRefresh.requestRefreshAfterCommit();
    }

    /** Risolve/crea la voce di catalogo per una richiesta MANUALE (upsert per label). */
    private EventoVoceCatalogo risolviCatalogo(EventoVoceRequest req) {
        if (req.catalogoId() != null) {
            EventoVoceCatalogo c = catalogoRepo.findById(req.catalogoId());
            if (c == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "CATALOGO_NON_TROVATO",
                        "Voce di catalogo non trovata: " + req.catalogoId());
            }
            return c;
        }
        String label = req.label() != null ? req.label().trim() : null;
        if (label == null || label.isBlank()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "LABEL_MANCANTE",
                    "Fornire catalogoId di una voce esistente o una label nuova");
        }
        return catalogoRepo.findByLabelIgnoreCase(label).orElseGet(() -> {
            EventoVoceCatalogo nuovo = new EventoVoceCatalogo();
            nuovo.label         = label;
            nuovo.isDefault     = false;
            nuovo.ordine        = 100;                 // in coda ai default guidati
            nuovo.attivo        = true;
            // Salva il prezzo della voce custom nel listino → riutilizzabile con prezzo precompilato
            nuovo.prezzoDefault = req.prezzoUnitario();
            catalogoRepo.persist(nuovo);
            em.flush();
            return nuovo;
        });
    }

    private void assertVociModificabili(Evento e) {
        if ("SALDATO".equals(e.stato)) {
            throw new ForbiddenException("Evento saldato: voci non modificabili");
        }
        if ("ANNULLATO".equals(e.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "EVENTO_ANNULLATO",
                    "Impossibile modificare le voci di un evento annullato");
        }
    }

    private void assertConsuntivabile(Evento e) {
        if (e.dataEvento != null && LocalDate.now(ITALY).isBefore(e.dataEvento)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONSUNTIVO_NON_ANCORA",
                    "Il consuntivo è compilabile solo dalla data dell'evento (" + e.dataEvento + ")");
        }
    }

    /**
     * Ricalcola importoTotalePreviventivato come Σ importoPreventivo delle voci.
     * Denormalizzazione coerente col pattern {@link #ricalcolaIncassi} (nessun trigger DB):
     * la colonna resta la fonte per MV, dashboard, forecasting, scadenzario, scheduler e
     * macchina a stati. Da chiamare dopo ogni mutazione di voce (incl. ricarico costo diretto).
     */
    private void ricalcolaPreventivato(Evento e) {
        BigDecimal tot = (BigDecimal) em.createQuery(
                "SELECT COALESCE(SUM(v.importoPreventivo), 0) FROM EventoVoce v WHERE v.eventoId = :eid")
                .setParameter("eid", e.id)
                .getSingleResult();
        e.importoTotalePreviventivato = tot;

        // Fase 4 / R7: il preventivo è uno dei due termini del credito. Se cambia qui, la riga
        // di competenza si riallinea subito, non alla prossima mutazione.
        allineaRigaDiCompetenza(e);
    }

    private BigDecimal calcolaTotaleConsuntivato(UUID eventoId) {
        return (BigDecimal) em.createQuery(
                "SELECT COALESCE(SUM(COALESCE(v.importoConsuntivo, v.importoPreventivo)), 0) " +
                "FROM EventoVoce v WHERE v.eventoId = :eid")
                .setParameter("eid", eventoId)
                .getSingleResult();
    }

    /** importo = quantità × prezzo unitario, per preventivo e (se presente) consuntivo. */
    private void ricalcolaImporti(EventoVoce v) {
        v.importoPreventivo = v.prezzoUnitario.multiply(v.quantitaPreventivo);
        v.importoConsuntivo = v.quantitaConsuntivo != null
                ? v.prezzoUnitario.multiply(v.quantitaConsuntivo)
                : null;
    }

    private EventoVoceDTO toVoceDTO(EventoVoce v) {
        BigDecimal scostamento = v.importoConsuntivo != null
                ? v.importoConsuntivo.subtract(v.importoPreventivo)
                : null;
        return new EventoVoceDTO(v.id, v.catalogoId, v.label,
                v.prezzoUnitario, v.quantitaPreventivo, v.quantitaConsuntivo,
                v.importoPreventivo, v.importoConsuntivo, scostamento,
                v.origine, v.costoDirettoId, v.note);
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
                "COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' AND m.dataFinanziaria IS NOT NULL THEN m.importo ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN m.tipoEventoMovimento='CAPARRA' AND m.tipo='ENTRATA' AND m.dataFinanziaria IS NOT NULL THEN m.importo ELSE 0 END), 0), " +
                "COALESCE(SUM(CASE WHEN m.tipo='USCITA' THEN m.importo ELSE 0 END), 0) " +
                "FROM Movimento m WHERE m.eventoId = :eid AND m.stato != 'ANNULLATO'")
                .setParameter("eid", evento.id)
                .getSingleResult();

        evento.importoIncassato       = (BigDecimal) r[0];
        evento.caparreIncassate       = (BigDecimal) r[1];
        evento.costiDirettiImputati   = (BigDecimal) r[2];

        allineaRigaDiCompetenza(evento);
    }

    // ── FASE 4 — competenza del ricavo evento ─────────────────────────────────
    // SPEC: docs/specs/competenza-ricavo-evento.md

    /**
     * Porta la riga di competenza dell'evento a {@code preventivato − incassato}, creandola,
     * restringendola o annullandola. Idempotente: chiamarla due volte non cambia nulla.
     *
     * È agganciata a {@link #ricalcolaIncassi} e a {@link #ricalcolaPreventivato}, cioè ai due
     * punti che muovono i termini della sottrazione: così l'invariante I1 della SPEC
     * («il ricavo di un evento nel perimetro è il preventivato, sempre») resta vero per
     * costruzione, senza che ogni chiamante debba ricordarsene.
     *
     * DECISIONE B del piano: l'incasso NON crea un secondo ricavo, restringe questo. Il ricavo
     * totale dell'evento non si muove quando arrivano i soldi — si sposta soltanto dalla colonna
     * "credito" alla colonna "incassato".
     *
     * ⚠️ INVARIANTE I2, la riga che romperebbe la vista finanziaria: banca, metodo di pagamento e
     * data finanziaria restano NULL. {@code mv_saldi_conti} non filtra su {@code data_finanziaria},
     * usa {@code COALESCE(data_finanziaria, data_movimento)}: con un conto bancario valorizzato il
     * saldo si gonfierebbe dell'intero credito (15.566 € sul solo luglio, misurati il 21/08/2026).
     */
    private void allineaRigaDiCompetenza(Evento e) {
        Optional<Movimento> esistente = movimentiRepo
                .find("eventoId = ?1 AND tipoEventoMovimento = ?2 AND stato <> 'ANNULLATO'",
                      e.id, TIPO_COMPETENZA)
                .firstResultOptional();

        // La data evento è la partition key della riga: se cambia, la riga si rifà da capo
        // invece di migrare fra partizioni.
        if (esistente.isPresent() && !esistente.get().dataMovimento.equals(e.dataEvento)) {
            esistente.get().stato = "ANNULLATO";
            esistente = Optional.empty();
        }

        BigDecimal residuo = residuoDaMaturare(e);

        if (residuo.compareTo(SOGLIA_SALDO) < 0) {
            esistente.ifPresent(m -> m.stato = "ANNULLATO");
            return;
        }

        Movimento m = esistente.orElseGet(() -> creaRigaDiCompetenza(e));
        m.importo = residuo;
    }

    /**
     * Quanto ricavo dell'evento è maturato ma non ancora incassato. Zero fuori dal perimetro
     * deciso il 21/08/2026 (SPEC §Perimetro): il ricavo nasce alla data dell'evento (D1), quindi
     * un evento futuro non ha ancora maturato nulla, e prima del go-live non esiste un conto
     * economico da alimentare — i 58 eventi SALDATO ante go-live hanno l'incassato storico senza
     * i movimenti che lo giustificano, e generare loro un credito significherebbe inventare
     * 101.823 € di ricavo.
     */
    private BigDecimal residuoDaMaturare(Evento e) {
        if (e.dataEvento == null || e.importoTotalePreviventivato == null) return BigDecimal.ZERO;
        if ("ANNULLATO".equals(e.stato))                                   return BigDecimal.ZERO;
        if (e.dataEvento.isBefore(GO_LIVE))                                return BigDecimal.ZERO;
        if (e.dataEvento.isAfter(LocalDate.now(ITALY)))                    return BigDecimal.ZERO;

        return e.importoTotalePreviventivato.subtract(incassatoDaiMovimenti(e));
    }

    /**
     * L'incassato letto dai MOVIMENTI, non dalla colonna denormalizzata {@code importoIncassato}.
     *
     * Non è pignoleria: quella colonna va stale. Annullando un pagamento da
     * {@code DELETE /api/movimenti/{id}} i totali dell'evento non vengono riallineati — è
     * documentato in {@code Movimento.java} («il trigger è stato RIMOSSO in V20 … il ricalcolo
     * scatta alla mutazione successiva di quell'evento passando da EventiService»).
     *
     * CONTROESEMPIO MISURATO il 21/08/2026 sulla copia di produzione: annullati due incassi da
     * 400 e 600 sull'evento «Elena molteni», la colonna continuava a dire 3.550,00 mentre i
     * movimenti dicevano 2.550,00 — e il credito da 1.000,00 non tornava, cioè il P&L
     * sottostimava i ricavi di 1.000,00 €. Leggere la somma vera rende il riallineamento
     * self-healing e toglie la dipendenza da una colonna che può essere vecchia.
     */
    private BigDecimal incassatoDaiMovimenti(Evento e) {
        return (BigDecimal) em.createQuery(
                "SELECT COALESCE(SUM(m.importo), 0) FROM Movimento m " +
                "WHERE m.eventoId = :eid AND m.tipo = 'ENTRATA' AND m.stato <> 'ANNULLATO' " +
                "AND m.dataFinanziaria IS NOT NULL")
                .setParameter("eid", e.id)
                .getSingleResult();
    }

    /** L'importo lo mette il chiamante: qui si costruisce solo la forma della riga (I2). */
    private Movimento creaRigaDiCompetenza(Evento e) {
        Movimento m = new Movimento();
        m.tipo                = "ENTRATA";
        m.importo             = BigDecimal.ZERO;
        m.importoCommissione  = BigDecimal.ZERO;
        m.dataMovimento       = e.dataEvento;
        m.dataCompetenza      = e.dataEvento;   // il ricavo è dell'evento, non dell'incasso
        m.dataFinanziaria     = null;           // I2 — non è denaro entrato
        m.dataLiquidita       = e.dataEvento;   // scadenza attesa: obbligatoria sui DA_LIQUIDARE
        m.contoBancarioId     = null;           // I2
        m.metodoPagamentoId   = null;           // I2
        m.stato               = "DA_LIQUIDARE";
        m.eventoId            = e.id;
        m.tipoEventoMovimento = TIPO_COMPETENZA;
        m.businessUnitId      = e.businessUnitId != null ? e.businessUnitId : 2;
        m.contoCoge           = lookupCogeRicavi("SALDO");   // 30.02.002 Saldi eventi
        m.descrizione         = "[EVENTO] " + spogliaSegnaposto(e.nome) + " – da incassare";
        m.fonte               = "MANUALE";
        m.createdBy           = e.createdBy;
        movimentiRepo.persist(m);
        return m;
    }

    /**
     * Riallinea le righe di competenza di tutti gli eventi del perimetro: backfill delle 15
     * celebrate prima di oggi, e maturazione di quelle che diventano passate col trascorrere
     * dei giorni. Idempotente — è pensata per essere rilanciata (endpoint ADMIN + scheduler
     * giornaliero), non per girare una volta sola.
     *
     * @return numero di eventi toccati e totale del credito aperto che ne risulta
     */
    @Transactional
    public Map<String, Object> allineaCompetenzaEventi() {
        List<Evento> eventi = repo.list(
                "dataEvento >= ?1 AND dataEvento <= ?2 AND stato <> 'ANNULLATO'",
                GO_LIVE, LocalDate.now(ITALY));

        for (Evento e : eventi) {
            allineaRigaDiCompetenza(e);
        }
        em.flush();

        BigDecimal credito = (BigDecimal) em.createQuery(
                "SELECT COALESCE(SUM(m.importo), 0) FROM Movimento m " +
                "WHERE m.tipoEventoMovimento = :t AND m.stato <> 'ANNULLATO'")
                .setParameter("t", TIPO_COMPETENZA)
                .getSingleResult();

        mvRefresh.requestRefreshAfterCommit();
        return Map.of("eventiEsaminati", eventi.size(), "creditoAperto", credito);
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
        return buildEventoDTO(e, isAdmin, null);
    }

    /**
     * @param pre figli già caricati per l'intera pagina ({@link #prefetch}), oppure {@code null}
     *            per la strada a evento singolo, che continua a interrogare il DB da sé.
     */
    private EventoDTO buildEventoDTO(Evento e, boolean isAdmin, Prefetch pre) {
        List<Movimento> movimenti = pre != null
                ? pre.movimenti().getOrDefault(e.id, List.of())
                : movimentiRepo.findByEventoId(e.id);

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
        List<String> allergie = pre != null
                ? pre.allergie().getOrDefault(e.id, List.of())
                : em.createNativeQuery(
                        "SELECT descrizione FROM evento_allergie WHERE evento_id = :eid ORDER BY id")
                        .setParameter("eid", e.id)
                        .getResultList();

        BigDecimal residuo    = isAdmin ? calcImportoResiduo(e) : null;
        BigDecimal perc       = isAdmin ? calcPercentualeIncassata(e) : null;
        // I costi reali sono un sottoinsieme di `movimenti`, che ha già lo stesso filtro
        // (stato != ANNULLATO): sommarli qui evita una query identica a quella appena fatta.
        BigDecimal costiReali = isAdmin ? sommaUscite(movimenti) : null;
        BigDecimal profitto   = isAdmin ? safeProfitto(e, costiReali) : null;

        List<EventoVoce> vociEntita = !isAdmin ? List.of()
                : pre != null ? pre.voci().getOrDefault(e.id, List.of())
                : vociRepo.findByEventoId(e.id);
        List<EventoVoceDTO> voci = isAdmin
                ? vociEntita.stream().map(this::toVoceDTO).toList()
                : null;
        // Stessa somma di calcolaTotaleConsuntivato, ma sulle voci che abbiamo già in mano.
        BigDecimal totaleConsuntivato = isAdmin ? sommaConsuntivo(vociEntita) : null;
        BigDecimal scostamentoConsuntivo = isAdmin
                ? totaleConsuntivato.subtract(
                        e.importoTotalePreviventivato != null ? e.importoTotalePreviventivato : BigDecimal.ZERO)
                : null;

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
                voci, totaleConsuntivato, scostamentoConsuntivo,
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

    private static final DateTimeFormatter GIORNO_IT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** «1500.00» → «1.500,00 €». Solo per i messaggi d'errore letti dal titolare. */
    private static String euro(BigDecimal v) {
        return String.format(Locale.ITALY, "%,.2f €", v);
    }

    private static String giorno(LocalDate d) {
        return d.format(GIORNO_IT);
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

    /**
     * Gemello in memoria di {@link #calcolaCostiReali}: la lista in ingresso arriva sempre da
     * {@code findByEventoId(s)}, che filtra già {@code stato != 'ANNULLATO'} — resta da filtrare
     * il solo tipo. Se i due criteri divergono, divergono anche i costi mostrati: tienili allineati.
     */
    private BigDecimal sommaUscite(List<Movimento> movimenti) {
        return movimenti.stream()
                .filter(m -> "USCITA".equals(m.tipo))
                .map(m -> m.importo)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Gemello in memoria di {@link #calcolaTotaleConsuntivato}: stesso COALESCE, stesse voci. */
    private BigDecimal sommaConsuntivo(List<EventoVoce> voci) {
        return voci.stream()
                .map(v -> v.importoConsuntivo != null ? v.importoConsuntivo : v.importoPreventivo)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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

    /**
     * Conto di ricavo su cui nasce il pagamento evento, quando il chiamante non ne impone uno.
     *
     * <p>Prima qui c'era {@code codice LIKE '30.%' ORDER BY codice LIMIT 1}, che restituisce
     * <b>30.01 "Ricavi Ristorazione e Agriturismo"</b>: un mastro (ha due figli) e per giunta
     * di un'altra business unit. Misurato il 07/08/2026 attribuendo i 26 incassi-evento
     * dell'import di luglio: 18.924,00 EUR finiti tutti su 30.01, quindi fuori dalla riga
     * "Ricavi Cerimonie ed Eventi" del conto economico. Lo storico seminato da V15 usa
     * 30.02.002, quindi lo stesso ricavo era contabilizzato in due posti diversi.
     *
     * <p>Ora si sceglie la foglia giusta: le caparre hanno un conto dedicato, tutto il resto
     * (acconto, saldo, penale, rimborso) sta sui saldi eventi.
     */
    private Integer lookupCogeRicavi(String tipoPagamento) {
        String codice = "CAPARRA".equals(tipoPagamento) ? COGE_CAPARRE_EVENTI : COGE_SALDI_EVENTI;
        return ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice)
                .getSingleResult()).intValue();
    }


    /** Marcatore dei segnaposto, definito in ImportTriageService: non va ripetuto in descrizione. */
    static final String PREFISSO_SEGNAPOSTO = "[DA ATTRIBUIRE] ";

    /** Toglie il marcatore di segnaposto dal nome evento, per non avere due prefissi in fila. */
    public static String spogliaSegnaposto(String nomeEvento) {
        if (nomeEvento == null) return "";
        return nomeEvento.startsWith(PREFISSO_SEGNAPOSTO)
                ? nomeEvento.substring(PREFISSO_SEGNAPOSTO.length())
                : nomeEvento;
    }

}
