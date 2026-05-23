package com.agostinelli.gestionale.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Entità utente applicativo, autenticato via Google SSO.
 * Mappata sulla tabella 'users'; usa Panache Repository (non Active Record).
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "google_sub", unique = true, nullable = false)
    public String googleSub;

    @Column(name = "email", nullable = false)
    public String email;

    @Column(name = "full_name")
    public String nome;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    public Role ruolo = Role.DIPENDENTE;

    @Column(name = "is_active", nullable = false)
    public boolean isActive = true;

    @Column(name = "last_login")
    public Instant lastLogin;

    @Column(name = "created_at", updatable = false, nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    /** Collegamento opzionale al record personale — presente solo per i DIPENDENTE. */
    @Column(name = "personale_id")
    public UUID personaleId;
}
