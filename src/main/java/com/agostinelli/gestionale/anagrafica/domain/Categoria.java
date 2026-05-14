package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "categorie")
public class Categoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "parent_id")
    public Long parentId;

    @Column(name = "bu_id", nullable = false)
    public Short buId;

    @Column(name = "ordinamento", nullable = false)
    public int ordinamento = 0;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
