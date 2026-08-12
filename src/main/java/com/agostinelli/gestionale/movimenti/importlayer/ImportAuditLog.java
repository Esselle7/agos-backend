package com.agostinelli.gestionale.movimenti.importlayer;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SCAFFOLDING DI AUDIT — TEMPORANEO, DA CANCELLARE.
 *
 * <p>Scrive su file di testo il percorso decisionale <b>di ogni singola riga</b> letta dai file
 * d'import: come viene normalizzata, quale regola/gate la intercetta, perché un piano ricorrente
 * o un evento la agganciano (o no), e che fine fa. Serve a capire dove la classificazione
 * ragiona male, non a far funzionare l'import: se lo si spegne, l'import fa esattamente le
 * stesse cose.
 *
 * <p><b>Come si spegne e si cancella tutto</b> (l'unica manutenzione prevista):
 * <ol>
 *   <li>{@code agos.import.audit=false} in application.properties → smette di scrivere;</li>
 *   <li>{@code DELETE /api/movimenti/import/audit-log} → cancella i .txt già prodotti
 *       ({@link #cancellaTutto()});</li>
 *   <li>a lavoro finito: cancellare questa classe, i suoi 3 endpoint in MovimentiResource,
 *       le chiamate {@code audit.*} in MovimentoImportService / MovimentoMappingEngineImpl e
 *       {@code RataMatcher.diagnostica}. Sono tutte marcate {@code AUDIT-TEMP}.</li>
 * </ol>
 *
 * <p>Un import per file, niente rotazione, nessuna concorrenza gestita: l'import è un'azione
 * manuale, una alla volta. ponytail: se un giorno diventasse concorrente, servirebbe una sessione
 * per thread — oggi sarebbe complessità senza bisogno.
 */
@ApplicationScoped
public class ImportAuditLog {

    private static final Logger log = Logger.getLogger(ImportAuditLog.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public static final String PREFISSO = "audit-import-";

    @ConfigProperty(name = "agos.import.audit", defaultValue = "true")
    boolean attivo;

    @ConfigProperty(name = "agos.import.audit.dir", defaultValue = "audit-import")
    String cartella;

    /** Sessione di scrittura del singolo import (un import per volta: vedi javadoc). */
    private final ThreadLocal<Writer> sessione = new ThreadLocal<>();
    private final ThreadLocal<Integer> rigaCorrente = new ThreadLocal<>();

    public boolean attivo() {
        return attivo && sessione.get() != null;
    }

    // ── ciclo di vita ────────────────────────────────────────────────────────────

    public void inizia(UUID importLogId, String fileBilly, String fileBpm, String fileCa) {
        if (!attivo) return;
        try {
            Path dir = Path.of(cartella);
            Files.createDirectories(dir);
            Path f = dir.resolve(PREFISSO + LocalDateTime.now().format(TS) + ".txt");
            Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            sessione.set(w);
            riga("╔══════════════════════════════════════════════════════════════════════════════");
            riga("║ AUDIT IMPORT — traccia decisionale riga per riga");
            riga("║ import_log_id : " + importLogId);
            riga("║ quando        : " + LocalDateTime.now());
            riga("║ file          : Billy=" + nomeFile(fileBilly) + " · BPM=" + nomeFile(fileBpm)
                    + " · CA=" + nomeFile(fileCa));
            riga("║ file di audit : " + f.toAbsolutePath());
            riga("╠══════════════════════════════════════════════════════════════════════════════");
            riga("║ Legenda del percorso di ogni riga:");
            riga("║   [PARSE]  riga grezza come letta dal CSV");
            riga("║   [NORM]   valori normalizzati (data, importo, tipo, conto, metodo, descrizione)");
            riga("║   [1]      regole data-driven (tabella regole_classificazione, priorità)");
            riga("║   [2..5]   Gate A: POS/Satispay · giroconti · keyword ricorrenti · match piani");
            riga("║   [6]      Gate B: riconoscimento incasso-evento");
            riga("║   [7]      classificazione contabile (CoGe, BU, fornitore, IVA)");
            riga("║   [8]      dedup e matching differiti");
            riga("║   →ESITO   che fine fa la riga, e perché");
            riga("╚══════════════════════════════════════════════════════════════════════════════");
        } catch (IOException e) {
            log.warnf("Audit import non avviato: %s", e.getMessage());
            sessione.remove();
        }
    }

    public void chiudi(String riepilogo) {
        Writer w = sessione.get();
        if (w == null) return;
        riga("");
        riga("══════════════════════════════════════════════════════════════════════════════");
        riga("RIEPILOGO: " + riepilogo);
        riga("══════════════════════════════════════════════════════════════════════════════");
        try { w.flush(); w.close(); } catch (IOException ignored) { /* file già scritto */ }
        sessione.remove();
        rigaCorrente.remove();
    }

    // ── traccia della singola riga ───────────────────────────────────────────────

    /** Apre il blocco di una riga e stampa i campi grezzi così come arrivano dal file. */
    public void iniziaRiga(String fonte, int numeroRiga, Map<String, String> campiGrezzi) {
        if (!attivo()) return;
        rigaCorrente.set(numeroRiga);
        riga("");
        riga("┌─ RIGA #" + numeroRiga + "  [" + fonte + "] " + "─".repeat(Math.max(0, 56 - fonte.length())));
        StringBuilder sb = new StringBuilder();
        campiGrezzi.forEach((k, v) -> {
            if (v != null && !v.isBlank() && !"SORGENTE".equals(k)) {
                sb.append(k).append('=').append(tronca(v, 120)).append(" | ");
            }
        });
        riga("│ [PARSE] " + sb);
    }

    /** Un passo del ragionamento: fase leggibile + esito + perché. */
    public void passo(String fase, String dettaglio) {
        if (!attivo()) return;
        riga("│ " + String.format("%-9s", fase) + " " + dettaglio);
    }

    /** Sotto-voce di un passo (es. il confronto con ciascun piano ricorrente). */
    public void dettaglio(String testo) {
        if (!attivo()) return;
        riga("│             · " + testo);
    }

    /** Chiude il blocco dicendo che fine ha fatto la riga. */
    public void esito(String esito, String perche) {
        if (!attivo()) return;
        riga("│ →ESITO   " + esito + (perche == null || perche.isBlank() ? "" : "  — " + perche));
        riga("└" + "─".repeat(78));
    }

    /** Evento che non appartiene a una singola riga (fasi di riconciliazione, totali). */
    public void fase(String titolo, String dettaglio) {
        if (!attivo()) return;
        riga("");
        riga("### " + titolo + (dettaglio == null || dettaglio.isBlank() ? "" : " — " + dettaglio));
    }

    private void riga(String s) {
        Writer w = sessione.get();
        if (w == null) return;
        try {
            w.write(s);
            w.write(System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String nomeFile(String s) {
        return s == null || s.isBlank() ? "(nome non trasmesso dal client)" : s;
    }

    private static String tronca(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    // ── gestione dei file prodotti ───────────────────────────────────────────────

    public record FileAudit(String nome, long byteScritti, String modificato) {}

    public List<FileAudit> elenco() {
        Path dir = Path.of(cartella);
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            List<FileAudit> out = new ArrayList<>();
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith(PREFISSO)).toList()) {
                out.add(new FileAudit(p.getFileName().toString(), Files.size(p),
                        Files.getLastModifiedTime(p).toString()));
            }
            out.sort(Comparator.comparing(FileAudit::nome).reversed());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Contenuto di un file, con il nome validato: mai path traversal fuori dalla cartella. */
    public String contenuto(String nome) {
        Path dir = Path.of(cartella).toAbsolutePath().normalize();
        Path f = dir.resolve(nome).normalize();
        if (!f.startsWith(dir) || !f.getFileName().toString().startsWith(PREFISSO)) return null;
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Cancella tutti i file di audit prodotti. Ritorna quanti ne ha rimossi. */
    public int cancellaTutto() {
        Path dir = Path.of(cartella);
        if (!Files.isDirectory(dir)) return 0;
        int n = 0;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().startsWith(PREFISSO)).toList()) {
                try { Files.deleteIfExists(p); n++; } catch (IOException ignored) { /* file in uso */ }
            }
        } catch (IOException e) {
            log.warnf("Cancellazione audit fallita: %s", e.getMessage());
        }
        return n;
    }
}
