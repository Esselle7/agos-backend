package com.agostinelli.gestionale.infrastructure.filter;

import com.agostinelli.gestionale.infrastructure.exception.UnauthorizedException;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.smallrye.jwt.auth.principal.JWTParser;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.io.IOException;
import java.security.Principal;

/**
 * Verifica il JWT in ogni richiesta e popola il SecurityContext.
 * I path /auth/* e /q/* sono esclusi dal controllo.
 */
@Slf4j
@Provider
@ApplicationScoped
@Priority(Priorities.AUTHENTICATION)
@UnlessBuildProfile("test")
public class JwtAuthFilter implements ContainerRequestFilter {

    @Inject
    JWTParser jwtParser;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        String path = requestContext.getUriInfo().getPath();
        if (isPublicPath(path)) {
            return;
        }

        String authHeader = requestContext.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new UnauthorizedException("Token JWT mancante");
        }

        String token = authHeader.substring(7);
        try {
            JsonWebToken jwt = jwtParser.parse(token);
            requestContext.setSecurityContext(buildSecurityContext(jwt));
        } catch (Exception e) {
            log.debug("JWT non valido: {}", e.getMessage());
            throw new UnauthorizedException("Token JWT non valido o scaduto");
        }
    }

    private boolean isPublicPath(String path) {
        // /auth/google/* = flusso OAuth2 pubblico
        // /auth/refresh  = rinnovo token tramite refresh token (nessun JWT)
        // /auth/logout e /auth/me richiedono JWT
        return path.startsWith("/auth/google/")
            || path.equals("/auth/refresh")
            || path.startsWith("/q/");
    }

    private SecurityContext buildSecurityContext(JsonWebToken jwt) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return jwt;
            }

            @Override
            public boolean isUserInRole(String role) {
                return jwt.getGroups().contains(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        };
    }
}
