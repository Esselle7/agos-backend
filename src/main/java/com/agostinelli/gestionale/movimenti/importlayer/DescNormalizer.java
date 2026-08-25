package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.movimenti.importlayer.model.EntitaEstratte;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizzazione del testo e parser semantico della descrizione
 * (ETL_CLASSIFICAZIONE_v2 §3). Lavora su una descrizione GIÀ ripulita dal
 * normalizzatore di formato (UPPERCASE, spazi singoli, niente {@code <>*}).
 *
 * <p>Espone due viste su cui i gate fanno match:
 * <ul>
 *   <li><b>SPACED</b>: la descrizione con spazi singoli (frasi: {@code INCASSO POS});</li>
 *   <li><b>COMPACT</b>: la descrizione senza spazi (keyword spezzate dal word-wrap
 *       degli estratti conto: {@code AFFIT TO SALA → AFFITTOSALA}).</li>
 * </ul>
 *
 * <p>Estrae inoltre IBAN, ordinante, beneficiario e codice Stripe (§3 tabella entità).
 * Stateless: tutti i metodi sono statici.
 */
public final class DescNormalizer {

    private DescNormalizer() {}

    // IBAN italiano: IT + 2 check + 23 alfanumerici. Prima con prefisso "IBAN", poi bare.
    private static final Pattern IBAN_PREFIXED = Pattern.compile("IBAN:?\\s*(IT\\d{2}[A-Z0-9]{23})");
    private static final Pattern IBAN_BARE = Pattern.compile("\\b(IT\\d{2}[A-Z0-9]{23})\\b");

    // Codice Stripe: PO + 8 cifre (data) + suffisso alfanumerico.
    private static final Pattern STRIPE_CODE = Pattern.compile("\\b(PO\\d{8}[A-Z0-9]*)");

