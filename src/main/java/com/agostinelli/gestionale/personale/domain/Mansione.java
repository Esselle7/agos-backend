package com.agostinelli.gestionale.personale.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mansioni")
public class Mansione {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "nome", nullable = false, unique = true, length = 100)
    public String nome;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
