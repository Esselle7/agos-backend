package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Costo diretto reale (DJ, Torta, Personalizzato) associato a un evento. Ogni
 * record è collegato a un movimento USCITA generato automaticamente: la coppia
 * (movimentoId, movimentoData) replica la PK composita di {@code movimenti}
 * (id, data_movimento), che è partizionata per anno.
 *
 * Affitto e catering NON sono qui: sono tracciati a fini analitici in
 * {@code evento_preventivo_tracking} e non generano movimenti.
 */
@Entity
@Table(name = "evento_costi_diretti")
public class EventoCostoDiretto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "evento_id", nullable = false, columnDefinition = "uuid")
    public UUID eventoId;

    /** FISSO | VARIABILE. */
    @Column(name = "tipo_costo", nullable = false, length = 20)
    public String tipoCosto;

    /** AFFITTO_SALA | DJ | CATERING | TORTA | CUSTOM. */
    @Column(name = "voce", nullable = false, length = 30)
    public String voce;

    @Column(name = "etichetta", nullable = false, length = 200)
    public String etichetta;

    /** Importo effettivo del costo. */
    @Column(name = "importo", nullable = false, precision = 15, scale = 2)
    public BigDecimal importo;

    @Column(name = "movimento_id", columnDefinition = "uuid")
    public UUID movimentoId;

    @Column(name = "movimento_data")
    public LocalDate movimentoData;

    @Column(name = "conto_coge_id")
    public Integer contoCogeId;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
