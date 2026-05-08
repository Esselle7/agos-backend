package com.agostinelli.gestionale.spese.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recurring_expense_plan")
public class RecurringExpensePlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "descrizione", nullable = false, length = 255)
    public String descrizione;

    @Column(name = "business_unit_id", nullable = false)
    public Short businessUnitId;

    @Column(name = "conto_bancario_id", nullable = false)
    public Short contoBancarioId;

    @Column(name = "conto_coge_id", nullable = false)
    public Integer contoCoge;

    @Column(name = "importo_rata", nullable = false, precision = 12, scale = 2)
    public BigDecimal importoRata;

    @Column(name = "variazione_pct", nullable = false, precision = 6, scale = 3)
    public BigDecimal variazionePct;

    @Column(name = "giorno_del_mese", nullable = false)
    public Short giornoDelMese;

    @Column(name = "frequenza", nullable = false, length = 20)
    public String frequenza;

    @Column(name = "numero_rate", nullable = false)
    public Integer numeroRate;

    @Column(name = "data_prima_rata", nullable = false)
    public LocalDate dataPrimaRata;

    @Column(name = "stato", nullable = false, length = 20)
    public String stato;

    @Column(name = "importo_penale", nullable = false, precision = 12, scale = 2)
    public BigDecimal importoPenale;

    @Column(name = "note", columnDefinition = "text")
    public String note;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (stato == null) stato = "ATTIVO";
        if (importoPenale == null) importoPenale = BigDecimal.ZERO;
        if (variazionePct == null) variazionePct = BigDecimal.ZERO;
        if (businessUnitId == null) businessUnitId = 5;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
