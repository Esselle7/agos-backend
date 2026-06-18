package com.agostinelli.gestionale.movimenti.importlayer.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Struttura dati normalizzata dopo il parsing grezzo, prima del mapping di dominio.
 * I campi finanziari (conto, metodo) sono valorizzati best-effort dal normalizzatore;
 * coge / BU / fornitore / evento vengono risolti dal MappingEngine.
 */
public record RawMovimento(
        int riga,
        String fonte,                 // IMPORT_BILLY | IMPORT_BANCA

        // Dati base normalizzati
        LocalDate dataMovimento,      // da colonna DATA (tutti i formati)
        LocalDate dataCompetenza,     // solo Stripe (estratta da descrizione); null = uguale dataMovimento
        BigDecimal importo,           // SEMPRE > 0 (abs value)
        String tipo,                  // ENTRATA | USCITA
        String descrizione,           // uppercase, trim, no caratteri malevoli

        // Finanziario (best-effort dal normalizzatore; null se non determinabile)
        Short contoBancarioId,        // ID fisso: 1=BPM, 2=CA, 3=Cassa, 4=Satispay, 5=Stripe
        String metodoPagamentoCodice, // es. "POS_CA_NEXI" -> lookup ID nel MappingEngine
        BigDecimal importoCommissione,// 0 se non applicabile
        BigDecimal aliquotaIva,       // es. 0.10 o 0.04; null se esente

        // Classificazione (valorizzata dal mapping, non dal parser)
        String riferimentoEsterno,    // per idempotenza
        String girosalto,             // "GIROCONTO_SKIP" se da saltare, null altrimenti

        // Colonne raw Billy per il mapping engine
        BigDecimal billyAgriturismo,  // null per BPM/CA
        BigDecimal billyAltro,        // null per BPM/CA
        BigDecimal billyCarne10,      // null per BPM/CA
        BigDecimal billyOrtofrutta4,  // null per BPM/CA

        // Normalizzazione testo + entità (ETL_CLASSIFICAZIONE_v2 §3)
        String descCompact,           // descrizione senza spazi (keyword spezzate dal word-wrap)
        String chiaveAggancio,        // colonna CHIAVE grezza (numeroMovBanca/importo): dedup cross-sorgente
        EntitaEstratte entita,        // IBAN / ordinante / beneficiario / codice Stripe (best-effort)

        RawRow rawOriginale,          // conservare per errori_dettaglio / raw_data ambiguità

        // ── Riconciliazione POS (import congiunto, REFACTOR-IMPORT-CONGIUNTO §FASE1) ──
        // Solo per le righe banca di incasso POS; null altrimenti. Servono al
        // RiconciliazioneService per agganciare l'accredito banca allo scontrino Billy.
        LocalDate dataIncassoPos,     // data reale "DEL gg/mm/aa" estratta dalla descrizione (≠ data contabile)
        String circuitoPos            // "NUMIA" (→BPM) | "NEXI" (→CA); null se non POS
) {}
