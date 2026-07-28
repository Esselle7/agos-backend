package com.agostinelli.gestionale.movimenti.dto;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Criteri di filtro della lista movimenti — dato puro, nessun comportamento di dominio.
 * Esiste per una ragione sola: {@code list}, {@code count} e {@code sommario} devono vedere
 * ESATTAMENTE gli stessi filtri (spec «lista e riepilogo vedono sempre lo stesso insieme»).
 * Con 15 criteri, passarli sciolti significherebbe tre firme da 15 parametri da tenere
 * allineate a mano: un parameter object li rende una cosa sola.
 *
 * Ogni dimensione è una lista: valori dentro la stessa dimensione vanno in OR,
 * dimensioni diverse vanno in AND.
 */
public record MovimentiFilterQuery(
        List<String> tipo,
        List<String> stato,
        List<String> fonte,
        List<Short> contoId,
        List<Integer> cogeId,
        List<Short> buId,
        List<Long> categoriaId,
        List<Integer> metodoPagamentoId,
        List<UUID> fornitoreId,
        List<UUID> eventoId,
        BigDecimal importoMin,
        BigDecimal importoMax,
        DateField dateField,
        LocalDate from,
        LocalDate to,
        String search
) {

    /**
     * Sentinella per «movimenti senza banca» ({@code conto_bancario_id IS NULL}).
     * Gli id reali di conti_bancari partono da 1, quindi 0 è libero e non collide.
     * NULL non può stare in una clausola IN: serve un valore trasportabile in query string.
     */
    public static final short CONTO_SENZA_BANCA = 0;

    /**
     * Quale delle tre date del modello riceve il range from/to.
     * È un enum e non una stringa proprio perché il nome della colonna finisce in JPQL:
     * l'input dell'utente non deve mai poter scegliere un campo arbitrario.
     */
    public enum DateField {
        MOVIMENTO("dataMovimento"),
        FINANZIARIA("dataFinanziaria"),
        LIQUIDITA("dataLiquidita");

        private final String field;

        DateField(String field) {
            this.field = field;
        }

        /** Nome del campo JPQL — proviene dall'enum, mai dalla richiesta. */
        public String field() {
            return field;
        }

        public static DateField parse(String raw) {
            if (raw == null || raw.isBlank()) return MOVIMENTO;
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiException(Response.Status.BAD_REQUEST, "DATE_FIELD_NON_VALIDO",
                        "dateField deve essere MOVIMENTO, FINANZIARIA o LIQUIDITA (ricevuto: " + raw + ")");
            }
        }
    }

    /**
     * Valida al trust boundary: un range invertito è un errore dell'utente, non una lista
     * vuota da mostrare in silenzio (Charter Corsia A — Fail Fast).
     */
    public MovimentiFilterQuery {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_DATE_INVERTITO",
                    "La data iniziale (" + from + ") è successiva alla finale (" + to + ")");
        }
        if (importoMin != null && importoMax != null && importoMin.compareTo(importoMax) > 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_IMPORTO_INVERTITO",
                    "L'importo minimo (" + importoMin + ") è maggiore del massimo (" + importoMax + ")");
        }
        dateField = dateField != null ? dateField : DateField.MOVIMENTO;
    }

    /** Filtro vuoto: la lista non filtrata resta identica a prima della feature. */
    public static MovimentiFilterQuery none() {
        return new MovimentiFilterQuery(null, null, null, null, null, null, null, null, null, null,
                null, null, DateField.MOVIMENTO, null, null, null);
    }
}
