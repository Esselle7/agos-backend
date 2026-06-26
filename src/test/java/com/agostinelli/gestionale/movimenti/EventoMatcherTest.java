package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.EventoMatcher;
import com.agostinelli.gestionale.movimenti.importlayer.EventoMatcher.Esito;
import com.agostinelli.gestionale.movimenti.importlayer.EventoMatcher.Segnali;
import com.agostinelli.gestionale.movimenti.importlayer.EventoMatcher.Spiegazione;
import com.agostinelli.gestionale.movimenti.importlayer.EventoMatcher.Tono;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test puri della logica di aggancio cross-sorgente senza chiave_aggancio.
 * Casi derivati da coppie reali Billy↔banca (COLOSIMO, SPINELLI, BERNASCONI...).
 */
class EventoMatcherTest {

    static final BigDecimal IMP = new BigDecimal("500.00");
    static final LocalDate D = LocalDate.of(2026, 2, 4);

    private Segnali s(String nome, String iban, LocalDate dataEvento, LocalDate dataMov) {
        return new Segnali(IMP, "ENTRATA", dataMov, nome, iban, dataEvento, null);
    }

    // ── corroborazione forte → CERTA ───────────────────────────────────────────

    @Test
    void nomeCognomeCoincide_certa() {
        Segnali a = s("SPINELLI DARIO", null, null, D);
        Segnali b = s("SPINELLI DARIO - PARROTTO STEFANIA", null, null, D);
        assertEquals(Esito.CERTA, EventoMatcher.valuta(a, b));
    }

    @Test
    void nomeOrdineInvertito_certa() {
        assertEquals(Esito.CERTA, EventoMatcher.valuta(
                s("DARIO SPINELLI", null, null, D), s("SPINELLI DARIO", null, null, D)));
    }

    @Test
    void ibanUguale_certa_ancheSenzaNome() {
        Segnali a = s(null, "IT56I0501803200000011223344", null, D);
        Segnali b = s(null, "IT56I0501803200000011223344", null, D.plusDays(3));
        assertEquals(Esito.CERTA, EventoMatcher.valuta(a, b));
    }

    @Test
    void dataEventoUguale_certa() {
        LocalDate ev = LocalDate.of(2026, 5, 16);
        assertEquals(Esito.CERTA, EventoMatcher.valuta(
                s(null, null, ev, D), s(null, null, ev, D.plusDays(2))));
    }

    // ── conflitto → NESSUNO (eventi diversi) ────────────────────────────────────

    @Test
    void nomiDiversi_nessuno() {
        assertEquals(Esito.NESSUNO, EventoMatcher.valuta(
                s("ROSSI MARIO", null, null, D), s("BIANCHI GIULIA", null, null, D)));
    }

    @Test
    void soloNomeProprioComune_nessuno() {
        // "MARCO" (len 5) condiviso ma nessun cognome lungo comune → non basta
        assertEquals(Esito.NESSUNO, EventoMatcher.valuta(
                s("MARCO ROSSI", null, null, D), s("MARCO BIANCHI", null, null, D)));
    }

    @Test
    void ibanDiverso_nessuno() {
        assertEquals(Esito.NESSUNO, EventoMatcher.valuta(
                s("CAPUTO LUIGI", "IT01A0000000000000000000001", null, D),
                s("CAPUTO LUIGI", "IT02B0000000000000000000002", null, D)));
    }

    @Test
    void dataEventoDiversa_nessuno() {
        assertEquals(Esito.NESSUNO, EventoMatcher.valuta(
                s("CAPUTO LUIGI", null, LocalDate.of(2026, 7, 4), D),
                s("CAPUTO LUIGI", null, LocalDate.of(2026, 8, 29), D)));
    }

    // ── solo importo+data → PROBABILE / NESSUNO ─────────────────────────────────

    @Test
    void sparse_dataRavvicinata_probabile() {
        // riga Billy "GIROCONTO/BONIFICO" (niente nome/iban/data-evento) vs evento generico
        assertEquals(Esito.PROBABILE, EventoMatcher.valuta(
                s(null, null, null, D), s(null, null, null, D.plusDays(1))));
    }

    @Test
    void sparse_dataLontana_nessuno() {
        assertEquals(Esito.NESSUNO, EventoMatcher.valuta(
                s(null, null, null, D), s(null, null, null, D.plusDays(10))));
    }

    @Test
    void riccoVsSparse_probabile() {
        // un lato ha nome+data-evento, l'altro no → nessun conflitto, nessun match forte
        assertEquals(Esito.PROBABILE, EventoMatcher.valuta(
                s("COLOSIMO ROBERTA", null, LocalDate.of(2026, 6, 1), D),
                s(null, null, null, D)));
    }

