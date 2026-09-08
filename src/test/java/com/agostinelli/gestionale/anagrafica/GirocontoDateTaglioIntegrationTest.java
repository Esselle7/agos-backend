package com.agostinelli.gestionale.anagrafica;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Un giroconto non può essere contato per metà.
 *
 * <h2>Il difetto, misurato</h2>
 * Il saldo di un conto ({@code mv_saldi_conti}, V24) taglia i movimenti alla data di apertura
 * <b>di quel conto</b>: {@code data_saldo_iniziale IS NULL OR COALESCE(data_finanziaria,
 * data_movimento) > data_saldo_iniziale}. Il taglio è quindi <b>per-conto</b>.
 *
 * <p>Un giroconto però è <b>una riga su due conti diversi</b>, con la stessa data. Se i due conti
 * hanno date di apertura diverse, la data può cadere in mezzo: una gamba entra nei saldi e
 * l'altra no — e il denaro totale <b>cresce o sparisce</b> senza che nulla lo dica.
 *
 * <p>Non è teoria. In produzione (copia dell'08/09/2026): il versamento di contanti del
 * 03/08/2026, 650,00 € (coge {@code 10.03.003}), ha l'ENTRATA su Banco BPM — aperto al
 * 30/06/2026, quindi contata — e l'USCITA da Cassa contanti — aperta al 05/08/2026, quindi
 * <b>esclusa</b>. La liquidità totale risultava sovrastimata di 650,00 €.
 *
 * <h2>Perché questa guardia guarda i DATI e non solo il codice</h2>
 * Il difetto non sta in una riga di Java: sta in {@code conti_bancari.data_saldo_iniziale}, che è
 * dato operativo, scritto dalla pagina Situazione iniziale. Nessun test sul codice lo avrebbe
 * visto. Perciò l'invariante si verifica sul database su cui la suite gira, e il secondo test
 * dimostra che il rilevatore non è vacuo: gli si costruisce davanti il controesempio.
 *
 * <p>Il criterio di inclusione è copiato <b>alla lettera</b> da V24: se quella cambia, questa
 * deve cambiare con lei — la guardia non è una seconda verità sul saldo.
 */
@QuarkusTest
class GirocontoDateTaglioIntegrationTest {

    @Inject EntityManager em;

    /** Inclusione nel saldo, con la stessa clausola di {@code mv_saldi_conti} (V24). */
    private static final String INCLUSO =
            "(%1$s.data_saldo_iniziale IS NULL " +
            " OR COALESCE(%2$s.data_finanziaria, %2$s.data_movimento) > %1$s.data_saldo_iniziale)";

    /**
     * Coppie di gambe dello stesso giroconto (stesso importo, stessa data di saldo, segno opposto,
     * conti diversi, entrambe su un conto di giroconto {@code 10.03.*}) contate in modo asimmetrico.
     * {@code a.id < b.id} evita di riportare due volte la stessa coppia.
     */
    private static final String COPPIE_SPEZZATE = """
            SELECT ca.nome, cb.nome, a.importo_lordo,
                   COALESCE(a.data_finanziaria, a.data_movimento),
                   ca.data_saldo_iniziale, cb.data_saldo_iniziale
            FROM movimenti a
            JOIN conti_bancari ca ON ca.id = a.conto_bancario_id AND ca.is_active
            JOIN piano_dei_conti_coge pa ON pa.id = a.conto_coge_id
            JOIN movimenti b
              ON b.importo_lordo = a.importo_lordo
             AND COALESCE(b.data_finanziaria, b.data_movimento)
                 = COALESCE(a.data_finanziaria, a.data_movimento)
             AND b.tipo <> a.tipo
             AND b.conto_bancario_id <> a.conto_bancario_id
             AND b.stato <> 'ANNULLATO'
            JOIN conti_bancari cb ON cb.id = b.conto_bancario_id AND cb.is_active
            JOIN piano_dei_conti_coge pb ON pb.id = b.conto_coge_id
            WHERE a.stato <> 'ANNULLATO'
              AND a.id < b.id
              AND pa.codice LIKE '10.03.%%'
              AND pb.codice LIKE '10.03.%%'
              AND """ + String.format(INCLUSO, "ca", "a")
              + " <> " + String.format(INCLUSO, "cb", "b");

    @SuppressWarnings("unchecked")
    private List<Object[]> spezzate() {
        return em.createNativeQuery(COPPIE_SPEZZATE).getResultList();
    }

    private static String descrivi(List<Object[]> righe) {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : righe) {
            sb.append("\n  · ").append(r[2]).append(" € del ").append(r[3])
              .append(" fra «").append(r[0]).append("» (aperto al ").append(r[4])
              .append(") e «").append(r[1]).append("» (aperto al ").append(r[5]).append(')');
        }
        return sb.toString();
    }

    /**
     * L'invariante sui dati veri del database su cui gira la suite.
     *
     * <p>Se fallisce, la via d'uscita NON è allentare il filtro di V24 (riaprirebbe il doppio
     * conteggio da −28.929,02 che V24 ha chiuso): è allineare le date di apertura dei conti dalla
     * pagina Situazione iniziale, perché un giroconto attraversa due conti e il taglio deve valere
     * per entrambi allo stesso modo.
     */
    @Test
    void nessunGirocontoContatoPerMeta() {
        List<Object[]> righe = spezzate();
        assertTrue(righe.isEmpty(),
                "Giroconti contati per metà: una gamba entra nei saldi e l'altra no, quindi la "
                + "liquidità totale è falsa di quell'importo. Allinea le date di apertura dei "
                + "conti (Situazione iniziale), non il filtro dei saldi." + descrivi(righe));
    }

    /**
     * Il rilevatore non è vacuo: gli si costruisce davanti un giroconto vero (due gambe, due
     * conti, stessa data e stesso importo) e poi gli si sposta sotto la data di apertura di uno
     * dei due conti. Deve vederlo.
     *
     * <p>Il giroconto se lo semina il test: {@code agosdb_test} è condiviso e volatile, e una
     * guardia che dipende da righe lasciate lì da un'altra classe si limita a saltare in
     * silenzio — cioè a non guardare niente.
     */
    @Test
    void ilRilevatoreVedeUnGirocontoSpezzato() {
        LocalDate giorno = LocalDate.now();
        UUID entrata = UUID.randomUUID();
        UUID uscita = UUID.randomUUID();
        Object dataOriginale = dataSaldoIniziale(CONTO_B);

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                seminaGamba(entrata, "ENTRATA", CONTO_A, giorno);
                seminaGamba(uscita, "USCITA", CONTO_B, giorno);
            });
            assertTrue(spezzate().isEmpty(),
                    "prima di spostare il taglio le due gambe devono contare entrambe");

            // Taglio ESATTAMENTE sul giorno del giroconto: il filtro di V24 è strict (`>`),
            // quindi questa gamba esce dal saldo mentre la gemella sull'altro conto ci resta.
            QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                            "UPDATE conti_bancari SET data_saldo_iniziale = :d WHERE id = :id")
                    .setParameter("d", giorno).setParameter("id", CONTO_B).executeUpdate());

            assertFalse(spezzate().isEmpty(),
                    "il rilevatore non ha visto un giroconto spezzato apposta: se non vede "
                    + "questo, non vedrebbe nemmeno quello vero da 650,00 € di produzione");
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("UPDATE conti_bancari SET data_saldo_iniziale = :d WHERE id = :id")
                        .setParameter("d", dataOriginale).setParameter("id", CONTO_B).executeUpdate();
                em.createNativeQuery("DELETE FROM movimenti WHERE id IN (CAST(:a AS uuid), CAST(:b AS uuid))")
                        .setParameter("a", entrata.toString()).setParameter("b", uscita.toString())
                        .executeUpdate();
            });
        }

        assertTrue(spezzate().isEmpty(), "la pulizia non ha rimesso le cose a posto");
    }

    private static final short CONTO_A = 1;
    private static final short CONTO_B = 2;
    private static final String USER = "00000000-0000-0000-0000-000000000099";

    /** Una gamba di giroconto sul conto d'appoggio {@code 10.03.001}. */
    private void seminaGamba(UUID id, String tipo, short conto, LocalDate giorno) {
        em.createNativeQuery("""
                INSERT INTO movimenti (id, data_movimento, data_competenza, data_finanziaria,
                    data_liquidita, tipo, importo_lordo, importo_commissione, conto_coge_id,
                    conto_bancario_id, business_unit_id, stato, fonte, descrizione,
                    created_by, created_at)
                VALUES (CAST(:id AS uuid), :d, :d, :d, :d, :tipo, 777.77, 0,
                    (SELECT id FROM piano_dei_conti_coge WHERE codice = '10.03.001'),
                    :conto, 5, 'REGISTRATO', 'MANUALE', 'ZZ giroconto guardia date di taglio',
                    CAST(:u AS uuid), now())
                """)
                .setParameter("id", id.toString())
                .setParameter("d", giorno)
                .setParameter("tipo", tipo)
                .setParameter("conto", conto)
                .setParameter("u", USER)
                .executeUpdate();
    }

    private Object dataSaldoIniziale(short id) {
        return em.createNativeQuery("SELECT data_saldo_iniziale FROM conti_bancari WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }
}
