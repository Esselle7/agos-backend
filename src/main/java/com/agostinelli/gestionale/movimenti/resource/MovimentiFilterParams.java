package com.agostinelli.gestionale.movimenti.resource;

import com.agostinelli.gestionale.movimenti.dto.MovimentiFilterQuery;
import jakarta.ws.rs.QueryParam;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Binding dei parametri di filtro della lista movimenti, condiviso via {@code @BeanParam}
 * fra {@code GET /api/movimenti} e {@code GET /api/movimenti/sommario}: i due endpoint devono
 * accettare gli stessi criteri, e ripeterli a mano su due firme li farebbe divergere.
 *
 * Ogni dimensione è un parametro RIPETIBILE (?stato=REGISTRATO&stato=DA_LIQUIDARE).
 */
public class MovimentiFilterParams {

    @QueryParam("tipo")              public List<String> tipo;
    @QueryParam("stato")             public List<String> stato;
    @QueryParam("fonte")             public List<String> fonte;
    /** Id conto bancario; 0 = «senza banca» (conto_bancario_id IS NULL). */
    @QueryParam("contoId")           public List<Short> contoId;
    @QueryParam("cogeId")            public List<Integer> cogeId;
    @QueryParam("buId")              public List<Short> buId;
    @QueryParam("categoriaId")       public List<Long> categoriaId;
    @QueryParam("metodoPagamentoId") public List<Integer> metodoPagamentoId;
    @QueryParam("fornitoreId")       public List<UUID> fornitoreId;
    @QueryParam("eventoId")          public List<UUID> eventoId;

    @QueryParam("importoMin")        public BigDecimal importoMin;
    @QueryParam("importoMax")        public BigDecimal importoMax;

    /** MOVIMENTO (default) | FINANZIARIA | LIQUIDITA — a quale data applicare from/to. */
    @QueryParam("dateField")         public String dateField;
    @QueryParam("from")              public LocalDate from;
    @QueryParam("to")                public LocalDate to;

    @QueryParam("search")            public String search;

    /** Converte al criterio di dominio, che valida i range e risolve l'enum data. */
    public MovimentiFilterQuery toQuery() {
        return new MovimentiFilterQuery(
                tipo, stato, fonte, contoId, cogeId, buId, categoriaId, metodoPagamentoId,
                fornitoreId, eventoId, importoMin, importoMax,
                MovimentiFilterQuery.DateField.parse(dateField), from, to, search);
    }
}
