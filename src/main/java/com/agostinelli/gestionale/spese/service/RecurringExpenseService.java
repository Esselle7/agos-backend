package com.agostinelli.gestionale.spese.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.spese.domain.RecurringExpenseInstallment;
import com.agostinelli.gestionale.spese.domain.RecurringExpensePlan;
import com.agostinelli.gestionale.spese.dto.CancelPlanRequest;
import com.agostinelli.gestionale.spese.dto.LiquidatePlanRequest;
import com.agostinelli.gestionale.spese.dto.RecurringExpenseInstallmentDTO;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanCreateRequest;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanDetailDTO;
import com.agostinelli.gestionale.spese.dto.RecurringExpensePlanSummaryDTO;
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
    @Inject EntityManager em;

    // ── CREATE ───────────────────────────────────────────────────────────────

    @Transactional
    public RecurringExpensePlanDetailDTO createPlan(RecurringExpensePlanCreateRequest req, UUID userId) {
        validateCogeIsPassivita(req.contoCoge());

        String tipoPiano = req.tipoPiano() != null ? req.tipoPiano() : "FLAT";
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

        if ("FINANZIAMENTO".equals(plan.tipoPiano) && rata.quotaCapitale != null) {
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

        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
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
                                // Fonde le quote delle due rate: la prossima assorbe capitale e interessi della saltata
                                next.quotaCapitale  = next.quotaCapitale.add(rata.quotaCapitale);
                                next.quotaInteressi = (next.quotaInteressi != null ? next.quotaInteressi : BigDecimal.ZERO)
                                        .add(rata.quotaInteressi != null ? rata.quotaInteressi : BigDecimal.ZERO);
                            } else {
                                // Split già perso in precedenza: forza ramo FLAT al pagamento
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
            // BUG 1 fix: propagate split so payInstallment uses the FINANZIAMENTO branch correctly
            extra.quotaCapitale  = rata.quotaCapitale;
            extra.quotaInteressi = rata.quotaInteressi;
            installmentRepo.persist(extra);
        }
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
        }

        plan.stato = "ANNULLATO";
        plan.importoPenale = penale;

        return buildDetail(plan, installmentRepo.findByPianoOrdered(planId));
    }

    // ── PROCESS SCHEDULED (chiamato dallo scheduler) ───────────────────────────

    @Transactional
    public int processScheduledInstallments() {
        List<RecurringExpenseInstallment> due = installmentRepo.findPendingDue(LocalDate.now());
        int processed = 0;
        for (RecurringExpenseInstallment rata : due) {
            try {
                RecurringExpensePlan plan = planRepo.findById(rata.pianoId);
                if (plan == null || !"ATTIVO".equals(plan.stato)) continue;

                if ("FINANZIAMENTO".equals(plan.tipoPiano) && rata.quotaCapitale != null) {
                    Movimento mCap = buildMovimento(plan, rata.quotaCapitale, rata.dataScadenza,
                            plan.createdBy, plan.descrizione + " – Rata " + rata.numeroRata + " (cap.)");
                    em.persist(mCap);
                    Movimento mInt = buildMovimentoInteressi(plan, rata.quotaInteressi, rata.dataScadenza,
                            plan.createdBy, plan.descrizione + " – Rata " + rata.numeroRata + " (int.)");
                    em.persist(mInt);
                    em.flush();
                    rata.stato                = "PAID";
                    rata.movimentoId          = mCap.id;
                    rata.movimentoInteressiId = mInt.id;
                } else {
                    Movimento m = buildMovimento(plan, rata.importo, rata.dataScadenza, plan.createdBy,
                            plan.descrizione + " – Rata " + rata.numeroRata);
                    em.persist(m);
                    em.flush();
                    rata.stato       = "PAID";
                    rata.movimentoId = m.id;
                }
                processed++;
            } catch (Exception e) {
                log.warnf("Errore generazione movimento per rata %s: %s", rata.id, e.getMessage());
            }
        }
        return processed;
    }

    // ── HELPERS ───────────────────────────────────────────────────────────────

    private void validateCogeIsPassivita(Integer cogeId) {
        String tipo = (String) em.createNativeQuery(
                "SELECT tipo FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", cogeId)
                .getSingleResult();
        if (!"PASSIVITA".equals(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_PASSIVITA",
                    "Il conto COGE selezionato deve appartenere al ramo PASSIVITÀ E DEBITI (tipo PASSIVITA)");
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
                plan.note, pagato, residuo, totale,
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
