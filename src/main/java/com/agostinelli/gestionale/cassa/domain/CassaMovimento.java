package com.agostinelli.gestionale.cassa.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "cassa_movimenti")
public class CassaMovimento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    /** ENTRATA | USCITA | PRELIEVO_DA_BANCA | VERSAMENTO_IN_BANCA */
    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "importo", nullable = false, precision = 15, scale = 2)
    public BigDecimal importo;

    @Column(name = "data_movimento", nullable = false)
    public LocalDate dataMovimento;

    @Column(name = "descrizione", length = 500)
    public String descrizione;

    /** Conto di riferimento contabile (opzionale). */
    @Column(name = "conto_coge_id")
    public Integer contoCoge;

    @Column(name = "business_unit_id")
    public Short businessUnitId;

    /** Conto bancario collegato – obbligatorio per PRELIEVO/VERSAMENTO. */
    @Column(name = "conto_banca_id")
    public Short contoBancaId;

    /** REGISTRATO | ANNULLATO – aggiunto tramite V11. */
    @Column(name = "stato", nullable = false, length = 50)
    public String stato;

    @Column(name = "created_by", columnDefinition = "uuid", updatable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (stato == null) stato = "REGISTRATO";
    }
}
