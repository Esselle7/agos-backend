package com.agostinelli.gestionale.contanti;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.importlayer.CogeRiservatoEventi;
import com.agostinelli.gestionale.movimenti.importlayer.CogeTransitorio;
import com.agostinelli.gestionale.movimenti.service.MovimentiService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Modulo «Contanti» (docs/specs/modulo-contanti.md).
 *
 * <p><b>Principio guida:</b> il modulo registra solo ciò che nessun estratto conto potrà mai
 * portare. Ogni operazione crea <b>una gamba sola</b>, quella sulla Cassa contanti (invariante I2);
 * la gamba bancaria speculare la porta l'import, e le due si annullano sul CoGe patrimoniale
 * {@code 10.03.x} lasciando la liquidità totale invariata (invariante I3: fuori dal P&L).
 *
 * <p>Nessuna astrazione di Corsia B, quindi nessun ADR: cinque metodi che compongono un
 * {@link MovimentoCreateRequest} e delegano a {@link MovimentiService#createMovimento}, riusando
 * validazione, mapper e refresh MV esistenti. Nessuna tabella propria — il saldo cassa ha una
 * fonte sola, le righe {@code movimenti} sul conto {@code tipo='CASSA'} (invariante I5).
 */
@ApplicationScoped
public class ContantiService {

    /** Overhead: prelievo, deposito e rettifica non appartengono a nessun ramo operativo. */
    private static final short BU_OVERHEAD = 5;

    private static final String COGE_PRELIEVO  = "10.03.004";
    private static final String COGE_DEPOSITO  = "10.03.003";
    private static final String COGE_RETTIFICA = "10.03.005";

    @Inject EntityManager em;
    @Inject MovimentiService movimenti;

    /** Il cassetto: id del conto, saldo calcolato al volo, data di apertura del saldo iniziale. */
    public record SaldoContanti(short contoId, String nome, BigDecimal saldo, LocalDate dataSaldoIniziale) {}

    /** Esito della conta cassa: {@code creato=false} quando il contato coincide col teorico (R6). */
    public record EsitoConta(boolean creato, BigDecimal delta, MovimentoDTO movimento) {}

    // ── R1 — saldo live ────────────────────────────────────────────────────────────

    /**
     * Saldo della cassa calcolato <b>al volo</b>, con la stessa formula di {@code mv_saldi_conti}
     * (identica a {@code CespitiService.verificaFondiEDerivaMetodo}): la MV è asincrona e non vede
     * la scrittura appena fatta, quindi un saldo letto da lì mentirebbe subito dopo un POST.
     *
     * <p>Il filtro sulla data di apertura è {@code >} stretto come in
     * {@code V24__saldo_conto_rispetta_data_apertura.sql}: un movimento datato ≤
     * {@code data_saldo_iniziale} è salvato ma resta fuori dal saldo (edge case noto di §4).
     */
    public SaldoContanti saldo() {
        List<?> rows = em.createNativeQuery("""
                SELECT cb.id, cb.nome, cb.data_saldo_iniziale,
                       cb.saldo_iniziale + COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' THEN m.importo_lordo
                                                             WHEN m.tipo='USCITA'  THEN -m.importo_lordo
                                                             ELSE 0 END), 0)
                FROM conti_bancari cb
                LEFT JOIN movimenti m ON m.conto_bancario_id = cb.id AND m.stato <> 'ANNULLATO'
                  AND (cb.data_saldo_iniziale IS NULL
                       OR COALESCE(m.data_finanziaria, m.data_movimento) > cb.data_saldo_iniziale)
                WHERE cb.tipo = 'CASSA' AND cb.is_active = true
                GROUP BY cb.id, cb.nome, cb.data_saldo_iniziale, cb.saldo_iniziale
                ORDER BY cb.id
                """).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(Response.Status.CONFLICT, "CASSA_NON_CONFIGURATA",
                    "Non esiste nessun conto di tipo CASSA attivo: il modulo Contanti non ha un cassetto su cui scrivere.");
        }
        Object[] r = (Object[]) rows.get(0);
        LocalDate apertura = r[2] instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) r[2];
        return new SaldoContanti(((Number) r[0]).shortValue(), (String) r[1], (BigDecimal) r[3], apertura);
    }

    // ── Le cinque operazioni ───────────────────────────────────────────────────────

    /** Op. 1 — prelievo da banca: entra nel cassetto. Nessuna guardia fondi: la cassa cresce. */
    @Transactional
    public MovimentoDTO prelievo(ContantiRequests.Prelievo req, UUID userId) {
        String banca = nomeBancaOrThrow(req.contoBancarioId());
        return crea("ENTRATA", req.importo(), req.data(), cogeId(COGE_PRELIEVO), BU_OVERHEAD,
                "Prelievo contanti da " + banca, null, userId);
    }

    /** Op. 2 — deposito in banca: esce dal cassetto, quindi passa dalla guardia fondi (I1). */
    @Transactional
    public MovimentoDTO deposito(ContantiRequests.Deposito req, UUID userId) {
        String banca = nomeBancaOrThrow(req.contoBancarioId());
        verificaFondi(req.importo());
        return crea("USCITA", req.importo(), req.data(), cogeId(COGE_DEPOSITO), BU_OVERHEAD,
                "Versamento contanti su " + banca, null, userId);
    }

    /** Op. 3 — incasso in contanti su una voce di ricavo scelta dall'operatore. */
    @Transactional
    public MovimentoDTO incasso(ContantiRequests.Incasso req, UUID userId) {
        validaCoge(req.contoCoge(), "RICAVO");
        return crea("ENTRATA", req.importo(), req.data(), req.contoCoge(), req.businessUnitId(),
                req.descrizione(), null, userId);
    }

    /** Op. 4 — spesa in contanti su una voce di costo. Uscita ⇒ guardia fondi. */
    @Transactional
    public MovimentoDTO spesa(ContantiRequests.Spesa req, UUID userId) {
        validaCoge(req.contoCoge(), "COSTO");
        verificaFondi(req.importo());
        return crea("USCITA", req.importo(), req.data(), req.contoCoge(), req.businessUnitId(),
                req.descrizione(), req.fornitoreId(), userId);
    }

    /**
     * Op. 5 — conta cassa (R6): si registra la <b>differenza</b> fra il contato e il teorico.
     * Delta zero non produce un movimento da 0 € (che {@code @DecimalMin("0.01")} rifiuterebbe
     * comunque con un 400 incomprensibile): risponde {@code creato=false} e non scrive niente.
     */
    @Transactional
    public EsitoConta conta(ContantiRequests.Conta req, UUID userId) {
        BigDecimal delta = req.contato().subtract(saldo().saldo());
        if (delta.signum() == 0) {
            return new EsitoConta(false, BigDecimal.ZERO, null);
        }
        String descrizione = "Rettifica di cassa: " + req.motivo();
        BigDecimal importo = delta.abs();
        if (delta.signum() < 0) {
            verificaFondi(importo);   // ammanco = uscita: fail closed anche qui
        }
        MovimentoDTO m = crea(delta.signum() > 0 ? "ENTRATA" : "USCITA", importo, req.data(),
                cogeId(COGE_RETTIFICA), BU_OVERHEAD, descrizione, null, userId);
        return new EsitoConta(true, delta, m);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /**
     * L'unico punto in cui il modulo scrive: conto = la cassa, metodo = CONTANTI,
     * data finanziaria = data movimento (il contante è liquido nell'istante in cui si muove),
     * evento = null. Sono i campi imposti dal server di §1 — non arrivano mai dal client.
     */
    private MovimentoDTO crea(String tipo, BigDecimal importo, LocalDate data, int contoCoge,
                              short businessUnitId, String descrizione, UUID fornitoreId, UUID userId) {
        MovimentoCreateRequest req = new MovimentoCreateRequest(
                tipo, importo, null, null,
                data, null, data, null,
                contoCassaId(), metodoContantiId(), businessUnitId, contoCoge,
                null, fornitoreId, null, null,
                descrizione, null, null, "MANUALE", null);
        return movimenti.createMovimento(req, userId);
    }

    /**
     * R3 / invariante I1 — dal cassetto non esce denaro che non c'è. Vincolo fisico, non contabile:
     * fail closed con 409, prima di qualunque persistenza.
     */
    private void verificaFondi(BigDecimal importo) {
        BigDecimal saldo = saldo().saldo();
        if (saldo.compareTo(importo) < 0) {
            throw new ApiException(Response.Status.CONFLICT, "FONDI_INSUFFICIENTI",
                    "In cassa ci sono EUR " + saldo + ", ne servono EUR " + importo
                            + ": dal cassetto non può uscire denaro che non c'è.");
        }
    }

    /**
     * R4 — il conto scelto per incasso/spesa dev'essere esistente, non riservato agli eventi
     * ({@code 30.02.*}), non un transitorio dell'import, e del tipo giusto: senza l'ultimo
     * controllo un incasso può essere registrato su un conto di costo.
     */
    private void validaCoge(int contoCogeId, String tipoAtteso) {
        List<?> rows = em.createNativeQuery(
                "SELECT codice, tipo FROM piano_dei_conti_coge WHERE id = :id AND is_active = true")
                .setParameter("id", contoCogeId).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_NON_TROVATO",
                    "Conto contabile non trovato o non attivo: " + contoCogeId);
        }
        Object[] r = (Object[]) rows.get(0);
        String codice = (String) r[0];
        String tipo = (String) r[1];
        CogeRiservatoEventi.vieta(codice);
        CogeTransitorio.vieta(codice);
        if (!tipoAtteso.equals(tipo)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "COGE_TIPO_ERRATO",
                    "Il conto " + codice + " è di tipo " + tipo + ": qui serve un conto di tipo "
                            + tipoAtteso + ".");
        }
    }

    /** La banca scelta per prelievo/deposito: dev'essere attiva e non essere il cassetto stesso. */
    private String nomeBancaOrThrow(Short contoBancarioId) {
        List<?> rows = em.createNativeQuery(
                "SELECT nome FROM conti_bancari WHERE id = :id AND is_active = true AND tipo <> 'CASSA'")
                .setParameter("id", contoBancarioId).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_NON_VALIDO",
                    "Conto bancario non trovato, non attivo, oppure è la cassa stessa: " + contoBancarioId);
        }
        return (String) rows.get(0);
    }

    private int cogeId(String codice) {
        List<?> rows = em.createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice = :c")
                .setParameter("c", codice).getResultList();
        if (rows.isEmpty()) {
            throw new ApiException(Response.Status.CONFLICT, "COGE_MANCANTE",
                    "Manca il conto contabile " + codice + ": la migration V35 non è stata applicata.");
        }
        return ((Number) rows.get(0)).intValue();
    }

    /** L'unico conto {@code tipo='CASSA'} attivo: letto, mai hardcodato a 3 (multi-cassa è fuori scopo). */
    private short contoCassaId() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM conti_bancari WHERE tipo = 'CASSA' AND is_active = true ORDER BY id LIMIT 1")
                .getSingleResult()).shortValue();
    }

    private int metodoContantiId() {
        return ((Number) em.createNativeQuery(
                "SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'").getSingleResult()).intValue();
    }
}
