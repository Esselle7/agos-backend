package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.ContoBancario;
import com.agostinelli.gestionale.anagrafica.dto.ContoBancarioDTO;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.math.BigDecimal;
import java.util.List;

@ApplicationScoped
public class ContiBancariRepository implements PanacheRepositoryBase<ContoBancario, Short> {

    @Inject
    EntityManager em;

    // Join diretto a mv_saldi_conti per includere il saldo calcolato in un'unica query.
    // COALESCE su mv: se la MV non è ancora refreshata cade sul saldo_iniziale.
    @SuppressWarnings("unchecked")
    public List<ContoBancarioDTO> findAllAttiviConSaldo() {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT cb.id,
                       cb.nome,
                       cb.tipo,
                       cb.iban,
                       COALESCE(ms.saldo_calcolato, cb.saldo_iniziale) AS saldo_calcolato
                FROM conti_bancari cb
                LEFT JOIN mv_saldi_conti ms ON ms.conto_id = cb.id
                WHERE cb.is_active = true
                ORDER BY cb.id
                """).getResultList();

        return rows.stream()
                .map(r -> new ContoBancarioDTO(
                        ((Number) r[0]).shortValue(),
                        (String) r[1],
                        (String) r[2],
                        (String) r[3],
                        r[4] != null ? new BigDecimal(r[4].toString()) : BigDecimal.ZERO
                ))
                .toList();
    }
}
