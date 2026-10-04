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
import org.uee.analysis.Analysis;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.AnalysisOutput;
import org.uee.analysis.Finding;
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

/**
 * Orchestrates a run: collection feeds sinks, sinks are sharded writers, writers stream to disk.
 *
 * <p>The two-phase shape lives here. {@code collectRegistries} is expected to run on the game's main
 * thread and finish fast — it only reads frozen state and produces plain records. Everything after
 * that (encoding, sharding, writing) is pure and could be parallelized per shard without touching
 * game state again.
 *
 * <p><b>Collection and analysis are decoupled.</b> Collection records what it saw into an
 * {@link AnalysisContext} and knows nothing about analyses; the analyses read that context and know
 * nothing about how it was gathered. The pipeline is what joins them, and it can run either half
 * alone:
 *
 * <ul>
 *   <li>collection only — {@code analyze = false}: data output, no diagnostics
 *   <li>analysis only — no collected categories selected: the pre-collection checks still run, which
 *       is possible because they read loader metadata rather than registries
 *   <li>both — the usual case, with the post-collection checks running once the observations exist
 * </ul>
 *
 * <p>Crash isolation is enforced at this level: a failure raised while collecting one element is
 * recorded and swallowed, so a single malformed entry cannot abort an export that is otherwise
 * fine.
 */
public final class Exporter implements ElementSink {

    /** Namespace used for collection-wide sections (mod list, environment debug) that shard by themselves. */
    public static final String GLOBAL_NAMESPACE = "_global";

