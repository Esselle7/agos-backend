package com.agostinelli.gestionale.movimenti.mapper;

import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoUpdateRequest;
import org.mapstruct.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Mapper(componentModel = "cdi")
public interface MovimentoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "importoImponibile", ignore = true)
    @Mapping(target = "importoIva", ignore = true)
    @Mapping(target = "importoCommissione", ignore = true)
    @Mapping(target = "stato", constant = "REGISTRATO")
    @Mapping(target = "fonteImportazioneId", ignore = true)
    @Mapping(target = "fonte", expression = "java(req.fonte() != null ? req.fonte() : \"MANUALE\")")
    // dataFinanziaria, dataLiquidita, dataCompetenza mappati per nome automaticamente
    Movimento fromRequest(MovimentoCreateRequest req);

    @Mapping(source = "importo", target = "importo")
    @Mapping(target = "giorniAllaScadenza", expression = "java(giorniAllaScadenza(m))")
    MovimentoDTO toDTO(Movimento m);

    /**
     * Campi derivati dal ritardo sui movimenti DA_LIQUIDARE (Feature 1).
     *
     * Restituisce i giorni che mancano alla scadenza (dataLiquidita - oggi):
     *  > 0  → scade tra N giorni (non ancora in ritardo);
     *  == 0 → scade oggi;
     *  < 0  → in ritardo di |N| giorni (USCITA: sei in ritardo sul pagamento;
     *         ENTRATA: qualcuno è in ritardo nel pagarti);
     *  null → non applicabile (movimento già liquidato/annullato, o senza scadenza).
     *
     * Le rate dei piani di spesa ricorrente non finiscono qui: lo scheduler le
     * converte in movimenti REGISTRATI alla scadenza (vedi RecurringExpenseScheduler),
     * quindi dataFinanziaria è sempre valorizzata e il metodo ritorna null.
     */
    default Long giorniAllaScadenza(Movimento m) {
        if (m == null || !"DA_LIQUIDARE".equals(m.stato) || m.dataLiquidita == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), m.dataLiquidita);
    }

    /**
     * Full-overwrite semantics: l'endpoint è PUT, il client invia sempre lo stato
     * completo del form. Un campo null nel request azzera il campo sull'entity
     * (così l'utente può rimuovere fornitore, evento, categoria, note, ecc.).
     * I campi strutturali e quelli derivati restano protetti via ignore:
     * importoCommissione/IVA/imponibile e stato sono ricalcolati dal service;
     * allegatoPath non è gestito da questo form e non va azzerato a ogni modifica.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "importoImponibile", ignore = true)
    @Mapping(target = "importoIva", ignore = true)
    @Mapping(target = "importoCommissione", ignore = true)
    @Mapping(target = "stato", ignore = true)
    @Mapping(target = "fonteImportazioneId", ignore = true)
    @Mapping(target = "allegatoPath", ignore = true)
    void updateFromRequest(@MappingTarget Movimento m, MovimentoUpdateRequest req);
}
