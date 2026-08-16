package com.agostinelli.gestionale.movimenti.importlayer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

/**
 * Estrazione della data evento dalla causale bancaria (funzione pura, niente Quarkus).
 *
 * <p>Casi presi dalle causali reali della copia di produzione del 07/08/2026 e dagli estratti
 * conto in {@code dati_luglio/} e {@code esempi_dati_storici/}: gli estratti conto vanno a capo
 * dentro la causale e l'a capo arriva qui come spazio, e la banca appende in coda il proprio
 * timestamp ("SCT ISTANTANEO DEL gg/mm/aaaa ORE hh:mm") che NON è la data dell'evento.
 *
 * <p>Due misure di riferimento, entrambe su corpus reale:
 * <ul>
 *   <li>chiusura degli spazi dentro la data — 198 descrizioni, 5 differenze tutte correzioni;</li>
 *   <li>data senza anno ancorata al marcatore — 1096 descrizioni, 14 date nuove + 2 corrette,
 *       0 perse, 0 falsi positivi (docs/adr/007-data-evento-senza-anno.md).</li>
 * </ul>
 */
class MovimentoMappingEngineDateTest {

    private final MovimentoMappingEngineImpl engine = new MovimentoMappingEngineImpl();

    /** Data del movimento bancario: dà l'anno alle date scritte senza. */
    private static final LocalDate LUGLIO = LocalDate.of(2026, 7, 20);

    @Test
    @DisplayName("La causale del cliente batte il timestamp che la banca appende in coda")
    void causaleClienteBatteTimestampBancario() {
        // LO MONACO VANESSA 274,00: l'evento è il 12/07, il 18/07 è l'ora dello SCT.
        Assertions.assertEquals(LocalDate.of(2026, 7, 12), engine.extractEventoDate(
                "ORD:LO MONACO VANESSA DT.ORD:200726 DESCR.OPERAZIONE SCT:EVENTO DOMENICA 12 LUGLIO 202 6 "
                + "LO MONACO VANESSA-SCT ISTANTANEO DEL 18/07/2026 ORE 13:14 RIFERIMENTO SCT:02INTER2026071",
                LUGLIO));
    }

    @Test
    @DisplayName("La data numerica scritta prima del timestamp resta quella buona")
    void dataNumericaPrimaDelTimestamp() {
        Assertions.assertEquals(LocalDate.of(2026, 6, 6), engine.extractEventoDate(
                "ORD:PASCHETTO DAVIDE DT.ORD:020726 DESCR.OPERAZIONE SCT:ACCONTO EVENTO 06/06/2026 PAS "
                + "CHETTO E SMOTER-SCT ISTANTANEO DEL02/07/2026 ORE 17:52", LUGLIO));
        Assertions.assertEquals(LocalDate.of(2026, 8, 16), engine.extractEventoDate(
                "ORD:TABBOUCH JANA DT.ORD:200726 DESCR.OPERAZIONE SCT:CAPARRAEVENTO 16/08/26 JANA, "
                + "TABBOUCH-SCT ISTANTANEO DEL 20/07/2026 ORE 11:43", LUGLIO));
    }

    @Test
    @DisplayName("Gli spazi da a-capo dentro la data non fanno perdere la data")
    void spaziDaACapoDentroLaData() {
        Assertions.assertEquals(LocalDate.of(2026, 9, 18), engine.extractEventoDate(
                "BONIF. VS. FAVORE - BON.DA CELLA ERIKA CAPARRA 3 BIS EVENTO 18.09 .26", LUGLIO));
        Assertions.assertEquals(LocalDate.of(2026, 6, 28), engine.extractEventoDate(
                "ORD:GIOVACCHINI MATTIA DT.ORD:000000 DESCR.OPERAZIONE SCT:SALDO FESTA AMALIA "
                + "GIOVACCHINI 28.06. 26 IDENTIFICATIVO SCT:5034013999656180485148051480IT", LUGLIO));
    }

