package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "aliquote_iva")
public class AliquotaIva {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Integer id;

    /** Percentuale IVA come numero intero/decimale (es. 10.0 per il 10%). */
    @Column(name = "aliquota", nullable = false, precision = 4, scale = 1)
    public BigDecimal aliquota;

    @Column(name = "descrizione", length = 100)
    public String descrizione;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
