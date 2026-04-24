package com.agostinelli.gestionale.eventi.mapper;

import com.agostinelli.gestionale.eventi.domain.Evento;
import com.agostinelli.gestionale.eventi.dto.EventoCreateRequest;
import com.agostinelli.gestionale.eventi.dto.EventoUpdateRequest;
import org.mapstruct.*;

@Mapper(componentModel = "cdi")
public interface EventoMapper {

    @Mapping(target = "id",                      ignore = true)
    @Mapping(target = "createdAt",               ignore = true)
    @Mapping(target = "updatedAt",               ignore = true)
    @Mapping(target = "createdBy",               ignore = true)
    @Mapping(target = "stato",                   constant = "PREVENTIVO")
    @Mapping(target = "importoIncassato",        ignore = true)
    @Mapping(target = "caparreIncassate",        ignore = true)
    @Mapping(target = "costiDirettiImputati",    ignore = true)
    @Mapping(target = "noteAnnullamento",        ignore = true)
    Evento fromRequest(EventoCreateRequest req);

    /**
     * PATCH semantics: aggiorna solo i campi non-null della request.
     * stato e noteAnnullamento sono gestiti dalla macchina a stati nel service.
     */
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id",                      ignore = true)
    @Mapping(target = "createdAt",               ignore = true)
    @Mapping(target = "createdBy",               ignore = true)
    @Mapping(target = "updatedAt",               ignore = true)
    @Mapping(target = "importoIncassato",        ignore = true)
    @Mapping(target = "caparreIncassate",        ignore = true)
    @Mapping(target = "costiDirettiImputati",    ignore = true)
    @Mapping(target = "stato",                   ignore = true)
    @Mapping(target = "noteAnnullamento",        ignore = true)
    void updateFromRequest(@MappingTarget Evento e, EventoUpdateRequest req);
}
