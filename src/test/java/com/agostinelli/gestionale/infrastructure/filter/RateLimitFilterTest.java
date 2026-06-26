package com.agostinelli.gestionale.infrastructure.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter();

    @Test
    void usaSubJwtQuandoAutenticato() {
        Principal p = mock(Principal.class);
        when(p.getName()).thenReturn("user-123");
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getUserPrincipal()).thenReturn(p);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getSecurityContext()).thenReturn(sc);

        assertEquals("u:user-123", filter.extractClientKey(ctx));
    }

    @Test
    void fallbackSuIpQuandoAnonimo() {
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getUserPrincipal()).thenReturn(null);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getSecurityContext()).thenReturn(sc);
        // security: il client finge "1.2.3.4"; il nostro proxy appende il vero peer.
        // Deve vincere l'ultimo hop (203.0.113.7), non quello forgiato dal client.
        when(ctx.getHeaderString("X-Forwarded-For")).thenReturn("1.2.3.4, 203.0.113.7");

        assertEquals("ip:203.0.113.7", filter.extractClientKey(ctx));
    }

    @Test
    void preferisceCfConnectingIpDietroCloudflare() {
        SecurityContext sc = mock(SecurityContext.class);
        when(sc.getUserPrincipal()).thenReturn(null);
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getSecurityContext()).thenReturn(sc);
        // security: dietro Cloudflare l'XFF finisce con l'IP di CF (tutti gli utenti).
        // CF-Connecting-IP porta il vero client e deve avere la precedenza.
        when(ctx.getHeaderString("CF-Connecting-IP")).thenReturn("198.51.100.42");
        when(ctx.getHeaderString("X-Forwarded-For")).thenReturn("198.51.100.42, 172.16.0.5");

        assertEquals("ip:198.51.100.42", filter.extractClientKey(ctx));
    }
}
