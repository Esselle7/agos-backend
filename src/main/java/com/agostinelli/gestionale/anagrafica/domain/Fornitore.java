package com.agostinelli.gestionale.anagrafica.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fornitori")
public class Fornitore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "ragione_sociale", nullable = false, length = 255)
    public String ragioneSociale;

    @Column(name = "alias", length = 100)
    public String alias;

    @Column(name = "piva", length = 11, unique = true)
    public String piva;

    @Column(name = "codice_sdi", length = 7)
    public String codiceSdi;

    @Column(name = "coge_default_id")
    public Integer cogeDefaultId;

    @Column(name = "bu_default_id")
    public Short buDefaultId;

    @Column(name = "note", columnDefinition = "text")
    public String note;

    @Column(name = "created_at", updatable = false, nullable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
