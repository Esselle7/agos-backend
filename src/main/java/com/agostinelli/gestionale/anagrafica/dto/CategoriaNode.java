package com.agostinelli.gestionale.anagrafica.dto;

import java.util.ArrayList;
import java.util.List;

public class CategoriaNode {

    public Long id;
    public String nome;
    public String tipo;
    public Short buId;
    public int ordinamento;
    public List<CategoriaNode> sottocategorie = new ArrayList<>();

    public CategoriaNode() {}

    public CategoriaNode(Long id, String nome, String tipo, Short buId, int ordinamento) {
        this.id = id;
        this.nome = nome;
        this.tipo = tipo;
        this.buId = buId;
        this.ordinamento = ordinamento;
    }
}
