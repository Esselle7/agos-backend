package com.agostinelli.gestionale.eventi.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Collegamento molti-a-molti tra un evento e un record della tabella personale.
 *
 * REGOLA: personaleId è sempre un riferimento a personale.id, MAI a users.id.
 * La tabella personale è la fonte di verità per le persone;
 * un record personale può non avere un utente associato.
 */
@Entity
@Table(name = "evento_partecipanti")
public class EventoPartecipante {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "evento_id", nullable = false, columnDefinition = "uuid")
    public UUID eventoId;

    /** FK → personale.id (UUID). Non usare users.id per i partecipanti. */
    @Column(name = "personale_id", nullable = false, columnDefinition = "uuid")
    public UUID personaleId;

    @Column(name = "ruolo", length = 100)
    public String ruolo;

    /** Costo del partecipante per l'evento. Per i dipendenti ORARIA = ore * pagaOraria. */
    @Column(name = "costo", precision = 15, scale = 2)
    public BigDecimal costo;

    /** Ore allocate al dipendente per questo evento (solo retribuzione ORARIA). */
    @Column(name = "ore", precision = 8, scale = 2)
    public BigDecimal ore;

    /** Riferimento al movimento USCITA generato per il costo orario (PK composita movimenti). */
    @Column(name = "movimento_id", columnDefinition = "uuid")
    public UUID movimentoId;

    @Column(name = "movimento_data")
    public LocalDate movimentoData;

    @Column(name = "note", length = 500)
    public String note;
}
