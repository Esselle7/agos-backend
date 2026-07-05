package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Voce di preventivo/consuntivo di un evento (lato ricavo verso cliente).
 *
 * INVARIANTE: una voce NON genera MAI un movimento — è pura pianificazione.
 * Esempio-limite: 20 bottiglie comprate in blocco sono UN movimento; spalmarle
 * su più eventi come voci non deve duplicarle in contabilità.
 *
 * {@code importoPreventivo} è quotato al cliente; {@code importoConsuntivo}
 * (compilabile solo dalla data evento in poi) è l'effettivo. Il totale
 * preventivato dell'evento = Σ importoPreventivo, ricalcolato dal service.
 *
 * {@code origine = COSTO_DIRETTO} + {@code costoDirettoId} valorizzato: voce di
 * ricarico generata da un costo diretto (es. DJ pagato 200, addebitato 250);
 * cade in cascata quando il costo diretto viene rimosso.
 */
@Entity
@Table(name = "evento_voce")
public class EventoVoce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "evento_id", nullable = false, columnDefinition = "uuid")
    public UUID eventoId;

    /** Riferimento al catalogo per le voci MANUALE selezionate/registrate; null per COSTO_DIRETTO. */
    @Column(name = "catalogo_id")
    public Long catalogoId;

    /** Snapshot della label per il display (sempre valorizzato). */
    @Column(name = "label", nullable = false, length = 120)
    public String label;

    /** Prezzo unitario (dal listino o inserito). importo = quantità × prezzo. */
    @Column(name = "prezzo_unitario", nullable = false, precision = 15, scale = 2)
    public BigDecimal prezzoUnitario = BigDecimal.ZERO;

    /** Quantità preventivata. */
    @Column(name = "quantita_preventivo", nullable = false, precision = 12, scale = 2)
    public BigDecimal quantitaPreventivo = BigDecimal.ONE;

    /** Quantità consuntivata; null finché non compilata (solo da data evento). */
    @Column(name = "quantita_consuntivo", precision = 12, scale = 2)
    public BigDecimal quantitaConsuntivo;

    /** Derivato = quantitaPreventivo × prezzoUnitario. */
    @Column(name = "importo_preventivo", nullable = false, precision = 15, scale = 2)
    public BigDecimal importoPreventivo = BigDecimal.ZERO;

    /** Derivato = quantitaConsuntivo × prezzoUnitario; null se q. consuntivo null. */
    @Column(name = "importo_consuntivo", precision = 15, scale = 2)
    public BigDecimal importoConsuntivo;

    /** MANUALE | COSTO_DIRETTO. */
    @Column(name = "origine", nullable = false, length = 20)
    public String origine = "MANUALE";

    @Column(name = "costo_diretto_id")
    public Long costoDirettoId;

    @Column(name = "note", length = 500)
    public String note;

    @Column(name = "created_by", columnDefinition = "uuid")
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
