package com.agostinelli.gestionale.movimenti.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Entità core del modulo finanziario. La tabella è partizionata per anno su
 * data_movimento – per questo la PK fisica è (id, data_movimento), ma il mapping
 * JPA usa solo id (Hibernate non genera DDL, generation=none).
 *
 * importo corrisponde alla colonna importo_lordo nel DB, cioè l'importo effettivo
 * del movimento (netto commissioni quando applicabile).
 *
 * I totali sull'entità Evento (importo_incassato, caparre, costi_diretti) vengono
 * aggiornati AUTOMATICAMENTE dal trigger DB trg_z_aggiorna_totali_evento.
 * Non aggiornare l'evento da Java per evitare race condition e doppio scrittura.
 */
@Entity
@Table(name = "movimenti")
public class Movimento {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", columnDefinition = "uuid", updatable = false)
    public UUID id;

    @Column(name = "tipo", nullable = false, length = 50)
    public String tipo;

    /** Importo effettivo del movimento – colonna DB importo_lordo. */
    @Column(name = "importo_lordo", nullable = false, precision = 15, scale = 2)
    public BigDecimal importo;

    /** Base imponibile IVA (opzionale). */
    @Column(name = "importo_imponibile", precision = 15, scale = 2)
    public BigDecimal importoImponibile;

    @Column(name = "importo_iva", precision = 15, scale = 2)
    public BigDecimal importoIva;

    @Column(name = "importo_commissione", precision = 15, scale = 2)
    public BigDecimal importoCommissione;

    /** Data di competenza economica (impatto P&L / EBITDA). Partition key, sempre valorizzata. */
    @Column(name = "data_movimento", nullable = false)
    public LocalDate dataMovimento;

    /** Alias economico per mv_conto_economico_mensile. Auto-impostato = dataMovimento in @PrePersist. */
    @Column(name = "data_competenza")
    public LocalDate dataCompetenza;

    /** Data di liquidazione effettiva (quando i soldi entrano/escono dal conto).
     *  null = DA_LIQUIDARE; valorizzata = REGISTRATO (liquidato). */
    @Column(name = "data_finanziaria")
    public LocalDate dataFinanziaria;

    /** Scadenza finanziaria attesa. Obbligatoria quando dataFinanziaria è null.
     *  Auto-impostata = dataFinanziaria quando il movimento viene liquidato. */
    @Column(name = "data_liquidita")
    public LocalDate dataLiquidita;

    @Column(name = "conto_bancario_id")
    public Short contoBancarioId;

    @Column(name = "metodo_pagamento_id")
    public Integer metodoPagamentoId;

    @Column(name = "aliquota_iva_id")
    public Integer aliquotaIvaId;

    @Column(name = "conto_coge_id", nullable = false)
    public Integer contoCoge;

    @Column(name = "centro_di_costo_id")
    public Integer centroDiCostoId;

    @Column(name = "business_unit_id", nullable = false)
    public Short businessUnitId;

    @Column(name = "fornitore_id", columnDefinition = "uuid")
    public UUID fornitoreId;

    @Column(name = "evento_id", columnDefinition = "uuid")
    public UUID eventoId;

    @Column(name = "tipo_evento_movimento", length = 50)
    public String tipoEventoMovimento;

    @Column(name = "cespite_id", columnDefinition = "uuid")
    public UUID cespiteId;

    /** Categoria operativa (albero UI) – aggiunta tramite V11. */
    @Column(name = "categoria_id")
    public Long categoriaId;

    @Column(name = "descrizione", length = 500)
    public String descrizione;

    @Column(name = "note", columnDefinition = "text")
    public String note;

    @Column(name = "stato", nullable = false, length = 50)
    public String stato;

    @Column(name = "fonte_importazione_id", columnDefinition = "uuid")
    public UUID fonteImportazioneId;

    @Column(name = "fonte", nullable = false, length = 50)
    public String fonte;

    @Column(name = "riferimento_esterno", length = 255)
    public String riferimentoEsterno;

    @Column(name = "allegato_path", length = 500)
    public String allegatoPath;

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid", updatable = false)
    public UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (stato == null) stato = "REGISTRATO";
        if (fonte == null) fonte = "MANUALE";
        if (importoCommissione == null) importoCommissione = BigDecimal.ZERO;
        // Garantisce che data_competenza sia sempre valorizzata per mv_conto_economico_mensile
        if (dataCompetenza == null) dataCompetenza = dataMovimento;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
