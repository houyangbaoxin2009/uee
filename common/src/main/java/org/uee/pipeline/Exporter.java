package org.uee.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uee.config.ExportConfig;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.util.StringPool;
import org.uee.write.Writer;
import org.uee.write.WriterFactory;
import org.uee.write.WriterFactory;
import org.uee.write.WriterFactory;

/**
 * Orchestrates an export: collection feeds sinks, sinks are sharded writers, writers stream to disk.
 *
 * <p>The two-phase shape lives here. {@code collectRegistries} is expected to run on the game's main
 * thread and finish fast — it only reads frozen state and produces plain records. Everything after
 * that (encoding, sharding, writing) is pure and could be parallelized per shard without touching
 * game state again.
 *
 * <p>Crash isolation is enforced at this level: a failure raised while collecting one element is
 * recorded and swallowed, so a single malformed entry cannot abort an export that is otherwise
 * fine.
 */
public final class Exporter implements ElementSink {

    /** Namespace used for collection-wide sections (mod list, environment debug) that shard by themselves. */
    public static final String GLOBAL_NAMESPACE = "_global";

    /** Categories produced by the diagnostic analysis rather than by a registry walk. */
    private static final Set<ElementKind> ANALYSIS_KINDS =
            Set.of(ElementKind.NAMESPACE, ElementKind.DEPENDENCY, ElementKind.CONFLICT,
                    ElementKind.MIXIN);

