package com.agostinelli.gestionale.eventi.service;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.infrastructure.exception.ForbiddenException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test puri per la macchina a stati degli eventi.
 * Non richiede Quarkus, DB o HTTP: testa la logica di business in isolamento.
 */
class EventoStatoMacchinaTest {

    // ── helpers ────────────────────────────────────────────────────────────────

    private Evento eventoConStato(String stato) {
        Evento e = new Evento();
        e.stato = stato;
        e.importoIncassato        = BigDecimal.ZERO;
        e.caparreIncassate        = BigDecimal.ZERO;
        e.costiDirettiImputati    = BigDecimal.ZERO;
        return e;
    }

    // ── SALDATO → qualsiasi ────────────────────────────────────────────────────

    @Test
    void saldato_a_confermato_forbidden() {
        Evento e = eventoConStato("SALDATO");
        e.importoTotalePreviventivato = new BigDecimal("1000");
        e.importoIncassato = new BigDecimal("1000");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", true, null));
        assertTrue(ex.getMessage().contains("saldato"), "Messaggio deve riferirsi a 'saldato'");
    }

    @Test
    void saldato_a_preventivato_forbidden() {
        Evento e = eventoConStato("SALDATO");
        e.importoTotalePreviventivato = new BigDecimal("500");
        e.importoIncassato = new BigDecimal("500");

        assertThrows(ForbiddenException.class,
                () -> EventoStatoMacchina.valida(e, "PREVENTIVATO", true, null));
    }

    @Test
    void saldato_a_annullato_comunque_forbidden() {
        Evento e = eventoConStato("SALDATO");
        e.importoTotalePreviventivato = new BigDecimal("1000");
        e.importoIncassato = new BigDecimal("1000");

        assertThrows(ForbiddenException.class,
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", true, "note"));
    }

    // ── QUALSIASI → ANNULLATO ─────────────────────────────────────────────────

    @Test
    void preventivato_a_annullato_dipendente_forbidden() {
        Evento e = eventoConStato("PREVENTIVATO");

        ForbiddenException ex = assertThrows(ForbiddenException.class,
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", false, "qualche nota"));
        assertTrue(ex.getMessage().contains("ADMIN"));
    }

    @Test
    void confermato_a_annullato_dipendente_forbidden() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("1000");

        assertThrows(ForbiddenException.class,
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", false, "nota"));
    }

    @Test
    void preventivato_a_annullato_admin_senza_note_bad_request() {
        Evento e = eventoConStato("PREVENTIVATO");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("NOTE_ANNULLAMENTO_OBBLIGATORIE", ex.getCode());
    }

    @Test
    void preventivato_a_annullato_admin_note_blank_bad_request() {
        Evento e = eventoConStato("PREVENTIVATO");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", true, "   "));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("NOTE_ANNULLAMENTO_OBBLIGATORIE", ex.getCode());
    }

    @Test
    void preventivato_a_annullato_admin_con_note_ok() {
        Evento e = eventoConStato("PREVENTIVATO");

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", true, "Motivo reale di annullamento"));
    }

    @Test
    void confermato_a_annullato_admin_con_note_ok() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("1000");
        e.importoIncassato = new BigDecimal("500");

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "ANNULLATO", true, "Cliente ha disdetto"));
    }

    // ── PREVENTIVATO → CONFERMATO ─────────────────────────────────────────────

    @Test
    void preventivato_a_confermato_senza_importo_bad_request() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = null;

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("IMPORTO_PREVENTIVATO_MANCANTE", ex.getCode());
    }

    @Test
    void preventivato_a_confermato_con_importo_zero_bad_request() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = BigDecimal.ZERO;

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("IMPORTO_PREVENTIVATO_MANCANTE", ex.getCode());
    }

    @Test
    void preventivato_a_confermato_con_importo_negativo_bad_request() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = new BigDecimal("-100");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", false, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
    }

    @Test
    void preventivato_a_confermato_con_importo_valido_ok() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = new BigDecimal("1500.00");

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", false, null));
    }

    @Test
    void preventivato_a_confermato_importo_minimo_un_centesimo_ok() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = new BigDecimal("0.01");

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", false, null));
    }

    // ── CONFERMATO → SALDATO ──────────────────────────────────────────────────

    @Test
    void confermato_a_saldato_senza_importo_bad_request() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = null;

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("IMPORTO_PREVENTIVATO_MANCANTE", ex.getCode());
    }

    @Test
    void confermato_a_saldato_con_residuo_significativo_conflict() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("1000.00");
        e.importoIncassato            = new BigDecimal("900.00"); // residuo = 100

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
        assertEquals(Response.Status.CONFLICT, ex.getHttpStatus());
        assertEquals("RESIDUO_NON_AZZERATO", ex.getCode());
    }

    @Test
    void confermato_a_saldato_residuo_0_02_conflict() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("100.00");
        e.importoIncassato            = new BigDecimal("99.98"); // residuo = 0.02 > 0.01

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
        assertEquals(Response.Status.CONFLICT, ex.getHttpStatus());
    }

    @Test
    void confermato_a_saldato_residuo_esattamente_0_01_ok() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("100.00");
        e.importoIncassato            = new BigDecimal("99.99"); // residuo = 0.01

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
    }

    @Test
    void confermato_a_saldato_saldo_zero_ok() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("2500.00");
        e.importoIncassato            = new BigDecimal("2500.00");

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
    }

    @Test
    void confermato_a_saldato_sovraincassato_ok() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("1000.00");
        e.importoIncassato            = new BigDecimal("1050.00"); // residuo = -50

        assertDoesNotThrow(
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
    }

    // ── Transizioni non ammesse ────────────────────────────────────────────────

    @Test
    void preventivato_a_saldato_non_ammesso() {
        Evento e = eventoConStato("PREVENTIVATO");
        e.importoTotalePreviventivato = new BigDecimal("1000");
        e.importoIncassato = new BigDecimal("1000");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "SALDATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("TRANSIZIONE_NON_AMMESSA", ex.getCode());
    }

    @Test
    void confermato_a_preventivato_non_ammesso() {
        Evento e = eventoConStato("CONFERMATO");
        e.importoTotalePreviventivato = new BigDecimal("500");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "PREVENTIVATO", true, null));
        assertEquals(Response.Status.BAD_REQUEST, ex.getHttpStatus());
        assertEquals("TRANSIZIONE_NON_AMMESSA", ex.getCode());
    }

    @Test
    void annullato_a_qualsiasi_non_ammesso() {
        Evento e = eventoConStato("ANNULLATO");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", true, null));
        assertEquals("TRANSIZIONE_NON_AMMESSA", ex.getCode());
    }

    @Test
    void stato_sconosciuto_come_sorgente_non_ammesso() {
        Evento e = eventoConStato("STATO_INESISTENTE");

        ApiException ex = assertThrows(ApiException.class,
                () -> EventoStatoMacchina.valida(e, "CONFERMATO", true, null));
        assertEquals("TRANSIZIONE_NON_AMMESSA", ex.getCode());
    }
}
