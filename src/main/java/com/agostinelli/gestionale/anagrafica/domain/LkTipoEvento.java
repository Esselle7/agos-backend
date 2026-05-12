package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "lk_tipi_evento")
public class LkTipoEvento {

    @Id
    @Column(name = "codice", nullable = false, length = 50)
    public String codice;

    @Column(name = "descrizione", nullable = false, length = 100)
    public String descrizione;
}
