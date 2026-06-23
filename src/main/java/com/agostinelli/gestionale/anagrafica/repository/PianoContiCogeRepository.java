package com.agostinelli.gestionale.anagrafica.repository;

import com.agostinelli.gestionale.anagrafica.domain.PianoContiCoge;
import com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeDTO;
import com.agostinelli.gestionale.anagrafica.dto.PianoContiCogeUpsertRequest;
import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;

import java.util.List;

@ApplicationScoped
public class PianoContiCogeRepository implements PanacheRepositoryBase<PianoContiCoge, Integer> {

    @Inject
    EntityManager em;

    public List<PianoContiCogeDTO> findAllAttivi() {
        return em.createQuery(
                "SELECT p FROM PianoContiCoge p WHERE p.isActive = true ORDER BY p.codice",
                PianoContiCoge.class)
                .getResultList()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    public List<PianoContiCogeDTO> findByTipo(String tipo) {
        return em.createQuery(
                "SELECT p FROM PianoContiCoge p WHERE p.isActive = true AND p.tipo = :tipo ORDER BY p.codice",
                PianoContiCoge.class)
                .setParameter("tipo", tipo)
                .getResultList()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    // ── Scrittura (admin: gestione piano dei conti) ───────────────────────────

    @Transactional
    @CacheInvalidateAll(cacheName = "piano-dei-conti")
    public PianoContiCogeDTO create(PianoContiCogeUpsertRequest req) {
        String codice = req.codice().trim();
        validaTipo(req.tipo());
        validaParent(req.parentId(), null);
        if (esisteCodice(codice, null)) {
            throw new ApiException(Response.Status.CONFLICT, "CODICE_DUPLICATO",
                    "Esiste già un conto con codice " + codice);
        }
        // ponytail: id = MAX+1 invece di nextval(sequence): il seed inserisce id espliciti senza
        // avanzare la sequence, quindi nextval partirebbe da valori già usati. MAX+1 è collision-safe
        // sotto @Transactional per un tool admin a bassissima concorrenza.
        // is_capex non è editabile da qui (resta al default false); il focus è codice/label/tipo.
        em.createNativeQuery(
                "INSERT INTO piano_dei_conti_coge (id, codice, descrizione, tipo, parent_id, is_active) " +
                "VALUES ((SELECT COALESCE(MAX(id),0)+1 FROM piano_dei_conti_coge), :codice, :descr, :tipo, :parent, true)")
                .setParameter("codice", codice)
                .setParameter("descr", req.descrizione().trim())
                .setParameter("tipo", req.tipo())
                .setParameter("parent", req.parentId())
                .executeUpdate();
        Integer id = ((Number) em.createNativeQuery(
                "SELECT id FROM piano_dei_conti_coge WHERE codice = :codice")
                .setParameter("codice", codice).getSingleResult()).intValue();
        return toDTO(id, codice, req.descrizione().trim(), req.tipo(), req.parentId());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "piano-dei-conti")
    public PianoContiCogeDTO update(Integer id, PianoContiCogeUpsertRequest req) {
        PianoContiCoge esistente = findById(id);
        if (esistente == null) {
            throw new ApiException(Response.Status.NOT_FOUND, "CONTO_NON_TROVATO",
                    "Conto COGE non trovato: " + id);
        }
        String nuovoCodice = req.codice().trim();
        validaTipo(req.tipo());
        validaParent(req.parentId(), id);
        if (esisteCodice(nuovoCodice, id)) {
            throw new ApiException(Response.Status.CONFLICT, "CODICE_DUPLICATO",
                    "Esiste già un altro conto con codice " + nuovoCodice);
        }
        String vecchioCodice = esistente.codice;

        // is_capex non è toccato: si preserva il valore esistente (non editabile da questo tool).
        em.createNativeQuery(
                "UPDATE piano_dei_conti_coge SET codice=:codice, descrizione=:descr, tipo=:tipo, " +
                "parent_id=:parent WHERE id=:id")
                .setParameter("codice", nuovoCodice)
                .setParameter("descr", req.descrizione().trim())
                .setParameter("tipo", req.tipo())
                .setParameter("parent", req.parentId())
                .setParameter("id", id)
                .executeUpdate();

        // Il codice è una chiave referenziata PER STRINGA: propagala dove serve per non orfanizzare
        // le regole di catalogazione. (La allowlist forecasting in ForecastBaselineService è in codice
        // Java: se rinomini un conto ricavo-cash 30.01.001/30.03.001/30.03.002 va aggiornata a mano.)
        if (!vecchioCodice.equals(nuovoCodice)) {
            for (String tabella : List.of("keyword_firma", "regole_classificazione")) {
                em.createNativeQuery("UPDATE " + tabella + " SET coge_codice=:nuovo WHERE coge_codice=:vecchio")
                        .setParameter("nuovo", nuovoCodice)
                        .setParameter("vecchio", vecchioCodice)
                        .executeUpdate();
            }
        }
        em.detach(esistente); // l'entità in cache è stale dopo l'UPDATE nativo
        return toDTO(id, nuovoCodice, req.descrizione().trim(), req.tipo(), req.parentId());
    }

    // ── Validazioni ───────────────────────────────────────────────────────────

    private boolean esisteCodice(String codice, Integer exceptId) {
        String sql = "SELECT count(*) FROM piano_dei_conti_coge WHERE codice = :codice"
                + (exceptId != null ? " AND id <> :id" : "");
        var q = em.createNativeQuery(sql).setParameter("codice", codice);
        if (exceptId != null) q.setParameter("id", exceptId);
        return ((Number) q.getSingleResult()).longValue() > 0;
    }

    private void validaTipo(String tipo) {
        long n = ((Number) em.createNativeQuery("SELECT count(*) FROM lk_tipi_coge WHERE codice = :t")
                .setParameter("t", tipo).getSingleResult()).longValue();
        if (n == 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "TIPO_NON_VALIDO",
                    "Tipo COGE non valido: " + tipo);
        }
    }

    private void validaParent(Integer parentId, Integer selfId) {
        if (parentId == null) return;
        if (parentId.equals(selfId)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARENT_NON_VALIDO",
                    "Un conto non può essere padre di sé stesso");
        }
        long n = ((Number) em.createNativeQuery("SELECT count(*) FROM piano_dei_conti_coge WHERE id = :id")
                .setParameter("id", parentId).getSingleResult()).longValue();
        if (n == 0) {
            throw new ApiException(Response.Status.BAD_REQUEST, "PARENT_NON_VALIDO",
                    "Conto padre inesistente: " + parentId);
        }
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private PianoContiCogeDTO toDTO(PianoContiCoge p) {
        return toDTO(p.id, p.codice, p.descrizione, p.tipo, p.parentId);
    }

    private PianoContiCogeDTO toDTO(Integer id, String codice, String descrizione, String tipo,
                                    Integer parentId) {
        int livello = (int) codice.chars().filter(c -> c == '.').count() + 1;
        return new PianoContiCogeDTO(id, codice, descrizione, tipo, parentId, livello);
    }
}
