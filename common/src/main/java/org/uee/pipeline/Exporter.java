package org.uee.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import org.uee.spi.BytesSource;
import org.uee.delta.Fingerprint;
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
import org.uee.delta.Snapshot;
import org.uee.delta.DeltaPlan;
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

    /**
     * The namespace the analysis half lives under when it is kept apart.
     *
     * <p>A name of its own rather than a suffix on the data half's: the two are different artifacts with
     * different readers, and calling one {@code ueea} says that in a word instead of describing it in terms
     * of the other.
     */
    public static final String ANALYSIS_NAMESPACE = "ueea";

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

    /**
     * What the previous run produced, and what this one should do about it.
     *
     * <p>Null when no delta was asked for, which is the default and keeps a plain run's behaviour exactly
     * what it was: no temporary files, no hashing, no manifest.
     */
    private DeltaPlan delta;

    /** The snapshot this run is being measured against, kept for the report's benefit. */
    private Snapshot previousSnapshot;

    /** How many stale artifacts this run removed, for the summary. */
    private int deletedByDelta;

    /**
     * How many asset kinds the sweep asked for, or zero until it has been asked.
     *
     * <p>Cached because reading the declaration costs a state file read and a decompression, and the summary
     * wants the number once while assets arrive one at a time. Computed lazily rather than in the
     * constructor so a run without assets never reads the state file at all.
     */
    private int assetKinds;

    /** The same, for the root files, cached for the same reason. */
    private int assetRootFiles;

    /** Assets offered for copying, how many were written, and how many were recognised as unchanged. */
    private int assetCount;
    private int assetCopied;
    private int assetSkipped;
    private long assetBytes;
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
        beginDelta();
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

            // --- assets: files rather than records, so they are copied on their own ---
            if (config.assets()) {
                safe(() -> adapter.collectAssets(config, this));
            }

            // --- analysis: reads the facts collection just recorded ---
            if (!engine.analyses().isEmpty()) {
                runAnalyses(kinds);
            }
        } finally {
            close();
        }
        // Settled after every shard, and only on a run that reached the end: a run that threw has not
        // produced the set it would be describing, and a manifest claiming otherwise would make the next
        // run believe a state that was never reached.
        deletedByDelta = endDelta();
        long millis = (System.nanoTime() - started) / 1_000_000L;
        // The summary is built before the report so the report carries it; a caller that wants to know
        // what changed reads it from there rather than from a runner it would have to hold on to.
        String deltaText = deltaSummary();
        String assetText = assetSummary();
        return ExportReport.of(root, artifacts(), failureMessages(), millis, findings, deltaText,
                assetText);
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
            finishShard(shard);
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
        Path base = kind.isAnalysis() && config.analysisSeparate() ? analysisDir(root) : root;
        if (!target.isEmpty()) {
            base = base.resolve(TARGETS_DIR).resolve(sanitise(target));
        }
        Path dir = config.packagePerKind() ? base.resolve(kind.plural()) : base;
        if (config.shardByNamespace()) {
            dir = dir.resolve(namespace);
        }
        Writer writer = WriterFactory.create(format, config, pool);
        Shard created = new Shard(namespace, kind, format, target, dir, writer, pool,
                watermarkBytes(), config.dryRun(), config.maxFileBytes(), part,
                // Asked for a delta: hash what is written and write through a temporary, so the decision
                // at close can go either way. Off otherwise, so a plain run behaves exactly as before.
                config.delta() && !config.dryRun());
        shards.put(key, created);
        return created;
    }

    /**
     * Where the analysis half goes, given the data half's directory.
     *
     * <p>One rule, asked by both the pipeline that writes there and the command that reports the path. Two
     * copies of a path rule is the shape of defect where a report sends someone to a directory nothing was
     * written into.
     */
    public static Path analysisDir(Path dataRoot) {
        Path name = dataRoot.getFileName();
        return name == null ? dataRoot.resolve(ANALYSIS_NAMESPACE)
                : dataRoot.resolveSibling(ANALYSIS_NAMESPACE);
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

    /**
     * Copies a file into the output.
     *
     * <h2>Fingerprint first, which is what makes assets cheap</h2>
     *
     * <p>A record's bytes only exist once they have been generated, so a shard has to be written before it
     * can be compared and thrown away. An asset is the opposite: the bytes already exist at the source, so
     * hashing the source answers the question before anything is written. When the answer is "unchanged",
     * the run does one read and <b>no write at all</b> — which is the whole point of a delta, and it is
     * only available here because the copy is verbatim. A re-encoded texture would have to be produced
     * before it could be compared.
     *
     * <p>The cost is a second read of a changed file: once to hash, once to copy. That trade is the right
     * way round, since the common case in an incremental run is that nothing changed.
     *
     * <h2>Failure is per file</h2>
     *
     * <p>One unreadable texture does not stop the export; it is recorded as a failure and the next file is
     * tried. A withheld or truncated asset in a pack is a normal thing to find, and the design asks for
     * isolation at this granularity.
     */
    @Override
    public void asset(String relativePath, BytesSource source) {
        if (relativePath == null || source == null) {
            return;
        }
        assetCount++;
        try {
            String fingerprint;
            try (java.io.InputStream in = source.open()) {
                fingerprint = Fingerprint.of(in);
            }

            Path target = root.resolve(relativePath);
            boolean exists = Files.isRegularFile(target);

            if (config.dryRun()) {
                // The bytes are still read and hashed, so the fingerprint in the report is real, but
                // nothing is written and nothing is recorded -- a dry run describes what would happen.
                assetBytes += Files.exists(target) ? Files.size(target) : 0;
                return;
            }

            if (delta != null) {
                DeltaPlan.Verdict verdict = delta.record(relativePath, fingerprint);
                if (!delta.shouldWrite(verdict, exists)) {
                    // Unchanged and present: the bytes on disk are already these bytes. Nothing written.
                    assetSkipped++;
                    return;
                }
            }

            writeAsset(target, source);
            assetBytes += Files.size(target);
        } catch (Throwable t) {
            failures.add(new Failure(null, relativePath, t));
        }
    }

    /**
     * Streams the source to a temporary and moves it into place.
     *
     * <p>Through a temporary for the same reason a shard is: an interrupted copy must not leave a truncated
     * texture where a complete one was, since a consumer reading it would have no way to tell. The move is
     * what makes the file either the old one or the whole new one.
     */
    private void writeAsset(Path target, BytesSource source) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        try (java.io.InputStream in = source.open();
                java.io.OutputStream out = Files.newOutputStream(temporary,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[1 << 14];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        assetCopied++;
    }

    /**
     * Reads the previous snapshot, if a delta was asked for.
     *
     * <p>Read before anything is collected rather than lazily, because every shard's decision depends on
     * it and a manifest read half-way through would make the answer depend on which shard got there first.
     */
    private void beginDelta() throws IOException {
        if (!config.delta() || config.dryRun()) {
            return;
        }
        previousSnapshot = Snapshot.read(snapshotPath());
        for (String complaint : previousSnapshot.complaints()) {
            failures.add(new Failure(null, Snapshot.FILE_NAME, new IllegalStateException(complaint)));
        }
        delta = new DeltaPlan(previousSnapshot);
    }

    /**
     * Where the snapshot lives.
     *
     * <p>Inside the output directory and named with a leading dot, so it travels with the artifacts it
     * describes: a delta is only meaningful against the export it was taken from, and a consumer that
     * copied the directory somewhere else should get the same answers.
     */
    private Path snapshotPath() {
        return root.resolve(Snapshot.FILE_NAME);
    }

    /**
     * Closes a shard and settles what to do with it.
     *
     * <p>One method for both the roll-over path and the final close, because the decision is the same in
     * both cases and having it in two places is how a half-open part number gets a different verdict from
     * the one before it.
     *
     * <p>The order matters: the file is closed first, so the fingerprint covers everything written; then
     * the decision, which needs the fingerprint; then the rename or the discard. A shard that produced no
     * bytes is not an artifact at all and is left alone entirely.
     */
    private void finishShard(Shard s) throws IOException {
        s.close();
        if (delta == null || !s.tracksFingerprint() || s.bytes() == 0) {
            return;
        }
        String path = relativePath(s.file());
        String fingerprint = s.fingerprint();
        DeltaPlan.Verdict verdict = delta.record(path, fingerprint);
        if (delta.shouldWrite(verdict, Files.isRegularFile(s.file()))) {
            s.keep();
        } else {
            s.discard();
        }
    }

    /**
     * A shard's path relative to the output root, which is what the snapshot records.
     *
     * <p>Relative rather than absolute so a manifest is portable: an export copied to another machine, or
     * produced under a different mount point, must still recognise its own artifacts. Separators are
     * normalised to the forward slash the rest of the project uses, for the same reason.
     */
    private String relativePath(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        Path relative = absolute.startsWith(base) ? base.relativize(absolute) : absolute;
        return relative.toString().replace('\\', '/');
    }

    /**
     * Writes the new snapshot and removes what this run did not produce.
     *
     * <p>Called after every shard is settled, since a removal is by definition an artifact this run never
     * reached. Deletions happen before the manifest is written: a run interrupted mid-way leaves a manifest
     * describing the previous state and some files already gone, and the next run computes the removal
     * again rather than believing a state that does not exist.
     *
     * @return how many files were actually deleted
     */
    private int endDelta() throws IOException {
        if (delta == null || config.dryRun()) {
            return 0;
        }
        DeltaPlan.Report report = delta.finish(0);
        int deleted = 0;
        for (String path : report.removed()) {
            Path doomed = root.resolve(path);
            try {
                if (Files.deleteIfExists(doomed)) {
                    deleted++;
                }
            } catch (IOException e) {
                // Reported rather than fatal: failing to remove a stale artifact is a smaller problem
                // than abandoning a run that has already produced everything else.
                failures.add(new Failure(null, path, e));
            }
        }
        Snapshot.write(snapshotPath(), report.current(), describeRun());
        return deleted;
    }

    /** A line in the manifest's header block, so a person opening it knows what produced it. */
    private String describeRun() {
        return "written by a run of " + config.kinds().size() + " categories";
    }

    private void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException first = null;
        for (Shard s : shards.values()) {
            try {
                finishShard(s);
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

    /**
     * What this run did relative to the previous one, or {@code null} when no delta was asked for.
     *
     * <p>Exposed rather than folded into the report record, because the report is about artifacts and
     * failures and this is about the relationship between two runs. A caller that wants to tell the user
     * "eleven files changed" needs it; one that does not can ignore it.
     */
    private String deltaSummary() {
        if (delta == null) {
            return null;
        }
        DeltaPlan.Report report = delta.finish(deletedByDelta);
        String summary = report.summary(previousSnapshot);
        if (report.consumerWorkload(previousSnapshot) == 0) {
            // The headline case, said plainly: an incremental run that found nothing to do is a success
            // and should read like one rather than like a run that produced nothing.
            return summary + " (nothing to do)";
        }
        return summary;
    }

    /**
     * What happened to the assets.
     *
     * <p>Reported separately from the sharded artifacts because they are a different kind of thing: a shard
     * is a set of records this run generated, an asset is a file that was copied or recognised as already
     * being there. Folding them into one count would hide the only number a user cares about when assets
     * are on, which is how many files were moved.
     */
    private String assetSummary() {
        if (assetCount == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(assetCount).append(" assets, ").append(assetCopied).append(" copied");
        if (assetSkipped > 0) {
            sb.append(", ").append(assetSkipped).append(" unchanged");
        }
        sb.append(", ").append(assetBytes / 1024).append(" KiB");
        // The basis of the sweep, said out loud. A directory outside the declared kinds is not swept and
        // cannot be discovered, so the report names what was asked for rather than leaving a shortfall to
        // be puzzled over.
        sb.append(" over ").append(org.uee.asset.AssetSweep.describe(assetKindCount(),
                assetRootFileCount()));
        return sb.toString();
    }

    /**
     * The number of kinds the sweep covered, read once and remembered.
     *
     * <p>Asked here rather than per asset: the count is the same for all of them, and re-reading the state
     * file for each of several hundred files would be a decompression apiece for a number that does not
     * change.
     */
    private int assetKindCount() {
        if (assetKinds == 0) {
            assetKinds = org.uee.asset.AssetSweep.merge(
                    org.uee.Uee.declared(config, org.uee.asset.AssetSweep.TABLE)).size();
        }
        return assetKinds;
    }

    private int assetRootFileCount() {
        if (assetRootFiles == 0) {
            assetRootFiles = org.uee.asset.AssetSweep.mergeRootFiles(
                    org.uee.Uee.declared(config, org.uee.asset.AssetSweep.ROOT_FILE_TABLE)).size();
        }
        return assetRootFiles;
    }

    /** One element that could not be collected. */
    public record Failure(ElementKind kind, String registryName, Throwable error) {
        public String describe() {
            return (kind == null ? "?" : kind.singular()) + " " + registryName + ": "
                    + (error == null ? "unknown" : error.toString());
        }
    }
}
