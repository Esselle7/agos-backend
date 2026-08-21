package com.agostinelli.gestionale.reporting.service;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.reporting.dto.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.faulttolerance.Timeout;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class ReportingService {

    @Inject
    EntityManager em;

    // ── GET /api/reporting/pl ─────────────────────────────────────────────────
    // WHY MV e non query diretta: mv_conto_economico_mensile usa data_competenza
    // (economica) e aggrega per conto_coge — aggregare su movimenti grezzo
    // richiederebbe full-scan con JOIN a piano_dei_conti_coge e business_units.

    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlDTO computePl(Short buId, LocalDate from, LocalDate to) {
        validateRange(from, to);
        validateRangeMensile(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        String buFilter = buId != null ? " AND business_unit_id = :buId" : "";
        @SuppressWarnings("unchecked")
        var query = em.createNativeQuery(
                "SELECT codice_coge, descrizione_coge, tipo_coge, is_capex, " +
                "COALESCE(SUM(ricavi),0), COALESCE(SUM(costi_operativi),0), " +
                "COALESCE(SUM(investimenti_capex),0), COALESCE(SUM(ebitda_proxy),0), " +
                "COALESCE(SUM(oneri_finanziari),0), COALESCE(SUM(imposte),0) " +
                "FROM mv_conto_economico_mensile " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM" + buFilter +
                " GROUP BY codice_coge, descrizione_coge, tipo_coge, is_capex")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM);
        if (buId != null) query.setParameter("buId", buId);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return buildPlDto(buId, from, to, rows, sommaOneri(oneriDaDescrizione(from, to), buId));
    }

    // Versione senza filtro buId usata dal job e da /pl/tutte-bu
    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlDTO computePlAllBu(LocalDate from, LocalDate to) {
        return computePl(null, from, to);
    }

    // ── GET /api/reporting/pl/tutte-bu ───────────────────────────────────────

    @Timeout(value = 10, unit = ChronoUnit.SECONDS)
    @Transactional
    public PlComparativoDTO computePlComparativo(LocalDate from, LocalDate to) {
        validateRange(from, to);
        validateRangeMensile(from, to);
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT business_unit_id, business_unit_nome, " +
                "COALESCE(SUM(ricavi),0), COALESCE(SUM(costi_operativi),0), " +
                "COALESCE(SUM(investimenti_capex),0), COALESCE(SUM(ebitda_proxy),0), " +
                "COALESCE(SUM(oneri_finanziari),0), COALESCE(SUM(imposte),0) " +
                "FROM mv_conto_economico_mensile " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM " +
                "GROUP BY business_unit_id, business_unit_nome")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getResultList();

        List<PlComparativoDTO.PlBuDTO> buList = new ArrayList<>();
        BigDecimal totRicavi          = BigDecimal.ZERO;
        BigDecimal totCosti           = BigDecimal.ZERO;
        BigDecimal totEbitda          = BigDecimal.ZERO;
        BigDecimal totOneriFinanziari = BigDecimal.ZERO;
        BigDecimal totImposte         = BigDecimal.ZERO;

        BigDecimal ammortamenti = computeAmmortamenti(from, to);
        // Fase 7: gli interessi dichiarati nella descrizione della rata, per BU — così la somma
        // delle BU resta uguale al consolidato e questa pagina non diverge da /pl.
        Map<Short, BigDecimal> oneriDescrPerBu = oneriDaDescrizione(from, to);

        // Prima passata per calcolare totale ebitda (necessario per pro-rata D&A per BU)
        BigDecimal sumEbitda = BigDecimal.ZERO;
        for (Object[] r : rows) {
            sumEbitda = sumEbitda.add(toBD(r[5]));
        }

        for (Object[] r : rows) {
            short buId              = toShort(r[0]);
            BigDecimal ric          = toBD(r[2]);
            // Solo costi operativi: il capex (r[4]) è investimento, non costo economico
            BigDecimal cos          = toBD(r[3]);
            BigDecimal ebitdaBu     = toBD(r[5]);
            BigDecimal oneriBu      = toBD(r[6]).add(oneriDescrPerBu.getOrDefault(buId, BigDecimal.ZERO));
            BigDecimal imposteBu    = toBD(r[7]);

            // Distribuisce D&A proporzionalmente all'EBITDA della BU; se sumEbitda ≤ 0 ripartisce in quote uguali
            BigDecimal ammortBu = sumEbitda.compareTo(BigDecimal.ZERO) > 0
                    ? ammortamenti.multiply(ebitdaBu).divide(sumEbitda, 2, RoundingMode.HALF_UP)
                    : rows.size() > 0
                        ? ammortamenti.divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            BigDecimal ebitBu       = ebitdaBu.subtract(ammortBu);
            BigDecimal utileNettoBu = ebitBu.subtract(oneriBu).subtract(imposteBu);
            BigDecimal mPct        = ric.compareTo(BigDecimal.ZERO) > 0
                    ? ebitdaBu.divide(ric, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;

            buList.add(new PlComparativoDTO.PlBuDTO(
                    new BuRefDTO(buId, (String) r[1]), ric, cos, ebitdaBu, ebitBu, utileNettoBu, mPct));

            totRicavi          = totRicavi.add(ric);
            totCosti           = totCosti.add(cos);
            totEbitda          = totEbitda.add(ebitdaBu);
            totOneriFinanziari = totOneriFinanziari.add(oneriBu);
            totImposte         = totImposte.add(imposteBu);
        }

        BigDecimal totEbit       = totEbitda.subtract(ammortamenti);
        BigDecimal totUtileNetto = totEbit.subtract(totOneriFinanziari).subtract(totImposte);
        BigDecimal totMargPct   = totRicavi.compareTo(BigDecimal.ZERO) > 0
                ? totEbitda.divide(totRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        return new PlComparativoDTO(from, to, buList,
                new PlComparativoDTO.ConsolidatoDTO(
                        totRicavi, totCosti, totEbitda,
                        ammortamenti, totEbit,
                        totOneriFinanziari, totImposte,
                        totUtileNetto, totMargPct),
                computeQualita(null, from, to, totRicavi, totCosti));
    }

    // ── GET /api/reporting/cashflow/storico ───────────────────────────────────

    @Transactional
    public List<CashFlowPeriodoDTO> getCashFlowStorico(LocalDate from, LocalDate to, String granularity) {
        validateRange(from, to);

        if ("WEEK".equals(granularity)) {
            long mesi = ChronoUnit.MONTHS.between(from.withDayOfMonth(1), to.withDayOfMonth(1));
            if (mesi > 6) {
                throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_TOO_LARGE",
                        "Granolarità settimanale supportata solo per range ≤ 6 mesi");
            }
            return getCashFlowSettimanale(from, to);
        }

        // MONTH: usa mv_cash_flow_statement
        return getCashFlowMensile(from, to);
    }

    // ── GET /api/reporting/cashflow/forecast ─────────────────────────────────

    @Transactional
    public List<ForecastPointDTO> getCashFlowForecast(int giorni) {
        int safeGiorni = Math.min(giorni, 365);
        LocalDate oggi = LocalDate.now();

        // Parte 1 – STORICO: ultimi 30 giorni con granularità giornaliera.
        // WHY data_finanziaria: il cash flow storico deve usare la data di liquidazione
        // effettiva (non quella economica) per riflettere i soldi realmente mossi.
        LocalDate storFrom = oggi.minusDays(30);
        @SuppressWarnings("unchecked")
        List<Object[]> storRows = em.createNativeQuery(
                "SELECT data_finanziaria, " +
                "COALESCE(SUM(CASE WHEN tipo='ENTRATA' THEN importo_lordo ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN tipo='USCITA'  THEN importo_lordo ELSE 0 END),0) " +
                "FROM movimenti " +
                "WHERE stato != 'ANNULLATO' " +
                "AND data_finanziaria IS NOT NULL " +
                "AND data_finanziaria BETWEEN :from AND :oggi " +
                "GROUP BY data_finanziaria ORDER BY data_finanziaria ASC")
                .setParameter("from", storFrom)
                .setParameter("oggi", oggi)
                .getResultList();

        // Liquidità iniziale = somma saldi_calcolati da mv_saldi_conti
        BigDecimal saldoIniziale = ((BigDecimal) em.createNativeQuery(
                "SELECT COALESCE(SUM(saldo_calcolato),0) FROM mv_saldi_conti")
                .getSingleResult());

        List<ForecastPointDTO> risultato = new ArrayList<>();
        BigDecimal saldoCumulato = saldoIniziale;

        // Calcola saldo cumulato storico
        Map<LocalDate, Object[]> storMap = new LinkedHashMap<>();
        for (Object[] r : storRows) {
            storMap.put(toLocalDate(r[0]), r);
        }
        for (LocalDate d = storFrom; !d.isAfter(oggi); d = d.plusDays(1)) {
            Object[] r = storMap.get(d);
            BigDecimal entr = r != null ? toBD(r[1]) : BigDecimal.ZERO;
            BigDecimal usc  = r != null ? toBD(r[2]) : BigDecimal.ZERO;
            saldoCumulato = saldoCumulato.add(entr).subtract(usc);
            risultato.add(new ForecastPointDTO(d, "STORICO", entr, usc, saldoCumulato, null));
        }

        // Parte 2 – PREVISTO: prossimi {safeGiorni} giorni
        // WHY: usiamo importoResiduo evento come stima incasso; nella realtà il cliente
        // può pagare in anticipo, in ritardo, o non pagare. Il forecast è indicativo.
        LocalDate prevFine = oggi.plusDays(safeGiorni);
        @SuppressWarnings("unchecked")
        List<Object[]> eventiRows = em.createNativeQuery(
                "SELECT data_evento, SUM(importo_totale_preventivato - importo_incassato) " +
                "FROM eventi " +
                "WHERE stato = 'CONFERMATO' " +
                "AND importo_incassato < importo_totale_preventivato " +
                "AND data_evento BETWEEN :oggi AND :fine " +
                "GROUP BY data_evento")
                .setParameter("oggi", oggi.plusDays(1))
                .setParameter("fine", prevFine)
                .getResultList();

        Map<LocalDate, BigDecimal> eventiMap = new HashMap<>();
        for (Object[] r : eventiRows) {
            eventiMap.put(toLocalDate(r[0]), toBD(r[1]));
        }

        for (LocalDate d = oggi.plusDays(1); !d.isAfter(prevFine); d = d.plusDays(1)) {
            BigDecimal entr = eventiMap.getOrDefault(d, BigDecimal.ZERO);
            BigDecimal usc  = BigDecimal.ZERO; // TODO: leggere da scadenze_fisse quando disponibile
            saldoCumulato = saldoCumulato.add(entr).subtract(usc);
            String note = eventiMap.containsKey(d) ? "Saldo atteso eventi: " + entr : null;
            risultato.add(new ForecastPointDTO(d, "PREVISTO", entr, usc, saldoCumulato, note));
        }

        return risultato;
    }

    // ── Fase 7: interessi passivi dichiarati nella descrizione bancaria ───────

    /** {@code Q.INT. E 168,41} — importo italiano con separatore migliaia opzionale. */
    private static final java.util.regex.Pattern P_QINT =
            java.util.regex.Pattern.compile("Q\\.INT\\.\\s*E\\s*([0-9.]+,[0-9]{2})");
    private static final java.util.regex.Pattern P_SPESE =
            java.util.regex.Pattern.compile("SPESE\\s*E\\s*([0-9.]+,[0-9]{2})");

    /**
     * Estrae la quota interessi (+ spese) dalle rate che la dichiarano nella propria descrizione,
     * per business unit. Esempio reale, rata Asconfidi CA del 06/07/2026:
     * <pre>RATA N. 003 SCAD. 05.07.2026 Q.CAP. E 1.594,91 Q.INT. E 168,41 SPESE E 2,00</pre>
     * La riga vale 1.765,32 su un conto PASSIVITA: capitale e interessi stanno insieme e gli
     * interessi non arrivano mai a conto economico. Qui si leggono dal dato, non si stimano:
     * <b>se la descrizione non li dichiara, non si inventa nulla</b>.
     *
     * <p>Niente doppio conteggio: i conti gia' ONERE_FINANZIARIO sono esclusi, e una rata
     * collegata a un piano FINANZIAMENTO non passa mai di qui (la riga banca resta parcheggiata
     * in {@code ricorrenti_da_riconciliare} e il piano genera due movimenti separati, con
     * descrizione «… (int.)» che non contiene {@code Q.INT.}).
     *
     * <p>Guardia percorso-soldi: la quota estratta non puo' superare l'importo della riga.
     */
    private Map<Short, BigDecimal> oneriDaDescrizione(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT m.business_unit_id, m.descrizione, m.importo_lordo " +
                "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato <> 'ANNULLATO' AND pc.tipo <> 'ONERE_FINANZIARIO' " +
                "AND m.data_competenza BETWEEN :from AND :to " +
                "AND m.descrizione LIKE '%Q.INT.%'")
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<Short, BigDecimal> perBu = new HashMap<>();
        for (Object[] r : rows) {
            String descrizione = (String) r[1];
            var mInt = P_QINT.matcher(descrizione);
            if (!mInt.find()) continue;

            BigDecimal quota = importoItaliano(mInt.group(1));
            var mSpese = P_SPESE.matcher(descrizione);
            if (mSpese.find()) quota = quota.add(importoItaliano(mSpese.group(1)));

            if (quota.compareTo(toBD(r[2])) > 0) continue;   // dato incoerente: si lascia stare
            perBu.merge(toShort(r[0]), quota, BigDecimal::add);
        }
        return perBu;
    }

    private static BigDecimal importoItaliano(String s) {
        return new BigDecimal(s.replace(".", "").replace(',', '.'));
    }

    private BigDecimal sommaOneri(Map<Short, BigDecimal> perBu, Short buId) {
        return buId != null
                ? perBu.getOrDefault(buId, BigDecimal.ZERO)
                : perBu.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ── Fase 6: attendibilita' del numero (additiva, non cambia nessun totale) ─

    /** Il gestionale riparte da qui: prima non ci sono i costi, quindi il P&amp;L non e' confrontabile. */
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 7, 1);

    /**
     * Conti transitori: quelli su cui una riga viene parcheggiata quando l'attribuzione non e'
     * certa. Non li tocco (decisione D: li cataloga il cliente), li MISURO — cosi' si vede quanto
     * del numero esposto e' ancora da attribuire.
     */
    private static final String TRANSITORI =
            "(pc.codice LIKE '%99.999' OR pc.descrizione ILIKE '%da classificare%' " +
            " OR pc.descrizione ILIKE '%temporane%' OR pc.descrizione ILIKE '%transitori%')";

    private PlDTO.QualitaDTO computeQualita(Short buId, LocalDate from, LocalDate to,
                                            BigDecimal totRicavi, BigDecimal totCosti) {
        String buFilter = buId != null ? " AND m.business_unit_id = :buId" : "";

        var qTrans = em.createNativeQuery(
                "SELECT " +
                "COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' AND pc.tipo='RICAVO' " +
                "         THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN m.tipo='USCITA' AND pc.tipo='COSTO' AND NOT pc.is_capex " +
                "         THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END),0) " +
                "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato <> 'ANNULLATO' AND m.data_competenza BETWEEN :from AND :to " +
                "AND " + TRANSITORI + buFilter)
                .setParameter("from", from).setParameter("to", to);
        if (buId != null) qTrans.setParameter("buId", buId);
        Object[] trans = (Object[]) qTrans.getSingleResult();

        // Crediti evento: ricavo maturato e non ancora incassato (data_finanziaria NULL).
        // Oggi vale 0 e lo dice: si popola quando parte la Fase 4.
        var qCrediti = em.createNativeQuery(
                "SELECT COALESCE(SUM(m.importo_lordo),0) FROM movimenti m " +
                "WHERE m.stato <> 'ANNULLATO' AND m.tipo = 'ENTRATA' AND m.evento_id IS NOT NULL " +
                "AND m.data_finanziaria IS NULL AND m.data_competenza BETWEEN :from AND :to" +
                (buId != null ? " AND m.business_unit_id = :buId" : ""))
                .setParameter("from", from).setParameter("to", to);
        if (buId != null) qCrediti.setParameter("buId", buId);
        BigDecimal crediti = toBD(qCrediti.getSingleResult());

        boolean perimetroIncompleto = from.isBefore(GO_LIVE);
        String nota = null;
        if (perimetroIncompleto) {
            var qAnte = em.createNativeQuery(
                    "SELECT COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' AND pc.tipo='RICAVO' " +
                    "       THEN COALESCE(m.importo_imponibile, m.importo_lordo) ELSE 0 END),0) " +
                    "FROM movimenti m JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                    "WHERE m.stato <> 'ANNULLATO' AND m.data_competenza BETWEEN :from AND :ante" +
                    (buId != null ? " AND m.business_unit_id = :buId" : ""))
                    .setParameter("from", from).setParameter("ante", GO_LIVE.minusDays(1));
            if (buId != null) qAnte.setParameter("buId", buId);
            BigDecimal ricaviAnte = toBD(qAnte.getSingleResult());
            nota = "Il periodo include mesi anteriori al " + GO_LIVE
                    + ": " + ricaviAnte.setScale(2, RoundingMode.HALF_UP)
                    + " € di ricavi ante go-live senza i costi corrispondenti.";
        }

        return new PlDTO.QualitaDTO(
                toBD(trans[0]), pct(toBD(trans[0]), totRicavi),
                toBD(trans[1]), pct(toBD(trans[1]), totCosti),
                crediti, perimetroIncompleto, nota);
    }

    private static BigDecimal pct(BigDecimal parte, BigDecimal totale) {
        return totale.compareTo(BigDecimal.ZERO) > 0
                ? parte.divide(totale, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }

    // ── helpers privati ───────────────────────────────────────────────────────

    private PlDTO buildPlDto(Short buId, LocalDate from, LocalDate to, List<Object[]> rows,
                             BigDecimal oneriDaDescrizione) {
        List<VoceDTO> vociRicavi  = new ArrayList<>();
        List<VoceDTO> vociCosti   = new ArrayList<>();
        BigDecimal totRicavi          = BigDecimal.ZERO;
        BigDecimal totCosti           = BigDecimal.ZERO;
        BigDecimal totCapex           = BigDecimal.ZERO;
        BigDecimal totEbitda          = BigDecimal.ZERO;
        BigDecimal totOneriFinanziari = BigDecimal.ZERO;
        BigDecimal totImposte         = BigDecimal.ZERO;

        for (Object[] r : rows) {
            totRicavi          = totRicavi.add(toBD(r[4]));
            totCosti           = totCosti.add(toBD(r[5]));
            totCapex           = totCapex.add(toBD(r[6]));
            totEbitda          = totEbitda.add(toBD(r[7]));
            totOneriFinanziari = totOneriFinanziari.add(toBD(r[8]));
            totImposte         = totImposte.add(toBD(r[9]));
        }

        BigDecimal baseRicavi = totRicavi.compareTo(BigDecimal.ZERO) > 0 ? totRicavi : BigDecimal.ONE;
        BigDecimal baseCosti  = totCosti.compareTo(BigDecimal.ZERO) > 0 ? totCosti : BigDecimal.ONE;

        for (Object[] r : rows) {
            String codice = (String) r[0];
            String desc   = (String) r[1];
            BigDecimal ric = toBD(r[4]);
            BigDecimal cos = toBD(r[5]);

            if (ric.compareTo(BigDecimal.ZERO) > 0) {
                vociRicavi.add(new VoceDTO(codice, desc, ric,
                        ric.divide(baseRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
            }
            // Solo costi OPERATIVI: il capex è un investimento (patrimoniale), non un costo
            // economico — il suo unico impatto P&L è l'ammortamento. Mostrarlo tra i costi
            // renderebbe incoerente il waterfall (ricavi − costi ≠ EBITDA) e doppio-conteggerebbe.
            if (cos.compareTo(BigDecimal.ZERO) > 0) {
                vociCosti.add(new VoceDTO(codice, desc, cos,
                        cos.divide(baseCosti, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))));
            }
        }

        // Fase 7: gli interessi che la banca dichiara nella descrizione della rata non hanno
        // un conto ONERE_FINANZIARIO a DB (la rata e' classificata PASSIVITA per intero), quindi
        // la MV non li vede. Qui si sommano a ciò che la MV ha già: nessuna riga viene toccata.
        totOneriFinanziari = totOneriFinanziari.add(oneriDaDescrizione);

        BigDecimal ammortamenti = computeAmmortamenti(from, to);
        BigDecimal ebit         = totEbitda.subtract(ammortamenti);
        BigDecimal ebt          = ebit.subtract(totOneriFinanziari);
        BigDecimal utileNetto   = ebt.subtract(totImposte);

        BigDecimal marginePct = totRicavi.compareTo(BigDecimal.ZERO) > 0
                ? totEbitda.divide(totRicavi, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : null;

        BuRefDTO buRef = buId != null ? new BuRefDTO(buId, null) : null;

        return new PlDTO(
                buRef, from, to,
                new PlDTO.RicaviDTO(totRicavi, vociRicavi),
                // totale = costi OPERATIVI (economici); capex esposto a parte come investimento
                new PlDTO.CostiDTO(totCosti, totCapex, vociCosti),
                totEbitda, ammortamenti, ebit,
                totOneriFinanziari, ebt, totImposte, utileNetto,
                marginePct,
                computeQualita(buId, from, to, totRicavi, totCosti)
        );
    }

    /**
     * Ammortamento di competenza nel periodo [from, to], a quote costanti (straight-line).
     * Per ogni cespite l'ammortamento corre da {@code data_acquisto} per
     * {@code vita_mesi = round(1200 / aliquota%)} mesi e poi SI FERMA: si contano solo i mesi
     * del periodo che cadono dentro la finestra di vita del bene. Così un bene già esaurito
     * non genera più costo (niente manutenzione manuale di is_active), e un bene comprato
     * prima del 2026 ammortizza nel 2026 solo per la sua vita residua.
     */
    @SuppressWarnings("unchecked")
    private BigDecimal computeAmmortamenti(LocalDate from, LocalDate to) {
        LocalDate pFrom = from.withDayOfMonth(1);
        LocalDate pToExcl = to.withDayOfMonth(1).plusMonths(1);   // primo mese DOPO il periodo

        List<Object[]> rows = em.createNativeQuery(
                "SELECT costo_storico, aliquota_ammortamento, data_acquisto " +
                "FROM cespiti WHERE is_active = true AND aliquota_ammortamento > 0").getResultList();

        BigDecimal tot = BigDecimal.ZERO;
        for (Object[] r : rows) {
            BigDecimal costo = toBD(r[0]);
            BigDecimal aliq  = toBD(r[1]);
            LocalDate acquisto = (r[2] instanceof java.sql.Date d) ? d.toLocalDate() : (LocalDate) r[2];

            BigDecimal mensile = costo.multiply(aliq)
                    .divide(BigDecimal.valueOf(1200), 6, RoundingMode.HALF_UP);
            int vitaMesi = BigDecimal.valueOf(1200)
                    .divide(aliq, 0, RoundingMode.HALF_UP).intValue();

            LocalDate inizio = acquisto.withDayOfMonth(1);
            LocalDate fineEscl = inizio.plusMonths(vitaMesi);     // primo mese NON ammortizzato

            LocalDate ovStart = pFrom.isAfter(inizio) ? pFrom : inizio;
            LocalDate ovEnd   = pToExcl.isBefore(fineEscl) ? pToExcl : fineEscl;
            long mesi = ChronoUnit.MONTHS.between(ovStart, ovEnd);
            if (mesi > 0) {
                tot = tot.add(mensile.multiply(BigDecimal.valueOf(mesi)));
            }
        }
        return tot.setScale(2, RoundingMode.HALF_UP);
    }

    private List<CashFlowPeriodoDTO> getCashFlowMensile(LocalDate from, LocalDate to) {
        int fromYM = from.getYear() * 100 + from.getMonthValue();
        int toYM   = to.getYear()   * 100 + to.getMonthValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT anno, mese, " +
                // entrate_investimento (V37): un'ENTRATA su conto capex (rimborso/disinvestimento)
                // prima non stava in nessuna colonna e spariva dal totale entrate.
                "COALESCE(SUM(entrate_operative + entrate_investimento + entrate_finanziarie),0), " +
                "COALESCE(SUM(uscite_operative + uscite_investimento + uscite_finanziarie),0) " +
                "FROM mv_cash_flow_statement " +
                "WHERE (anno * 100 + mese) >= :fromYM AND (anno * 100 + mese) <= :toYM " +
                "GROUP BY anno, mese ORDER BY anno ASC, mese ASC")
                .setParameter("fromYM", fromYM)
                .setParameter("toYM", toYM)
                .getResultList();

        List<CashFlowPeriodoDTO> result = new ArrayList<>();
        BigDecimal cumulato = BigDecimal.ZERO;
        for (Object[] r : rows) {
            int anno = toInt(r[0]);
            int mese = toInt(r[1]);
            BigDecimal entr = toBD(r[2]);
            BigDecimal usc  = toBD(r[3]);
            BigDecimal saldo = entr.subtract(usc);
            cumulato = cumulato.add(saldo);
            LocalDate inizio = LocalDate.of(anno, mese, 1);
            LocalDate fine   = inizio.withDayOfMonth(inizio.lengthOfMonth());
            result.add(new CashFlowPeriodoDTO(inizio, fine, entr, usc, saldo, cumulato));
        }
        return result;
    }

    private List<CashFlowPeriodoDTO> getCashFlowSettimanale(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT DATE_TRUNC('week', m.data_finanziaria) AS settimana, " +
                "COALESCE(SUM(CASE WHEN m.tipo='ENTRATA' THEN m.importo_lordo ELSE 0 END),0), " +
                "COALESCE(SUM(CASE WHEN m.tipo='USCITA'  THEN m.importo_lordo ELSE 0 END),0) " +
                // Stessa base di getCashFlowMensile: i due rami rispondevano alla stessa domanda
                // con due basi diverse (MONTH da mv_cash_flow_statement per data_finanziaria,
                // WEEK da movimenti per data_movimento) e su luglio 2026 davano 42.059,14 contro
                // 32.439,14 — 9.620,00 € di scarto strutturale. Decisione dell'utente del
                // 21/08/2026: un CASH flow si aggrega per data di liquidazione, WEEK si allinea.
                //
                // I due JOIN e il NOT ATTIVITA replicano i secchi della MV (V37), non sono
                // decorazione: la MV somma entrate/uscite operative + investimento + finanziarie,
                // e una riga ATTIVITA non-capex non cade in nessuno dei tre. Su luglio è un
                // giroconto da 300,00 fra conto 2 e conto 1 (COGE 10.03.001, 06/07): senza questo
                // predicato WEEK dava 42.359,14 / 49.336,83 contro i 42.059,14 / 49.036,83 di
                // MONTH. Con, coincidono al centesimo su entrambi i lati (misurato su
                // agosdb_postdeploy, copia della produzione del 21/08).
                //
                // data_finanziaria IS NOT NULL non serve più: lo implica il BETWEEN. È il filtro
                // che teneva fuori le righe di competenza della Fase 4, e continua a valere.
                "FROM movimenti m " +
                "JOIN conti_bancari cb ON cb.id = m.conto_bancario_id " +
                "JOIN piano_dei_conti_coge pc ON pc.id = m.conto_coge_id " +
                "WHERE m.stato != 'ANNULLATO' " +
                "AND m.data_finanziaria BETWEEN :from AND :to " +
                "AND NOT (pc.tipo = 'ATTIVITA' AND NOT pc.is_capex) " +
                "GROUP BY DATE_TRUNC('week', m.data_finanziaria) " +
                "ORDER BY settimana ASC")
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<CashFlowPeriodoDTO> result = new ArrayList<>();
        BigDecimal cumulato = BigDecimal.ZERO;
        for (Object[] r : rows) {
            LocalDate inizio = toLocalDate(r[0]);
            BigDecimal entr = toBD(r[1]);
            BigDecimal usc  = toBD(r[2]);
            BigDecimal saldo = entr.subtract(usc);
            cumulato = cumulato.add(saldo);
            result.add(new CashFlowPeriodoDTO(inizio, inizio.plusDays(6), entr, usc, saldo, cumulato));
        }
        return result;
    }

    private void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) return;
        if (from.isAfter(to)) {
            throw new ApiException(Response.Status.BAD_REQUEST, "INVALID_RANGE",
                    "from non può essere successivo a to");
        }
        if (ChronoUnit.YEARS.between(from, to) >= 5) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_TOO_LARGE",
                    "Range massimo consentito: 5 anni");
        }
    }

    /**
     * Fase 5 / decisione C (docs/specs/piano-conto-economico-italiano.md): il conto economico è
     * MENSILE e lo dice.
     *
     * {@code mv_conto_economico_mensile} aggrega per {@code anno * 100 + mese}: la parte giorno del
     * range non arriva alla query, quindi {@code from=2026-07-10&to=2026-07-20} restituiva tutto
     * luglio — in silenzio. Misurato il 21/08/2026 sul dump di produzione: sulla finestra
     * 01/07 → 20/08 il P&L rispondeva 19.691,75 e la dashboard (che filtra per giorno esatto)
     * 20.191,75, cioè 500,00 € di scarto fra due schermate della stessa app.
     *
     * Scartata l'ipotesi giornaliera: sarebbe una query nuova sul percorso caldo, senza il
     * beneficio della vista materializzata, per una precisione che un conto economico non usa.
     *
     * Vale SOLO per il P&L. Il cash flow ({@link #getCashFlowStorico}) continua a usare
     * {@link #validateRange}: la sua granularità WEEK esiste apposta per i range parziali.
     */
    public void validateRangeMensile(LocalDate from, LocalDate to) {
        if (from == null || to == null) return;
        if (from.getDayOfMonth() != 1 || to.getDayOfMonth() != to.lengthOfMonth()) {
            throw new ApiException(Response.Status.BAD_REQUEST, "RANGE_NON_MENSILE",
                    "Il conto economico è mensile: il periodo deve coprire mesi interi. "
                    + "Ricevuto " + from + " → " + to + ", atteso "
                    + from.withDayOfMonth(1) + " → " + to.withDayOfMonth(to.lengthOfMonth()) + ".");
        }
    }

    // ── type-cast helpers ─────────────────────────────────────────────────────

    private BigDecimal toBD(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal bd) return bd;
        return new BigDecimal(o.toString());
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(o.toString());
    }

    private short toShort(Object o) {
        if (o instanceof Number n) return n.shortValue();
        return Short.parseShort(o.toString());
    }

    private LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }
}
