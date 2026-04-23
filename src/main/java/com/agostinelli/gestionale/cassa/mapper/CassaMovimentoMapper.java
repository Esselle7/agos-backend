package com.agostinelli.gestionale.cassa.mapper;

import com.agostinelli.gestionale.cassa.domain.CassaMovimento;
import com.agostinelli.gestionale.cassa.dto.CassaMovimentoDTO;
import com.agostinelli.gestionale.cassa.dto.CreateCassaMovimentoRequest;
import org.mapstruct.*;

@Mapper(componentModel = "cdi")
public interface CassaMovimentoMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "stato", constant = "REGISTRATO")
    CassaMovimento fromRequest(CreateCassaMovimentoRequest req);

    CassaMovimentoDTO toDTO(CassaMovimento m);
}
