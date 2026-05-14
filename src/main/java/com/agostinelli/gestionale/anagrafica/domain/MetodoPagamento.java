package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "metodi_pagamento")
public class MetodoPagamento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Integer id;

    @Column(name = "codice", nullable = false, unique = true, length = 50)
    public String codice;

    @Column(name = "descrizione", nullable = false, length = 100)
    public String descrizione;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
