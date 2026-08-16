package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Movimento registrato su un conto transitorio (39.99.999 ricavi / 49.99.999 costi)
 * in attesa di catalogazione (ETL v2 §6 C3 / §13). Espone i dati del movimento + le
 * entità ri-estratte dalla descrizione (IBAN/nome controparte) per il triage assistito.
 */
public record TransitorioDTO(
        UUID id,
        String tipo,                 // ENTRATA | USCITA
        BigDecimal importo,
        LocalDate dataMovimento,
        String descrizione,
        String cogeCodiceAttuale,    // 39.99.999 | 49.99.999
        UUID fornitoreId,
        Short contoBancarioId,
        String ibanEstratto,         // dalla descrizione (best-effort)
        String controparteEstratta,  // beneficiario/ordinante ri-estratto
        /**
         * Chiave del gruppo del wizard §7.1: righe dello STESSO esercente. null = la riga è una
         * decisione a sé (nessuna controparte affidabile: POS, effetti/RiBa, causali generiche).
         */
        String gruppo,
        /** Data in cui la vendita è avvenuta, se la causale la dichiara ("… DEL 10/01/26"). */
        String dataOperazione,
        /** Circuito dell'incasso POS letto dalla causale (NEXI, NUMIA-INTER…); null se non è POS. */
        String circuitoPos,
        /** Riscontro Billy della stessa giornata sullo stesso conto — di GIORNATA, non 1:1. */
        RiscontroBillyDTO riscontroBilly,
        /** Conto proposto da una firma keyword appresa — SUGGERIMENTO, mai applicato da solo. */
        Integer cogeSuggeritoId,
        /** Il "perché" del suggerimento, in italiano: si mostra accanto alla proposta. */
        String motivoSuggerimento,
        /**
         * Ramo che il motore aveva calcolato prima di dirottare la riga sul transitorio, oppure
         * quello di default del fornitore riconosciuto. SUGGERIMENTO: il wizard lo pre-seleziona,
         * l'operatore lo vede scritto e può cambiarlo prima di confermare.
         */
        Short buSuggerita,
        /** Ragione sociale del fornitore che il motore ha riconosciuto dall'alias; null se nessuno. */
        String fornitoreNome,
        /** La chiave della riga sull'estratto conto: è ciò che permette di ritrovarla nel PDF banca. */
        String riferimentoEsterno,
        /** Metodo di pagamento letto dalla banca (BONIFICO, SDD, POS_BPM…); null se non determinato. */
        String metodoPagamento,
        /**
         * Le firme che il sistema imparerebbe confermando questa riga — <b>vuota</b> se da questa
         * causale non si impara nulla (POS, effetti/RiBa: senza un intestatario vero nascerebbe una
         * firma spuria che dirotta tutte le righe simili future).
         *
         * <p>Viaggia col DTO e non da un endpoint a parte: l'estrazione è la STESSA di
         * {@code KeywordLearningService.apprendi} — stesso {@code KeywordExtractor}, stessa
         * {@code EntitaEstratte}, già calcolata qui per riga — quindi l'anteprima non può divergere
         * da ciò che verrà davvero scritto. Chiedere la stessa cosa a {@code /keyword/anteprima} una
         * riga per volta significherebbe rimettere N round-trip nella pagina che ne ha appena persi 5.
         */
        java.util.List<FirmaDaImparareDTO> firmeDaImparare) {

    /** Una firma candidata: i token che la compongono e la sua natura (IDENTITA / DOMINIO). */
    public record FirmaDaImparareDTO(java.util.List<String> token, String natura) {}

    /**
     * Che cosa Billy ha registrato lo stesso giorno, sullo stesso conto, di questa riga bancaria.
     *
     * <p><b>È un confronto di GIORNATA, non della singola transazione.</b> L'abbinamento
     * scontrino↔accredito non esiste nei dati: la riconciliazione POS ripartisce i TOTALI di
     * periodo (misurato l'11/08/2026 — l'aggancio per data operazione dà 9 righe su 10 a zero, e
     * per data di accredito gli importi non corrispondono). Serve a far vedere all'operatore
     * l'ordine di grandezza e a segnalare uno scarto, non a quadrare al centesimo.
     *
     * @param scontrini quante righe di ricavo Billy quel giorno su quel conto
     * @param totale    la loro somma
     * @param scarto    importo della riga bancaria − totale Billy (positivo = la banca ha di più)
     * @param categorie di che cosa erano fatti quei ricavi, per voce di bilancio Billy (13/08/2026):
     *                  «320 € di spaccio e 140 € di agriturismo» dice all'operatore che giornata
     *                  era, mentre il solo totale gli dice soltanto che i conti non tornano
     */
    public record RiscontroBillyDTO(long scontrini, java.math.BigDecimal totale,
                                    java.math.BigDecimal scarto,
                                    java.util.List<VoceBillyDTO> categorie) {}

    /** Una voce di ricavo Billy della giornata: come si chiama e quanto vale. */
    public record VoceBillyDTO(String voce, java.math.BigDecimal totale) {}
}
