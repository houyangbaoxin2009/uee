package org.uee.pipeline;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.uee.delta.Fingerprint;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.nio.file.StandardCopyOption;
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

    /**
     * Whether to hash what this shard writes, and to write through a temporary file.
     *
     * <p>Both are the same decision: knowing an artifact's fingerprint is only useful for deciding
     * whether to keep it, and deciding whether to keep it only matters if it is somewhere it can be
     * discarded from. Off by default, which is why a run without a delta produces exactly the files it
     * produced before the feature existed — same name, written directly, no temporary to clean up.
     */
    private final boolean trackFingerprint;

    /**
     * The hash of the bytes written, fed as they are flushed.
     *
     * <p>Fed during the write rather than by reading the file back afterwards: the bytes are already in
     * hand at this point, and re-reading a shard of a hundred megabytes to hash it would double the I/O
     * of the whole export to learn something that was free a moment earlier.
     */
    private final MessageDigest digest;

    private OutputStream os;
    private long bytes;
    private long records;

    Shard(String namespace, ElementKind kind, String format, String target, Path dir, Writer writer,
            StringPool pool, long watermark, boolean dryRun, long maxBytes, int part) {
        this(namespace, kind, format, target, dir, writer, pool, watermark, dryRun, maxBytes, part,
                false);
    }

    Shard(String namespace, ElementKind kind, String format, String target, Path dir, Writer writer,
            StringPool pool, long watermark, boolean dryRun, long maxBytes, int part,
            boolean trackFingerprint) {
        this.trackFingerprint = trackFingerprint;
        this.digest = trackFingerprint ? sha256() : null;
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

    /**
     * The file the bytes actually go to.
     *
     * <p>When tracking a fingerprint this is a temporary beside the real file, because whether the real
     * file should be replaced is not known until the last byte has been hashed. Writing to the final name
     * and deciding afterwards would mean the decision could only ever be "keep", which is not a delta.
     */
    private Path writeTarget() {
        return trackFingerprint ? temporary() : file;
    }

    /** Where the bytes go while the decision is unknown. */
    private Path temporary() {
        return file.resolveSibling(file.getFileName() + ".part");
    }

    void open() throws IOException {
        if (dryRun) {
            return;
        }
        if (os == null) {
            Path destination = writeTarget();
            Files.createDirectories(destination.getParent());
            os = Files.newOutputStream(destination, StandardOpenOption.CREATE,
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
        if (digest != null) {
            // Fed here because the bytes are in hand; reading the file back afterwards would double the
            // I/O of the export to learn something already known.
            digest.update(data);
        }
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
     * The fingerprint of everything this shard wrote, or {@code null} when nothing was tracked.
     *
     * <p>Only meaningful once the shard is closed, since a hash of a prefix is the hash of a file that
     * does not exist yet.
     */
    String fingerprint() {
        if (digest == null) {
            return null;
        }
        return Fingerprint.of(digest.digest());
    }

    /**
     * Moves a tracked shard into place.
     *
     * <p>Called when the artifact is new, changed, or unchanged but missing from disk. The rename is what
     * makes the write atomic from a consumer's point of view: the file is either the previous one or the
     * complete new one, never a prefix of either.
     */
    void keep() throws IOException {
        if (!trackFingerprint || dryRun) {
            return;
        }
        Files.move(temporary(), file, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Throws a tracked shard away, leaving whatever was already at the final path.
     *
     * <p>This is the case the whole feature exists for: the content did not change, so the bytes just
     * written are identical to the bytes already there, and writing them again was a waste that is now
     * not committed.
     */
    void discard() throws IOException {
        if (!trackFingerprint || dryRun) {
            return;
        }
        Files.deleteIfExists(temporary());
    }

    /** Whether bytes were written to a temporary rather than to the final path. */
    boolean tracksFingerprint() {
        return trackFingerprint;
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256, so this is unreachable rather than defensive.
            throw new IllegalStateException("SHA-256 is required of every JVM and is missing", e);
        }
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