    // ── decisione duplicato su lista candidati ──────────────────────────────────

    @Test
    void isDuplicato_unaCerta_true() {
        Segnali nuovo = s("BERNASCONI MASSIMO", null, null, D);
        assertTrue(EventoMatcher.isDuplicato(nuovo, List.of(
                s("ISELLA CRISTINA BERNASCONI MASSIMO", null, null, D),
                s("ALTRO TIZIO", null, null, D))));
    }

    @Test
    void isDuplicato_unaProbabileUnica_true() {
        assertTrue(EventoMatcher.isDuplicato(s(null, null, null, D),
                List.of(s(null, null, null, D.plusDays(2)))));
    }

    @Test
    void isDuplicato_dueProbabiliAmbigue_false() {
        // due candidati sparse stesso importo nella finestra → ambiguo → si tiene (no fusione)
        assertFalse(EventoMatcher.isDuplicato(s(null, null, null, D),
                List.of(s(null, null, null, D), s(null, null, null, D.plusDays(1)))));
    }

    @Test
    void isDuplicato_certaVinceSuProbabiliMultiple() {
        // anche con più PROBABILI, una CERTA decide la fusione
        Segnali nuovo = s("VALLI CHIARA", null, null, D);
        assertTrue(EventoMatcher.isDuplicato(nuovo, List.of(
                s(null, null, null, D),
                s(null, null, null, D.plusDays(1)),
                s("VALLI CHIARA", null, null, D.plusDays(1)))));
    }

    @Test
    void isDuplicato_nessunCandidato_false() {
        assertFalse(EventoMatcher.isDuplicato(s("TIZIO CAIO", null, null, D), List.of()));
    }

    // ── tokenizzazione ──────────────────────────────────────────────────────────

    @Test
    void tokens_scartaStopwordECorti() {
        var t = EventoMatcher.tokens("SOCIETA AGRICOLA AGOSTINELLI SRL DI MARIO");
        assertTrue(t.contains("MARIO"));
        assertFalse(t.contains("SRL"));
        assertFalse(t.contains("DI"));
        assertFalse(t.contains("AGOSTINELLI"));
    }

    // ── spiegazione motivata ────────────────────────────────────────────────────

    @Test
    void spiega_certa_motiviForti() {
        Spiegazione sp = EventoMatcher.spiega(
                s("SPINELLI DARIO", null, LocalDate.of(2026, 5, 16), D),
                s("SPINELLI DARIO - PARROTTO STEFANIA", null, LocalDate.of(2026, 5, 16), D));
        assertEquals(Esito.CERTA, sp.esito());
        assertTrue(sp.punteggio() >= 80, "confidenza alta attesa, era " + sp.punteggio());
        assertTrue(sp.motivi().stream().anyMatch(m -> m.segnale().equals("Nominativo") && m.tono() == Tono.FORTE));
        assertTrue(sp.motivi().stream().anyMatch(m -> m.segnale().equals("Data evento") && m.tono() == Tono.FORTE));
        assertTrue(sp.motivi().stream().anyMatch(m -> m.segnale().equals("Importo")));
    }

    @Test
    void spiega_conflitto_motivoConflittoEPunteggioBasso() {
        Spiegazione sp = EventoMatcher.spiega(
                s("ROSSI MARIO", null, null, D), s("BIANCHI GIULIA", null, null, D));
        assertEquals(Esito.NESSUNO, sp.esito());
        assertTrue(sp.punteggio() < 20);
        assertTrue(sp.motivi().stream().anyMatch(m -> m.tono() == Tono.CONFLITTO),
                "deve esserci una motivazione di conflitto");
    }

    @Test
    void spiega_probabile_soloImportoEData() {
        Spiegazione sp = EventoMatcher.spiega(
                s(null, null, null, D), s(null, null, null, D.plusDays(2)));
        assertEquals(Esito.PROBABILE, sp.esito());
        assertTrue(sp.motivi().stream().anyMatch(m -> m.segnale().equals("Data movimento")));
        assertTrue(sp.motivi().stream().anyMatch(m -> m.segnale().equals("Importo")));
    }

    @Test
    void nomiCoincidono_cognomeLungoSingoloBasta() {
        assertTrue(EventoMatcher.nomiCoincidono(
                EventoMatcher.tokens("BERNASCONI ANNA"), EventoMatcher.tokens("BERNASCONI LUCA")));
        assertFalse(EventoMatcher.nomiCoincidono(
                EventoMatcher.tokens("ROSSI ANNA"), EventoMatcher.tokens("ROSSI LUCA")));
    }
}
