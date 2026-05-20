package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.auth.dto.LoginResponse;
import com.agostinelli.gestionale.auth.dto.UserInfo;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test del code store usato dal flusso OAuth callback → exchange.
 *
 * Le proprietà critiche per la sicurezza sono:
 *   - i codici sono opachi e non collidono (256 bit di entropia da SecureRandom)
 *   - sono monouso: una seconda chiamata a consume restituisce vuoto
 *   - hanno TTL breve: oltre la scadenza il consume restituisce vuoto
 *   - chiavi invalide (null, blank, stringhe arbitrarie) non eccezionano
 */
@QuarkusTest
class AuthorizationCodeStoreTest {

    @Inject AuthorizationCodeStore store;

    @Test
    void issue_eConsume_restituisce_la_login_response() {
        LoginResponse login = sampleLogin();
        String code = store.issue(login);

        assertNotNull(code);
        assertFalse(code.isBlank());

        var consumed = store.consume(code);
        assertTrue(consumed.isPresent());
        assertEquals(login.accessToken(),  consumed.get().accessToken());
        assertEquals(login.refreshToken(), consumed.get().refreshToken());
        assertEquals(login.user().id(),    consumed.get().user().id());
    }

    @Test
    void consume_e_monouso() {
        String code = store.issue(sampleLogin());
        assertTrue(store.consume(code).isPresent());
        // Seconda consume: il codice è già stato rimosso
        assertTrue(store.consume(code).isEmpty());
    }

    @Test
    void consume_codice_inesistente_vuoto() {
        assertTrue(store.consume("non-esiste-affatto").isEmpty());
    }

    @Test
    void consume_null_e_blank_vuoto_senza_eccezione() {
        assertTrue(store.consume(null).isEmpty());
        assertTrue(store.consume("").isEmpty());
        assertTrue(store.consume("   ").isEmpty());
    }

    @Test
    void issue_genera_codici_distinti() {
        String c1 = store.issue(sampleLogin());
        String c2 = store.issue(sampleLogin());
        String c3 = store.issue(sampleLogin());
        assertNotEquals(c1, c2);
        assertNotEquals(c2, c3);
        assertNotEquals(c1, c3);
        // Cleanup
        store.consume(c1);
        store.consume(c2);
        store.consume(c3);
    }

    @Test
    void ttl_dichiarata_non_zero() {
        // Sanity check: la TTL esposta non deve mai diventare 0 per errore di refactor
        assertTrue(AuthorizationCodeStore.ttl().toSeconds() >= 60,
                "TTL del code store deve essere >= 60s, è " + AuthorizationCodeStore.ttl());
    }

    private LoginResponse sampleLogin() {
        UserInfo user = new UserInfo(
                UUID.randomUUID(),
                "test@example.com",
                "Test User",
                "DIPENDENTE",
                null);
        return new LoginResponse("access-jwt", "refresh-jwt", 3600, "Bearer", user);
    }
}
