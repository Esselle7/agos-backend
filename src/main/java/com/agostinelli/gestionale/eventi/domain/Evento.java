package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entità centrale del modulo BU2 – Cerimonie ed Eventi.
 *
 * I campi importoIncassato, caparreIncassate e costiDirettiImputati sono
 * aggiornati AUTOMATICAMENTE dal trigger DB trg_z_aggiorna_totali_evento
 * (fn_ricalcola_evento) ogni volta che un Movimento collegato viene
 * inserito, modificato o eliminato.
 * Non aggiornare questi campi da Java per evitare race condition e doppio-scrittura.
 */
@Entity
@Table(name = "eventi")
public class Evento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "nome", nullable = false, length = 255)
    public String nome;

    /** Codice lookup: MATRIMONIO, BANCHETTO_PRIVATO, AZIENDALE, RISTORAZIONE_GRUPPO, ALTRO. */
    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    @Column(name = "data_evento", nullable = false)
    public LocalDate dataEvento;

    @Column(name = "data_preventivo")
    public LocalDate dataPreventivo;

    @Column(name = "importo_totale_preventivato", precision = 15, scale = 2)
    public BigDecimal importoTotalePreviventivato;

    /**
     * Totale incassato (ENTRATA) dai movimenti collegati.
     * Aggiornato automaticamente dal trigger DB – non modificare da Java.
     */
    @Column(name = "importo_incassato", nullable = false, precision = 15, scale = 2)
    public BigDecimal importoIncassato;

    /**
     * Somma delle caparre incassate (tipo_evento_movimento='CAPARRA').
     * Aggiornato automaticamente dal trigger DB – non modificare da Java.
     */
    @Column(name = "caparre_incassate", nullable = false, precision = 15, scale = 2)
    public BigDecimal caparreIncassate;

    /**
     * Costi diretti imputati tramite movimenti USCITA collegati all'evento.
     * Aggiornato automaticamente dal trigger DB – non modificare da Java.
     * Per il calcolo dei costi reali usare il metodo calcolaCostiReali(eventoId) nel service.
     */
    @Column(name = "costi_diretti_imputati", nullable = false, precision = 15, scale = 2)
    public BigDecimal costiDirettiImputati;

    /** Codice lookup: PREVENTIVATO, CONFERMATO, SALDATO, ANNULLATO. */
    @Column(name = "stato", nullable = false, length = 50)
    public String stato;

    @Column(name = "business_unit_id")
    public Short businessUnitId;

    @Column(name = "contatto_nome", length = 255)
    public String contattoNome;

    @Column(name = "contatto_telefono", length = 20)
    public String contattoTelefono;

    @Column(name = "contatto_email", length = 255)
    public String contattoEmail;

    @Column(name = "n_ospiti")
    public Integer nOspiti;

    /** Numero totale partecipanti — obbligatorio in API, default 0 in DB per migrazione. */
    @Column(name = "numero_totale_partecipanti", nullable = false)
    public int numeroTotalePartecipanti;

    @Column(name = "numero_bambini")
    public Integer numeroBambini;

    @Column(name = "note", columnDefinition = "text")
    public String note;

    /**
     * Obbligatoria in business logic quando stato=ANNULLATO.
     * Il vincolo di presenza è gestito a livello applicativo (non nel DB).
     */
    @Column(name = "note_annullamento", columnDefinition = "text")
    public String noteAnnullamento;

    @Column(name = "created_by", columnDefinition = "uuid", updatable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (stato == null) stato = "PREVENTIVATO";
        if (importoIncassato == null) importoIncassato = BigDecimal.ZERO;
        if (caparreIncassate == null) caparreIncassate = BigDecimal.ZERO;
        if (costiDirettiImputati == null) costiDirettiImputati = BigDecimal.ZERO;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