    // Ordinante CA: "ORD:<nome> DT.ORD" — oppure il nome fino a fine stringa quando la descrizione
    // è troncata subito dopo (nessun DT.ORD). Il lazy preferisce comunque DT.ORD se presente.
    private static final Pattern ORD_CA = Pattern.compile("ORD:\\s*(.+?)(?:\\s*DT\\.ORD|$)");
    // Ordinante BPM: "BON.DA <nome>" fino al codice Stripe o fine stringa.
    private static final Pattern ORD_BPM = Pattern.compile("BON\\.?\\s*DA\\s+(.+?)(?:\\s+PO\\d{6,}|\\s+BONIFICO|$)");
    // Beneficiario CA uscita: "...AGRICOLA AGO<num> <beneficiario> (RIF|V/ORDINE|DESCR | fine stringa)".
    // Il "| fine" copre le disposizioni troncate col nome in coda (es. "...AGOS0000007371 AIANI FLAVIO").
    private static final Pattern BENEF_CA = Pattern.compile(
            "AGRICOLA\\s+AGO\\w*?\\d{6,}\\s+(.+?)(?:\\s+(?:RIF\\.?|V/ORDINE|DESCR)|$)");
    // Pagamento F24/I24 (deleghe fiscali, anche "F24CBI ..."): la controparte è sempre l'Agenzia
    // delle Entrate, pure quando il testo riporta solo codici. Titolo/keyword deterministici.
    private static final Pattern F24 = Pattern.compile("\\b[FI]24(?:CBI)?\\b");
    // Esercente di un pagamento carta BPM (causale 118): "...CARTA <num>-[HH:MM-]<esercente> <indirizzo>".
    // L'esercente sta tra il numero carta e il primo marcatore di indirizzo / CAP / "-DA CONTAB" / fine.
    // Senza questa estrazione il nome esercente è solo testo "NORMALE" e non genera keyword apprendibili.
    private static final Pattern MERCHANT_CARTA = Pattern.compile(
            "CARTA\\s*\\*?\\s*\\d+\\s*-\\s*(?:\\d{1,2}:\\d{2}\\s*-\\s*)?(.+?)"
            + "(?:\\s+(?:VIA|VIALE|V\\.LE|P\\.ZA|PIAZZA|CORSO|C\\.SO|LOC\\.?|LOCALITA|SNC|STR\\.?|STRADA)\\b"
            + "|\\s+\\d{4,}|\\s*-\\s*DA\\b|$)");
    // Creditore di un'utenza CBILL BPM: "...BOLL.CBILL <creditore> [- R] CBILL <codice>".
    private static final Pattern CREDITORE_CBILL = Pattern.compile(
            "BOLL\\.?\\s*CBILL\\s+(.+?)\\s*(?:-\\s*R\\s+)?CBILL\\s+\\d");
    // Beneficiario di un bonifico di pagamento BPM "vostra disposizione": "...VS.DISP. RIF. <rif>
    // FAVORE <beneficiario> (NOTPROVIDE | - ADD.TOT | fine)". Àncora su VS.DISP per NON agganciare
    // il "FAVORE" di un normale "BONIF. VS. FAVORE - BON.DA ..." (quello è ordinante, non beneficiario).
    private static final Pattern VOSTRA_DISP_BPM = Pattern.compile(
            "VS\\.?\\s*DISP\\..*?FAVORE\\s+(.+?)\\s*(?:NOTPROVIDE|-\\s*ADD|$)");
    // Beneficiario di un addebito SDD BPM: "...SDD B2B|CORE : <mandato> <beneficiario>" (nome in coda).
    private static final Pattern SDD_BPM = Pattern.compile(
            "SDD\\s+(?:B2B|CORE)\\s*:\\s*\\S+\\s+(.+?)\\s*$");
    // Beneficiario di un addebito SDD CA: "SDD A : <beneficiario> <SDD03|PV|FT|SALDO|RIF|ADDEBIT|numero>".
    private static final Pattern SDD_A_CA = Pattern.compile(
            "SDD\\s+A\\s*:\\s*(.+?)\\s+(?:SDD\\d|PV\\s|FT\\b|SALDO|RIF\\b|ADDEBIT|\\d{5,})");
    // Rata di finanziamento (BPM causale 150): NON è una controparte anagrafica — il creditore è la
    // banca, assente dal testo. Serve solo a dare un titolo leggibile al posto di "—" e una firma
    // stabile per la rata ricorrente. Copre "MUTUO N.1273 5796807 RATA" e "PAG.RATE SU FIN.TO 1273/05...".
    private static final Pattern FINANZIAMENTO = Pattern.compile(
            "(?:MUTUO\\s+N\\.?|FIN\\.?TO)\\s*[:./]?\\s*(\\d[\\d/ ]*\\d)");
    // Esercente di un pagamento carta Crédit Agricole: "... C/O <esercente> <località> [PV] ITA".
    // Formato DISGIUNTO da quello BPM (che è "CARTA <num>-<esercente>" e non contiene mai "C/O":
    // misurato sul corpus reale, 0 occorrenze sui due file BPM), quindi il ramo non può toccarli.
    // Senza questo pattern la riga è inapprendibile: non ha ORD:, non ha FAVORE e non ha alcun
    // CODICE da cui segmentoNomi() possa ripartire, quindi estraiFirme() non produce nulla.
    private static final Pattern MERCHANT_CO_CA = Pattern.compile("\\bC/O\\s+(.+?)\\s*$");
    // Taglio alla forma societaria, INCLUSA: dove c'è, il nome dell'esercente finisce lì.
    private static final Pattern FORMA_SOC_TAGLIO = Pattern.compile(
            "^(.*?\\b(?:S\\.?R\\.?L\\.?S?|S\\.?P\\.?A\\.?|S\\.?N\\.?C\\.?|S\\.?A\\.?S\\.?|SRL|SPA|SNC|SAS|COOP)\\.?)(?:\\s|$)");
    // Sigla di provincia in coda ("… ALBAIRATE MI"), quando non c'è una forma societaria su cui tagliare.
    private static final Pattern CODA_PROVINCIA = Pattern.compile("\\s+[A-Z]{2}\\s*$");

    /** Vista COMPACT: rimuove tutti gli spazi (ricongiunge le parole spezzate dal word-wrap). */
    public static String compact(String spaced) {
        if (spaced == null) return null;
        return spaced.replaceAll("\\s+", "");
    }

    // Token di rumore (forme societarie / connettivi) rimossi dalla normalizzazione nomi (§7.1).
    private static final Set<String> NOISE_TOKENS = Set.of(
            "SRL", "SPA", "SNC", "SAS", "SS", "SC", "SOC", "SOCIETA", "AGRICOLA",
            "DI", "E", "DEL", "DELLA", "DELLE", "DEI", "RIF", "CRO", "V", "ORDINE", "CONTO");

