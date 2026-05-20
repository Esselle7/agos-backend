package com.agostinelli.gestionale.auth.resource;

import com.agostinelli.gestionale.auth.domain.User;
import com.agostinelli.gestionale.auth.domain.UserRepository;
import com.agostinelli.gestionale.auth.dto.ExchangeCodeRequest;
import com.agostinelli.gestionale.auth.dto.LoginResponse;
import com.agostinelli.gestionale.auth.dto.RefreshRequest;
import com.agostinelli.gestionale.auth.dto.TokenResponse;
import com.agostinelli.gestionale.auth.dto.UserInfo;
import com.agostinelli.gestionale.auth.service.AuthService;
import com.agostinelli.gestionale.auth.service.AuthorizationCodeStore;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint REST per il flusso di autenticazione OAuth2 Google e gestione sessioni JWT.
 *
 * Endpoint pubblici (senza JWT richiesto):
 *   - GET  /auth/google/login       redirect verso Google
 *   - GET  /auth/google/callback    callback Google, redirige al FE con code opaco
 *   - POST /auth/google/exchange    scambia code opaco con i JWT interni
 *   - POST /auth/refresh            rinnovo token tramite refresh token
 *
 * Endpoint autenticati:
 *   - POST /auth/logout, GET /auth/me   richiedono JWT valido
 */
@Slf4j
@Path("/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final String STATE_COOKIE = "oauth_state";

    @Inject
    AuthService authService;

    @Inject
    AuthorizationCodeStore codeStore;

    @Inject
    UserRepository userRepository;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String googleClientId;

    @ConfigProperty(name = "app.google.redirect-uri")
    String redirectUri;

    @ConfigProperty(name = "app.frontend.url", defaultValue = "http://localhost:4200")
    String frontendUrl;

    /**
     * In dev/test il cookie non viene marcato {@code Secure} perché viaggia su
     * HTTP localhost; in prod è obbligatorio per evitare downgrade su canali
     * non cifrati. Override esplicito tramite {@code app.security.cookie-secure}.
     */
    @ConfigProperty(name = "app.security.cookie-secure", defaultValue = "false")
    boolean cookieSecure;

    /**
     * Avvia il flusso OAuth2: genera uno state CSRF e redirige verso Google.
     */
    @GET
    @Path("/google/login")
    public Response googleLogin() {
        String state = UUID.randomUUID().toString();

        NewCookie stateCookie = stateCookieBuilder(state, 600);

        String googleAuthUrl = buildGoogleAuthUrl(state);
        return Response.status(302)
            .location(URI.create(googleAuthUrl))
            .cookie(stateCookie)
            .build();
    }

    /**
     * Riceve il callback di Google, verifica lo state CSRF e completa il login
     * lato backend. Anziché redirigere il frontend con i token JWT in chiaro
     * (cronologia, Referer, log), emette un codice opaco a 256 bit
     * monouso/TTL-90s che il frontend scambierà con i token via
     * {@link #exchangeCode(ExchangeCodeRequest)}.
     */
    @GET
    @Path("/google/callback")
    public Response googleCallback(
        @QueryParam("code")  String code,
        @QueryParam("state") String state,
        @CookieParam(STATE_COOKIE) String cookieState
    ) {
        if (code == null || code.isBlank()) {
            throw new UnauthorizedException("Parametro 'code' mancante nel callback Google");
        }

        if (cookieState == null || !cookieState.equals(state)) {
            throw new UnauthorizedException("State CSRF non valido");
        }

        LoginResponse loginResponse = authService.handleGoogleCallback(code);
        String opaqueCode = codeStore.issue(loginResponse);

        NewCookie clearedCookie = stateCookieBuilder("", 0);

        // Solo il codice opaco viaggia nel redirect: nessun token, nessun PII.
        String redirectUrl = frontendUrl + "/oauth/callback?code=" + opaqueCode;

        return Response.seeOther(URI.create(redirectUrl))
            .cookie(clearedCookie)
            .build();
    }

    /**
     * Scambia il codice opaco emesso da {@link #googleCallback} con i JWT
     * interni e i dati utente. Il codice è monouso: una seconda chiamata
     * restituirà 401.
     */
    @POST
    @Path("/google/exchange")
    public LoginResponse exchangeCode(@Valid ExchangeCodeRequest request) {
        return codeStore.consume(request.code())
            .orElseThrow(() -> new ApiException(Response.Status.UNAUTHORIZED,
                    "INVALID_OR_EXPIRED_CODE",
                    "Codice di autorizzazione non valido o scaduto"));
    }

    /**
     * Rinnova la coppia di token usando un refresh token valido (token rotation).
     */
    @POST
    @Path("/refresh")
    public TokenResponse refresh(@Valid RefreshRequest request) {
        return authService.refreshTokens(request.refreshToken());
    }

    /**
     * Revoca il refresh token dell'utente corrente. Richiede JWT valido
     * (enforcement via {@code JwtAuthFilter}).
     */
    @POST
    @Path("/logout")
    public Response logout(
        Map<String, String> body,
        @Context SecurityContext securityContext
    ) {
        // L'enforcement del JWT è fatto dal JwtAuthFilter: questo endpoint NON
        // è nell'allow-list, quindi una richiesta senza JWT viene respinta a
        // monte con 401. Qui possiamo presumere principal != null.
        String refreshToken = body != null ? body.get("refreshToken") : null;
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        return Response.noContent().build();
    }

    /**
     * Restituisce le informazioni dell'utente corrente dal JWT. Richiede JWT valido.
     */
    @GET
    @Path("/me")
    public UserInfo me(@Context SecurityContext securityContext) {
        java.security.Principal principal = securityContext.getUserPrincipal();
        if (principal == null) {
            throw new UnauthorizedException("Token JWT mancante");
        }

        UUID userId = UUID.fromString(principal.getName());
        User user = Optional.ofNullable(userRepository.findById(userId))
            .orElseThrow(() -> new UnauthorizedException("Utente non trovato"));

        return new UserInfo(user.id, user.email, user.nome, user.ruolo.name(), user.personaleId);
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private NewCookie stateCookieBuilder(String value, int maxAgeSeconds) {
        return new NewCookie.Builder(STATE_COOKIE)
            .value(value)
            .httpOnly(true)
            .secure(cookieSecure)
            .path("/auth")
            .maxAge(maxAgeSeconds)
            .sameSite(NewCookie.SameSite.LAX)
            .build();
    }

    private String buildGoogleAuthUrl(String state) {
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=" + encode(googleClientId) +
            "&redirect_uri=" + encode(redirectUri) +
            "&response_type=code" +
            "&scope=" + encode("openid email profile") +
            "&state=" + encode(state) +
            "&access_type=offline" +
            "&prompt=consent";
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
