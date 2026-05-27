package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracciamento analitico (monitoring) della composizione del totale
 * preventivato di un evento. NON genera movimenti e NON impatta i KPI
 * contabili (costi_diretti_imputati, profitto): è solo a scopo di analisi.
 *
 * Un solo record AFFITTO e uno CATERING per evento (UNIQUE evento_id, tipo):
 * - AFFITTO  → quota del preventivato attribuita all'affitto ({@code importoIncasso}).
 * - CATERING → costo/persona interno, prezzo/persona al cliente, n. persone.
 */
@Entity
@Table(name = "evento_preventivo_tracking")
public class EventoPreventivoTracking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "evento_id", nullable = false, columnDefinition = "uuid")
    public UUID eventoId;

    /** AFFITTO | CATERING. */
    @Column(name = "tipo", nullable = false, length = 20)
    public String tipo;

    @Column(name = "importo_incasso", precision = 15, scale = 2)
    public BigDecimal importoIncasso;

    @Column(name = "costo_per_persona", precision = 15, scale = 2)
    public BigDecimal costoPerPersona;

    @Column(name = "prezzo_per_persona", precision = 15, scale = 2)
    public BigDecimal prezzoPerPersona;

    @Column(name = "num_persone")
    public Integer numPersone;

    @Column(name = "note", length = 500)
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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
