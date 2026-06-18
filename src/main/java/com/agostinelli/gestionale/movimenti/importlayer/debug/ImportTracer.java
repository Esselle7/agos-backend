package com.agostinelli.gestionale.movimenti.importlayer.debug;

import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * <b>DEBUG-ONLY — DA RIMUOVERE.</b> Tracer di un singolo import bulk che scrive un file di
 * testo strutturato: traccia passo-passo dall'inserimento dei file (parse) fino alla
 * creazione dei movimenti in DB, mostrando come ogni riga viene catalogata.
 *
 * <p>Un file per import. Stateless rispetto al resto: si istanzia localmente nell'orchestratore
 * e si chiude a fine import. In caso di errore di I/O non interrompe mai l'import (best-effort).
 *
 * <p>Per disattivarlo: {@code -Dimport.trace.enabled=false}. Per cambiare cartella:
 * {@code -Dimport.trace.dir=/percorso}. Rimozione definitiva: cancellare il package
 * {@code importlayer/debug} e le chiamate al tracer in {@code MovimentoImportService}.
 */
public final class ImportTracer {

    private static final Logger log = Logger.getLogger(ImportTracer.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Writer out;       // null se disabilitato o I/O fallita
    private final Path file;

    private ImportTracer(Writer out, Path file) {
        this.out = out;
        this.file = file;
    }

    /** Crea un tracer per l'import indicato; no-op se disabilitato o se la cartella non è scrivibile. */
    public static ImportTracer create(String prefisso, String importLogId) {
        if (!Boolean.parseBoolean(System.getProperty("import.trace.enabled", "true"))) {
            return new ImportTracer(null, null);
        }
        try {
            Path dir = Path.of(System.getProperty("import.trace.dir", "import-traces")).toAbsolutePath();
            Files.createDirectories(dir);
            String shortId = importLogId == null ? "noid"
                    : importLogId.substring(0, Math.min(8, importLogId.length()));
            Path f = dir.resolve(prefisso + "_" + LocalDateTime.now().format(TS) + "_" + shortId + ".txt");
            Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.infof("[DEBUG] Import trace su file: %s", f);
            return new ImportTracer(w, f);
        } catch (IOException e) {
            log.warnf("[DEBUG] Impossibile creare il file di trace import: %s", e.getMessage());
            return new ImportTracer(null, null);
        }
    }

    public boolean attivo() {
        return out != null;
    }

    public Path file() {
        return file;
    }

    /** Titolo di sezione con riga di separazione. */
    public ImportTracer section(String titolo) {
        return line("").line("── %s ──", titolo);
    }

    /** Riga formattata (printf-style). */
    public ImportTracer line(String fmt, Object... args) {
        if (out == null) return this;
        try {
            out.write(args.length == 0 ? fmt : String.format(fmt, args));
            out.write('\n');
        } catch (IOException ignored) {
            // best-effort: il debug non deve mai rompere l'import
        }
        return this;
    }

    /** Coppia chiave/valore allineata. */
    public ImportTracer kv(String chiave, Object valore) {
        return line("  %-18s : %s", chiave, valore);
    }

    public void close() {
        if (out == null) return;
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
