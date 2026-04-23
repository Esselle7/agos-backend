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
    Movimento fromRequest(MovimentoCreateRequest req);

    @Mapping(source = "importo", target = "importo")
    MovimentoDTO toDTO(Movimento m);

    /**
     * PATCH semantics: aggiorna solo i campi non-null.
     * importoCommissione e importoIva vengono ricalcolati dal service, non qui.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "importoImponibile", ignore = true)
    @Mapping(target = "importoIva", ignore = true)
    @Mapping(target = "importoCommissione", ignore = true)
    @Mapping(target = "stato", ignore = true)
    @Mapping(target = "fonteImportazioneId", ignore = true)
    void updateFromRequest(@MappingTarget Movimento m, MovimentoUpdateRequest req);
}
