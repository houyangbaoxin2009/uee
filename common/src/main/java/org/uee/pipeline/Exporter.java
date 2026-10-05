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
import org.uee.analysis.DeclarativeAnalysis;
import org.uee.datapack.DatapackCatalog;
import org.uee.datapack.StrategyDefinition;
import org.uee.datapack.TargetDefinition;
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

    /** Directory that holds datapack-target output, one sub-directory per target. */
    public static final String TARGETS_DIR = "targets";

    private final LoaderAdapter adapter;
    private final ExportConfig config;
    private final Path root;
    private final StringPool pool = new StringPool();
    private final Map<String, Shard> shards = new LinkedHashMap<>();
    /**
     * Next part number per logical shard, for size-based roll-over.
     *
     * <p>Separate from the shard map because it outlives any particular part: a rolled-over shard is
     * finished, but the next record still belongs to the same logical output.
     */
    private final Map<String, Integer> parts = new LinkedHashMap<>();

    /**
     * Shards that have been closed, kept so they still appear in the report.
     *
     * <p>A rolled-over shard leaves the live map — the next record starts a new part — and without
     * this list it would also vanish from the report, under-stating the run by exactly the files that
     * exist because of the size budget.
     */
    private final List<Shard> closedShards = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();
    /** Facts collection observed, for the analyses to read. */
    private final AnalysisContext context;
    private final AnalysisEngine engine;
    /** Datapack targets this run writes, resolved from the catalog. */
    private final List<TargetDefinition> targets;
    private long totalRecords;
    private int findings;
    private boolean closed;

    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root) {
        this(adapter, config, root, AnalysisEngine.standard());
    }

    /** Runs with a specific analysis set, for tests and for a caller that wants a subset. */
    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root, AnalysisEngine engine) {
        this(adapter, config, root, engine, DatapackCatalog.empty());
    }

    /**
     * Runs with a specific analysis set and datapack catalog.
     *
     * <p>The catalog is passed in rather than built here because it is the same catalog the other
     * interfaces resolved their flow against, and rebuilding it would risk the command surface and
     * the pipeline disagreeing about which definitions are active.
     */
    public Exporter(LoaderAdapter adapter, ExportConfig config, Path root, AnalysisEngine engine,
            DatapackCatalog catalog) {
        this.adapter = adapter;
        this.config = config;
        this.root = root;
        // Datapack rules are added to whatever engine the caller supplied, so a caller that wants a
        // subset of the built-in checks still gets the pack's rules.
        AnalysisEngine base = config.analyze() ? engine : AnalysisEngine.none();
        List<StrategyDefinition> strategies = config.datapacks()
                ? catalog.resolveStrategies(new ArrayList<>(config.strategies()))
                : List.of();
        this.engine = strategies.isEmpty() ? base : withDeclarative(base, strategies);
        this.targets = config.datapacks()
                ? catalog.resolveTargets(new ArrayList<>(config.targets()))
                : List.of();
        // The gathering half of the decoupling: collectors write facts here and never learn what
        // reads them.
        this.context = new AnalysisContext(adapter.mods(), adapter.containers(),
                adapter.mixinConfigs(), config.includePaths());
    }

    /**
     * Adds the datapack rules to an engine, once per stage.
     *
     * <p>The declarative check is registered twice because its rules divide along the same
     * pre/post-collection seam the built-in checks use — a rule about mod presence is meaningful
     * before collection, a rule about element counts is not. Registering both lets the engine
     * schedule each half correctly rather than forcing one stage on all of it.
     */
    private static AnalysisEngine withDeclarative(AnalysisEngine base,
            List<StrategyDefinition> strategies) {
        List<Analysis> all = new ArrayList<>(base.analyses());
        all.add(DeclarativeAnalysis.pre(strategies));
        all.add(DeclarativeAnalysis.post(strategies));
        return AnalysisEngine.of(all);
    }

    /**
     * Runs a complete export and returns the report.
     *
     * <p>With {@code dry_run} the run is planned but nothing is written: records still stream through
     * the writers so the artifact set and the record counts are real, but no file is opened. A caller
     * can therefore find out exactly what a configuration produces before committing to it, without
     * the write costs and without a temporary directory to clean up.
     */
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
        Set<ElementKind> wanted = analysisRecordsWanted(kinds);

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
        List<ExportReport.Artifact> list = new ArrayList<>(shards.size() + closedShards.size());
        for (Shard s : closedShards) {
            // Closed parts first, so a report reads in the order the files were produced.
            list.add(new ExportReport.Artifact(s.namespace(), s.kind().singular(), s.format(),
                    s.file(), s.bytes(), s.records(), s.target()));
        }
        for (Shard s : shards.values()) {
            list.add(new ExportReport.Artifact(s.namespace(), s.kind().singular(), s.format(),
                    s.file(), s.bytes(), s.records(), s.target()));
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

    /**
     * Which analysis records this run should write.
     *
     * <h2>Two ways to ask, and why both are needed</h2>
     *
     * <p>Naming an analysis category is the specific request: "give me the dependency graph, and
     * nothing else about the analysis". That is what a consumer that wants one table says.
     *
     * <p>Turning the analysis on without naming any is the general request, and it has to mean "write
     * all of them" — otherwise a category set like the default, which is pure collected content,
     * would run every check and then write none of their results. That would make the one-key command
     * quietly produce no diagnostics at all while reporting findings, which is the sort of silence
     * that reads as "nothing to report".
     */
    private static Set<ElementKind> analysisRecordsWanted(Set<ElementKind> kinds) {
        Set<ElementKind> named = EnumSet.noneOf(ElementKind.class);
        for (ElementKind k : kinds) {
            if (k.isAnalysis()) {
                named.add(k);
            }
        }
        return named.isEmpty() ? EnumSet.copyOf(ElementKind.ANALYSIS_KINDS) : named;
    }

    // ---------------------------------------------------------------- sink

    @Override
    public void mod(ModElement e) {
        route(GLOBAL_NAMESPACE, ElementKind.MOD, e.id(), null, w -> w.mod(e));
    }

    @Override
    public void item(ItemElement e) {
        route(e.namespace(), ElementKind.ITEM, e.registryName(), e.tags(), w -> w.item(e));
    }

    @Override
    public void entity(EntityElement e) {
        route(e.namespace(), ElementKind.ENTITY, e.registryName(), null, w -> w.entity(e));
    }

    @Override
    public void block(BlockElement e) {
        route(e.namespace(), ElementKind.BLOCK, e.registryName(), e.tags(), w -> w.block(e));
    }

    @Override
    public void recipe(RecipeElement e) {
        route(e.namespace(), ElementKind.RECIPE, e.id(), null, w -> w.recipe(e));
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        route(namespace, kind, key, listValues,
                w -> w.generic(kind, namespace, key, nameZh, nameEn, listValues, extra));
    }

    @Override
    public void debug(DebugSection section) {
        route(GLOBAL_NAMESPACE, ElementKind.DEBUG, section.name(), null, w -> w.debug(section));
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
    private void route(String namespace, ElementKind kind, String registryName, String[] tags,
            WriteAction action) {
        if (!config.acceptsNamespace(namespace) && !GLOBAL_NAMESPACE.equals(namespace)) {
            context.filter(kind, namespace);
            return;
        }
        // The tag filter is applied here, at the one place every record passes through, so it cannot
        // be forgotten for some category. Records without tags pass an exclude-only filter — there is
        // nothing to exclude them for — and fail an include filter, since they cannot be shown to
        // carry one.
        if (!config.acceptsTags(tags)) {
            context.filter(kind, namespace);
            return;
        }
        // The whole of what collection tells the analyses. Nothing downstream needs to know an
        // analysis exists. Observed once, before targets: a target is another copy of the same
        // record, not another record, so counting it twice would inflate the coverage report.
        context.observe(kind, namespace);

        offer(namespace, kind, action);

        // Targets are additive: the category's own output is unaffected, so enabling a target can
        // never silently truncate an export someone already depends on.
        for (TargetDefinition target : targets) {
            if (target.category() != kind || !target.accepts(registryName, namespace, tags)) {
                continue;
            }
            offer(namespace, kind, target.shardKey(), action);
        }
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
        offer(namespace, kind, "", action);
    }

    private void offer(String namespace, ElementKind kind, String target, WriteAction action) {
        for (String format : requestedFormats()) {
            if (!WriterFactory.supports(format, kind, config)) {
                continue;
            }
            Shard shard = shard(namespace, kind, format, target);
            action.apply(shard.writer());
            shard.countRecord();
            // Only the shards that received this record can have grown, so only those are candidates.
            maybeRoll(shard);
        }
        if (target.isEmpty()) {
            totalRecords++;
        }
        if ((totalRecords & 0xFF) == 0) {
            flushAll();
        }
    }

    /**
     * Closes a shard that has reached its byte budget, so the next record starts a new part.
     *
     * <p>Deliberately not "split into equally sized files": the check happens after a whole record, so
     * a part may overshoot by one record. That is the right trade — a file boundary must never fall
     * inside a record, and a consumer reading a part has to see whole records.
     */
    private void maybeRoll(Shard shard) {
        if (!shard.shouldRoll()) {
            return;
        }
        String logical = shard.logicalKey();
        shards.remove(shard.key());
        closedShards.add(shard);
        // The count is derived from the number of parts already finished for this output, so a
        // roll-over cannot produce a duplicate part number even if the map is touched elsewhere.
        parts.merge(logical, 1, Integer::sum);
        try {
            shard.close();
        } catch (IOException e) {
            failures.add(new Failure(shard.kind(), shard.namespace(), e));
        }
    }

    private Set<String> requestedFormats() {
        return config.formats();
    }

    private Shard shard(String namespace, ElementKind kind, String format, String target) {
        String logical = target.isEmpty()
                ? namespace + '|' + kind.plural() + '|' + format
                : namespace + '|' + kind.plural() + '|' + format + "|target:" + target;
        int part = parts.getOrDefault(logical, 0);
        // The key carries the part, so a rolled-over shard is a different shard while the logical key
        // stays the same. That keeps "what part are we on" separate from "what is this shard".
        String key = part == 0 ? logical : logical + "|part:" + part;
        Shard existing = shards.get(key);
        if (existing != null) {
            return existing;
        }
        // Layout has four independent choices, applied in order:
        //   0. a target's output goes under its own root, so it cannot collide with the category's
        //      own file names — a target is a shard of the same category, so the directories, not the
        //      file names, are what keep them apart;
        //   1. analysis output may live in a sibling directory, so the data half can be handed to one
        //      consumer (a wiki importer) without the diagnostics riding along;
        //   2. each category may get its own directory;
        //   3. shards may be grouped by namespace within that.
        Path base = kind.isAnalysis() && config.analysisSeparate()
                ? root.resolveSibling(root.getFileName() + "-analysis")
                : root;
        if (!target.isEmpty()) {
            base = base.resolve(TARGETS_DIR).resolve(sanitise(target));
        }
        Path dir = config.packagePerKind() ? base.resolve(kind.plural()) : base;
        if (config.shardByNamespace()) {
            dir = dir.resolve(namespace);
        }
        Writer writer = WriterFactory.create(format, config, pool);
        Shard created = new Shard(namespace, kind, format, target, dir, writer, pool,
                watermarkBytes(), config.dryRun(), config.maxFileBytes(), part);
        shards.put(key, created);
        return created;
    }

    /** Turns a target key into one safe path segment. */
    private static String sanitise(String target) {
        String cleaned = target.replace(':', '_').replace('/', '_').replace('\\', '_');
        return cleaned.isEmpty() ? "target" : cleaned;
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
