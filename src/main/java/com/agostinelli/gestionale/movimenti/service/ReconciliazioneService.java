package com.agostinelli.gestionale.movimenti.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.domain.Movimento;
import com.agostinelli.gestionale.movimenti.dto.MovimentoDTO;
import com.agostinelli.gestionale.movimenti.mapper.MovimentoMapper;
import com.agostinelli.gestionale.movimenti.repository.MovimentiRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ReconciliazioneService {

    @Inject MovimentiRepository repo;
    @Inject MovimentoMapper mapper;
    @Inject EntityManager em;

    public List<MovimentoDTO> getMovimentiNonRiconciliati() {
        return repo.findNonRiconciliatiDaBanca()
                .stream().map(mapper::toDTO).toList();
    }

    @Transactional
    public void segnaRiconciliato(UUID id, String note) {
        Movimento m = repo.findByIdOptional(id)
                .orElseThrow(() -> new ApiException(Response.Status.NOT_FOUND, "NOT_FOUND",
                        "Movimento non trovato: " + id));
        if ("ANNULLATO".equals(m.stato)) {
            throw new ApiException(Response.Status.CONFLICT, "MOVIMENTO_ANNULLATO",
                    "Non è possibile riconciliare un movimento annullato");
        }
        m.stato = "RICONCILIATO";
        if (note != null && !note.isBlank()) {
            m.note = note;
        }
    }

    /**
     * Match automatico: lato DB con una JOIN per evitare O(n²) in memoria.
     * Trova coppie (IMPORT_BILLY, IMPORT_BANCA) con stesso importo e data ±2 giorni.
     * Ritorna il numero di coppie riconciliate.
     */
    @Transactional
    public int matchAutomatico() {
        @SuppressWarnings("unchecked")
        List<Object[]> coppie = em.createNativeQuery(
                "SELECT CAST(mb.id AS text), CAST(mk.id AS text) " +
                "FROM movimenti mb " +
                "JOIN movimenti mk ON mk.importo_lordo = mb.importo_lordo " +
                "    AND mk.data_movimento BETWEEN mb.data_movimento - INTERVAL '2 days' " +
                "                               AND mb.data_movimento + INTERVAL '2 days' " +
                "WHERE mb.fonte = 'IMPORT_BILLY'  AND mb.stato = 'REGISTRATO' " +
                "  AND mk.fonte = 'IMPORT_BANCA'  AND mk.stato = 'REGISTRATO'")
                .getResultList();

        if (coppie.isEmpty()) return 0;

        List<UUID> billyIds = coppie.stream().map(r -> UUID.fromString((String) r[0])).toList();
        List<UUID> bancaIds = coppie.stream().map(r -> UUID.fromString((String) r[1])).toList();

        em.createQuery("UPDATE Movimento m SET m.stato = 'RICONCILIATO' WHERE m.id IN :ids")
                .setParameter("ids", billyIds)
                .executeUpdate();
        em.createQuery("UPDATE Movimento m SET m.stato = 'RICONCILIATO' WHERE m.id IN :ids")
                .setParameter("ids", bancaIds)
                .executeUpdate();

        return coppie.size();
    }
}
