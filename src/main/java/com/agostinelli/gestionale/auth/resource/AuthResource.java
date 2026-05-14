package com.agostinelli.gestionale.auth.resource;

import com.agostinelli.gestionale.auth.domain.User;
import com.agostinelli.gestionale.auth.domain.UserRepository;
import com.agostinelli.gestionale.auth.dto.LoginResponse;
import com.agostinelli.gestionale.auth.dto.RefreshRequest;
import com.agostinelli.gestionale.auth.dto.TokenResponse;
import com.agostinelli.gestionale.auth.dto.UserInfo;
import com.agostinelli.gestionale.auth.service.AuthService;
import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Endpoint REST per il flusso di autenticazione OAuth2 Google e gestione sessioni JWT.
 * Tutti i path sotto /auth/google/* e /auth/refresh sono pubblici (senza JWT).
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
    UserRepository userRepository;

    @ConfigProperty(name = "quarkus.oidc.client-id")
    String googleClientId;

    @ConfigProperty(name = "app.google.redirect-uri")
    String redirectUri;

    @ConfigProperty(name = "app.frontend.url", defaultValue = "http://localhost:4200")
    String frontendUrl;

    /**
     * Avvia il flusso OAuth2: genera uno state CSRF e redirige verso Google.
     */
    @GET
    @Path("/google/login")
    public Response googleLogin() {
        String state = UUID.randomUUID().toString();

        NewCookie stateCookie = new NewCookie.Builder(STATE_COOKIE)
            .value(state)
            .httpOnly(true)
            .path("/auth")
            .maxAge(600)
            .sameSite(NewCookie.SameSite.LAX)
            .build();

        String googleAuthUrl = buildGoogleAuthUrl(state);
        return Response.status(302)
            .location(URI.create(googleAuthUrl))
            .cookie(stateCookie)
            .build();
    }

    /**
     * Riceve il callback di Google, verifica lo state CSRF e completa il login.
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

        NewCookie clearedCookie = new NewCookie.Builder(STATE_COOKIE)
            .value("")
            .path("/auth")
            .maxAge(0)
            .build();

        String redirectUrl = frontendUrl + "/auth/callback"
            + "?accessToken=" + loginResponse.accessToken()
            + "&refreshToken=" + loginResponse.refreshToken()
            + "&id=" + loginResponse.user().id()
            + "&nome=" + encode(loginResponse.user().nome())
            + "&email=" + encode(loginResponse.user().email())
            + "&ruolo=" + loginResponse.user().ruolo();

        return Response.seeOther(URI.create(redirectUrl))
            .cookie(clearedCookie)
            .build();
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
     * Revoca il refresh token dell'utente corrente. Richiede JWT valido.
     */
    @POST
    @Path("/logout")
    public Response logout(
        Map<String, String> body,
        @Context SecurityContext securityContext
    ) {
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
        JsonWebToken jwt = (JsonWebToken) securityContext.getUserPrincipal();
        if (jwt == null) {
            throw new UnauthorizedException("Token JWT mancante");
        }

        UUID userId = UUID.fromString(jwt.getSubject());
        User user = Optional.ofNullable(userRepository.findById(userId))
            .orElseThrow(() -> new UnauthorizedException("Utente non trovato"));

        return new UserInfo(user.id, user.email, user.nome, user.ruolo.name());
    }

    private String buildGoogleAuthUrl(String state) {
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=" + googleClientId +
            "&redirect_uri=" + encode(redirectUri) +
            "&response_type=code" +
            "&scope=" + encode("openid email profile") +
            "&state=" + state +
            "&access_type=offline" +
            "&prompt=consent";
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
