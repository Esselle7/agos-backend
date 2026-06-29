package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bene durevole ammortizzabile (cespite). L'ammortamento di competenza è calcolato a
 * quote costanti da {@code ReportingService.computeAmmortamenti} su {@code costoStorico},
 * {@code aliquotaAmmortamento} e {@code dataAcquisto}: si ferma a fine vita utile.
 * {@code fondoAmmortamento} è informativo (quanto già ammortizzato), non entra nel calcolo.
 */
@Entity
@Table(name = "cespiti")
public class Cespite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "descrizione", nullable = false, length = 255)
    public String descrizione;

    @Column(name = "conto_coge_id", nullable = false)
    public Integer contoCogeId;

    @Column(name = "costo_storico", nullable = false, precision = 12, scale = 2)
    public BigDecimal costoStorico;

    @Column(name = "aliquota_ammortamento", nullable = false, precision = 5, scale = 2)
    public BigDecimal aliquotaAmmortamento;

    @Column(name = "fondo_ammortamento", nullable = false, precision = 12, scale = 2)
    public BigDecimal fondoAmmortamento = BigDecimal.ZERO;

    @Column(name = "data_acquisto", nullable = false)
    public LocalDate dataAcquisto;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
