package com.agostinelli.gestionale.movimenti.mapper;

import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.MovimentoCreateRequest;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.dto.MovimentoUpdateRequest;
import org.mapstruct.*;

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
    MovimentoDTO toDTO(Movimento m);

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