    private static boolean wantsAnalysis(Set<ElementKind> kinds) {
        for (ElementKind k : ANALYSIS_KINDS) {
            if (kinds.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private final LoaderAdapter adapter;
    private final ExportConfig config;
    private final Path root;
    private final StringPool pool = new StringPool();
    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private long totalRecords;
    private boolean closed;

    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root) {
        this.adapter = adapter;
        this.config = config;
        this.root = root;
    }

    /** Runs a complete export and returns the report. */
    public ExportReport run() throws IOException {
        Set<ElementKind> kinds = config.kinds();
        long started = System.nanoTime();
        try {
            if (kinds.contains(ElementKind.MOD)) {
                collectMods();
            }
            if (kinds.contains(ElementKind.DEBUG)) {
                collectDebug();
            }
            Set<ElementKind> registryKinds = EnumSet.copyOf(kinds);
            registryKinds.remove(ElementKind.MOD);
            registryKinds.remove(ElementKind.DEBUG);
            registryKinds.removeAll(ANALYSIS_KINDS);
            if (!registryKinds.isEmpty()) {
                safe(() -> adapter.collectRegistries(config, registryKinds, this));
                safe(() -> adapter.collectDatapacks(config, registryKinds, this));
            }
            if (wantsAnalysis(kinds)) {
                // Runs last: the analysis is derived from the mod list and the container files, and
                // benefits from the observed-namespace set that registry collection just filled in.
                safe(() -> adapter.collectDebugRecords(config, this));
            }
        } finally {
            close();
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new ExportReport(root, artifacts(), failureMessages(), totalRecords, millis);
    }

    private List<ExportReport.Artifact> artifacts() {
        List<ExportReport.Artifact> list = new ArrayList<>(shards.size());
        for (Shard s : shards.values()) {
            list.add(new ExportReport.Artifact(s.namespace(), s.kind().singular(), s.format(),
                    s.file(), s.bytes(), s.records()));
        }
        return list;
    }

    private List<String> failureMessages() {
        List<String> msgs = new ArrayList<>(failures.size());
        for (Failure f : failures) {
            msgs.add(f.describe());
        }
        return msgs;
    }

    private void collectMods() {
        for (ModElement m : adapter.mods()) {
            if (config.excludeMods().contains(m.id())) {
                continue;
            }
            mod(m);
        }
    }

    private void collectDebug() {
        for (DebugSection s : adapter.debugSections()) {
            debug(s);
        }
    }

    private List<Shard> allShards() {
        return new ArrayList<>(shards.values());
    }

    // ---------------------------------------------------------------- sink

    @Override
    public void mod(ModElement e) {
        offer(GLOBAL_NAMESPACE, ElementKind.MOD, w -> w.mod(e));
    }

    @Override
    public void item(ItemElement e) {
        if (!config.acceptsNamespace(e.namespace())) {
            return;
        }
        offer(e.namespace(), ElementKind.ITEM, w -> w.item(e));
    }

    @Override
    public void entity(EntityElement e) {
        if (!config.acceptsNamespace(e.namespace())) {
            return;
        }
        offer(e.namespace(), ElementKind.ENTITY, w -> w.entity(e));
    }

    @Override
    public void block(BlockElement e) {
        if (!config.acceptsNamespace(e.namespace())) {
            return;
        }
        offer(e.namespace(), ElementKind.BLOCK, w -> w.block(e));
    }

    @Override
    public void recipe(RecipeElement e) {
        if (!config.acceptsNamespace(e.namespace())) {
            return;
        }
        offer(e.namespace(), ElementKind.RECIPE, w -> w.recipe(e));
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        if (!config.acceptsNamespace(namespace) && !GLOBAL_NAMESPACE.equals(namespace)) {
            return;
        }
        offer(namespace, kind, w -> w.generic(kind, namespace, key, nameZh, nameEn, listValues, extra));
    }

    @Override
    public void debug(DebugSection section) {
        offer(GLOBAL_NAMESPACE, ElementKind.DEBUG, w -> w.debug(section));
    }

    @Override
    public void failure(ElementKind kind, String registryName, Throwable error) {
        failures.add(new Failure(kind, registryName, error));
    }

    // ---------------------------------------------------------------- internals

    /** A unit of work applied to one shard's writer. */
    private interface WriteAction {
        void apply(Writer w);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }

    /**
     * Routes one record to every requested format of a shard, creating the shard lazily.
     *
     * <p>The record is offered to each writer in turn, so a single collection pass feeds every
     * output format — no second traversal, no retained model.
     */
    private void offer(String namespace, ElementKind kind, WriteAction action) {
        for (String format : requestedFormats()) {
            if (!WriterFactory.supports(format, kind, config)) {
                continue;
            }
            Shard shard = shard(namespace, kind, format);
            action.apply(shard.writer());
            shard.countRecord();
        }
        totalRecords++;
        if ((totalRecords & 0xFF) == 0) {
            flushAll();
        }
    }

    private Set<String> requestedFormats() {
        return config.formats();
    }

    private Shard shard(String namespace, ElementKind kind, String format) {
        String key = namespace + '|' + kind.plural() + '|' + format;
        Shard existing = shards.get(key);
        if (existing != null) {
            return existing;
        }
        Path dir = config.shardByNamespace() ? root.resolve(namespace) : root;
        Writer writer = WriterFactory.create(format, config, pool);
        Shard created = new Shard(namespace, kind, format, dir, writer, pool, watermarkBytes());
        shards.put(key, created);
        return created;
    }

    private long watermarkBytes() {
        // Keep each shard buffer small; a bounded watermark is what stops live shards from eating
        // the whole heap when a pack has hundreds of namespaces.
        return Math.max(1 << 16, Math.min(config.memoryLimitBytes() / 16, 8L << 20));
    }

    private void flushAll() {
        for (Shard s : shards.values()) {
            try {
                s.maybeFlush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void safe(ThrowingRunnable r) {
        try {
            r.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            // A loader adapter blowing up must degrade to a reported failure, never to a lost export.
            failures.add(new Failure(null, "<adapter>", e));
        }
    }

    private void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException first = null;
        for (Shard s : shards.values()) {
            try {
                s.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** One element that could not be collected. */
    public record Failure(ElementKind kind, String registryName, Throwable error) {
        public String describe() {
            return (kind == null ? "?" : kind.singular()) + " " + registryName + ": "
                    + (error == null ? "unknown" : error.toString());
        }
    }
}
