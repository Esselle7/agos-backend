package com.agostinelli.gestionale.auth.service;

import com.agostinelli.gestionale.auth.domain.User;
import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.jwt.build.Jwt;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Genera e valida i JWT interni firmati RS256.
 * La blacklist dei refresh token è in-memory (si azzera al restart).
 * TODO: migrare a Redis per HA
 */
@Slf4j
@ApplicationScoped
public class JwtService {

    private static final String ISSUER         = "https://agostinelli.gestionale";
    private static final long   ACCESS_TTL_S   = 3600L;
    private static final long   REFRESH_TTL_S  = 7L * 24 * 3600;

    @ConfigProperty(name = "BASE64_PRIVATE_KEY", defaultValue = "")
    String base64PrivateKey;

    @ConfigProperty(name = "BASE64_PUBLIC_KEY", defaultValue = "")
    String base64PublicKey;

    @Inject
    ObjectMapper objectMapper;

    private RSAPrivateKey privateKey;
    private RSAPublicKey  publicKey;

    private final Set<String> revokedJtis = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void init() {
        try {
            if (!base64PrivateKey.isBlank()) {
                byte[] keyBytes = Base64.getDecoder().decode(base64PrivateKey);
                privateKey = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            } else {
                privateKey = loadPrivateKeyFromClasspath("privateKey.pem");
                if (privateKey == null) {
                    log.warn("BASE64_PRIVATE_KEY non configurata e privateKey.pem non trovato: la generazione di token fallirà");
                }
            }

            if (!base64PublicKey.isBlank()) {
                byte[] keyBytes = Base64.getDecoder().decode(base64PublicKey);
                publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
            } else {
                publicKey = loadPublicKeyFromClasspath("publicKey.pem");
                if (publicKey == null) {
                    log.warn("BASE64_PUBLIC_KEY non configurata e publicKey.pem non trovato: la validazione interna dei token fallirà");
                }
            }
        } catch (Exception e) {
            log.error("Errore caricamento chiavi RSA per JWT: {}", e.getMessage());
        }
    }

    private RSAPrivateKey loadPrivateKeyFromClasspath(String resource) {
        try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            log.warn("Impossibile caricare chiave privata dal classpath ({}): {}", resource, e.getMessage());
            return null;
        }
    }

    private RSAPublicKey loadPublicKeyFromClasspath(String resource) {
        try (var is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(pem);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            log.warn("Impossibile caricare chiave pubblica dal classpath ({}): {}", resource, e.getMessage());
            return null;
        }
    }

    /**
     * Genera un access token JWT RS256 per l'utente specificato (TTL 1h).
     *
     * @param user l'utente autenticato
     * @return stringa JWT firmata
     */
    public String generateAccessToken(User user) {
        requirePrivateKey();
        try {
            return Jwt.claims()
                .issuer(ISSUER)
                .subject(user.id.toString())
                .claim("email", user.email)
                .claim("role", user.ruolo.name())
                .groups(Set.of(user.ruolo.name()))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(ACCESS_TTL_S))
                .jws()
                .algorithm(io.smallrye.jwt.algorithm.SignatureAlgorithm.RS256)
                .sign(privateKey);
        } catch (Exception e) {
            throw new RuntimeException("Errore generazione access token", e);
        }
    }

    /**
     * Genera un refresh token JWT RS256 con jti univoco (TTL 7 giorni).
     *
     * @param userId ID dell'utente
     * @return stringa JWT firmata con claim type="refresh"
     */
    public String generateRefreshToken(UUID userId) {
        requirePrivateKey();
        try {
            return Jwt.claims()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("type", "refresh")
                .claim("jti", UUID.randomUUID().toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(REFRESH_TTL_S))
                .jws()
                .algorithm(io.smallrye.jwt.algorithm.SignatureAlgorithm.RS256)
                .sign(privateKey);
        } catch (Exception e) {
            throw new RuntimeException("Errore generazione refresh token", e);
        }
    }

    /**
     * Valida un token JWT interno verificando firma, issuer e scadenza.
     *
     * @param token stringa JWT
     * @return token decodificato con i claim principali
     * @throws UnauthorizedException se il token non è valido o scaduto
     */
    public DecodedToken validateToken(String token) {
        requirePublicKey();
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new UnauthorizedException("Token malformato");
            }

            if (!verifySignature(parts[0] + "." + parts[1], parts[2])) {
                throw new UnauthorizedException("Firma JWT non valida");
            }

            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));

            String iss = payload.path("iss").asText();
            if (!ISSUER.equals(iss)) {
                throw new UnauthorizedException("Issuer JWT non valido");
            }

            long exp = payload.path("exp").asLong(0);
            if (Instant.now().getEpochSecond() > exp) {
                throw new UnauthorizedException("Token scaduto");
            }

            return new DecodedToken(
                UUID.fromString(payload.path("sub").asText()),
                payload.path("email").asText(null),
                payload.path("role").asText(null),
                payload.path("jti").asText(null),
                payload.path("type").asText(null)
            );
        } catch (UnauthorizedException e) {
            throw e;
        } catch (Exception e) {
            log.debug("Errore validazione token JWT: {}", e.getMessage());
            throw new UnauthorizedException("Token JWT non valido");
        }
    }

    /**
     * Aggiunge il jti del refresh token alla blacklist in-memory.
     *
     * @param jti identificatore univoco del token da revocare
     */
    public void revokeRefreshToken(String jti) {
        if (jti != null && !jti.isBlank()) {
            revokedJtis.add(jti);
        }
    }

    /**
     * Verifica se il jti di un refresh token è stato revocato.
     *
     * @param jti identificatore del token
     * @return true se revocato
     */
    public boolean isRefreshTokenRevoked(String jti) {
        return jti != null && revokedJtis.contains(jti);
    }

    /**
     * Estrae il jti dal payload del token senza verificare firma o scadenza.
     * Usato nel flusso di logout per revocare anche token scaduti.
     *
     * @param token stringa JWT
     * @return jti, o null se il token è malformato
     */
    public String extractJtiLenient(String token) {
        try {
            String[] parts = token.split("\\.");
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return payload.path("jti").asText(null);
        } catch (Exception e) {
            log.debug("Impossibile estrarre jti dal token: {}", e.getMessage());
            return null;
        }
    }

    private boolean verifySignature(String headerDotPayload, String signatureB64) throws Exception {
        byte[] data     = headerDotPayload.getBytes(StandardCharsets.UTF_8);
        byte[] sigBytes = Base64.getUrlDecoder().decode(signatureB64);

        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(sigBytes);
    }

    private void requirePrivateKey() {
        if (privateKey == null) {
            throw new IllegalStateException("Chiave privata RSA non configurata (BASE64_PRIVATE_KEY)");
        }
    }

    private void requirePublicKey() {
        if (publicKey == null) {
            throw new IllegalStateException("Chiave pubblica RSA non configurata (BASE64_PUBLIC_KEY)");
        }
    }
}