    @Test
    @DisplayName("Anche il separatore trattino è una data")
    void separatoreTrattino() {
        Assertions.assertEquals(LocalDate.of(2026, 9, 27), engine.extractEventoDate(
                "ORD:ERICA LO STILE DI ESSERE SRL DT.ORD:000000 DESCR.OPERAZIONE SCT:ACCONTO "
                + "EVENTO DEL 27-09-2026 PAGAMENTO FORNITORE", LUGLIO));
    }

    @Test
    @DisplayName("Un anno a tre cifre non è una data: prima nasceva l'anno 202")
    void annoATreCifreNonEUnaData() {
        Assertions.assertEquals(LocalDate.of(2026, 7, 8), engine.extractEventoDate(
                "BONIF. VS. FAVORE - BON.DA BRICCOLA GI FATT NR 15/001 DEL 8/7/202 6", LUGLIO));
        Assertions.assertNull(engine.toDate("202", "7", "8"), "anno 202 = spazzatura, non una data");
    }

    @Test
    @DisplayName("Una data impossibile non blocca la ricerca di quella buona")
    void dataImpossibileNonBlocca() {
        Assertions.assertEquals(LocalDate.of(2026, 12, 4), engine.extractEventoDate(
                "SCT:CAPARRA EVENTO 32/13/2026 POI QUELLA VERA 4 DICEMBRE 2026 CHIARA TIGANO", LUGLIO));
    }

    // ── Data senza anno, ancorata a un marcatore di pagamento-evento ────────────────────────

    @Test
    @DisplayName("«SALDO EVENTO 6/07»: senza anno la data c'è lo stesso, l'anno lo dà il movimento")
    void dataSenzaAnnoAncorataAlMarcatore() {
        // FONTANA / BALZARETTI 550,00 → evento "18esimo Erica balzaretti" del 06/07/2026.
        Assertions.assertEquals(LocalDate.of(2026, 7, 6), engine.extractEventoDate(
                "ORD:FONTANA MARCO E BALZARETTI ERICA DT.ORD:070726 DESCR.OPERAZIONE SCT:SALDO EVENTO "
                + "6/07-SCT ISTANTA NEO DEL 07/07/2026ORE 17:36 IDENTIFICATIVO SCT:260707327362023748032",
                LocalDate.of(2026, 7, 7)));
        // DOTTI FABIO 430,00 → evento "Marianna" dell'08/07/2026.
        Assertions.assertEquals(LocalDate.of(2026, 7, 8), engine.extractEventoDate(
                "ORD:DOTTI FABIO DT.ORD:000000 DESCR.OPERAZIONE SCT:SALDO EVENTO 8/07 DOTTI "
                + "IDENTIFICATIVO SCT:0843000034837020485116051160IT", LocalDate.of(2026, 7, 9)));
        // ABARTH CLUB COMO 500,00 → evento "Abarth" del 05/12/2026 (mese a parole, nessun anno).
        Assertions.assertEquals(LocalDate.of(2026, 12, 5), engine.extractEventoDate(
                "ORD:ABARTH CLUB COMO DT.ORD:000000 DESCR.OPERAZIONE SCT:ACCONTO EVENTO 5 DICEMBRE "
                + "IDENTIFICATIVO SCT:0306914762130109S90960609606IT", LocalDate.of(2026, 7, 30)));
    }

    @Test
    @DisplayName("La data senza anno del cliente batte comunque il timestamp della banca")
    void dataSenzaAnnoBatteIlTimestampBancario() {
        // Prima del fix qui usciva 02/06 e 02/05, cioè l'ora dello SCT: una data evento SBAGLIATA.
        Assertions.assertEquals(LocalDate.of(2026, 5, 31), engine.extractEventoDate(
                "ORD:VISCOMI MIRIAM DT.ORD:030626 DESCR.OPERAZIONE SCT:SALDOEVENTO 31/05-SCT ISTANT "
                + "ANEO DEL 02/06/2026 ORE 12:03 RIFERIMENTO SCT:02INTER20260602HSRT1433698967",
                LocalDate.of(2026, 6, 3)));
        Assertions.assertEquals(LocalDate.of(2026, 5, 17), engine.extractEventoDate(
                "ORD:LENZO DARIO DT.ORD:040526 DESCR.OPERAZIONE SCT:CAPARRA EVENTO 17/05 LENZO DA "
                + "RIO-SCT ISTANTANEO DEL 02/05/2026 ORE 17:58 IDENTIFICATIVO SCT:2612260645856",
                LocalDate.of(2026, 5, 4)));
    }

