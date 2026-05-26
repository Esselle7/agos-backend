package com.agostinelli.gestionale.movimenti.dto;

import java.util.Map;

public record EtlRowError(int riga, String messaggio, Map<String, String> rawData) {}