    private final LoaderAdapter adapter;
    private final ExportConfig config;
    private final Path root;
    private final StringPool pool = new StringPool();
    private final Map<String, Shard> shards = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    /** Facts collection observed, for the analyses to read. */
    private final AnalysisContext context;
    private final AnalysisEngine engine;
    private long totalRecords;
    private int findings;
    private boolean closed;

    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root) {
        this(adapter, config, root, AnalysisEngine.standard());
    }

    /** Runs with a specific analysis set, for tests and for a caller that wants a subset. */
    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root, AnalysisEngine engine) {
        this.adapter = adapter;
        this.config = config;
        this.root = root;
        this.engine = config.analyze() ? engine : AnalysisEngine.none();
        // The gathering half of the decoupling: collectors write facts here and never learn what
        // reads them.
        this.context = new AnalysisContext(adapter.mods(), adapter.containers(),
                adapter.mixinConfigs(), config.includePaths());
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

            // --- collection: registries and datapacks, minus the analysis-only categories ---
            Set<ElementKind> registryKinds = EnumSet.copyOf(kinds);
            registryKinds.remove(ElementKind.MOD);
            registryKinds.remove(ElementKind.DEBUG);
            registryKinds.removeAll(ElementKind.ANALYSIS_KINDS);
            if (!registryKinds.isEmpty()) {
                safe(() -> adapter.collectRegistries(config, registryKinds, this));
                safe(() -> adapter.collectDatapacks(config, registryKinds, this));
            }

            // --- analysis: reads the facts collection just recorded ---
            if (!engine.analyses().isEmpty()) {
                runAnalyses(kinds);
            }
        } finally {
            close();
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        return new ExportReport(root, artifacts(), failureMessages(), totalRecords, millis, findings);
    }

    /**
     * Runs the analyses and routes their output.
     *
     * <p>The stage boundary is honoured here rather than inside the analyses: a check that needs the
     * registries to have been walked is skipped when nothing was walked, because running it anyway
     * would report "nothing found" when the truth is "not asked".
     *
     * <p>Records go to the sink under the category the analysis named, so they land in the same
     * sharded output as everything else. Findings go to both the sink and a local count, so a caller
     * can report how many problems the run found without re-reading the artifacts.
     */
    private void runAnalyses(Set<ElementKind> kinds) {
        // Analysis records are wanted when the run asked for any analysis category. Requesting only
        // data still runs the analyses whose findings matter, but discards their records.
        Set<ElementKind> wanted = EnumSet.noneOf(ElementKind.class);
        for (ElementKind k : kinds) {
            if (k.isAnalysis()) {
                wanted.add(k);
            }
        }

        AnalysisOutput output = new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                findings++;
                if (!wanted.contains(ElementKind.CONFLICT)) {
                    return;
                }
                generic(ElementKind.CONFLICT, GLOBAL_NAMESPACE,
                        "finding:" + finding.key(), finding.message(), null,
                        new String[0],
                        new String[] {
                                "findingKind", finding.kind(),
                                "severity", finding.severity().token(),
                                "subject", finding.subject() == null ? "" : finding.subject(),
                                "detail", String.join(",", finding.detail())});
            }

            @Override
            public void record(ElementKind kind, String namespace, String key,
                    String[] listValues, String[] extra) {
                if (!wanted.contains(kind)) {
                    return;
                }
                generic(kind, namespace, key, null, null, listValues, extra);
            }
        };

        Analysis.Stage stage = context.collected()
                ? Analysis.Stage.POST_COLLECTION
                : Analysis.Stage.PRE_COLLECTION;
        engine.run(stage, context, output);
    }

    /** Findings the analyses produced, whether or not they were written out. */
    public int findings() {
        return findings;
    }

    /** The facts collection observed; available after {@link #run()}. */
    public AnalysisContext context() {
        return context;
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
        route(GLOBAL_NAMESPACE, ElementKind.MOD, w -> w.mod(e));
    }

    @Override
    public void item(ItemElement e) {
        route(e.namespace(), ElementKind.ITEM, w -> w.item(e));
    }

    @Override
    public void entity(EntityElement e) {
        route(e.namespace(), ElementKind.ENTITY, w -> w.entity(e));
    }

    @Override
    public void block(BlockElement e) {
        route(e.namespace(), ElementKind.BLOCK, w -> w.block(e));
    }

    @Override
    public void recipe(RecipeElement e) {
        route(e.namespace(), ElementKind.RECIPE, w -> w.recipe(e));
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        route(namespace, kind,
                w -> w.generic(kind, namespace, key, nameZh, nameEn, listValues, extra));
    }

    @Override
    public void debug(DebugSection section) {
        route(GLOBAL_NAMESPACE, ElementKind.DEBUG, w -> w.debug(section));
    }

    @Override
    public void failure(ElementKind kind, String registryName, Throwable error) {
        failures.add(new Failure(kind, registryName, error));
    }

    /**
     * Routes one record: records the observation, then offers it to the writers.
     *
     * <p>Every category passes through here, which turns "every record is observed" into a property of
     * the code rather than a discipline. Before this, the typed entry points and the generic one each
     * carried their own filter check, and the gathering half of the decoupling existed in only one of
     * them — a new category would have silently stopped feeding the analyses.
     */
    private void route(String namespace, ElementKind kind, WriteAction action) {
        if (!config.acceptsNamespace(namespace) && !GLOBAL_NAMESPACE.equals(namespace)) {
            context.filter(kind, namespace);
            return;
        }
        // The whole of what collection tells the analyses. Nothing downstream needs to know an
        // analysis exists.
        context.observe(kind, namespace);
        offer(namespace, kind, action);
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
        // Layout has three independent choices, applied in order:
        //   1. analysis output may live in a sibling directory, so the data half can be handed to one
        //      consumer (a wiki importer) without the diagnostics riding along;
        //   2. each category may get its own directory;
        //   3. shards may be grouped by namespace within that.
        Path base = kind.isAnalysis() && config.analysisSeparate()
                ? root.resolveSibling(root.getFileName() + "-analysis")
                : root;
        Path dir = config.packagePerKind() ? base.resolve(kind.plural()) : base;
        if (config.shardByNamespace()) {
            dir = dir.resolve(namespace);
        }
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
