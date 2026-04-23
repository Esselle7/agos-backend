package com.agostinelli.gestionale.auth.service;

/**
 * Dati utente estratti e validati dal Google ID token.
 */
public record GoogleUserInfo(String sub, String email, String name) {}
