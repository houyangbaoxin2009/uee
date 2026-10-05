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
    /** When true, nothing is opened: the shard is counted and named but not written. */
    private final boolean dryRun;
    /** Split threshold in bytes, or zero to split by record count instead. */
    private final long maxBytes;
    /** Which part of its logical output this shard is. Zero for the first. */
    private final int part;
    private OutputStream os;
    private long bytes;
    private long records;

    Shard(String namespace, ElementKind kind, String format, String target, Path dir, Writer writer,
            StringPool pool, long watermark, boolean dryRun, long maxBytes, int part) {
        this.namespace = namespace;
        this.kind = kind;
        this.format = format;
        this.target = target == null ? "" : target;
        this.dryRun = dryRun;
        this.maxBytes = maxBytes;
        this.part = part;
        this.writer = writer;
        this.pool = pool;
        this.watermark = watermark;
        // Part 0 keeps the plain name, so a run that never rolls over produces exactly the file names
        // it produced before this option existed.
        String name = writer.outputPath(namespace, kind);
        this.file = dir.resolve(part == 0 ? name : withPart(name, part + 1));
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

    /** This shard's identity, including its part number. */
    String key() {
        String base = target.isEmpty()
                ? namespace + '|' + kind.plural() + '|' + format
                : namespace + '|' + kind.plural() + '|' + format + "|target:" + target;
        return part == 0 ? base : base + "|part:" + part;
    }

    /** The output this shard belongs to, ignoring which part it is. */
    String logicalKey() {
        return target.isEmpty()
                ? namespace + '|' + kind.plural() + '|' + format
                : namespace + '|' + kind.plural() + '|' + format + "|target:" + target;
    }

    void open() throws IOException {
        if (dryRun) {
            return;
        }
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
        if (dryRun) {
            // The bytes are counted so the report is truthful about size, but nothing is written.
            bytes += writer.drain().length;
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

    /**
     * Whether this shard should be rolled over before the next record.
     *
     * <p>Two ways to decide, because they answer different needs. A record count bounds the work per
     * file predictably; a byte size bounds the file, which is what a consumer actually cares about —
     * and records vary enough (a recipe against a block) that counting them is a poor proxy for size.
     */
    boolean shouldRoll() {
        if (maxBytes > 0) {
            return bytes + writer.pendingBytes() >= maxBytes;
        }
        return false;
    }

    /** Bytes buffered but not yet flushed, for the roll-over decision. */
    long pendingBytes() {
        return writer.pendingBytes();
    }

    /**
     * Inserts a part marker before a file's extension.
     *
     * <p>Before the extension rather than after, so the result is still recognised as the same kind of
     * file by anything that looks at extensions — which is most consumers, and all of the importers.
     */
    static String withPart(String fileName, int part) {
        int slash = fileName.lastIndexOf('/');
        int dot = fileName.lastIndexOf('.');
        String marker = "-p" + part;
        if (dot <= slash) {
            // No extension, or a dot in a directory name: appending is the only safe option.
            return fileName + marker;
        }
        return fileName.substring(0, dot) + marker + fileName.substring(dot);
    }

    StringPool pool() {
        return pool;
    }

    Writer writer() {
        return writer;
    }
}
