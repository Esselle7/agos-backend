package com.agostinelli.gestionale.anagrafica.mapper;

import com.agostinelli.gestionale.anagrafica.domain.Fornitore;
import com.agostinelli.gestionale.anagrafica.domain.FornitoreAlias;
import com.agostinelli.gestionale.anagrafica.dto.AliasDTO;
import com.agostinelli.gestionale.anagrafica.dto.CreateFornitoreRequest;
import com.agostinelli.gestionale.anagrafica.dto.FornitoreDTO;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "cdi")
public interface FornitoreMapper {

    AliasDTO aliasToDTO(FornitoreAlias alias);

    default FornitoreDTO toDTO(Fornitore f, List<FornitoreAlias> aliases) {
        return new FornitoreDTO(
                f.id,
                f.ragioneSociale,
                f.alias,
                f.piva,
                f.codiceSdi,
                f.cogeDefaultId,
                f.buDefaultId,
                f.note,
                aliases.stream().map(this::aliasToDTO).toList()
        );
    }

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    Fornitore fromRequest(CreateFornitoreRequest req);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateFromRequest(@MappingTarget Fornitore fornitore, CreateFornitoreRequest req);
}
