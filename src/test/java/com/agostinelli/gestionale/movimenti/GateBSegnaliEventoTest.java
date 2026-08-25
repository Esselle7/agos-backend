package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.MovimentoMappingEngineImpl;
import com.agostinelli.gestionale.movimenti.importlayer.model.ParkEvento;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guardie sul Gate B (parcheggio degli incassi-evento), audit keyword del 24/08/2026 §8.4 e §8.8.
 *
 * <p>Due comportamenti diversi, difesi nello stesso posto perché vivono nello stesso metodo:
 * <ul>
 *   <li><b>AFFITTO_SALA non è un tipo valido.</b> {@code lk_tipi_evento_mov} contiene i MOMENTI
 *       di pagamento (acconto, caparra, competenza, penale, rimborso, saldo); AFFITTO_SALA è un
 *       servizio. Il motore smette di indovinare su quel ramo e lascia scegliere l'operatore.</li>
 *   <li><b>Il match substring del Gate B è portante, non tollerato.</b> Passare al confine di
 *       parola costa 15 righe-evento (misurato sul corpus reale): il word-wrap degli estratti
 *       conto incolla le parole, e {@code SCT:SALDOEVENTO} è la forma normale, non l'eccezione.</li>
 * </ul>
 */
@QuarkusTest
class GateBSegnaliEventoTest {

    @Inject
    MovimentoMappingEngineImpl engine;

    private ParkEvento park(String spaced) {
        return engine.estraiSegnaliEvento(spaced, spaced.replaceAll("\\s+", ""), LocalDate.of(2026, 6, 1));
    }

    // ── §8.4 — AFFITTO_SALA: il tipo resta null, la keyword no ───────────────────────────

    @Test
    void affittoSala_nonProduceUnTipoNonValido() {
        ParkEvento p = park("BONIFICO SCT:AFFITTO SALA 12/06/2026 ROSSI MARIO");
        assertNull(p.tipoEventoPresunto(),
                "AFFITTO_SALA non e' un momento di pagamento: lk_tipi_evento_mov non lo contiene");
        // La seconda asserzione e' quella che conta: dimostra che non si e' buttato via il
        // segnale insieme al tipo. La keyword serve a spiegare all'operatore PERCHE' la riga
        // e' stata parcheggiata; senza, il parcheggio diventa muto.
        assertEquals("AFFITTO", p.keywordMatch(),
                "la keyword che ha attivato il parcheggio deve sopravvivere");
    }

    @Test
    void affittoSala_laRigaVieneComunqueParcheggiata() {
        // Il tipo null non deve impedire il parcheggio: la data dell'evento resta estratta.
        assertNotNull(park("BONIFICO SCT:AFFITTOSALA 12/06/2026").keywordMatch(),
                "senza keyword la riga non verrebbe piu' riconosciuta come evento");
    }

    // ── §8.8 — la guardia sul substring: difende il comportamento in ENTRAMBE le direzioni ──

    @Test
    void nozze_dentroUnAltraParola_suggerisceLaKeywordMaNonUnTipo() {
        // Il difetto teorico esiste ed e' bene che il test lo DICA invece di negarlo: NOZZE e'
        // contenuto in ANNOZZERO, e questo metodo lo propone come keyword. Misurato pero':
        // ZERO occorrenze di NOZZE su 1.134 righe reali, nemmeno come substring.
        //
        // Perche' resta innocuo, ed e' il punto che questo test fissa:
        //  (a) estraiSegnaliEvento NON e' il Gate B — e' il costruttore del park, chiamato dal
        //      triage DOPO che l'operatore ha dichiarato «questa riga e' un incasso-evento».
        //      Il gate vero, in map(), e' spento da FATTURA/DOCUM/NOTA CREDITO prima di arrivarci.
        //  (b) il tipo presunto resta null: nessun tipo_evento sbagliato entra nei dati, e la
        //      keyword serve solo a spiegare all'operatore perche' la riga e' stata proposta.
        ParkEvento p = park("BONIFICO ANNOZZERO SRL FATTURA");
        assertNull(p.tipoEventoPresunto(),
                "un match per substring non deve MAI produrre un tipo evento: quello finisce a DB");
    }

    @Test
    void saldoEvento_incollatoDalWordWrap_restaRiconosciuto() {
        // QUESTA e' l'asserzione che protegge il substring da una "correzione" ben intenzionata.
        // Misurato: passare il Gate B al confine di parola fa perdere 15 righe-evento come questa,
        // perche' l'estratto conto scrive SALDOEVENTO tutto attaccato. Se qualcuno cambia
        // compact.contains(k) in un match su parola intera, questo test diventa rosso.
        assertNotNull(park("ORD:CATELLI ASTRID SCT:SALDOEVENTO 01/06/26").keywordMatch(),
                "SALDOEVENTO attaccato deve continuare a far scattare il parcheggio");
        assertNotNull(park("ORD:ESTERI MARCO SCT:CAPARRAEVENTO 29/05/2026").keywordMatch(),
                "CAPARRAEVENTO attaccato deve continuare a far scattare il parcheggio");
    }
}
