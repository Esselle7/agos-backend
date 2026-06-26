package com.agostinelli.gestionale.movimenti.importlayer;

import com.agostinelli.gestionale.infrastructure.exception.ApiException;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawMovimento;
import com.agostinelli.gestionale.movimenti.importlayer.model.RawRow;
import com.agostinelli.gestionale.movimenti.importlayer.parser.Sorgente;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizzatore unico per le tre fonti. Esegue solo trasformazioni di formato
 * (date, importi, descrizioni, tipo, conto/metodo deterministici dalla fonte,
 * giroconto, riferimento esterno). NON accede al DB: coge/BU/fornitore/evento
 * sono delegati al {@link MovimentoMappingEngineImpl}.
 *
 * La fonte è riconosciuta dal discriminatore {@link Sorgente#KEY} iniettato dai parser.
 */
@ApplicationScoped
public class MovimentoNormalizerImpl implements MovimentoNormalizer {

    private static final DateTimeFormatter IT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern STRIPE_DATE = Pattern.compile("PO(\\d{4})(\\d{2})(\\d{2})", Pattern.CASE_INSENSITIVE);

    // Data reale dell'incasso POS, riportata nella descrizione banca come "... DEL gg/mm/aa[aa]".
    // È diversa dalla data contabile dell'accredito (sfasata di 1-3 giorni) ed è la chiave di
    // confronto preferita con lo scontrino Billy (REFACTOR-IMPORT-CONGIUNTO §FASE1).
    private static final Pattern POS_DEL_DATE = Pattern.compile("\\bDEL\\s+(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b");

    public static final String GIROCONTO_SKIP = "GIROCONTO_SKIP";

    // Circuiti POS → banca di accredito (fact #2): il marcatore vive SOLO in descrizione banca.
    public static final String CIRCUITO_NUMIA = "NUMIA"; // POS fisico Numia → Banco BPM
    public static final String CIRCUITO_NEXI = "NEXI";   // app NFC Nexi → Crédit Agricole

    @Override
    public RawMovimento normalize(RawRow row) {
        String sorgente = row.campi().get(Sorgente.KEY);
        if (sorgente == null) {
            throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR, "SORGENTE_MANCANTE",
                    "RawRow privo del discriminatore di sorgente");
        }
        return switch (sorgente) {
            case Sorgente.BILLY -> normalizeBilly(row);
            case Sorgente.BPM -> normalizeBpm(row);
            case Sorgente.CA -> normalizeCa(row);
            default -> throw new ApiException(Response.Status.INTERNAL_SERVER_ERROR,
                    "SORGENTE_SCONOSCIUTA", "Sorgente non gestita: " + sorgente);
        };
    }

    // ── BILLY ────────────────────────────────────────────────────────────────
    private RawMovimento normalizeBilly(RawRow row) {
        var c = row.campi();
        LocalDate data = parseIso(c.get("DATA"));
        BigDecimal importo = canonicalAmount(c.get("IMPORTO"));

        String banca = upper(c.get("BANCA"));
        String pagamento = upper(c.get("PAGAMENTO"));

        Short conto = contoFromBanca(banca);
        String metodo = metodoBilly(pagamento, banca);

        String descrizione = clean(coalesce(c.get("DESCRIZIONE"), c.get("NOTE")));
        String chiave = trimToEmpty(c.get("CHIAVE"));
        String numero = trimToEmpty(c.get("NUMERO"));
        String rif = chiave + "-" + numero;

        return new RawMovimento(
                row.riga(), "IMPORT_BILLY",
                data, null, abs(importo), "ENTRATA", descrizione,
                conto, metodo, BigDecimal.ZERO, null,
                rif, null,
                canonicalAmount(c.get("AGRITURISMO")),
                canonicalAmount(c.get("ALTRO")),
                canonicalAmount(c.get("CARNE_10")),
                canonicalAmount(c.get("ORTOFRUTTA_4")),
                DescNormalizer.compact(descrizione), chiave,
                DescNormalizer.extract(descrizione, Sorgente.BILLY),
                row,
                null, null); // Billy non è una riga banca: nessun incasso/circuito POS
    }

    private Short contoFromBanca(String banca) {
        if (banca == null) return null;
        if (banca.contains("CREDIT")) return 2;
        if (banca.equals("BPM")) return 1;
        if (banca.equals("CASH")) return 3;
        if (banca.contains("SATISPAY")) return 2; // confluisce su CA
        return null; // #N/A, NA, sconosciuta -> BANCA_NON_IDENTIFICATA
    }

    private String metodoBilly(String pagamento, String banca) {
        if (banca != null && banca.contains("SATISPAY")) return "SATISPAY";
        if ("C".equals(pagamento)) return "CONTANTI";
        if ("E".equals(pagamento)) {
            if (banca == null) return null;
            if (banca.contains("CREDIT")) return "POS_CA_NEXI";
            if (banca.equals("BPM")) return "POS_BPM";
            if (banca.equals("CASH")) return null; // impossibile -> METODO_NON_IDENTIFICATO
        }
        return null;
    }

    // ── BPM ──────────────────────────────────────────────────────────────────
    private RawMovimento normalizeBpm(RawRow row) {
        var c = row.campi();
        LocalDate data = parseItDate(c.get("DATA_CONTABILE"));
        // BPM ha UNA colonna Importo con segno: il segno determina entrata/uscita. (Prima era
        // sempre ENTRATA: corretto solo perché le uscite erano comunque ambigue e non salvate.)
        BigDecimal segnato = parseEuroAmount(c.get("IMPORTO"));
        BigDecimal importo = abs(segnato);
        String tipo = (segnato != null && segnato.signum() < 0) ? "USCITA" : "ENTRATA";
        String descrizione = clean(c.get("DESCRIZIONE"));
        String causale = trimToEmpty(c.get("CAUSALE")).toUpperCase();

        String metodo = metodoBpm(causale);

        // "RIMBORSO CARTA" condivide la causale POS (349) ma è uno STORNO, non una vendita: lo
        // riportiamo a CARTA_DEBITO così non è un incasso POS (niente circuito, fuori dalla
        // riconciliazione/aggregato-giorno e dalla vista "Incassi POS da ripartire", che filtra POS_*).
        boolean rimborsoCarta = descrizione != null && descrizione.contains("RIMBORSO CARTA");
        if (rimborsoCarta) metodo = "CARTA_DEBITO";

        // Giroconti interni → scarto deterministico (non sono né incassi né costi reali).
        String girosalto = null;
        if ("480".equals(causale) && descrizione != null
                && stripApostrophe(descrizione).contains("SOCIETA AGRICOLA AGOSTINELLI")) {
            girosalto = GIROCONTO_SKIP; // trasferimento interno CA → BPM
        } else if ("78A".equals(causale)) {
            girosalto = GIROCONTO_SKIP; // versamento contante ATM (no doppio conteggio cassa→banca)
        }

        LocalDate dataCompetenza = extractStripeDate(descrizione);

        // Incasso POS (Numia → BPM): solo entrate POS_BPM. Conserva circuito e data reale
        // "DEL gg/mm/aa" per la riconciliazione con lo scontrino Billy.
        String circuitoPos = null;
        LocalDate dataIncassoPos = null;
        if ("POS_BPM".equals(metodo) && "ENTRATA".equals(tipo)) { // RIMBORSO CARTA è già stato dirottato a CARTA_DEBITO
            circuitoPos = CIRCUITO_NUMIA;
            dataIncassoPos = extractPosDate(descrizione);
        }

        String chiave = trimToEmpty(c.get("CHIAVE"));
        String rif = rifBanca(chiave, importo, descrizione, data, c.get("DATA_VALUTA"));

        return new RawMovimento(
                row.riga(), "IMPORT_BANCA",
                data, dataCompetenza, importo, tipo, descrizione,
                (short) 1, metodo, BigDecimal.ZERO, null,
                rif, girosalto,
                null, null, null, null,
                DescNormalizer.compact(descrizione), chiave,
                DescNormalizer.extract(descrizione, Sorgente.BPM),
                row,
                dataIncassoPos, circuitoPos);
    }

    /**
     * Mappa il codice causale BPM (codici interni della banca) al metodo di pagamento, così
     * che la riga non resti {@code CAUSALE_NON_MAPPATA}. Criterio: il metodo riflette lo
     * <b>strumento</b> con cui il denaro si è mosso (bonifico, carta, addebito…), NON la natura
     * contabile — quella la deduce {@code classifyEntrata/Uscita} dal COGE. Vedi AMBIGUI-OVERVIEW.md.
     *
     * <p>Causali non previste → {@code null}: la riga resta in revisione manuale, senza ipotesi
     * azzardate (rete di sicurezza per codici nuovi/rari).
     */
    private String metodoBpm(String causale) {
        return switch (causale) {
            // ── INCASSI (accrediti, segno +) ──
            case "480", "ZI0" -> "BONIFICO";        // bonifico in accredito (Stripe/Satispay/estero/generico)
            case "090", "092", "349" -> "POS_BPM";  // incassi POS Numia (+ rimborso carta)
            // ── PAGAMENTI (addebiti, segno −) ──
            case "260" -> "BONIFICO";               // "vostra disposizione" = bonifico a fornitore
            case "118" -> "CARTA_DEBITO";           // pagamento con carta di debito aziendale
            case "50C", "110", "150", "174"         // addebiti SEPA: SDD B2B, utenze CBILL, rata mutuo, premi assicurativi
                    -> "RID_SDDMANDAT";
            case "310", "314" -> "RID_SDDMANDAT";   // effetti ritirati / RIBA (addebito a scadenza)
            case "662", "660", "16H", "16I", "16G", "16X", "16Z", "18D", "195"
                    -> "ADDEBITO_CONTO";            // commissioni, spese, competenze, interessi, imposta di bollo c/c
            case "198" -> "F24";                    // tributi (I24 Agenzia Entrate)
            // 78A (versamento ATM) → giroconto, il metodo non serve
            default -> null;                        // causale sconosciuta → CAUSALE_NON_MAPPATA (rivedere a mano)
        };
    }

    // ── CA ───────────────────────────────────────────────────────────────────
    private RawMovimento normalizeCa(RawRow row) {
        var c = row.campi();
        LocalDate data = parseItDate(c.get("DATA_OPERAZIONE"));
        String descrizione = clean(c.get("DESCRIZIONE"));
        String causale = upper(c.get("CAUSALE"));

        BigDecimal entrate = parseEuroAmount(c.get("ENTRATE"));
        BigDecimal uscite = parseEuroAmount(c.get("USCITE"));

        String tipo;
        BigDecimal importo;
        if (entrate != null && entrate.signum() != 0) {
            tipo = "ENTRATA";
            importo = abs(entrate);
        } else {
            tipo = "USCITA";
            importo = abs(uscite);
        }

        String metodo = metodoCa(causale);

        // Giroconto interno CA→BPM lato USCITA (simmetrico al lato BPM in entrata):
        // disposizione di pagamento il cui BENEFICIARIO è la stessa società (…AGOSTINELLI SRL).
        // Il prefisso ordinante è sempre "AGRICOLA AGO<cifre>", mai "AGRICOLA AGOSTINELLI"
        // contiguo: così un pagamento a "PIETRO AGOSTINELLI" (persona) NON viene scartato.
        String girosalto = null;
        if ("USCITA".equals(tipo) && "DISPOSIZIONE DI PAGAMENTO".equals(causale)
                && descrizione != null
                && stripApostrophe(descrizione).contains("AGRICOLA AGOSTINELLI")) {
            girosalto = GIROCONTO_SKIP;
        }

        // Incasso POS (Nexi → CA): causale "INCASSO TRAMITE POS" (metodo POS_CA_NEXI).
        // Conserva circuito e data reale "DEL gg/mm/aa" per la riconciliazione con Billy.
        String circuitoPos = null;
        LocalDate dataIncassoPos = null;
        if ("POS_CA_NEXI".equals(metodo)) {
            circuitoPos = CIRCUITO_NEXI;
            dataIncassoPos = extractPosDate(descrizione);
        }

        String chiave = trimToEmpty(c.get("CHIAVE"));
        String rif = rifBanca(chiave, importo, descrizione, data, c.get("DATA_VALUTA"));

        return new RawMovimento(
                row.riga(), "IMPORT_BANCA",
                data, null, importo, tipo, descrizione,
                (short) 2, metodo, BigDecimal.ZERO, null,
                rif, girosalto,
                null, null, null, null,
                DescNormalizer.compact(descrizione), chiave,
                DescNormalizer.extract(descrizione, Sorgente.CA),
                row,
                dataIncassoPos, circuitoPos);
    }

    private String metodoCa(String causale) {
        if (causale == null) return null;
        return switch (causale) {
            case "INCASSO TRAMITE POS" -> "POS_CA_NEXI";
            case "GIROCONTO/BONIFICO", "DISPOSIZIONE DI PAGAMENTO" -> "BONIFICO";
            case "COMMISSIONI/SPESE", "PAGAMENTO UTENZE", "EFFETTI RITIRATI/RICHIAMATI" -> "RID_SDDMANDAT";
            case "IMPOSTE E TASSE" -> "F24"; // tributi addebitati dal conto
            default -> null; // CAUSALE_NON_MAPPATA
        };
    }

    // ── helpers di formato ─────────────────────────────────────────────────────

    /** Formato italiano "1.234,56" / "-321,76" → BigDecimal. null se vuoto o #N/A. */
    static BigDecimal parseEuroAmount(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || s.equalsIgnoreCase("#N/A") || s.equalsIgnoreCase("NA")) return null;
        s = s.replace(".", "").replace(",", ".");
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Importo Billy già canonico (toPlainString di un double Excel) → BigDecimal. */
    private BigDecimal canonicalAmount(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate parseIso(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseItDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), IT_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate extractStripeDate(String descrizione) {
        if (descrizione == null) return null;
        Matcher m = STRIPE_DATE.matcher(descrizione);
        if (m.find()) {
            try {
                return LocalDate.of(Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** Data reale dell'incasso POS dalla descrizione ("... DEL gg/mm/aa"); null se assente. */
    private LocalDate extractPosDate(String descrizione) {
        if (descrizione == null) return null;
        Matcher m = POS_DEL_DATE.matcher(descrizione);
        if (m.find()) {
            try {
                int d = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int y = Integer.parseInt(m.group(3));
                if (y < 100) y += 2000;
                return LocalDate.of(y, mo, d);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private BigDecimal abs(BigDecimal v) {
        return v == null ? null : v.abs();
    }

    private String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase()
                .replaceAll("\\s+", " ")
                .replaceAll("[<>*]", "");
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    private String upper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private String stripApostrophe(String s) {
        return s.replace("'", "").replace("`", "");
    }

    private String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private String coalesce(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return b;
    }

    private String safeSub(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) : s;
    }

    /**
     * Riferimento esterno per la dedup delle righe banca:
     * {@code chiave|importo|hash(descr)|data}.
     * <p>La <b>data</b> è parte della chiave (coerente con l'indice univoco DB
     * {@code (fonte, riferimento_esterno, data_movimento)}): senza di essa gli addebiti
     * ricorrenti con stesso importo e stessa descrizione ma date diverse (es. commissione
     * SDD €1,00 mensile, rata mutuo) verrebbero erroneamente collassati come duplicati nel
     * loop di import. Resta deterministico tra reimport dello stesso file (stessa data).
     */
    private String rifBanca(String chiave, BigDecimal importo, String descrizione, LocalDate data, String dataValuta) {
        String imp = importo == null ? "" : importo.toPlainString();
        String d = descrizione == null ? "" : descrizione;
        String dt = data == null ? "" : data.toString();
        String dv = dataValuta == null ? "" : dataValuta;
        // SHA-256 della descrizione (anti-collisione) al posto del vecchio String.hashCode() che
        // poteva fondere righe distinte; + data valuta per disambiguare stesso-importo/stesso-giorno.
        return chiave + "|" + imp + "|" + sha256hex(d) + "|" + dt + "|" + dv;
    }

    /** SHA-256 esadecimale (primi 24 char): hash anti-collisione per la chiave di dedup banca. */
    private String sha256hex(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 0xF, 16)).append(Character.forDigit(h[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
