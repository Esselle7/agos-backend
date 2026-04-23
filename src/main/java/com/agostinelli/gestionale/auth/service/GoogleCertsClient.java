package com.agostinelli.gestionale.auth.service;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Client MicroProfile REST per il JWKS di Google.
 * Le chiavi vengono usate per verificare la firma dei Google ID token.
 */
@RegisterRestClient(configKey = "google-certs")
@Path("/")
public interface GoogleCertsClient {

    /**
     * Scarica le chiavi pubbliche RSA di Google in formato JWK.
     */
    @GET
    @Path("/oauth2/v3/certs")
    @Produces(MediaType.APPLICATION_JSON)
    GoogleCertsResponse getCerts();
}