    /**
     * Normalizzazione "a token" dei nomi controparte per il matching della rubrica (§7.1):
     * UPPERCASE, punteggiatura → spazio, rimozione forme societarie/connettivi, spazi singoli.
     * Es. {@code "GRUPPO ITALIANO VINI S.R.L."} → {@code "GRUPPO ITALIANO VINI"}.
     */
    public static String normalizeToken(String s) {
        if (s == null) return null;
        String up = s.toUpperCase().replaceAll("[^A-Z0-9]", " ");
        StringBuilder sb = new StringBuilder();
        for (String tok : up.trim().split("\\s+")) {
            if (tok.isEmpty() || NOISE_TOKENS.contains(tok)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(tok);
        }
        return sb.toString();
    }

    /** Estrae le entità semantiche dalla descrizione SPACED in base alla sorgente. */
    public static EntitaEstratte extract(String descSpaced, String sorgente) {
        if (descSpaced == null || descSpaced.isBlank()) return EntitaEstratte.EMPTY;

        String iban = firstGroup(IBAN_PREFIXED, descSpaced);
        if (iban == null) iban = firstGroup(IBAN_BARE, descSpaced);

        String stripe = firstGroup(STRIPE_CODE, descSpaced);

        String ordinante = null;
        String beneficiario = null;
        if (Sorgente.CA.equals(sorgente)) {
            ordinante = clean(firstGroup(ORD_CA, descSpaced));
            beneficiario = clean(firstGroup(BENEF_CA, descSpaced));
            if (beneficiario == null) beneficiario = clean(firstGroup(SDD_A_CA, descSpaced));
            if (beneficiario == null) beneficiario = merchantCo(descSpaced);
            if (beneficiario == null) beneficiario = agenziaEntrate(descSpaced);
            if (beneficiario == null) beneficiario = finanziamento(descSpaced);
        } else if (Sorgente.BPM.equals(sorgente)) {
            ordinante = clean(firstGroup(ORD_BPM, descSpaced));
            // Pagamenti carta / utenze CBILL: l'esercente/creditore è la controparte → beneficiario,
            // così l'estrattore keyword lo riconosce come IDENTITÀ (e non più testo NORMALE ignorato).
            beneficiario = clean(firstGroup(MERCHANT_CARTA, descSpaced));
            if (beneficiario == null) beneficiario = clean(firstGroup(CREDITORE_CBILL, descSpaced));
            if (beneficiario == null) beneficiario = clean(firstGroup(VOSTRA_DISP_BPM, descSpaced));
            if (beneficiario == null) beneficiario = clean(firstGroup(SDD_BPM, descSpaced));
            if (beneficiario == null) beneficiario = agenziaEntrate(descSpaced);
            if (beneficiario == null) beneficiario = finanziamento(descSpaced);
        }

        return new EntitaEstratte(iban, ordinante, beneficiario, stripe);
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Titolo sintetico "FINANZIAMENTO <num>" per le rate mutuo (non è una controparte anagrafica). */
    private static String finanziamento(String s) {
        String n = firstGroup(FINANZIAMENTO, s);
        return n == null ? null : "FINANZIAMENTO " + n.trim();
    }

    /** Controparte deterministica dei pagamenti fiscali F24/I24. */
    private static String agenziaEntrate(String s) {
        return F24.matcher(s).find() ? "AGENZIA DELLE ENTRATE" : null;
    }

    /**
     * Esercente di un pagamento carta Crédit Agricole ({@code "… C/O <esercente> <località> ITA"}).
     *
     * <p><b>Precisione misurata: 5 righe esatte su 10</b> (audit 24/08/2026 §8.5). Nel file grezzo
     * il confine fra esercente e località non esiste — verificato con {@code cat -A}: spazi singoli,
     * nessun padding di campo — quindi dove non c'è una forma societaria su cui tagliare restano
     * dentro 1-3 token di località. L'errore è sempre <b>per eccesso</b>: non cataloga mai una
     * controparte diversa, e il wizard lascia all'operatore spegnere i token prima di salvare la
     * firma. Allargare l'euristica per "azzeccarle tutte" significherebbe indovinare.
     */
    private static String merchantCo(String descSpaced) {
        String dopo = clean(firstGroup(MERCHANT_CO_CA, descSpaced));
        if (dopo == null) return null;
        String s = CODA_LOCALITA.matcher(dopo).replaceFirst("").trim();
        Matcher forma = FORMA_SOC_TAGLIO.matcher(s);
        if (forma.find()) return clean(forma.group(1));
        return clean(CODA_PROVINCIA.matcher(s).replaceFirst(""));
    }

    // ── Chiave di raggruppamento del wizard «Che spesa è questa?» (audit §7.1) ────────────
    //
    // 177 righe da catalogare non sono 177 decisioni: 48 sono incassi POS tutti della stessa
    // forma, e gli stessi esercenti tornano più volte. La chiave serve a chiedere UNA volta
    // per esercente. È anche l'etichetta mostrata a schermo: deve restare leggibile.

    /** Coda di rumore bancario attaccata al nome: da lì in poi si taglia. */
    private static final Pattern CODA_RUMORE = Pattern.compile(
            "\\s*(?:RIF\\.?\\s*CRO|NROSUPCBI|V/ORDINE|ANTICIPO\\s+FATT|PROGETTO\\s+KAIROS|SPESA)\\b.*$");
    /** Località/nazione in coda ai pagamenti carta ("… MONTANO LUCIN ITA"). */
    private static final Pattern CODA_LOCALITA = Pattern.compile("\\s+(?:I\\s*TA|ITA)$");
    /** Forme societarie: non distinguono l'esercente per chi deve dire "che spesa è". */
    private static final Pattern FORMA_SOCIETARIA = Pattern.compile(
            "\\b(?:S\\s?R\\s?L|S\\s?P\\s?A|S\\s?N\\s?C|S\\s?A\\s?S|SOCIETA|SOC|SPA|SRLS|COOP)\\b");
    /**
     * Chiave del gruppo di una riga da catalogare, o <b>null quando la riga va decisa da sola</b>.
     *
     * <p>Si raggruppa <b>solo su una controparte vera</b> (l'esercente estratto dalla causale):
     * lì «sistema anche quella» dice qualcosa di sensato, perché è lo stesso fornitore. Non si
     * raggruppa mai su parole della causale — misurato sul corpus: gli incassi POS hanno tutti la
     * stessa forma ma sono giornate, importi e circuiti diversi, e le righe EFFETTI/RIBA sono
     * pagamenti a fornitori <b>distinti</b>. Metterli insieme farebbe applicare una voce sola a
     * cose diverse: è il difetto che questa firma esiste per evitare.
     *
     * @return la chiave (ed etichetta) del gruppo, oppure null = questa riga è una decisione a sé
     */
    public static String chiaveGruppo(String controparte, String descrizione) {
        if (controparte == null || controparte.isBlank()) return null;
        String k = CODA_RUMORE.matcher(controparte.toUpperCase()).replaceFirst("");
        k = k.replace('&', 'E').replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        k = CODA_LOCALITA.matcher(k).replaceFirst("").trim();
        k = FORMA_SOCIETARIA.matcher(k).replaceAll(" ").replaceAll("\\s+", " ").trim();
        return k.isEmpty() ? null : k;
    }

    /** Data dell'operazione letta dalla causale ("… DEL 10/01/26"), o null. */
    private static final Pattern DATA_OPERAZIONE = Pattern.compile("\\bDEL\\s+(\\d{2}/\\d{2}/\\d{2,4})");

    /**
     * La data in cui la vendita è avvenuta, quando la causale la dichiara: sugli accrediti POS la
     * banca acconta a giorni di distanza, e chi guarda la riga deve vedere <b>quale giornata</b>
     * sta guardando, non solo quando è arrivato il denaro.
     */
    public static String dataOperazione(String descrizione) {
        if (descrizione == null) return null;
        Matcher m = DATA_OPERAZIONE.matcher(descrizione.toUpperCase());
        return m.find() ? m.group(1) : null;
    }

    // "POS" come SOTTOSTRINGA aggancia "VOSTRA DISPOSIZIONE" (DIS-POS-IZIONE): serve il confine
    // di parola, altrimenti un bonifico diventa un incasso POS. Trovato dal test sul corpus reale.
    private static final Pattern E_UN_POS = Pattern.compile("\\bP\\.?O\\.?S\\b|\\bNUMIA\\b|\\bNEXI\\b");
    private static final Pattern CIRCUITO = Pattern.compile("\\b(NUMIA-[A-Z]+|NEXI(?:\\s+CORE)?)\\b");

    /** Circuito dell'incasso POS letto dalla causale (NEXI, NUMIA-INTER…), o null se non è un POS. */
    public static String circuitoPos(String descrizione) {
        if (descrizione == null) return null;
        String d = descrizione.toUpperCase();
        if (!E_UN_POS.matcher(d).find()) return null;
        Matcher m = CIRCUITO.matcher(d);
        return m.find() ? m.group(1).trim() : "POS";
    }
}
