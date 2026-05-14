package com.agostinelli.gestionale.anagrafica.mapper;

import com.agostinelli.gestionale.anagrafica.domain.BusinessUnit;
import com.agostinelli.gestionale.anagrafica.dto.BusinessUnitDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "cdi")
public interface BusinessUnitMapper {

    @Mapping(target = "colore", source = "coloreHex")
    BusinessUnitDTO toDTO(BusinessUnit bu);

    List<BusinessUnitDTO> toDTOList(List<BusinessUnit> list);
}
