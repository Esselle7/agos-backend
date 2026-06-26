package com.agostinelli.gestionale.movimenti.importlayer.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Quadratura di periodo Billy ↔ banche (PROMPT-RICONCILIAZIONE-PERIODO §5): pannello
 * <b>informativo</b> (non un cancello). I ricavi/BU sono comunque contabilizzati da Billy;
 * qui si mostra il confronto Σ Billy elettronico vs Σ POS banca e si scompone il Δ nelle sue
 * cause note (coda testa esclusa, coda fondo in attesa, residuo core).
 *
 * <p>Tutti gli importi sono in euro (BigDecimal, scala 2). La scomposizione vale:
 * {@code posBancaTotale = posBancaCore + codaTesta} e
 * {@code residuoCore = posBancaCore − billyContabilizzato} (può essere ≠ 0: è reale, va mostrato).
 *
 * @param anno                  anno del periodo (dedotto dagli scontrini Billy)
 * @param billyElettronicoNonAgri Σ scontrini Billy elettronici non-agriturismo (tutto il file)
 * @param billyContabilizzato   Σ ricavi effettivamente contabilizzati (= non-agri − coda fondo)
 * @param posBancaTotale        Σ righe POS banca (BPM+CA, tutte)
 * @param posBancaCore          Σ righe POS banca del periodo (coda testa esclusa)
 * @param sigmaBpm              Σ POS BPM core (target ripartizione)
 * @param sigmaCa               Σ POS CA core (target ripartizione)
 * @param assegnatoBpm          Σ ricavi Billy assegnati al conto BPM dalla ripartizione
 * @param assegnatoCa           Σ ricavi Billy assegnati al conto CA dalla ripartizione
 * @param codaTesta             POS con DEL di anno precedente → esclusi (non contabilizzati)
 * @param codaFondo             vendite Billy dopo l'ultima DEL → in attesa di accredito (non contab.)
 * @param residuoCore           Δ core residuo (agriturismo-a-POS, Satispay netto/lordo, storni)
 * @param maxDelBanca           ultima data "DEL" presente negli estratti banca (soglia coda fondo)
 * @param note                  cause leggibili del residuo (per il pannello)
 */
public record QuadraturaPeriodo(
        int anno,
        BigDecimal billyElettronicoNonAgri,
        BigDecimal billyContabilizzato,
        BigDecimal posBancaTotale,
        BigDecimal posBancaCore,
        BigDecimal sigmaBpm,
        BigDecimal sigmaCa,
        BigDecimal assegnatoBpm,
        BigDecimal assegnatoCa,
        BigDecimal codaTesta,
        BigDecimal codaFondo,
        BigDecimal residuoCore,
        LocalDate maxDelBanca,
        List<String> note
) {}
