package com.agostinelli.gestionale.movimenti.dto;

import java.util.UUID;

/**
 * Tutto ciò che serve alla console Import per ridisegnarsi dopo un'azione, in UNA risposta.
 *
 * <p><b>Perché esiste.</b> Fino al 13/08/2026 lo shell chiedeva gli stessi numeri con <b>8</b>
 * chiamate HTTP ({@code ImportCountsService.reload}: 6 liste con {@code size=1} usate solo per
 * leggerne il {@code totalElements}, più {@code /import/history} solo per sapere l'id dell'ultimo
 * import, più il contatore). Ogni conferma del wizard le rifà tutte. Non è un problema di
 * millisecondi — a 153 movimenti le query costano nulla — è un problema di <b>round-trip</b>:
 * {@code RateLimitFilter} concede 100 richieste/minuto per utente (default di produzione), e la
 * 429 che ne segue è già costata un bug vero l'11/08/2026 (una {@code /import/history} rifiutata
 * faceva scrivere «Nessun import da rifinire» con 127 movimenti a DB).
 *
 * <p>Il KPI resta un endpoint a sé: {@code getKpi()} è {@code @CacheResult}, e chiamarlo dall'interno
 * dello stesso bean scavalcherebbe l'interceptor CDI — la cache smetterebbe di esistere senza che
 * nulla lo dica. Meglio 3 chiamate oneste che 2 con una cache morta.
 *
 * <p>I conteggi sono gli STESSI delle liste che sostituiscono, con le stesse clausole
 * ({@code stato = 'DA_RICONCILIARE'} sulle tre code, {@code 'DA_VEDERE'} sugli scartati,
 * transitorio 39/49.99.999 non annullato per «da catalogare»): l'endpoint aggrega, non ridefinisce.
 * «Duplicati» resta fuori di proposito — il suo conteggio è O(n²) e si calcola solo aprendo la
 * sezione (era già così).
 */
public record ImportBadgeDTO(
        long catalogare,
        long ricorrenti,
        long eventi,
        long matchingDifferiti,
        long scartati,
        /** L'import a cui si riferiscono contatore e registro: l'ultimo caricato. null se non ce ne sono. */
        UUID ultimoImportId
) {}
