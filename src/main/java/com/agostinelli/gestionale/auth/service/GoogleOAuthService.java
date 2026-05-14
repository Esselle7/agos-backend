package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Gestisce il flusso OAuth2 con Google: scambio del codice e validazione dell'ID token.
 * La verifica della firma usa le chiavi JWKS pubbliche di Google (cache 1h).
 */
@Slf4j
@ApplicationScoped
public class GoogleOAuthService {

    @Inject
    @RestClient
    GoogleOAuthClient googleOAuthClient;

    @Inject
    @RestClient
    GoogleCertsClient googleCertsClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String googleClientId;

    @ConfigProperty(name = "quarkus.oidc.credentials.secret")
    String googleClientSecret;

    @ConfigProperty(name = "app.google.redirect-uri")
    String redirectUri;

    private static final List<String> VALID_ISSUERS = List.of(
        "accounts.google.com",
        "https://accounts.google.com"
    );

    /**
     * Scambia il codice OAuth2 con i token Google tramite l'endpoint token.
     *
     * @param code il codice di autorizzazione ricevuto nel callback
     * @return risposta con access_token, id_token e refresh_token
     */
    public GoogleTokenResponse exchangeCodeForToken(String code) {
        return googleOAuthClient.exchangeToken(
            code,
            googleClientId,
            googleClientSecret,
            redirectUri,
            "authorization_code"
        );
    }

    /**
     * Valida un Google ID token verificando firma, issuer, audience e scadenza.
     * Scarica le chiavi JWKS da Google con cache di 1 ora.
     *
     * @param idToken il token JWT firmato da Google
     * @return dati utente estratti dal token validato
     * @throws UnauthorizedException se la validazione fallisce
     */
    public GoogleUserInfo validateIdToken(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) {
                throw new UnauthorizedException("ID token malformato");
            }

            JsonNode header  = objectMapper.readTree(decode(parts[0]));
            JsonNode payload = objectMapper.readTree(decode(parts[1]));

            validateClaims(payload);

            String kid = header.path("kid").asText(null);
            RSAPublicKey publicKey = resolvePublicKey(kid);

            if (!verifySignature(parts[0] + "." + parts[1], parts[2], publicKey)) {
                throw new UnauthorizedException("Firma Google ID token non valida");
            }

            return new GoogleUserInfo(
                payload.path("sub").asText(),
                payload.path("email").asText(),
                payload.path("name").asText()
            );
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Errore validazione Google ID token: {}", e.getMessage());
            throw new UnauthorizedException("Google ID token non valido");
        }
    }

    @CacheResult(cacheName = "google-certs")
    GoogleCertsResponse fetchGoogleCerts() {
        log.debug("Scarico chiavi JWKS di Google");
        return googleCertsClient.getCerts();
    }

    private void validateClaims(JsonNode payload) {
        String iss = payload.path("iss").asText();
        if (!VALID_ISSUERS.contains(iss)) {
            throw new UnauthorizedException("Issuer Google non valido: " + iss);
        }

        String aud = payload.path("aud").asText();
        if (!googleClientId.equals(aud)) {
            throw new UnauthorizedException("Audience Google ID token non valida");
        }

        long exp = payload.path("exp").asLong(0);
        if (Instant.now().getEpochSecond() > exp) {
            throw new UnauthorizedException("Google ID token scaduto");
        }
    }

    private RSAPublicKey resolvePublicKey(String kid) throws Exception {
        GoogleCertsResponse certs = fetchGoogleCerts();

        GoogleCertsResponse.JwkKey jwk = certs.keys().stream()
            .filter(k -> kid == null || kid.equals(k.kid()))
            .filter(k -> "RSA".equals(k.kty()))
            .findFirst()
            .orElseThrow(() -> new UnauthorizedException("Chiave Google JWK non trovata per kid: " + kid));

        BigInteger modulus  = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.n()));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.e()));

        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private boolean verifySignature(String headerDotPayload, String signatureB64, RSAPublicKey key) throws Exception {
        byte[] data      = headerDotPayload.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes  = Base64.getUrlDecoder().decode(signatureB64);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(sigBytes);
    }

    private byte[] decode(String base64Url) {
        return Base64.getUrlDecoder().decode(base64Url);
    }
}
