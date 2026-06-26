package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * security: fissa la verifica di {@code email_verified} sul Google ID token.
 * Se il controllo viene rimosso, il caso "false"/assente non lancia più → rosso.
 */
class RequireVerifiedEmailTest {

    private final ObjectMapper om = new ObjectMapper();

    private com.fasterxml.jackson.databind.JsonNode node(String json) throws Exception {
        return om.readTree(json);
    }

    @Test
    void verified_boolean_true_passa() throws Exception {
        assertDoesNotThrow(() -> GoogleOAuthService.requireVerifiedEmail(node("{\"email_verified\":true}")));
    }

    @Test
    void verified_stringa_true_passa() throws Exception {
        assertDoesNotThrow(() -> GoogleOAuthService.requireVerifiedEmail(node("{\"email_verified\":\"true\"}")));
    }

    @Test
    void non_verificata_rifiutata() throws Exception {
        assertThrows(UnauthorizedException.class,
                () -> GoogleOAuthService.requireVerifiedEmail(node("{\"email_verified\":false}")));
    }

    @Test
    void claim_assente_rifiutata() throws Exception {
        assertThrows(UnauthorizedException.class,
                () -> GoogleOAuthService.requireVerifiedEmail(node("{\"email\":\"x@y.z\"}")));
    }
}
