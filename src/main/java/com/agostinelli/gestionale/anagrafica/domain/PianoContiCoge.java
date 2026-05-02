package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "piano_dei_conti_coge")
public class PianoContiCoge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Integer id;

    @Column(name = "codice", nullable = false, unique = true, length = 20)
    public String codice;

    @Column(name = "descrizione", nullable = false, length = 255)
    public String descrizione;

    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "is_capex", nullable = false)
    public boolean isCapex = false;

    @Column(name = "parent_id")
    public Integer parentId;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
