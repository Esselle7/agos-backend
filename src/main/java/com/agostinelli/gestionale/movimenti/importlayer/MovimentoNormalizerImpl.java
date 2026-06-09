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
 * sono delegati al {@link MovimentoMappingEngine}.
 *
 * La fonte è riconosciuta dal discriminatore {@link Sorgente#KEY} iniettato dai parser.
 */
@ApplicationScoped
public class MovimentoNormalizerImpl implements MovimentoNormalizer {

    private static final DateTimeFormatter IT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern STRIPE_DATE = Pattern.compile("PO(\\d{4})(\\d{2})(\\d{2})", Pattern.CASE_INSENSITIVE);

    public static final String GIROCONTO_SKIP = "GIROCONTO_SKIP";

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
                row);
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
        BigDecimal importo = abs(parseEuroAmount(c.get("IMPORTO")));
        String descrizione = clean(c.get("DESCRIZIONE"));
        String causale = trimToEmpty(c.get("CAUSALE"));

        String metodo = null;
        String girosalto = null;
        if (causale.equalsIgnoreCase("480")) {
            metodo = "BONIFICO";
            if (descrizione != null && stripApostrophe(descrizione).contains("SOCIETA AGRICOLA AGOSTINELLI")) {
                girosalto = GIROCONTO_SKIP; // trasferimento interno CA->BPM
            }
        } else if (causale.equalsIgnoreCase("090") || causale.equalsIgnoreCase("092")) {
            metodo = "POS_BPM";
        } else if (causale.equalsIgnoreCase("78A")) {
            girosalto = GIROCONTO_SKIP; // versamento contante ATM
        } else if (causale.equalsIgnoreCase("ZI0")) {
            metodo = "BONIFICO";
        } else if (causale.equalsIgnoreCase("349")) {
            metodo = "POS_BPM";
        }
        // causale sconosciuta -> metodo null -> CAUSALE_NON_MAPPATA nel mapping engine

        LocalDate dataCompetenza = extractStripeDate(descrizione);

        String chiave = trimToEmpty(c.get("CHIAVE"));
        String rif = rifBanca(chiave, importo, descrizione);

        return new RawMovimento(
                row.riga(), "IMPORT_BANCA",
                data, dataCompetenza, importo, "ENTRATA", descrizione,
                (short) 1, metodo, BigDecimal.ZERO, null,
                rif, girosalto,
                null, null, null, null,
                DescNormalizer.compact(descrizione), chiave,
                DescNormalizer.extract(descrizione, Sorgente.BPM),
                row);
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

        String chiave = trimToEmpty(c.get("CHIAVE"));
        String rif = rifBanca(chiave, importo, descrizione);

        return new RawMovimento(
                row.riga(), "IMPORT_BANCA",
                data, null, importo, tipo, descrizione,
                (short) 2, metodo, BigDecimal.ZERO, null,
                rif, girosalto,
                null, null, null, null,
                DescNormalizer.compact(descrizione), chiave,
                DescNormalizer.extract(descrizione, Sorgente.CA),
                row);
    }

    private String metodoCa(String causale) {
        if (causale == null) return null;
        return switch (causale) {
            case "INCASSO TRAMITE POS" -> "POS_CA_NEXI";
            case "GIROCONTO/BONIFICO", "DISPOSIZIONE DI PAGAMENTO" -> "BONIFICO";
            case "COMMISSIONI/SPESE", "PAGAMENTO UTENZE", "EFFETTI RITIRATI/RICHIAMATI" -> "RID_SDDMANDAT";
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
     * Riferimento esterno per la dedup delle righe banca.
     * Il vecchio formato {@code chiave + descr[:30]} (CA) / {@code descr[:20]} (BPM)
     * collassava righe diverse con stessa chiave/giorno (es. più "COMMISSIONI …"
     * con primi 30 char identici). Si usa {@code chiave|importo|hash(descr)}:
     * discriminante e deterministico tra reimport dello stesso file.
     */
    private String rifBanca(String chiave, BigDecimal importo, String descrizione) {
        String imp = importo == null ? "" : importo.toPlainString();
        String d = descrizione == null ? "" : descrizione;
        return chiave + "|" + imp + "|" + Integer.toHexString(d.hashCode());
    }
}
