package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "centri_di_costo_coan")
public class CentroDiCostoCoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Integer id;

    @Column(name = "codice", nullable = false, length = 20)
    public String codice;

    @Column(name = "descrizione", nullable = false, length = 255)
    public String descrizione;

    @Column(name = "business_unit_id", nullable = false)
    public Short businessUnitId;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
