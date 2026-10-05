package org.uee.write;

import org.uee.config.ExportConfig;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.util.ByteBuf;

/**
 * Base class for output writers.
 *
 * <p>A writer is a pure sink over the normalized model: it appends to a byte buffer and never reads
 * back, never mutates the model and never talks to the filesystem. The pipeline owns the buffer
 * lifecycle and flushes it to shards, which keeps writers independently testable — the whole format
 * contract can be verified with no game and no disk.
 *
 * <p>Writers are single-use per shard and are not required to be thread-safe.
 */
public abstract class Writer {

    /** Short format token, matching the {@code ExportConfig} constants. */
    public abstract String token();

    /** File extension without the dot. */
    public abstract String extension();

    /**
     * Relative output path for a shard of this writer.
     *
     * <p>Overridden by backends that need their own layout — the wiki projection writes into the
     * nested directory structure an importer expects, and the two JSON layouts use distinct
     * extensions ({@code .ndjson} and {@code .json}) precisely so they cannot overwrite each other.
     */
    public String outputPath(String namespace, ElementKind kind) {
        return namespace + "-" + kind.plural() + "." + extension();
    }

    /**
     * Whether this writer can carry the given element category.
     *
     * <p>Returning false makes the pipeline skip creating a shard at all, so unsupported categories
     * do not leave behind empty files.
     */
    public boolean supports(ElementKind kind) {
        return true;
    }

    protected final ExportConfig config;
    protected final ByteBuf out;

    protected Writer(ExportConfig config) {
        this.config = config;
        this.out = new ByteBuf(1 << 16);
    }

    /** Called before the first element of a shard. A shard carries exactly one element category. */
    public void beginShard(String namespace, ElementKind kind) {
    }

    /** Called after the last element of a shard, before the buffer is drained. */
    public void endShard() {
    }

    public void mod(ModElement e) {
    }

    public void item(ItemElement e) {
    }

    public void entity(EntityElement e) {
    }

    public void block(BlockElement e) {
    }

    public void recipe(RecipeElement e) {
    }

    public void generic(ElementKind kind, String namespace, String key, String nameZh, String nameEn,
            String[] listValues, String[] extra) {
    }

    public void debug(DebugSection s) {
    }

    /** True when this writer needs icon payloads; lets the collector skip rendering otherwise. */
    public boolean needsIcons() {
        return false;
    }

    /** True when this writer buffers the whole shard and can only be finalized at {@link #endShard()}. */
    public boolean isBatch() {
        return false;
    }

    /**
     * Drains everything appended so far and resets the buffer.
     *
     * <p>The returned array is owned by the caller. The pipeline calls this periodically so that a
     * large export streams to disk instead of accumulating in memory.
     */
    public byte[] drain() {
        byte[] data = out.toByteArray();
        out.reset();
        return data;
    }

    public boolean isEmpty() {
        return out.isEmpty();
    }

    /**
     * Whether a field should be written, per the configuration's projection.
     *
     * <p>Declared on the class rather than implemented per writer: a class method takes precedence
     * over an interface default, so every writer that implements {@link FieldVisitor} gets projection
     * without writing anything. A writer that emits its own field set — the wiki projection, whose
     * names are a contract with a third party — never calls this and is therefore unaffected.
     */
    public boolean has(String field) {
        return config.fields().has(field);
    }

    /**
     * Bytes buffered and not yet drained.
     *
     * <p>Exposed so the pipeline can decide whether a shard should roll over on size. The writer is
     * still a pure sink — this reports, it does not flush — and the alternative would be flushing to
     * measure, which defeats the point of buffering.
     */
    public int pendingBytes() {
        return out.length();
    }
}
