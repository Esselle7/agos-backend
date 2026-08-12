package com.agostinelli.gestionale.eventi;

import com.agostinelli.gestionale.eventi.service.EventiService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REGRESSION (2026-08-09) — la descrizione del movimento-evento non deve mai portare due
 * prefissi tra parentesi quadre in fila.
 *
 * <p>Sintomo osservato in produzione sul primo movimento a DB:
 * {@code "[EVENTO] [DA ATTRIBUIRE] GIOVACCHINI MATTIA – SALDO"} — è la "frase sballata"
 * segnalata dal proprietario.
 *
 * <p>Root cause: {@code EventiService} componeva {@code "[EVENTO] " + e.nome + …} mentre
 * {@code ImportTriageService:520} crea i segnaposto con il nome già prefissato
 * {@code "[DA ATTRIBUIRE] …"}. Due marcatori indipendenti che si concatenano.
 *
 * <p>Che sia un segnaposto è già portato dalla colonna {@code is_segnaposto}: nella descrizione
 * basta il nome della controparte.
 */
class DescrizioneMovimentoEventoTest {

    /** Ricompone la descrizione come fa EventiService, per verificarne il formato. */
    private static String descrizione(String nomeEvento, String tipo) {
        return "[EVENTO] " + EventiService.spogliaSegnaposto(nomeEvento) + " – " + tipo;
    }

    @Test
    void segnaposto_nonProduceDuePrefissiInFila() {
        String d = descrizione("[DA ATTRIBUIRE] GIOVACCHINI MATTIA", "SALDO");

        assertEquals("[EVENTO] GIOVACCHINI MATTIA – SALDO", d);
        // il check che fallirebbe senza il fix: prima il risultato era
        // "[EVENTO] [DA ATTRIBUIRE] GIOVACCHINI MATTIA – SALDO"
        assertFalse(d.contains("[DA ATTRIBUIRE]"),
                "il marcatore di segnaposto non va ripetuto nella descrizione del movimento");
        assertEquals(1, d.chars().filter(c -> c == '[').count(),
                "un solo prefisso tra parentesi quadre");
    }

    @Test
    void eventoNormale_restaInvariato() {
        assertEquals("[EVENTO] Matrimonio Sara cantaluppi – ACCONTO",
                descrizione("Matrimonio Sara cantaluppi", "ACCONTO"));
    }

    @Test
    void nomeNullo_nonEsplode() {
        assertEquals("[EVENTO]  – SALDO", descrizione(null, "SALDO"));
    }
}
