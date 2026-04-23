package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "business_units")
public class BusinessUnit {

    @Id
    @Column(name = "id")
    public Short id;

    @Column(name = "codice", nullable = false, unique = true, length = 10)
    public String codice;

    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    @Column(name = "descrizione", columnDefinition = "text")
    public String descrizione;

    @Column(name = "colore_hex", length = 7)
    public String coloreHex;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
