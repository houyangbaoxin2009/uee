package org.uee.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.uee.config.ExportConfig;
import org.uee.model.ElementKind;
import org.uee.util.StringPool;
import org.uee.write.Writer;

/**
 * One output stream on disk: a (namespace, category, format) triple.
 *
 * <p>Sharding is what keeps peak memory bounded. Instead of accumulating a full export in memory
 * and writing it once, each shard streams to its own file as soon as its buffer crosses a
 * watermark. Peak memory is then proportional to the number of <em>live</em> shards times the
 * watermark, not to the size of the pack.
 *
 * <p>Sharded output is also what makes the export resumable and diffable: a shard is a natural unit
 * for both.
 */
final class Shard {

    private final String namespace;
    private final ElementKind kind;
    private final String format;
    private final String target;
    private final Path file;
    private final Writer writer;
    private final StringPool pool;
    private final long watermark;
    private OutputStream os;
    private long bytes;
    private long records;

    Shard(String namespace, ElementKind kind, String format, String target, Path dir, Writer writer,
            StringPool pool, long watermark) {
        this.namespace = namespace;
        this.kind = kind;
        this.format = format;
        this.target = target == null ? "" : target;
        this.writer = writer;
        this.pool = pool;
        this.watermark = watermark;
        this.file = dir.resolve(writer.outputPath(namespace, kind));
        // Header emission is a pure buffer operation, so it happens at construction. Only the file
        // itself is created lazily — a shard that never receives a record must not leave behind an
        // empty file.
        this.writer.beginShard(namespace, kind);
    }

    Path file() {
        return file;
    }

    long bytes() {
        return bytes;
    }

    long records() {
        return records;
    }

    ElementKind kind() {
        return kind;
    }

    String namespace() {
        return namespace;
    }

    String format() {
        return format;
    }

    /** The datapack target this shard belongs to, or an empty string for a category's own output. */
    String target() {
        return target;
    }

    void open() throws IOException {
        if (os == null) {
            Files.createDirectories(file.getParent());
            os = Files.newOutputStream(file, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
    }

    void countRecord() {
        records++;
    }

    /** Flushes the writer buffer to disk once it is worth the syscall. */
    void maybeFlush() throws IOException {
        if (!writer.isEmpty()) {
            flush();
        }
    }

    void flush() throws IOException {
        if (writer.isEmpty()) {
            return;
        }
        open();
        byte[] data = writer.drain();
        os.write(data);
        bytes += data.length;
    }

    void close() throws IOException {
        writer.endShard();
        flush();
        if (os != null) {
            os.close();
            os = null;
        }
    }

    StringPool pool() {
        return pool;
    }

    Writer writer() {
        return writer;
    }
}
