package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Catalogo delle label di voce di preventivo riutilizzabili tra eventi.
 * Le nuove label inserite dall'utente si auto-registrano qui (upsert per label),
 * così diventano selezionabili sugli eventi futuri. I default guidati
 * ({@code is_default = true}: Affitto, Menu) sono proposti in UI a ogni evento.
 */
@Entity
@Table(name = "evento_voce_catalogo")
public class EventoVoceCatalogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "label", nullable = false, length = 120)
    public String label;

    @Column(name = "is_default", nullable = false)
    public boolean isDefault;

    @Column(name = "ordine", nullable = false)
    public short ordine;

    @Column(name = "attivo", nullable = false)
    public boolean attivo = true;

    /** Prezzo unitario di listino, precompilato quando si seleziona la voce. */
    @Column(name = "prezzo_default", precision = 15, scale = 2)
    public BigDecimal prezzoDefault;

    /** Unità di misura (persona, bottiglia, caraffa…), per la UI. */
    @Column(name = "unita", length = 30)
    public String unita;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
