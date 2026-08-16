package com.agostinelli.gestionale.spese.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.reporting.scheduler.MvRefreshService;
import com.agostinelli.gestionale.spese.domain.RecurringExpenseInstallment;
import com.agostinelli.gestionale.spese.domain.RecurringExpensePlan;
import com.agostinelli.gestionale.spese.dto.CancelPlanRequest;
import com.agostinelli.gestionale.spese.dto.LiquidatePlanRequest;
import com.agostinelli.gestionale.spese.dto.RecurringExpenseInstallmentDTO;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanCreateRequest;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanDetailDTO;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanSummaryDTO;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanUpdateRequest;
import com.agostinelli.gestionale.spese.dto.SkipInstallmentRequest;
import com.agostinelli.gestionale.spese.dto.UpdateInstallmentRequest;
import com.agostinelli.gestionale.spese.repository.RecurringExpenseInstallmentRepository;
import com.agostinelli.gestionale.spese.repository.RecurringExpensePlanRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class RecurringExpenseService {

    private static final Logger log = Logger.getLogger(RecurringExpenseService.class);

    @Inject RecurringExpensePlanRepository planRepo;
    @Inject RecurringExpenseInstallmentRepository installmentRepo;
    @Inject com.agostinelli.gestionale.movimenti.repository.MovimentiRepository movimentiRepo;
    @Inject EntityManager em;
    @Inject MvRefreshService mvRefresh;

    // ── CREATE ───────────────────────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO createPlan(RecurringExpensePlanCreateRequest req, UUID userId) {
        String tipoPiano = req.tipoPiano() != null ? req.tipoPiano() : "FLAT";
        validateCogePiano(req.contoCoge(), tipoPiano);

        if ("FINANZIAMENTO".equals(tipoPiano)) {
            if (req.importoDebitoIniziale() == null || req.tassoInteresseAnnuo() == null
                    || req.contoCogeInteressiId() == null) {
                throw new ApiException(Response.Status.BAD_REQUEST, "FINANZIAMENTO_INCOMPLETO",
                        "Per tipo_piano=FINANZIAMENTO sono obbligatori: importo_debito_iniziale, tasso_interesse_annuo, conto_coge_interessi_id");
            }
            validateCogeIsOnereFinanziario(req.contoCogeInteressiId());
        }

        RecurringExpensePlan plan = new RecurringExpensePlan();
        plan.descrizione           = req.descrizione();
        plan.businessUnitId        = 5; // sempre OVERHEAD
        plan.contoBancarioId       = req.contoBancarioId();
        plan.contoCoge             = req.contoCoge();
        plan.importoRata           = req.importoRata();
        plan.variazionePct         = req.variazionePct() != null ? req.variazionePct() : BigDecimal.ZERO;
        plan.giornoDelMese         = req.giornoDelMese();
        plan.frequenza             = req.frequenza();
        plan.numeroRate            = req.numeroRate();
        plan.dataPrimaRata         = req.dataInizio().withDayOfMonth(req.giornoDelMese());
        plan.note                  = req.note();
        plan.riferimentoEstrattoConto = blankToNull(req.riferimentoEstrattoConto());
        plan.createdBy             = userId;
        plan.tipoPiano             = tipoPiano;
        plan.importoDebitoIniziale = req.importoDebitoIniziale();
        plan.tassoInteresseAnnuo   = req.tassoInteresseAnnuo();
        plan.contoCogeInteressiId  = req.contoCogeInteressiId();

        planRepo.persist(plan);
        em.flush(); // ensure plan row is committed before installment FK references it

        List<RecurringExpenseInstallment> installments = generateInstallments(plan);
        installments.forEach(installmentRepo::persist);

        return buildDetail(plan, installments);
    }

    // ── LIST ─────────────────────────────────────────────────────────────────

    @Transactional
    public List<RecurringExpensePlanSummaryDTO> listPlans() {
        return planRepo.findAllOrderedByCreatedAt().stream()
                .map(plan -> {
                    List<RecurringExpenseInstallment> rate = installmentRepo.findByPianoOrdered(plan.id);
                    return buildSummary(plan, rate);
                })
                .toList();
    }

    // ── DETAIL ───────────────────────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO getPlanDetail(UUID planId) {
        RecurringExpensePlan plan = findPlanOrThrow(planId);
        List<RecurringExpenseInstallment> rate = installmentRepo.findByPianoOrdered(planId);
        return buildDetail(plan, rate);
    }

    // ── UPDATE PLAN (solo anagrafica + riconoscimento) ────────────────────────

    /**
     * Modifica descrizione, conto bancario, riferimento in estratto conto e note di un piano
     * esistente. Sono i campi che governano il RICONOSCIMENTO della rata nell'import
     * (docs/specs/ricorrenti-match-strutturato.md): senza questo endpoint un riferimento
     * sbagliato costringeva a cestinare e ricreare il piano.
     *
     * <p>Fuori: importo rata, numero rate, giorno, frequenza, tipo piano, CoGe. Cambiarli
     * imporrebbe di rigenerare le rate — comprese quelle già PAID — e romperebbe l'invariante
     * Σ quote capitale = debito iniziale. Per quelli esistono i percorsi dedicati (modifica della
     * singola rata, liquidazione, annullamento).
     */
    @Transactional
    public RecurringExpensePlanDetailDTO updatePlan(UUID planId, RecurringExpensePlanUpdateRequest req) {
        RecurringExpensePlan plan = findPlanOrThrow(planId);
        validateContoBancario(req.contoBancarioId());

        plan.descrizione = req.descrizione().trim();
        plan.contoBancarioId = req.contoBancarioId();
        plan.riferimentoEstrattoConto = blankToNull(req.riferimentoEstrattoConto());
        plan.note = blankToNull(req.note());
        planRepo.persist(plan);

        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
    }

    private void validateContoBancario(Short contoBancarioId) {
        Long n = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM conti_bancari WHERE id = :id")
                .setParameter("id", contoBancarioId).getSingleResult()).longValue();
        if (n == 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_BANCARIO_NON_TROVATO",
                    "Conto bancario inesistente: " + contoBancarioId);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ── UPDATE SINGLE INSTALLMENT ─────────────────────────────────────────────

    @Transactional
    public RecurringExpenseInstallmentDTO updateInstallment(UUID planId, UUID installmentId,
                                                            UpdateInstallmentRequest req) {
        RecurringExpensePlan plan = findPlanOrThrow(planId);
        RecurringExpenseInstallment rata = findInstallmentOrThrow(installmentId, planId);

        if (!"PENDING".equals(rata.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "RATA_NON_MODIFICABILE",
                    "Solo le rate PENDING possono essere modificate");
        }
        if (req.importo() != null) {
            rata.importo = req.importo();
            // BUG 4 fix: importo changes make the original split stale — clear it to force FLAT branch on pay
            if ("FINANZIAMENTO".equals(plan.tipoPiano)) {
                rata.quotaCapitale  = null;
                rata.quotaInteressi = null;
            }
        }
        if (req.dataScadenza() != null) rata.dataScadenza = req.dataScadenza();
        if (req.note() != null) rata.note = req.note();

        return toInstallmentDTO(rata);
    }

    // ── PAY SINGLE INSTALLMENT ───────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO payInstallment(UUID planId, UUID installmentId, UUID userId) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);
        RecurringExpenseInstallment rata = findInstallmentOrThrow(installmentId, planId);

        if (!"PENDING".equals(rata.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "RATA_NON_PAGABILE",
                    "Solo le rate PENDING possono essere pagate");
        }

        checkSaldo(plan.contoBancarioId, rata.importo);

        boolean splitFinanziamento = "FINANZIAMENTO".equals(plan.tipoPiano) && rata.quotaCapitale != null;
        if (splitFinanziamento) {
            LocalDate oggi = LocalDate.now();
            Movimento mCapitale = buildMovimento(plan, rata.quotaCapitale, oggi, userId,
                    plan.descrizione + " – Rata " + rata.numeroRata + " (cap.)");
            em.persist(mCapitale);

            Movimento mInteressi = buildMovimentoInteressi(plan, rata.quotaInteressi, oggi, userId,
                    plan.descrizione + " – Rata " + rata.numeroRata + " (int.)");
            em.persist(mInteressi);
            em.flush();

            rata.stato                = "PAID";
            rata.movimentoId          = mCapitale.id;
            rata.movimentoInteressiId = mInteressi.id;
        } else {
            Movimento m = buildMovimento(plan, rata.importo, LocalDate.now(), userId,
                    plan.descrizione + " – Rata " + rata.numeroRata);
            em.persist(m);
            em.flush();

            rata.stato       = "PAID";
            rata.movimentoId = m.id;
        }

        if (installmentRepo.findPendingByPiano(planId).isEmpty()) {
            plan.stato = "COMPLETATO";
        }

        mvRefresh.requestRefreshAfterCommit();
        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
    }

    // ── COLLEGA DA IMPORT ────────────────────────────────────────────────────

    /**
     * Aggancia un addebito bancario (riga parcheggiata dall'import) alla rata di un piano.
     * Chiamato da {@code ImportTriageService.risolviRicorrente} con azione COLLEGA.
     *
     * Due casi, ed è QUI che si chiude il doppio conteggio:
     *  - rata {@code PENDING} → la riga banca È il pagamento: si paga la rata usando la **data reale
     *    dell'addebito** (non la scadenza, non oggi) e nascono i movimenti del piano;
     *  - rata già {@code PAID} → lo scheduler è arrivato prima: **non si crea nulla**, si restituisce
     *    il movimento che esiste già, così la riga vi si aggancia invece di duplicarlo.
     *
     * @return l'id del movimento a cui agganciare la riga (capitale, per i FINANZIAMENTO).
     */
    @Transactional
    public UUID collegaRataDaImport(UUID planId, UUID installmentId, LocalDate dataAddebito,
                                    BigDecimal importoReale, UUID userId) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);
        RecurringExpenseInstallment rata = findInstallmentOrThrow(installmentId, planId);

        if ("PAID".equals(rata.stato)) {
            return rata.movimentoId;                       // già contabilizzata: nessun movimento nuovo
        }
        if (!"PENDING".equals(rata.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "RATA_NON_COLLEGABILE",
                    "Si collegano solo rate PENDING o già PAID (stato attuale: " + rata.stato + ")");
        }
        if (dataAddebito == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "DATA_MANCANTE",
                    "La riga da collegare non ha una data di addebito");
        }

        // Il piano è il previsionale, l'estratto conto è la verità: se l'addebito reale differisce
        // dalla stima, vince il reale e la rata viene riscritta (SPEC ricorrenti-importo-reale-da-import).
        BigDecimal importo = importoReale != null ? importoReale : rata.importo;

        // FINANZIAMENTO: la quota CAPITALE resta quella del piano e lo scarto va tutto sugli
        // interessi — è il comportamento di un tasso variabile. Così il debito continua a
        // estinguersi esattamente come da ammortamento e le rate successive restano valide
        // (invariante: Σ quote capitale = debito iniziale). ponytail: guardia money, non rimuovere.
        boolean finanziamento = "FINANZIAMENTO".equals(plan.tipoPiano) && rata.quotaCapitale != null;
        BigDecimal quotaInteressi = null;
        if (finanziamento) {
            quotaInteressi = importo.subtract(rata.quotaCapitale);
            if (quotaInteressi.signum() <= 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "IMPORTO_SOTTO_QUOTA_CAPITALE",
                        "L'addebito reale (€" + importo + ") non copre la quota capitale della rata (€"
                        + rata.quotaCapitale + "): non è la rata di questo piano, oppure il piano va corretto.");
            }
        }

        // NIENTE checkSaldo qui: collegare REGISTRA un addebito che la banca ha già eseguito, non
        // autorizza un pagamento. Con la guardia, dopo il reset del go-live (saldo BPM 486,93 al
        // 07/08) 3 collegamenti su 10 di luglio fallivano con SALDO_INSUFFICIENTE — fra cui la rata
        // del mutuo da 2.501,17 — cioè l'app rifiutava di registrare un fatto già avvenuto.
        // La guardia resta dov'è un'autorizzazione vera: payInstallment / liquidazione / penale.

        if (finanziamento) {
            Movimento mCap = buildMovimento(plan, rata.quotaCapitale, dataAddebito, userId,
                    plan.descrizione + " – Rata " + rata.numeroRata + " (cap.)");
            em.persist(mCap);
            Movimento mInt = buildMovimentoInteressi(plan, quotaInteressi, dataAddebito, userId,
                    plan.descrizione + " – Rata " + rata.numeroRata + " (int.)");
            em.persist(mInt);
            em.flush();
            rata.quotaInteressi       = quotaInteressi;
            rata.stato                = "PAID";
            rata.movimentoId          = mCap.id;
            rata.movimentoInteressiId = mInt.id;
        } else {
            Movimento m = buildMovimento(plan, importo, dataAddebito, userId,
                    plan.descrizione + " – Rata " + rata.numeroRata);
            em.persist(m);
            em.flush();
            rata.stato       = "PAID";
            rata.movimentoId = m.id;
        }
        rata.importo = importo;   // la rata registra il fatto, non più la stima

        if (installmentRepo.findPendingByPiano(planId).isEmpty()) {
            plan.stato = "COMPLETATO";
        }
        mvRefresh.requestRefreshAfterCommit();
        return rata.movimentoId;
    }

    // ── SKIP ─────────────────────────────────────────────────────────────────

    @Transactional
    public void skipInstallment(UUID planId, UUID installmentId, SkipInstallmentRequest req) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);
        RecurringExpenseInstallment rata = findInstallmentOrThrow(installmentId, planId);

        if (!"PENDING".equals(rata.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "RATA_NON_SKIPPABLE",
                    "Solo le rate PENDING possono essere saltate");
        }

        rata.stato = "SKIPPED";

        if ("ACCORPA".equals(req.modalita())) {
            // somma importo alla prossima rata PENDING
            installmentRepo.findNextPendingAfter(planId, rata.numeroRata)
                    .ifPresent(next -> {
                        next.importo = next.importo.add(rata.importo);
                        if ("FINANZIAMENTO".equals(plan.tipoPiano)) {
                            if (rata.quotaCapitale != null && next.quotaCapitale != null) {
                                next.quotaCapitale  = next.quotaCapitale.add(rata.quotaCapitale);
                                next.quotaInteressi = (next.quotaInteressi != null ? next.quotaInteressi : BigDecimal.ZERO)
                                        .add(rata.quotaInteressi != null ? rata.quotaInteressi : BigDecimal.ZERO);
                            } else {
                                next.quotaCapitale  = null;
                                next.quotaInteressi = null;
                            }
                        }
                    });
        } else {
            // RIMANDA: aggiunge nuova rata in fondo con la stessa scadenza offset di una frequenza
            int maxNumero = installmentRepo.maxNumeroRata(planId);
            RecurringExpenseInstallment extra = new RecurringExpenseInstallment();
            extra.pianoId        = planId;
            extra.numeroRata     = maxNumero + 1;
            extra.dataScadenza   = nextDate(plan.dataPrimaRata
                    .plusMonths((long) (maxNumero - 1) * frequenzaMesi(plan.frequenza)),
                    plan.frequenza);
            extra.importo        = rata.importo;
            extra.quotaCapitale  = rata.quotaCapitale;
            extra.quotaInteressi = rata.quotaInteressi;
            installmentRepo.persist(extra);
        }

        mvRefresh.requestRefreshAfterCommit();
    }

    // ── LIQUIDATE (maxi rata) ─────────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO liquidatePlan(UUID planId, LiquidatePlanRequest req, UUID userId) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);
        List<RecurringExpenseInstallment> pending = installmentRepo.findPendingByPiano(planId);

        if (pending.isEmpty()) {
            throw new ApiException(Response.Status.CONFLICT, "NESSUNA_RATA_PENDING",
                    "Non ci sono rate PENDING da liquidare");
        }

        if ("FINANZIAMENTO".equals(plan.tipoPiano) && pending.get(0).quotaCapitale != null) {
            BigDecimal totCapitale  = pending.stream()
                    .map(r -> r.quotaCapitale != null ? r.quotaCapitale : r.importo)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totInteressi = pending.stream()
                    .map(r -> r.quotaInteressi != null ? r.quotaInteressi : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal importoMaxi = req.importoTotale() != null ? req.importoTotale() : totCapitale.add(totInteressi);

            checkSaldo(plan.contoBancarioId, importoMaxi);

            LocalDate oggi = LocalDate.now();
            Movimento mCapitale = buildMovimento(plan, totCapitale, oggi, userId,
                    plan.descrizione + " – Liquidazione totale (cap.)");
            if (req.note() != null) mCapitale.note = req.note();
            em.persist(mCapitale);

            Movimento mInteressi = buildMovimentoInteressi(plan, totInteressi, oggi, userId,
                    plan.descrizione + " – Liquidazione totale (int.)");
            if (req.note() != null) mInteressi.note = req.note();
            em.persist(mInteressi);
            em.flush();

            pending.forEach(r -> {
                r.stato                = "PAID";
                r.movimentoId          = mCapitale.id;
                r.movimentoInteressiId = mInteressi.id;
            });
        } else {
            BigDecimal totaleResiduo = pending.stream()
                    .map(r -> r.importo)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal importoMaxi = req.importoTotale() != null ? req.importoTotale() : totaleResiduo;

            checkSaldo(plan.contoBancarioId, importoMaxi);

            Movimento maxi = buildMovimento(plan, importoMaxi, LocalDate.now(), userId,
                    plan.descrizione + " – Liquidazione totale");
            if (req.note() != null) maxi.note = req.note();
            em.persist(maxi);
            em.flush();

            pending.forEach(r -> {
                r.stato       = "PAID";
                r.movimentoId = maxi.id;
            });
        }

        plan.stato = "COMPLETATO";

        mvRefresh.requestRefreshAfterCommit();
        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
    }

    // ── CANCEL PLAN ───────────────────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO cancelPlan(UUID planId, CancelPlanRequest req, UUID userId) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);
        List<RecurringExpenseInstallment> pending = installmentRepo.findPendingByPiano(planId);
        pending.forEach(r -> r.stato = "CANCELLED");

        BigDecimal penale = req.importoPenale() != null ? req.importoPenale() : BigDecimal.ZERO;
        if (penale.compareTo(BigDecimal.ZERO) > 0) {
            Movimento movPenale = buildMovimento(plan, penale, LocalDate.now(), userId,
                    plan.descrizione + " – Penale cancellazione");
            if (req.note() != null) movPenale.note = req.note();
            em.persist(movPenale);
            em.flush(); // serve l'id generato per tracciare la penale (la userà 'cestina')
            plan.movimentoPenaleId = movPenale.id;
        }

        plan.stato = "ANNULLATO";
        plan.importoPenale = penale;

        mvRefresh.requestRefreshAfterCommit();
        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
    }

    // ── DELETE PLAN (fisica) ───────────────────────────────────────────────────

    /**
     * Elimina fisicamente un piano e le sue rate. Consentito SOLO se il piano è
     * ATTIVO e nessuna rata ha un movimento contabile collegato: così non si
     * orfanano mai scritture (USCITA fonte=RICORRENTE) né penali. Se c'è anche una
     * sola rata pagata/liquidata → 409, si usa 'annulla' che preserva la contabilità.
     * La guardia è ATOMICA: la delete condizionata elimina solo rate senza movimenti;
     * se il conteggio non torna (es. lo scheduler ha appena pagato una rata scaduta,
     * cron 06:00) la transazione fa rollback — nessuna finestra count-then-delete.
     * Le rate cascatano (FK ON DELETE CASCADE); ricorrenti_da_riconciliare → SET NULL.
     */
    @Transactional
    public void deletePlan(UUID planId) {
        RecurringExpensePlan plan = findActivePlanOrThrow(planId);

        long totale    = installmentRepo.count("pianoId = ?1", planId);
        long eliminate = installmentRepo.delete(
                "pianoId = ?1 AND movimentoId IS NULL AND movimentoInteressiId IS NULL", planId);
        if (eliminate != totale) {
            throw new ApiException(Response.Status.CONFLICT, "PIANO_CON_MOVIMENTI",
                    "Il piano ha " + (totale - eliminate) + " rata/e con movimenti contabili collegati: "
                    + "usa 'annulla' invece di eliminare per non perdere le scritture.");
        }
        planRepo.delete(plan);
    }

    // ── CESTINA (purga fisica totale di un piano ANNULLATO) ────────────────────

    /**
     * Purga DEFINITIVA di un piano ANNULLATO e di TUTTA la sua contabilità: rate +
     * ogni movimento collegato (rate PAID: movimentoId e movimentoInteressiId; più il
     * movimento di penale creato all'annullamento). Cancellare i movimenti ripristina i
     * saldi conto (gli USCITA spariscono → il saldo torna su). Irreversibile.
     *
     * Guardia: consentita SOLO su stato=ANNULLATO (409 PIANO_NON_ANNULLATO altrimenti) —
     * la delete fisica normale (ATTIVO, nessun movimento) resta su deletePlan.
     *
     * I movimenti vanno cancellati ESPLICITAMENTE per id: la tabella movimenti è
     * partizionata e non ha FK in ingresso, quindi non c'è cascade. Le rate invece
     * cascatano dal piano (FK ON DELETE CASCADE). Tutto atomico in una @Transactional.
     */
    @Transactional
    public void purgePlan(UUID planId) {
        RecurringExpensePlan plan = findPlanOrThrow(planId);
        if (!"ANNULLATO".equals(plan.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "PIANO_NON_ANNULLATO",
                    "La cestina è consentita solo su un piano ANNULLATO: usa 'annulla' prima.");
        }

        // ponytail: raccolta id in lista (volumi piccoli); duplicati ok, 'id IN' è idempotente.
        List<UUID> movIds = new ArrayList<>();
        for (RecurringExpenseInstallment rata : installmentRepo.findByPianoOrdered(planId)) {
            if (rata.movimentoId != null)          movIds.add(rata.movimentoId);
            if (rata.movimentoInteressiId != null) movIds.add(rata.movimentoInteressiId);
        }
        if (plan.movimentoPenaleId != null) movIds.add(plan.movimentoPenaleId);

        if (!movIds.isEmpty()) {
            movimentiRepo.delete("id in ?1", movIds);
        }
        planRepo.delete(plan); // le rate cascatano via FK ON DELETE CASCADE

        mvRefresh.requestRefreshAfterCommit();
    }

    // ── PROCESS SCHEDULED — RIMOSSO il 2026-08-05 ─────────────────────────────
    //
    // Fino a questa data un job giornaliero (RecurringExpenseScheduler, cron 06:00) prendeva le rate
    // scadute e le convertiva in movimenti REGISTRATI, marcandole PAID. Cioè: il gestionale dava per
    // pagata una rata alla sua data di scadenza ANCHE SE la banca non aveva addebitato nulla. Se
    // l'addebito slittava, cambiava importo o non arrivava, il dato era falso e nessuno se ne accorgeva.
    //
    // Ora la rata la conferma l'ESTRATTO CONTO:
    //  - dall'import, azione COLLEGA sulla riga parcheggiata (ImportTriageService) → collegaRataDaImport,
    //    che paga la rata con la data reale dell'addebito;
    //  - oppure a mano dal dettaglio piano, bottone "Paga" → payInstallment.
    // Finché nessuno conferma, la rata resta PENDING: la vedi nello Scadenzario e nel previsionale
    // come uscita ATTESA, che è la verità.
    //
    // Vedi docs/specs/ricorrenti-collega-da-import.md.


    // ── HELPERS ───────────────────────────────────────────────────────────────

    /**
     * CoGe ammesso per il piano, secondo il tipo:
     * <ul>
     *   <li><b>FINANZIAMENTO</b> → solo PASSIVITA: la rata rimborsa un debito, e la quota capitale
     *       deve scaricarsi su un conto patrimoniale perché l'ammortamento resti valido.</li>
     *   <li><b>FLAT</b> → PASSIVITA <i>oppure</i> COSTO: un canone, una bolletta o un premio
     *       assicurativo sono costi d'esercizio, non rimborsi di debito. Col solo vincolo
     *       PASSIVITA, 5 delle 10 spese ricorrenti reali (Enel, Telepass, TIM, Nexi, polizza)
     *       non erano rappresentabili se non forzandole su «Debiti verso fornitori» — cioè
     *       sbagliando la contabilità.</li>
     * </ul>
     */
    private void validateCogePiano(Integer cogeId, String tipoPiano) {
        String tipo = (String) em.createNativeQuery(
                "SELECT tipo FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId)
                .getSingleResult();
        if ("FINANZIAMENTO".equals(tipoPiano)) {
            if (!"PASSIVITA".equals(tipo)) {
                throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_PASSIVITA",
                        "Su un piano FINANZIAMENTO il conto COGE deve appartenere al ramo "
                        + "PASSIVITÀ E DEBITI (la quota capitale rimborsa un debito)");
            }
            return;
        }
        if (!"PASSIVITA".equals(tipo) && !"COSTO".equals(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_AMMESSO",
                    "Il conto COGE di una spesa ricorrente deve essere un COSTO (canoni, utenze, "
                    + "assicurazioni) oppure una PASSIVITÀ (rate di debito)");
        }
    }

    private void validateCogeIsOnereFinanziario(Integer cogeId) {
        String tipo = (String) em.createNativeQuery(
                "SELECT tipo FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId)
                .getSingleResult();
        if (!"ONERE_FINANZIARIO".equals(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_ONERE_FINANZIARIO",
                    "Il conto COGE interessi deve appartenere al ramo ONERI FINANZIARI (tipo ONERE_FINANZIARIO)");
        }
    }

    private List<RecurringExpenseInstallment> generateInstallments(RecurringExpensePlan plan) {
        List<RecurringExpenseInstallment> list = new ArrayList<>();
        LocalDate date = plan.dataPrimaRata;
        BigDecimal importo = plan.importoRata;

        if ("FINANZIAMENTO".equals(plan.tipoPiano)) {
            int periodoMesi = frequenzaMesi(plan.frequenza);
            BigDecimal tassoAnnuo = plan.tassoInteresseAnnuo
                    .divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP);
            BigDecimal tassoPeriodo = tassoAnnuo
                    .multiply(BigDecimal.valueOf(periodoMesi))
                    .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_UP);

            BigDecimal debitoResiduo = plan.importoDebitoIniziale;

            // BUG 3 fix: importo must exceed the very first interest slice, otherwise capitale < 0
            BigDecimal primaInteresse = debitoResiduo.multiply(tassoPeriodo).setScale(2, RoundingMode.HALF_UP);
            if (importo.compareTo(primaInteresse) <= 0) {
                throw new ApiException(Response.Status.BAD_REQUEST, "RATA_INSUFFICIENTE",
                        "L'importo rata (€" + importo + ") è ≤ agli interessi del primo periodo (€" + primaInteresse +
                        "). Aumentare l'importo rata o ridurre debito/tasso.");
            }

            for (int i = 1; i <= plan.numeroRate; i++) {
                BigDecimal interessi = debitoResiduo.multiply(tassoPeriodo)
                        .setScale(2, RoundingMode.HALF_UP);
                BigDecimal capitale  = importo.subtract(interessi)
                        .setScale(2, RoundingMode.HALF_UP);

                // Speculare a RATA_INSUFFICIENTE (BUG 3): se il debito si estingue prima
                // dell'ultima rata, le rate successive over-ammortizzano (capitale negativo
                // + interessi fantasma sull'ultima rata). ponytail: guardia money, non rimuovere.
                if (i < plan.numeroRate && debitoResiduo.subtract(capitale).signum() <= 0) {
                    throw new ApiException(Response.Status.BAD_REQUEST, "RATA_ECCESSIVA",
                            "L'importo rata (€" + importo + ") estingue il debito prima dell'ultima rata ("
                            + plan.numeroRate + "): ridurre l'importo rata o il numero di rate.");
                }

                if (i == plan.numeroRate) {
                    capitale  = debitoResiduo;
                    interessi = importo.subtract(capitale).max(BigDecimal.ZERO)
                            .setScale(2, RoundingMode.HALF_UP);
                    // L'ultima rata chiude il debito esattamente, quindi può differire
                    // leggermente dalla PMT (accumulo di arrotondamenti su 60 rate è
                    // tipicamente qualche €). Aggiorniamo importo per mantenere
                    // l'invariante rata.importo = quotaCapitale + quotaInteressi.
                    importo = capitale.add(interessi);
                }
                debitoResiduo = debitoResiduo.subtract(capitale);

                RecurringExpenseInstallment r = new RecurringExpenseInstallment();
                r.pianoId        = plan.id;
                r.numeroRata     = i;
                r.dataScadenza   = date;
                r.importo        = importo.setScale(2, RoundingMode.HALF_UP);
                r.quotaCapitale  = capitale;
                r.quotaInteressi = interessi;
                list.add(r);

                date = nextDate(date, plan.frequenza);
                if (plan.variazionePct.compareTo(BigDecimal.ZERO) != 0) {
                    importo = importo.multiply(BigDecimal.ONE.add(
                            plan.variazionePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }
        } else {
            for (int i = 1; i <= plan.numeroRate; i++) {
                RecurringExpenseInstallment r = new RecurringExpenseInstallment();
                r.pianoId      = plan.id;
                r.numeroRata   = i;
                r.dataScadenza = date;
                r.importo      = importo.setScale(2, RoundingMode.HALF_UP);
                list.add(r);

                date = nextDate(date, plan.frequenza);
                if (plan.variazionePct.compareTo(BigDecimal.ZERO) != 0) {
                    importo = importo.multiply(BigDecimal.ONE.add(
                            plan.variazionePct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)))
                            .setScale(2, RoundingMode.HALF_UP);
                }
            }
        }
        return list;
    }

    private LocalDate nextDate(LocalDate from, String frequenza) {
        return from.plusMonths(frequenzaMesi(frequenza));
    }

    private int frequenzaMesi(String frequenza) {
        return switch (frequenza) {
            case "MENSILE"     -> 1;
            case "BIMESTRALE"  -> 2;
            case "TRIMESTRALE" -> 3;
            case "ANNUALE"     -> 12;
            default -> throw new ApiException(Response.Status.BAD_REQUEST, "FREQUENZA_INVALIDA",
                    "Frequenza non valida: " + frequenza);
        };
    }

    private Movimento buildMovimento(RecurringExpensePlan plan, BigDecimal importo,
                                     LocalDate data, UUID createdBy, String descrizione) {
        Movimento m = new Movimento();
        m.tipo               = "USCITA";
        m.importo            = importo;
        m.dataMovimento      = data;
        m.dataCompetenza     = data;
        m.dataFinanziaria    = data;
        m.dataLiquidita      = data;
        m.contoBancarioId    = plan.contoBancarioId;
        m.contoCoge          = plan.contoCoge;
        m.businessUnitId     = plan.businessUnitId;
        m.descrizione        = descrizione;
        m.stato              = "REGISTRATO";
        m.fonte              = "RICORRENTE";
        m.createdBy          = createdBy;
        m.createdAt          = Instant.now();
        m.importoCommissione = BigDecimal.ZERO;
        return m;
    }

    private Movimento buildMovimentoInteressi(RecurringExpensePlan plan, BigDecimal importo,
                                              LocalDate data, UUID createdBy, String descrizione) {
        Movimento m = new Movimento();
        m.tipo               = "USCITA";
        m.importo            = importo;
        m.dataMovimento      = data;
        m.dataCompetenza     = data;
        m.dataFinanziaria    = data;
        m.dataLiquidita      = data;
        m.contoBancarioId    = plan.contoBancarioId;
        m.contoCoge          = plan.contoCogeInteressiId;
        m.businessUnitId     = plan.businessUnitId;
        m.descrizione        = descrizione;
        m.stato              = "REGISTRATO";
        m.fonte              = "RICORRENTE";
        m.createdBy          = createdBy;
        m.createdAt          = Instant.now();
        m.importoCommissione = BigDecimal.ZERO;
        return m;
    }

    private RecurringExpensePlan findPlanOrThrow(UUID id) {
        RecurringExpensePlan plan = planRepo.findById(id);
        if (plan == null) throw new ApiException(Response.Status.NOT_FOUND, "PIANO_NOT_FOUND",
                "Piano di spesa non trovato: " + id);
        return plan;
    }

    private RecurringExpensePlan findActivePlanOrThrow(UUID id) {
        RecurringExpensePlan plan = findPlanOrThrow(id);
        if (!"ATTIVO".equals(plan.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "PIANO_NON_ATTIVO",
                    "Il piano non è in stato ATTIVO");
        }
        return plan;
    }

    private RecurringExpenseInstallment findInstallmentOrThrow(UUID installmentId, UUID planId) {
        RecurringExpenseInstallment r = installmentRepo.findById(installmentId);
        if (r == null || !r.pianoId.equals(planId)) {
            throw new ApiException(Response.Status.NOT_FOUND, "RATA_NOT_FOUND",
                    "Rata non trovata");
        }
        return r;
    }

    private BigDecimal getContoBancarioSaldo(Short contoBancarioId) {
        try {
            Object result = em.createNativeQuery(
                    "SELECT cb.saldo_iniziale + COALESCE(SUM(" +
                    "  CASE WHEN m.tipo = 'ENTRATA' THEN m.importo_lordo" +
                    "       WHEN m.tipo = 'USCITA'  THEN -m.importo_lordo" +
                    "       ELSE 0 END), 0)" +
                    " FROM conti_bancari cb" +
                    " LEFT JOIN movimenti m ON m.conto_bancario_id = cb.id" +
                    "   AND m.data_finanziaria IS NOT NULL" +
                    // Mancava il filtro sugli ANNULLATI (le altre 3 formule ce l'hanno): la guardia
                    // FONDI_INSUFFICIENTI autorizzava pagamenti su denaro storniato.
                    "   AND m.stato <> 'ANNULLATO'" +
                    // V24: "saldo AL giorno X" = conta solo ciò che si muove DOPO quel giorno.
                    "   AND (cb.data_saldo_iniziale IS NULL" +
                    "        OR COALESCE(m.data_finanziaria, m.data_movimento) > cb.data_saldo_iniziale)" +
                    " WHERE cb.id = :id" +
                    " GROUP BY cb.saldo_iniziale")
                    .setParameter("id", contoBancarioId)
                    .getSingleResult();
            return result instanceof BigDecimal bd ? bd : new BigDecimal(result.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private void checkSaldo(Short contoBancarioId, BigDecimal importoNecessario) {
        BigDecimal saldo = getContoBancarioSaldo(contoBancarioId);
        if (saldo.compareTo(importoNecessario) < 0) {
            throw new ApiException(Response.Status.CONFLICT, "SALDO_INSUFFICIENTE",
                    "Saldo insufficiente: disponibile €" +
                    saldo.setScale(2, RoundingMode.HALF_UP) +
                    ", richiesto €" + importoNecessario.setScale(2, RoundingMode.HALF_UP));
        }
    }

    private String lookupContoBancarioNome(Short id) {
        try {
            return (String) em.createNativeQuery("SELECT nome FROM conti_bancari WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
        } catch (Exception e) { return ""; }
    }

    private String lookupContoCogeDescrizione(Integer id) {
        try {
            return (String) em.createNativeQuery("SELECT descrizione FROM piano_dei_conti_coge WHERE id = :id")
                    .setParameter("id", id).getSingleResult();
        } catch (Exception e) { return ""; }
    }

    private RecurringExpensePlanSummaryDTO buildSummary(RecurringExpensePlan plan,
                                                         List<RecurringExpenseInstallment> rate) {
        BigDecimal pagato  = rate.stream().filter(r -> "PAID".equals(r.stato))
                .map(r -> r.importo).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residuo = rate.stream().filter(r -> "PENDING".equals(r.stato))
                .map(r -> r.importo).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new RecurringExpensePlanSummaryDTO(
                plan.id, plan.descrizione,
                plan.contoBancarioId, lookupContoBancarioNome(plan.contoBancarioId),
                plan.contoCoge, lookupContoCogeDescrizione(plan.contoCoge),
                plan.importoRata, plan.variazionePct, plan.giornoDelMese,
                plan.frequenza, plan.numeroRate, plan.dataPrimaRata, plan.stato,
                plan.riferimentoEstrattoConto,
                (int) rate.stream().filter(r -> "PENDING".equals(r.stato)).count(),
                (int) rate.stream().filter(r -> "PAID".equals(r.stato)).count(),
                (int) rate.stream().filter(r -> "SKIPPED".equals(r.stato)).count(),
                (int) rate.stream().filter(r -> "CANCELLED".equals(r.stato)).count(),
                pagato, residuo
        );
    }

    private RecurringExpensePlanDetailDTO buildDetail(RecurringExpensePlan plan,
                                                       List<RecurringExpenseInstallment> rate) {
        BigDecimal pagato  = rate.stream().filter(r -> "PAID".equals(r.stato))
                .map(r -> r.importo).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal residuo = rate.stream().filter(r -> "PENDING".equals(r.stato))
                .map(r -> r.importo).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totale  = rate.stream().filter(r -> !"SKIPPED".equals(r.stato) && !"CANCELLED".equals(r.stato))
                .map(r -> r.importo).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totaleInteressi = rate.stream()
                .filter(r -> !"CANCELLED".equals(r.stato) && r.quotaInteressi != null)
                .map(r -> r.quotaInteressi).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totaleCapitale = rate.stream()
                .filter(r -> !"CANCELLED".equals(r.stato) && r.quotaCapitale != null)
                .map(r -> r.quotaCapitale).reduce(BigDecimal.ZERO, BigDecimal::add);

        String cogeInteressiDesc = plan.contoCogeInteressiId != null
                ? lookupContoCogeDescrizione(plan.contoCogeInteressiId) : null;

        return new RecurringExpensePlanDetailDTO(
                plan.id, plan.descrizione,
                plan.contoBancarioId, lookupContoBancarioNome(plan.contoBancarioId),
                plan.contoCoge, lookupContoCogeDescrizione(plan.contoCoge),
                plan.importoRata, plan.variazionePct, plan.giornoDelMese,
                plan.frequenza, plan.numeroRate, plan.dataPrimaRata, plan.stato,
                plan.note, plan.riferimentoEstrattoConto, pagato, residuo, totale,
                totaleInteressi, totaleCapitale,
                plan.tipoPiano, plan.tassoInteresseAnnuo, plan.importoDebitoIniziale,
                plan.contoCogeInteressiId, cogeInteressiDesc,
                getContoBancarioSaldo(plan.contoBancarioId),
                rate.stream().map(this::toInstallmentDTO).toList()
        );
    }

    private RecurringExpenseInstallmentDTO toInstallmentDTO(RecurringExpenseInstallment r) {
        return new RecurringExpenseInstallmentDTO(
                r.id, r.numeroRata, r.dataScadenza, r.importo, r.stato, r.movimentoId, r.note,
                r.quotaCapitale, r.quotaInteressi);
    }
}
