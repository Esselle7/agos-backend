package com.agostinelli.gestionale.auth.service;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Client MicroProfile REST per l'endpoint token di Google OAuth2.
 * Gestisce lo scambio del authorization code con i token Google.
 */
@RegisterRestClient(configKey = "google-oauth")
@Path("/")
public interface GoogleOAuthClient {

    /**
     * Scambia il codice OAuth2 con i token Google (access_token + id_token).
     */
    @POST
    @Path("/token")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    GoogleTokenResponse exchangeToken(
        @FormParam("code")          String code,
        @FormParam("client_id")     String clientId,
        @FormParam("client_secret") String clientSecret,
        @FormParam("redirect_uri")  String redirectUri,
        @FormParam("grant_type")    String grantType
    );
}
