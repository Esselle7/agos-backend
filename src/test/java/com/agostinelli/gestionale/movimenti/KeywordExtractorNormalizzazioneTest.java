package com.agostinelli.gestionale.movimenti;

import com.agostinelli.gestionale.movimenti.importlayer.keyword.KeywordExtractor;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit test (NO DB) dei due difetti di normalizzazione che rendono alcuni fornitori
 * strutturalmente inagganciabili (audit keyword del 24/08/2026, §8.6 e §8.6-bis).
 *
 * <p>Entrambi vivono dentro {@code lex()}, che alimenta <b>solo</b> il layer keyword (match e
 * apprendimento). Non toccano {@code MovimentoNormalizerImpl.clean()}, che alimenta anche il
 * Gate B, le {@code regole_classificazione} e i carve-out hardcoded: allargare il raggio d'azione
 * di tutto il motore per un caso da 2 righe sarebbe sproporzionato.
 *
 * <p><b>Perché "spazi ai lati" e non "togli i punti".</b> La variante che si limita a togliere i
 * punti lascia la sigla incollata alla parola vicina e produce token spazzatura — misurato:
 * {@code MANGINOSRL}, {@code SASRIF}, {@code SNCDI}. La variante con gli spazi ha le stesse zero
 * regressioni e in più recupera {@code MANGINO}, che è il cognome vero del fornitore.
 */
class KeywordExtractorNormalizzazioneTest {

    /** Le stopword che contano qui: le forme societarie sono già stopword a DB (verificato). */
    private static final Set<String> STOP = Set.of("SRL", "SPA", "SNC", "SAS", "SS", "SOC", "SOCIETA", "RIF", "CRO");

    private static Set<String> tok(String d) {
        return KeywordExtractor.tokenizza(d, STOP);
    }

    // ── §8.6 — sigle puntate ─────────────────────────────────────────────────────────────

    @Test
    void siglaPuntata_diventaUnTokenIntero() {
        // "F.O.C." oggi si spezza in F/O/C, tutti sotto LEN_MIN=3 e quindi già buttati:
        // compattarli AGGIUNGE un token, non ne toglie nessuno.
        assertTrue(tok("EFFETTI RITIRATI - ADD.EFFETTO - F.O.C. SRL VIA S.").contains("FOC"),
                "F.O.C. deve produrre il token FOC");
    }

    @Test
    void siglaPuntata_conSpaziAiLati_nonIncollaLaSiglaAllaParolaVicina() {
        Set<String> t = tok("C7ZWE SOCIETA' AGRICOLA AGOS000000754300594 COMEDIL MANGINOS.R.L. RIF. CRO: NROSUPCBI");
        assertTrue(t.contains("MANGINO"), "il cognome del fornitore deve emergere: " + t);
        assertFalse(t.contains("MANGINOS"), "MANGINOS era il token rotto di oggi");
        assertFalse(t.contains("MANGINOSRL"), "variante 'togli i punti': token spazzatura");
    }

    @Test
    void siglaPuntata_nonProduceTokenSpazzatura() {
        // I tre token che la variante scartata produceva, misurati sull'audit.
        assertFalse(tok("ECOPAPER S.A.S.RIF. CRO: 40361717735688").contains("SASRIF"));
        assertFalse(tok("GABAGLIO S.N.C.DI NICODEMO E DAVIDE DI DIO").contains("SNCDI"));
    }

    // ── §8.6-bis — RIF. incollato al cognome ─────────────────────────────────────────────

    @Test
    void rifIncollatoAlCognome_vieneStaccato() {
        Set<String> t = tok("C7ZWE SOCIETA' AGRICOLA AGOS000000741888806 CARLO BERNASCONIRIF. CRO: NROSUPCBI 35625784");
        assertTrue(t.contains("BERNASCONI"), "il cognome deve staccarsi da RIF.: " + t);
        assertFalse(t.contains("BERNASCONIRIF"), "BERNASCONIRIF era l'artefatto di parsing");
    }

    @Test
    void rifIncollato_altriCognomiReali() {
        assertTrue(tok("GIULIA PISCHEDDURIF. CRO: NROSUPCBI").contains("PISCHEDDU"));
        assertTrue(tok("RODOLFO AQUILINIRIF. CRO: NROSUPCBI 35682478").contains("AQUILINI"));
        assertTrue(tok("DANIELA GUARISCORIF. CRO: NROSUPCBI").contains("GUARISCO"));
        assertTrue(tok("MALLAMACE FABIORIF. CRO: NROSUPCBI").contains("FABIO"));
    }

    @Test
    void rifResta_unRiferimentoVolatile_scartato() {
        // ANTI-REGRESSIONE sull'oracolo: staccare RIF. non deve far entrare i codici volatili che
        // seguono RIF/CRO — è la difesa di KeywordExtractor:177, e deve continuare a valere.
        Set<String> t = tok("VS.DISP. RIF. MBVT91830637/00970175 FAVORE SELECOVER SRL NOTPROVIDE");
        assertFalse(t.contains("MBVT91830637"), "il codice dopo RIF resta volatile e va scartato: " + t);
        assertTrue(t.contains("SELECOVER"), "il nome del beneficiario resta: " + t);
    }

    // ── Il match è su token interi: un set che cresce può far scattare PIÙ firme, mai meno ──

    @Test
    void ilSetDiTokenCresce_nonPerdeToken() {
        String d = "C7ZWE SOCIETA' AGRICOLA AGOS000000754300594 COMEDIL MANGINOS.R.L. RIF. CRO: NROSUPCBI";
        Set<String> t = tok(d);
        // i token che c'erano prima del fix e che devono restare
        assertTrue(t.contains("COMEDIL"), "COMEDIL non deve sparire: " + t);
        assertTrue(t.contains("AGOS000000754300594"), "il codice di disposizione resta un CODICE: " + t);
    }
}
