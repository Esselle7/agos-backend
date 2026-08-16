package com.agostinelli.gestionale.movimenti.importlayer.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Il marcatore è l'UNICO contratto fra l'import e il wizard (ADR 008: nessuna colonna nuova), e
 * dal 13/08/2026 porta anche il ramo. Due cose devono restare vere insieme:
 *
 * <ol>
 *   <li>quello che il motore scrive, il wizard lo rilegge identico;</li>
 *   <li>le note già a DB — scritte quando il marcatore portava solo il conto — continuano a
 *       rileggersi, e a farsi togliere quando l'utente decide. Se {@code rimuovi} smettesse di
 *       riconoscerle, la frase «ti propongo X» resterebbe attaccata a un movimento già catalogato
 *       e mentirebbe per sempre.</li>
 * </ol>
 */
class PropostaTest {

    @Test
    void ilRamoScrittoEIlRamoRiletto() {
        Proposta scritta = new Proposta("40.05.002", (short) 2, UUID.randomUUID(), "la firma «ENEL»");
        String nota = scritta.marcatore() + " " + scritta.perche();

        Proposta riletta = Proposta.leggi(nota);
        assertNotNull(riletta);
        assertEquals("40.05.002", riletta.cogeCodice());
        assertEquals((short) 2, riletta.bu());
        assertEquals("la firma «ENEL»", riletta.perche());
        // Il fornitore NON viaggia nella nota: sta su movimenti.fornitore_id, una fonte sola.
        assertNull(riletta.fornitoreId());
    }

    @Test
    void senzaRamoIlMarcatoreRestaQuelloDiPrima() {
        Proposta senzaBu = new Proposta("49.01.001", null, null, "alias fornitore");
        assertEquals("PROPOSTA[49.01.001]", senzaBu.marcatore());
        assertNull(Proposta.leggi(senzaBu.marcatore() + " alias fornitore").bu());
    }

    @Test
    void leNoteScritteFinoAlDodiciAgostoSiRileggonoAncora() {
        Proposta vecchia = Proposta.leggi("PROPOSTA[40.05.002] la firma keyword «TELECOM»");
        assertNotNull(vecchia);
        assertEquals("40.05.002", vecchia.cogeCodice());
        assertNull(vecchia.bu(), "una nota vecchia non ha ramo: si dice, non si inventa");
        assertEquals("la firma keyword «TELECOM»", vecchia.perche());
    }

    @Test
    void rimuoviCancellaEntrambiIFormatiEConservaCioCheAvevaScrittoLUtente() {
        assertNull(Proposta.rimuovi("PROPOSTA[40.05.002|bu=2] perché sì"));
        assertNull(Proposta.rimuovi("PROPOSTA[40.05.002] perché sì"));
        assertEquals("nota mia", Proposta.rimuovi("nota mia | PROPOSTA[40.05.002|bu=3] perché sì"));
        assertEquals("nota mia", Proposta.rimuovi("nota mia | PROPOSTA[40.05.002] perché sì"));
    }

    @Test
    void unRamoIllegibileValeNonCeUnRamo() {
        // Fail fast qui vorrebbe dire far esplodere la coda intera per una nota storta: il
        // movimento c'è, i soldi ci sono, e l'unica cosa che manca è un suggerimento.
        Proposta p = Proposta.leggi("PROPOSTA[40.05.002|bu=due] perché");
        assertNotNull(p);
        assertEquals("40.05.002", p.cogeCodice());
        assertNull(p.bu());
    }

    @Test
    void unaNotaSenzaMarcatoreNonEUnaProposta() {
        assertNull(Proposta.leggi("appunto dell'operatore"));
        assertNull(Proposta.leggi(null));
        assertEquals("appunto dell'operatore", Proposta.rimuovi("appunto dell'operatore"));
    }
}
