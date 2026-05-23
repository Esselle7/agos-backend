package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.auth.domain.Role;
import com.agostinelli.gestionale.auth.domain.User;
import com.agostinelli.gestionale.auth.domain.UserRepository;
import com.agostinelli.gestionale.auth.dto.LoginResponse;
import com.agostinelli.gestionale.auth.dto.TokenResponse;
import com.agostinelli.gestionale.auth.dto.UserInfo;
import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;

/**
 * Coordina il flusso OAuth2 Google: login, refresh e logout.
 * Unico punto di orchestrazione tra GoogleOAuthService, JwtService e UserRepository.
 */
@Slf4j
@ApplicationScoped
public class AuthService {

    @Inject
    GoogleOAuthService googleOAuthService;

    @Inject
    JwtService jwtService;

    @Inject
    UserRepository userRepository;

    // Comma-separated allowlist — assente/vuoto = nessun nuovo utente ammesso
    @ConfigProperty(name = "app.security.allowed-emails")
    Optional<String> allowedEmailsRaw;

    @Transactional
    public LoginResponse handleGoogleCallback(String code) {
        GoogleTokenResponse googleTokens = googleOAuthService.exchangeCodeForToken(code);
        GoogleUserInfo googleUser = googleOAuthService.validateIdToken(googleTokens.idToken());

        User user = userRepository.findByGoogleSub(googleUser.sub())
            .orElseGet(() -> userRepository.findByEmail(googleUser.email())
                .map(u -> { u.googleSub = googleUser.sub(); return u; })
                .orElseGet(() -> createOrRejectUser(googleUser)));

        if (!user.isActive) {
            throw new UnauthorizedException("Account disabilitato");
        }

        user.lastLogin = Instant.now();

        String accessToken  = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user.id);

        log.info("Login completato per utente [{}]", user.id);
        return LoginResponse.of(accessToken, refreshToken, toUserInfo(user));
    }

    @Transactional
    public TokenResponse refreshTokens(String refreshToken) {
        DecodedToken decoded = jwtService.validateToken(refreshToken);

        if (!"refresh".equals(decoded.type())) {
            throw new UnauthorizedException("Token non è di tipo refresh");
        }

        if (jwtService.isRefreshTokenRevoked(decoded.jti())) {
            throw new UnauthorizedException("Refresh token revocato");
        }

        User user = Optional.ofNullable(userRepository.findById(decoded.userId()))
            .orElseThrow(() -> new UnauthorizedException("Utente non trovato"));

        if (!user.isActive) {
            throw new UnauthorizedException("Account disabilitato");
        }

        jwtService.revokeRefreshToken(decoded.jti());

        String newAccessToken  = jwtService.generateAccessToken(user);
        String newRefreshToken = jwtService.generateRefreshToken(user.id);

        return TokenResponse.of(newAccessToken, newRefreshToken);
    }

    public void logout(String refreshToken) {
        String jti;
        try {
            DecodedToken decoded = jwtService.validateToken(refreshToken);
            jti = decoded.jti();
        } catch (UnauthorizedException e) {
            jti = jwtService.extractJtiLenient(refreshToken);
        }

        if (jti != null) {
            jwtService.revokeRefreshToken(jti);
            log.debug("Refresh token revocato: jti={}", jti);
        }
    }

    private User createOrRejectUser(GoogleUserInfo googleUser) {
        Set<String> allowed = allowedEmailsRaw
            .map(raw -> Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(e -> !e.isEmpty())
                .collect(Collectors.toSet()))
            .orElse(Collections.emptySet());

        if (!allowed.contains(googleUser.email())) {
            log.warn("Accesso negato a email non in allowlist: {}", googleUser.email());
            throw new UnauthorizedException("Accesso non autorizzato");
        }

        User newUser = new User();
        newUser.googleSub  = googleUser.sub();
        newUser.email      = googleUser.email();
        newUser.nome       = googleUser.name();
        newUser.ruolo      = Role.DIPENDENTE;
        newUser.isActive   = true;
        newUser.createdAt  = Instant.now();

        userRepository.persist(newUser);
        log.info("Nuovo utente creato da allowlist: email={}", googleUser.email());
        return newUser;
    }

    private UserInfo toUserInfo(User user) {
        return new UserInfo(user.id, user.email, user.nome, user.ruolo.name(), user.personaleId);
    }
}
