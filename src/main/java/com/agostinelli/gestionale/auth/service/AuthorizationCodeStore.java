package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.auth.dto.LoginResponse;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.slf4j.Slf4j;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage in-memory di codici monouso usati nel flusso OAuth callback → exchange.
 *
 * Risolve la vulnerabilità di trasportare access/refresh token JWT come query
 * parameter nel redirect verso il frontend ({@code GET /oauth/callback?token=...}):
 *   - i token comparirebbero in cronologia, Referer header e log del web server;
 *   - i parametri di URL sono indicizzabili da estensioni browser e analytics.
 *
 * Flusso sicuro:
 *   1. {@code GET /auth/google/callback} riceve il code Google, scambia con
 *      JWT interni, salva la {@link LoginResponse} in questa cache con un
 *      codice opaco a 256 bit e redirige a {@code /oauth/callback?code=<opaco>}.
 *   2. Il frontend chiama {@code POST /auth/google/exchange {code}} per
 *      ricevere i token nel body JSON (mai più nell'URL).
 *
 * Il codice è monouso ({@link #consume(String)} lo rimuove) e ha TTL breve
 * ({@value #TTL_SECONDS}s). La cache è in-memory: l'autenticazione richiede
 * sticky session se l'applicazione gira su più istanze. Lo TODO sotto traccia
 * la migrazione a Redis quando l'app sarà HA.
 */
@Slf4j
@ApplicationScoped
public class AuthorizationCodeStore {

    /**
     * TTL dei codici opachi (90 secondi). Sufficiente per il round-trip
     * redirect→exchange anche su reti lente; abbastanza breve da limitare
     * la finestra di replay in caso di leak del code.
     */
    static final long TTL_SECONDS = 90L;

    private static final int CODE_BYTES = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * Genera un codice opaco a 256 bit e memorizza la risposta di login
     * fino al consumo o alla scadenza.
     *
     * @return codice opaco URL-safe
     */
    public String issue(LoginResponse loginResponse) {
        purgeExpired();
        String code = randomCode();
        store.put(code, new Entry(loginResponse, Instant.now().plusSeconds(TTL_SECONDS)));
        return code;
    }

    /**
     * Recupera la risposta di login associata al codice e lo invalida.
     * I codici sono monouso: la seconda chiamata restituirà sempre vuoto.
     */
    public Optional<LoginResponse> consume(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        Entry e = store.remove(code);
        if (e == null) return Optional.empty();
        if (Instant.now().isAfter(e.expiresAt)) {
            log.debug("Authorization code scaduto");
            return Optional.empty();
        }
        return Optional.of(e.loginResponse);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
    }

    private static String randomCode() {
        byte[] bytes = new byte[CODE_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Costante di durata in millisecondi, utile per i test che simulano scadenza. */
    public static Duration ttl() {
        return Duration.ofSeconds(TTL_SECONDS);
    }

    private record Entry(LoginResponse loginResponse, Instant expiresAt) {}
}
