package com.agostinelli.gestionale.movimenti.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Il contatore dell'import (SPEC import-v2 §5/§6, R7–R10): quanto è stato estratto dalle banche,
 * e dove si trova adesso — al centesimo e <b>per direzione</b>.
 *
 * <p>Universo: le righe dei <b>due file banca</b> (BPM + CA). Tutto ciò che non è una riga banca
 * (contanti Billy, coda fondo, scontrini agriturismo) sta in {@link #fuoriUniverso()}: si mostra
 * dichiarato, non si somma mai.
 *
 * <p>{@link #lette()} è misurato all'import sulle righe normalizzate dei file, non derivato dalla
 * somma dei bucket: è l'unico modo perché {@link #quadra()} possa essere falso.
 */
public record ContatoreImportDTO(
        UUID importLogId,

        /** Universo misurato: le righe dei due file banca. Vuoto per gli import anteriori a V32. */
        Bucket lette,
        /**
         * false = questo import è stato caricato PRIMA che l'universo si misurasse (V32): il
         * termine sinistro dell'invariante non esiste, quindi {@link #quadra()} non significa
         * nulla e non va mostrato come un buco. Non è un errore, è una misura che manca.
         */
        boolean universoMisurato,

        /** Movimento creato su un conto CoGe definitivo (l'import diretto o una coda risolta). */
        Bucket aLibro,
        /** Aspetta una decisione: coda aperta, o movimento ancora sul transitorio 39/49.99.999. */
        Bucket daCatalogare,
        /** Denaro bancario vero che l'import ha lasciato fuori e nessuno ha ancora guardato. */
        Bucket fuoriDaiConti,
        /** Chiusa a mano senza toccare i saldi, con motivo scritto (R9). */
        Bucket esclusi,
        /** Riga già importata da un import precedente (R8). */
        Bucket duplicate,
        /** Trasferimento fra due conti propri: né ricavo né costo. */
        Bucket partiteDiGiro,

        /**
         * true se l'invariante di §5 chiude al centesimo su ENTRAMBE le direzioni.
         * Ha senso solo con {@link #universoMisurato()} true.
         */
        boolean quadra,
        BigDecimal scartoEntrate,
        BigDecimal scartoUscite,

        /** Fuori dall'universo, dichiarati e MAI sommati (§5). */
        List<VoceFuoriUniverso> fuoriUniverso) {

    /** Un bucket, per direzione. Gli importi sono sempre positivi: la direzione è la colonna. */
    public record Bucket(long righe, BigDecimal entrate, BigDecimal uscite) {
        public static final Bucket ZERO =
                new Bucket(0, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
    }

    public record VoceFuoriUniverso(String etichetta, long righe, BigDecimal importo, String perche) {}
}
