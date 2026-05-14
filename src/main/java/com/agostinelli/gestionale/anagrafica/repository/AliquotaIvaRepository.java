package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.AliquotaIva;
import com.agostinelli.gestionale.anagrafica.dto.AliquotaIvaDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@ApplicationScoped
public class AliquotaIvaRepository implements PanacheRepositoryBase<AliquotaIva, Integer> {

    @Inject
    EntityManager em;

    public List<AliquotaIvaDTO> findAllAttive() {
        return em.createQuery(
                "SELECT a FROM AliquotaIva a WHERE a.isActive = true ORDER BY a.aliquota",
                AliquotaIva.class)
                .getResultList()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    private AliquotaIvaDTO toDTO(AliquotaIva a) {
        // DB stores percentage (10.0 for 10%); return decimal multiplier (0.10) for direct use in POST /api/movimenti
        BigDecimal decimale = a.aliquota.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return new AliquotaIvaDTO(a.id, decimale, a.descrizione);
    }
}