    @Test
    @DisplayName("Senza marcatore evento una coppia di numeri NON è una data (falsi positivi)")
    void senzaMarcatoreNessunaData() {
        // Il "02-02" è il riferimento del bonifico estero: senza ancora batteva la data vera
        // scritta per esteso più avanti, e l'incasso finiva sull'evento del 2 febbraio.
        Assertions.assertEquals(LocalDate.of(2026, 3, 7), engine.extractEventoDate(
                "ESTERO - BONIFICO IN ENTRATA - 02-02 00650071 PATRIZIA CASOLARI . ACCONTO FESTA "
                + "7 MARZO 2026", LocalDate.of(2026, 2, 10)));
        Assertions.assertNull(engine.extractEventoDate("BONIF. VS. FAVORE - RIF. 12/34", LUGLIO));
        Assertions.assertNull(engine.extractEventoDate(
                "PAGAMENTO EFFETTI/RIBA DETTAGLIO: E. 287,49 E. 663,68 E. 1.098,00", LUGLIO));
        // Un numero di documento dopo il marcatore resta un numero di documento.
        Assertions.assertNull(engine.extractEventoDate(
                "BONIF. VS. FAVORE - BON.DA ROSSI SALDO FATTURA N. 15/001", LUGLIO));
    }

    @Test
    @DisplayName("Senza la data del movimento l'anno non si inventa")
    void senzaRiferimentoNessunAnnoInventato() {
        Assertions.assertNull(engine.extractEventoDate(
                "ORD:DOTTI FABIO DT.ORD:000000 DESCR.OPERAZIONE SCT:SALDO EVENTO 8/07 DOTTI", null));
        Assertions.assertNull(engine.extractEventoDate(
                "ORD:ABARTH CLUB COMO DT.ORD:000000 DESCR.OPERAZIONE SCT:ACCONTO EVENTO 5 DICEMBRE", null));
        Assertions.assertNull(engine.extractEventoDate(null, LUGLIO));
    }

    // ── Filtro anti-fattura del Gate B (audit-catena-import-2026-08-11.md §6.2/1) ──────────

    @Test
    @DisplayName("«FATTI DI SETA» non è un contesto fattura: il nome del cliente non spegne il Gate B")
    void nomeClienteConFattNonEUnaFattura() {
        // Riga #246 CA, 10/02/2026, 1.000,00 €: contains("FATT") agganciava la ragione sociale e
        // l'acconto evento non veniva parcheggiato. Misurati 2 falsi positivi su 6 ENTRATE con FATT.
        Assertions.assertFalse(engine.fatturaCtx(
                "ORD:FATTI DI SETA DI GAGIARDO MARA DT.ORD:000000 DESCR.OPERAZIONE SCT:ACCONTO "
                + "PER EVENTO AZIENDALE DEL 17/0 5/26"));
        Assertions.assertFalse(engine.fatturaCtx("BONIF. VS. FAVORE - BRICCOLA GI ANTICIPO FATT NR 33/001"));
    }

    @Test
    @DisplayName("La fattura vera continua a spegnere il Gate B (parola intera o abbreviazione col punto)")
    void fatturaVeraRestaRiconosciuta() {
        Assertions.assertTrue(engine.fatturaCtx("ORD:FATTI DI SETA SALDO VS. FATTURA N. 7 DEL 19.05.26"));
        Assertions.assertTrue(engine.fatturaCtx("BON.DA ROSSI PAGAMENTO FATTURE 12 E 13"));
        Assertions.assertTrue(engine.fatturaCtx("BON.DA ROSSI SALDO FATT. 15/001"));
        Assertions.assertTrue(engine.fatturaCtx("ACCREDITO DOCUMENTO 998"));
        Assertions.assertTrue(engine.fatturaCtx("STORNO NOTA CREDITO 4"));
        Assertions.assertFalse(engine.fatturaCtx(null));
    }
}
