package com.agostinelli.gestionale.cassa.service;

import com.agostinelli.gestionale.cassa.domain.CassaMovimento;
import com.agostinelli.gestionale.cassa.dto.*;
import com.agostinelli.gestionale.cassa.mapper.CassaMovimentoMapper;
import com.agostinelli.gestionale.cassa.repository.CassaMovimentiRepository;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import com.agostinelli.gestionale.shared.dto.PagedResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CassaService {

    @Inject CassaMovimentiRepository cassaRepo;
    @Inject MovimentiRepository movimentiRepo;
    @Inject CassaMovimentoMapper mapper;
    @Inject EntityManager em;

    public SaldoResponse getSaldo() {
        BigDecimal saldo = cassaRepo.calcolaSaldo();
        LocalDate aggiornatoAl = cassaRepo.dataUltimoMovimento();
        return new SaldoResponse(saldo, aggiornatoAl);
    }

    public PagedResponse<CassaMovimentoDTO> getMovimenti(
            LocalDate from, LocalDate to, int page, int size) {
        List<CassaMovimentoDTO> content = cassaRepo.findByPeriodo(from, to, page, size)
                .stream().map(mapper::toDTO).toList();
        long total = cassaRepo.countByPeriodo(from, to);
        return PagedResponse.of(content, page, size, total);
    }

    public CassaMovimentoDTO findById(UUID id) {
        CassaMovimento m = cassaRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento cassa non trovato: " + id));
        return mapper.toDTO(m);
    }

    /**
     * Crea un movimento di cassa.
     *
     * PRELIEVO_DA_BANCA e VERSAMENTO_IN_BANCA richiedono contoBancaId.
     * Per questi tipi viene creato automaticamente anche il movimento bancario
     * speculare (USCITA o ENTRATA) in un'unica transazione.
     */
    @Transactional
    public CassaMovimentoDTO createMovimentoCassa(CreateCassaMovimentoRequest req, UUID userId) {
        boolean eTrasferimento = "PRELIEVO_DA_BANCA".equals(req.tipo())
                || "VERSAMENTO_IN_BANCA".equals(req.tipo());

        if (eTrasferimento && req.contoBancaId() == null) {
            throw new ApiException(Response.Status.BAD_REQUEST, "CONTO_BANCA_MANCANTE",
                    "contoBancaId è obbligatorio per " + req.tipo());
        }

        CassaMovimento cassa = mapper.fromRequest(req);
        cassa.createdBy = userId;
        cassaRepo.persist(cassa);

        if (eTrasferimento) {
            createMovimentoBancaCollegato(req, userId);
        }

        return mapper.toDTO(cassa);
    }

    @Transactional
    public CassaMovimentoDTO updateMovimentoCassa(UUID id, CreateCassaMovimentoRequest req, UUID userId) {
        CassaMovimento m = findActiveOrThrow(id);
        m.tipo = req.tipo();
        m.importo = req.importo();
        m.dataMovimento = req.dataMovimento();
        m.descrizione = req.descrizione();
        m.contoCoge = req.contoCoge();
        m.businessUnitId = req.businessUnitId();
        m.contoBancaId = req.contoBancaId();
        return mapper.toDTO(m);
    }

    @Transactional
    public void annullaMovimentoCassa(UUID id) {
        CassaMovimento m = findActiveOrThrow(id);
        m.stato = "ANNULLATO";
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Crea il movimento bancario speculare per PRELIEVO_DA_BANCA (→ USCITA dal conto)
     * o VERSAMENTO_IN_BANCA (→ ENTRATA sul conto).
     * Usa metodoPagamento CONTANTI (id=1, primo record seeded in V6).
     */
    private void createMovimentoBancaCollegato(CreateCassaMovimentoRequest req, UUID userId) {
        Integer metodoCONTANTI = (Integer) em
                .createNativeQuery("SELECT id FROM metodi_pagamento WHERE codice = 'CONTANTI'")
                .getSingleResult();

        Integer cogeGiroconti = (Integer) em
                .createNativeQuery("SELECT id FROM piano_dei_conti_coge WHERE codice LIKE '10.03%' LIMIT 1")
                .getSingleResult();

        Movimento banco = new Movimento();
        banco.tipo = "PRELIEVO_DA_BANCA".equals(req.tipo()) ? "USCITA" : "ENTRATA";
        banco.importo = req.importo();
        banco.importoCommissione = BigDecimal.ZERO;
        banco.dataMovimento = req.dataMovimento();
        banco.contoBancarioId = req.contoBancaId();
        banco.metodoPagamentoId = metodoCONTANTI;
        banco.contoCoge = cogeGiroconti;
        banco.businessUnitId = req.businessUnitId();
        banco.descrizione = "Giroconto automatico da/per cassa: " +
                (req.descrizione() != null ? req.descrizione() : req.tipo());
        banco.fonte = "MANUALE";
        banco.stato = "REGISTRATO";
        banco.createdBy = userId;
        movimentiRepo.persist(banco);
    }

    private CassaMovimento findActiveOrThrow(UUID id) {
        CassaMovimento m = cassaRepo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento cassa non trovato: " + id));
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Il movimento " + id + " è già annullato");
        }
        return m;
    }
}
