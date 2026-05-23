package com.agostinelli.gestionale.spese.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_expense_installment")
public class RecurringExpenseInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "piano_id", nullable = false, columnDefinition = "uuid")
    public UUID pianoId;

    @Column(name = "numero_rata", nullable = false)
    public Integer numeroRata;

    @Column(name = "data_scadenza", nullable = false)
    public LocalDate dataScadenza;

    @Column(name = "importo", nullable = false, precision = 12, scale = 2)
    public BigDecimal importo;

    @Column(name = "stato", nullable = false, length = 20)
    public String stato;

    @Column(name = "movimento_id", columnDefinition = "uuid")
    public UUID movimentoId;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @Column(name = "quota_capitale", precision = 12, scale = 2)
    public BigDecimal quotaCapitale;

    @Column(name = "quota_interessi", precision = 12, scale = 2)
    public BigDecimal quotaInteressi;

    @Column(name = "movimento_interessi_id", columnDefinition = "uuid")
    public UUID movimentoInteressiId;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (stato == null) stato = "PENDING";
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
