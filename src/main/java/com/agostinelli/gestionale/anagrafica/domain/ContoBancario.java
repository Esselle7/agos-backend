package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "conti_bancari")
public class ContoBancario {

    @Id
    @Column(name = "id")
    public Short id;

    @Column(name = "nome", nullable = false, length = 100)
    public String nome;

    // Riferisce lk_tipi_conto: BANCARIO, CASSA, DIGITALE
    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "iban", length = 34)
    public String iban;

    @Column(name = "saldo_iniziale", nullable = false, precision = 12, scale = 2)
    public BigDecimal saldoIniziale = BigDecimal.ZERO;

    @Column(name = "data_saldo_iniziale")
    public LocalDate dataSaldoIniziale;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;
}
