package com.agostinelli.gestionale.personale.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "personale")
public class Personale {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    @Column(name = "cognome", nullable = false, length = 100)
    public String cognome;

    /** FK → mansioni.id. La mansione effettiva si ottiene tramite JOIN. */
    @Column(name = "mansione_id", columnDefinition = "uuid")
    public UUID mansioneId;

    @Column(name = "centro_di_costo_id")
    public Integer centroDiCostoId;

    @Column(name = "business_unit_id")
    public Short businessUnitId;

    @Column(name = "costo_aziendale_mensile", precision = 10, scale = 2)
    public BigDecimal costoAziendaleMensile;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
