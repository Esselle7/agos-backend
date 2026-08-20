package com.agostinelli.gestionale.eventi.repository;

import com.agostinelli.gestionale.eventi.domain.Evento;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class EventiRepository implements PanacheRepositoryBase<Evento, UUID> {

    @Inject
    EntityManager em;

    /** Fuso della sala: il confine «oggi» è quello di Como, non UTC (alle 02:00 un evento di
     *  oggi non deve scivolare fra i passati). */
    public static final ZoneId FUSO = ZoneId.of("Europe/Rome");

    /** Vista LISTA: eventi da lavorare, dal più vicino a oggi in avanti; i passati non ancora
     *  saldati ("da chiudere") chiudono la lista. Decisione utente 19/08/2026: la spec si
     *  contraddiceva (Obiettivo «prima riga = il prossimo» vs R1 «i passati sopra») e con 17
     *  passati a libro metterli in cima riportava il prossimo evento in 18ª posizione, cioè
     *  esattamente il dolore di partenza («per arrivare al prossimo weekend bisogna scorrere»). */
    public static final String VISTA_LISTA = "LISTA";

    /** Vista STORICO: solo i saldati, dal più recente. */
    public static final String VISTA_STORICO = "STORICO";

    public List<Evento> findWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to, String search,
            String vista, int page, int size) {

        StringBuilder jpql = new StringBuilder("FROM Evento e WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(jpql, params, stato, buId, from, to, search, vista);
        if (VISTA_LISTA.equals(vista)) {
            // Due blocchi in un solo ORDER BY: prima da oggi in avanti (il prossimo per primo),
            // poi i passati da chiudere, dal più vecchio. Ordinare lato server è obbligatorio: la
            // pagina è di 20 righe e riordinare solo quelle darebbe un ordine falso.
            jpql.append(" ORDER BY CASE WHEN e.dataEvento < :oggi THEN 1 ELSE 0 END, e.dataEvento ASC");
            params.put("oggi", LocalDate.now(FUSO));
        } else {
            jpql.append(" ORDER BY e.dataEvento DESC");
        }

        TypedQuery<Evento> q = em.createQuery(jpql.toString(), Evento.class)
                .setFirstResult(page * size)
                .setMaxResults(size);
        params.forEach(q::setParameter);
        return q.getResultList();
    }

    public long countWithFilters(
            String stato, Short buId, LocalDate from, LocalDate to, String search, String vista) {

        StringBuilder jpql = new StringBuilder("SELECT COUNT(e) FROM Evento e WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        appendFilters(jpql, params, stato, buId, from, to, search, vista);

        TypedQuery<Long> q = em.createQuery(jpql.toString(), Long.class);
        params.forEach(q::setParameter);
        return q.getSingleResult();
    }

    private void appendFilters(StringBuilder jpql, Map<String, Object> params,
            String stato, Short buId, LocalDate from, LocalDate to, String search, String vista) {

        // I segnaposto ("[DA ATTRIBUIRE] …") sono contenitori tecnici di incassi non ancora
        // attribuiti: restano fuori da lista e calendario, si vedono solo nella vista dedicata.
        jpql.append(" AND e.isSegnaposto = false");

        // Partizione fra le due schede: SALDATO ⟺ Storico, tutto il resto ⟺ Lista. Un evento sta
        // in una sola scheda, nessuno sparisce da entrambe. ANNULLATO resta in Lista di proposito:
        // non è storia chiusa finché c'è una penale da incassare.
        if (VISTA_LISTA.equals(vista)) {
            jpql.append(" AND e.stato <> 'SALDATO'");
        } else if (VISTA_STORICO.equals(vista)) {
            jpql.append(" AND e.stato = 'SALDATO'");
        }

        if (stato != null) {
            jpql.append(" AND e.stato = :stato");
            params.put("stato", stato);
        }
        if (buId != null) {
            jpql.append(" AND e.businessUnitId = :buId");
            params.put("buId", buId);
        }
        if (from != null) {
            jpql.append(" AND e.dataEvento >= :from");
            params.put("from", from);
        }
        if (to != null) {
            jpql.append(" AND e.dataEvento <= :to");
            params.put("to", to);
        }
        if (search != null && !search.isBlank()) {
            jpql.append(" AND (LOWER(e.nome) LIKE :search OR LOWER(e.contattoNome) LIKE :search)");
            params.put("search", "%" + search.toLowerCase() + "%");
        }
    }

    /** CONFERMATI senza caparra con data evento nei prossimi {@code giorni} giorni. */
    public List<Evento> findEventiConCaparraMancanteEntroDays(int giorni) {
        LocalDate oggi  = LocalDate.now();
        LocalDate limite = oggi.plusDays(giorni);
        return list(
                "stato = 'CONFERMATO' AND caparreIncassate = 0 AND dataEvento >= ?1 AND dataEvento <= ?2",
                oggi, limite);
    }

    /** SALDATI con residuo ancora positivo (anomalia scheduler). */
    public List<Evento> findSaldatiConResiduoPositivo() {
        return em.createQuery(
                "FROM Evento e WHERE e.stato = 'SALDATO' " +
                "AND e.importoTotalePreviventivato IS NOT NULL " +
                "AND (e.importoTotalePreviventivato - e.importoIncassato) > 0.01",
                Evento.class).getResultList();
    }

    public List<Evento> findCalendario(LocalDate from, LocalDate to) {
        return list("isSegnaposto = false AND dataEvento >= ?1 AND dataEvento <= ?2 ORDER BY dataEvento ASC",
                from, to);
    }

    /**
     * Restituisce gli eventi a cui il personale è assegnato come partecipante.
     * Usata dall'endpoint {@code GET /api/eventi/miei}.
     */
    public List<Evento> findByPersonaleId(UUID personaleId, int page, int size) {
        return em.createQuery(
                "FROM Evento e WHERE e.id IN " +
                "(SELECT ep.eventoId FROM EventoPartecipante ep WHERE ep.personaleId = :pid) " +
                "ORDER BY e.dataEvento DESC", Evento.class)
                .setParameter("pid", personaleId)
                .setFirstResult(page * size)
                .setMaxResults(size)
                .getResultList();
    }

    public long countByPersonaleId(UUID personaleId) {
        return (Long) em.createQuery(
                "SELECT COUNT(e) FROM Evento e WHERE e.id IN " +
                "(SELECT ep.eventoId FROM EventoPartecipante ep WHERE ep.personaleId = :pid)")
                .setParameter("pid", personaleId)
                .getSingleResult();
    }
}
