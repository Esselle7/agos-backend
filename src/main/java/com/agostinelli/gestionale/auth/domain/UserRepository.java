package com.agostinelli.gestionale.auth.domain;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository Panache per l'entità User. Espone ricerche per google_sub e email.
 */
@ApplicationScoped
public class UserRepository implements PanacheRepositoryBase<User, UUID> {

    /** Ricerca per identificatore Google OAuth2 (campo primario di autenticazione). */
    public Optional<User> findByGoogleSub(String sub) {
        return find("googleSub", sub).firstResultOptional();
    }

    /** Ricerca per email, usata solo per log e diagnostica. */
    public Optional<User> findByEmail(String email) {
        return find("email", email).firstResultOptional();
    }
}
