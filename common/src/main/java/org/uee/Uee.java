package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.Finding;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.config.Tokens;
import org.uee.datapack.DatapackCatalog;
import org.uee.datapack.DatapackSource;
import org.uee.datapack.FlowDefinition;
import org.uee.globalpack.GlobalPackDiscovery;
import org.uee.globalpack.GlobalPackPolicy;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.pipeline.UeeJob;
import org.uee.pipeline.UeeJobs;
import org.uee.spi.LoaderAdapter;

/**
 * The programmatic interface, and the funnel the other two interfaces go through.
 *
 * <p>There are three ways to drive UEE, and they differ only in how a run is described:
 *
 * <ul>
 *   <li><b>function API</b> — this class: build a config, call {@link #export}, read the report
 *   <li><b>command</b> — {@code /uee ...}, parsed into a partial {@link ConfigFile} and resolved here
 *   <li><b>config file</b> — {@code uee.data.tie}, loaded as a partial {@code ConfigFile} too
 * </ul>
 *
 * <p>All three converge on {@link ConfigResolver}, so a setting means the same thing whichever way it
 * was expressed, and there is one place to test. This class deliberately has no Minecraft dependency,
 * so it compiles and is exercised by the harnesses without a game present.
 */
public final class Uee {

    /** The mod id, identical on all four loaders so automation can locate artifacts by a fixed path. */
    public static final String MOD_ID = "uee";

    /** The human-readable name, used in command output and reports. */
    public static final String NAME = "Universal Element Exporter";

    private static volatile LoaderAdapter adapter;

    private Uee() {
    }

    /** Called once by a loader's entry point, after its adapter is ready. */
    public static void bind(LoaderAdapter loaderAdapter) {
        adapter = loaderAdapter;
    }

    /**
     * Where failures outside a run are reported.
     *
     * <p>A loader can install its own log sink. The default writes to stderr, which is the honest
     * choice for a core that has no logger: a failure during a loader hook, such as a global datapack
     * that would not register, otherwise disappears.
     */
    private static volatile java.util.function.BiConsumer<String, Throwable> failureReporter =
            (message, error) -> {
                System.err.println("[" + MOD_ID + "] " + message);
                if (error != null) {
                    error.printStackTrace();
                }
            };

    /** Installs a log sink for failures raised outside a run. */
    public static void onFailure(java.util.function.BiConsumer<String, Throwable> reporter) {
        if (reporter != null) {
            failureReporter = reporter;
        }
    }

    /**
     * Reports a failure that happened outside a run.
     *
     * <p>Needed because the pipeline's failure list only exists during an export, and some things —
     * a loader's pack registration, for one — happen before any export does.
     */
    public static void reportFailure(String message, Throwable error) {
        failureReporter.accept(message, error);
    }

    /** The bound adapter, or {@code null} when no loader has initialised yet. */
    public static LoaderAdapter adapter() {
        return adapter;
    }

    // ---------------------------------------------------------------- function API

    /**
     * Runs an export with the given configuration.
     *
     * <p>The datapack catalog is resolved here and handed to the pipeline. Omitting it would mean a
     * run described by a datapack target or strategy quietly exported nothing extra, which is the
     * kind of silence that makes a feature look broken rather than misconfigured.
     *
     * @param config the resolved configuration
     * @param root where artifacts are written
     * @return what was produced, including any per-element failures and the finding count
     * @throws IllegalStateException when no adapter is bound
     */
    public static ExportReport export(ExportConfig config, Path root) throws IOException {
        return export(config, root, AnalysisEngine.standard(), catalog());
    }

    /** Runs an export with a specific analysis set, for a caller that wants a subset of the checks. */
    public static ExportReport export(ExportConfig config, Path root, AnalysisEngine engine)
            throws IOException {
        return export(config, root, engine, catalog());
    }

    /** Runs an export against an already-resolved catalog. */
    public static ExportReport export(ExportConfig config, Path root, AnalysisEngine engine,
            DatapackCatalog catalog) throws IOException {
        return new Exporter(requireAdapter(), config, root, engine, catalog).run();
    }

    // ---------------------------------------------------------------- jobs (the function interface)

    /**
     * The job runner behind the function-facing commands.
     *
     * <p>A single instance, because exports are deliberately serialised: two runs writing one bundle
     * would corrupt it rather than finish it faster.
     */
    private static final UeeJobs JOBS = new UeeJobs();

    /** The job runner. */
    public static UeeJobs jobs() {
        return JOBS;
    }

    /**
     * Starts an export on a worker thread and returns immediately.
     *
     * <h2>Why this exists</h2>
     *
     * <p>An export invoked from an MC function runs inside a tick. Doing it synchronously there is not
     * just slow — a large pack can hold the tick past the watchdog limit and take the server down. So
     * a function-facing run is <em>started</em>, and the caller polls {@link UeeJobs#unfinished()}.
     *
     * <h2>The precondition, which is checked rather than assumed</h2>
     *
     * <p>This works because collection reads frozen registries, and the adapter has to say so through
     * {@link LoaderAdapter#supports}. An adapter that has not declared
     * {@link LoaderAdapter#CAP_REGISTRY_FROZEN} may not be read from a worker thread, and pretending
     * otherwise would trade a slow tick for intermittent corruption. In that case this refuses and the
     * caller is told to use the synchronous command instead.
     *
     * <p>Everything that touches loader state is resolved on the calling thread; only the registry walk
     * and the writing happen off it.
     *
     * @param config the resolved configuration
     * @param root where artifacts are written
     * @return the started job, for polling by id
     * @throws IllegalStateException when no adapter is bound, or the adapter cannot be read off-thread
     */
    public static UeeJob startExport(ExportConfig config, Path root) {
        return startExport(config, root, null);
    }

    /**
     * Starts an export and arranges to be told when it finishes.
     *
     * <p>The notifier is opaque here — a command passes a lambda that resolves its own recipient — so
     * the pipeline never learns what a player or a chat channel is.
     */
    public static UeeJob startExport(ExportConfig config, Path root,
            java.util.function.Consumer<UeeJob> whenFinished) {
        LoaderAdapter current = requireAdapter();
        if (!current.supports(LoaderAdapter.CAP_REGISTRY_FROZEN)) {
            throw new IllegalStateException("the " + current.info().loader()
                    + " adapter has not declared '" + LoaderAdapter.CAP_REGISTRY_FROZEN
                    + "', so an export cannot run off the server thread; use /uee export instead");
        }
        // Resolved here, on the caller's thread, because reading the mod list and scanning containers
        // is loader state. Whether the catalog is actually used is the configuration's business, and
        // the pipeline already honours that flag — duplicating the check here would be a second place
        // for it to be wrong.
        Exporter exporter = new Exporter(current, config, root, AnalysisEngine.standard(), catalog());
        return JOBS.submit(config, root, () -> {
            try {
                return exporter.run();
            } catch (IOException e) {
                throw new IllegalStateException("export failed: " + e.getMessage(), e);
            }
        }, whenFinished);
    }

    /**
     * Stops the job runner and waits briefly for the running export.
     *
     * <p>Called when the game is shutting down, so a partly written bundle is not left behind on a
     * normal exit.
     */
    public static void shutdownJobs() {
        JOBS.shutdown();
    }

    /**
     * Resolves a layered configuration without running anything.
     *
     * <p>Exposed because both other interfaces need it before they can report what they are about to
     * do, and a caller of the function API generally wants the same preview.
     *
     * @param fromFile the config file, or {@code null}
     * @param fromCaller the command line or programmatic overrides, or {@code null}
     */
    public static ConfigResolver.Resolved resolve(ConfigFile fromFile, ConfigFile fromCaller) {
        return ConfigResolver.resolve(fromFile, fromCaller, ExportConfig.builder().build());
    }

    /** Resolves the built-in defaults with no layering, which is what the one-key command runs. */
    public static ExportConfig defaults() {
        return ConfigResolver.resolveDefaults(ExportConfig.builder().build()).config();
    }

    /** The config file path for the bound adapter's game directory, or {@code null} when unbound. */
    public static Path configFile() {
        LoaderAdapter current = adapter;
        if (current == null || current.info() == null || current.info().gameDirectory() == null) {
            return null;
        }
        return ConfigResolver.configFile(Path.of(current.info().gameDirectory()));
    }

    /**
     * The interactive configuration, resolved from the file and any overrides.
     *
     * <p>The layers, lowest precedence first: the config file, then a named flow if the caller asked
     * for one, then whatever the caller said explicitly. Putting the flow where the config file sits
     * means a flow is a starting point the caller can still override rather than a separate mode — so
     * {@code /uee flow wiki export items} is "the wiki flow, but only items", and both interfaces
     * continue to share one resolution path.
     *
     * @throws IllegalStateException when no adapter is bound
     */
    public static ConfigResolver.Resolved resolveForRun(ConfigFile overrides) throws IOException {
        return resolveForRun(List.of(), overrides);
    }

    // ---------------------------------------------------------------- the portable state

    /**
     * The keys that control persistence, which must therefore never be persisted.
     *
     * <p>{@code persist} says what is kept and {@code user_dir} says where it is kept. If either could be
     * written into the state file, a user who set one wrongly would have made it permanent through the very
     * mechanism it controls — and the surface that would let them correct it reads that same file, so there
     * would be no way back. Excluding them is a rule of the design rather than a default.
     */
    public static final java.util.Set<String> PERSISTENCE_CONTROLS = java.util.Set.of("persist",
            "user_dir");

    /**
     * The keys a configuration asks to be kept, minus the ones that may not be.
     *
     * <p>Names this build does not recognise are dropped rather than stored: a state file holding a key
     * nothing understands fails its own validation when read back, which would turn a typo into a file that
     * no longer loads at all.
     */
    public static List<String> persistableKeys(ExportConfig config) {
        if (config == null || config.persist().isEmpty()) {
            return List.of();
        }
        List<String> known = ConfigFile.renderableKeys();
        List<String> out = new java.util.ArrayList<>(config.persist().size());
        for (String key : config.persist()) {
            if (key == null || PERSISTENCE_CONTROLS.contains(key) || !known.contains(key)) {
                continue;
            }
            out.add(key);
        }
        return out;
    }

    /** Where the portable state lives, given what a configuration says about it. */
    public static Path userStateFile(ExportConfig config) {
        String configured = config == null || config.userDir() == null
                ? null : config.userDir().toString();
        return org.uee.state.UserStore.fileIn(org.uee.state.UserStore.resolveDir(configured));
    }

    /**
     * Exports once, unprompted, if the configuration asks for it.
     *
     * <p>Off unless asked, and the check is the first thing the method does — a tool that writes files on
     * every launch without being asked is a tool people remove. The point of the option is the case it
     * serves: someone who moves between packs and wants the export to simply be there.
     *
     * <p>Submitted as a job rather than run here, for the same reason the command's short form starts a job:
     * an export reads registries and writes files, and doing that inside a startup callback would hold up
     * whatever called it.
     *
     * @return the job identifier, or zero when nothing was started
     */
    public static int maybeAutoRun() {
        try {
            ExportConfig config = resolveForRun(ConfigFile.empty()).config();
            if (!config.autoRun()) {
                // The default. Nothing is read, nothing is written, and the caller does not have to know
                // whether a state file exists at all.
                return 0;
            }
            Path root = config.outputDir();
            UeeJob job = jobs().submit(config, root, () -> {
                try {
                    return export(config, root);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return job.id();
        } catch (Throwable t) {
            // A startup that cannot read its own configuration should not stop the game from starting. The
            // export simply does not happen, and the next thing the user does will report why.
            return 0;
        }
    }

    /**
     * The portable settings as a layer, or null when there are none.
     *
     * <p>Parsed with the same parser as every other configuration text, so a state file cannot describe
     * something the syntax does not allow and a hand-edited one is validated rather than trusted. Damage is
     * reported through the complaint list rather than thrown: a state file is not worth failing a run over,
     * and losing accumulated tables should be visible without stopping an export that could still happen.
     */
    private static ConfigFile portableLayer(List<String> complaints) {
        try {
            LoaderAdapter current = adapter;
            if (current == null) {
                return null;
            }
            Path gameDir = Path.of(current.info().gameDirectory());
            ExportConfig base = ExportConfig.builder()
                    .outputDir(ExportConfig.defaultOutputDir(gameDir)).build();
            org.uee.state.UserStore store = org.uee.state.UserStore.read(userStateFile(base));
            complaints.addAll(store.complaints());
            String text = org.uee.state.SettingsMember.textOf(store);
            if (text.isBlank()) {
                return null;
            }
            return ConfigFile.parse(text);
        } catch (Throwable t) {
            complaints.add("the portable state could not be read: " + t);
            return null;
        }
    }

    /**
     * Keeps the declared settings in the portable state.
     *
     * <p>Called after a setting is made, so that "declare it once and it sticks" is what actually happens
     * rather than something the user has to remember to do. Only the whitelisted keys are written, which is
     * why a setting that is not on the list still changes the session and nothing else.
     *
     * @return how many keys were kept, zero when nothing is declared to stick
     */
    public static int saveUserState(ExportConfig resolved) throws IOException {
        List<String> keys = persistableKeys(resolved);
        Path file = userStateFile(resolved);
        org.uee.state.UserStore existing = org.uee.state.UserStore.read(file);
        java.util.Map<String, byte[]> members = new java.util.LinkedHashMap<>(existing.members());
        if (keys.isEmpty()) {
            // Nothing is declared to stick, so the settings member is removed rather than left stale: a file
            // still describing an older choice would apply it on the next run regardless of the whitelist.
            members.remove(org.uee.state.UserStore.SETTINGS);
        } else {
            members.put(org.uee.state.UserStore.SETTINGS, org.uee.state.SettingsMember.encode(
                    ConfigFile.renderKeys(resolved, keys)));
        }
        org.uee.state.UserStore.write(file, members);
        return keys.size();
    }

    /**
     * Resolves with extra layers between the file and the caller's own description.
     *
     * <p>Those middle layers are what a command surface accumulates: settings made earlier in the
     * session that should apply to this run too. They go above the file — an explicit setting beats
     * the file — and below the caller's own arguments, so a per-run override still wins.
     */
    public static ConfigResolver.Resolved resolveForRun(List<ConfigFile> sessionLayers,
            ConfigFile overrides) throws IOException {
        LoaderAdapter current = requireAdapter();
        Path gameDir = Path.of(current.info().gameDirectory());
        DatapackCatalog catalog = catalog();

        List<ConfigFile> layers = new java.util.ArrayList<>(6);
        List<String> portableComplaints = new java.util.ArrayList<>(1);
        // The portable state sits below the instance's own file: it is a baseline that follows the person,
        // and a pack that says something different is more specific than that. Read here rather than in a
        // loader, so every interface -- command, programmatic, automatic -- gets it through the one
        // resolution path there is.
        ConfigFile portable = portableLayer(portableComplaints);
        if (portable != null) {
            layers.add(portable);
        }
        layers.add(ConfigFile.readFrom(ConfigResolver.configDir(gameDir)));
        if (sessionLayers != null) {
            layers.addAll(sessionLayers);
        }
        if (overrides != null && overrides.flow() != null) {
            FlowDefinition flow = catalog.flow(overrides.flow());
            if (flow == null) {
                // Left unexpanded and reported: the rest of what the caller asked for still runs.
                layers.add(ConfigFile.builder().build());
                return withCatalogProblems(ConfigResolver.resolve(
                        ExportConfig.builder()
                                .outputDir(ExportConfig.defaultOutputDir(gameDir)).build(),
                        layers), catalog, List.of("flow '" + overrides.flow()
                                + "' was requested but no datapack defines it"));
            }
            layers.add(flow.config());
        }
        layers.add(overrides);

        ConfigResolver.Resolved resolved = ConfigResolver.resolve(
                ExportConfig.builder().outputDir(ExportConfig.defaultOutputDir(gameDir)).build(),
                layers);
        return withCatalogProblems(resolved, catalog, portableComplaints);
    }

    /**
     * A resolution result with the catalog's own problems appended as warnings.
     *
     * <p>Folded in here so a user hears about a malformed datapack through whatever interface they
     * happened to use, rather than only through the one that lists datapacks.
     */
    private static ConfigResolver.Resolved withCatalogProblems(ConfigResolver.Resolved resolved,
            DatapackCatalog catalog, List<String> extraWarnings) {
        boolean none = extraWarnings == null || extraWarnings.isEmpty();
        if (catalog.problems().isEmpty() && none) {
            return resolved;
        }
        java.util.List<String> warnings = new java.util.ArrayList<>(resolved.warnings());
        for (var problem : catalog.problems()) {
            warnings.add("datapack: " + problem.message());
        }
        if (!none) {
            warnings.addAll(extraWarnings);
        }
        return new ConfigResolver.Resolved(resolved.config(), warnings, resolved.errors());
    }

    /**
     * The datapack definitions currently available.
     *
     * <p>Built on demand from wherever definitions are found. Cached per call rather than held in a
     * field, so a datapack added while the game is running is picked up on the next use instead of
     * requiring a restart — and so a test can build a catalog without a game.
     */
    public static DatapackCatalog catalog() {
        LoaderAdapter current = adapter;
        if (current == null) {
            return DatapackCatalog.empty();
        }
        DatapackCatalog catalog = current.info().gameDirectory().isEmpty()
                ? DatapackCatalog.empty()
                : DatapackCatalog.of(datapackSources(Path.of(current.info().gameDirectory()), current));
        // Function-defined flows come from the loader, which is the only thing that can enumerate a
        // datapack's functions. Merging them here means one name reaches a flow of either kind.
        catalog.addFunctionFlows(current.functionFlows());
        return catalog;
    }

    /**
     * Names the flow functions a caller would write, for the command surface and for documentation.
     *
     * <p>Exposed so a user can be told the convention rather than having to infer it from an empty
     * list: {@code data/<namespace>/function/uee/<name>.mcfunction}.
     */
    public static String functionFlowPattern() {
        return "data/<namespace>/function/" + org.uee.datapack.FunctionFlow.FUNCTION_DIR
                + "/<name>.mcfunction";
    }

    /**
     * Where definitions are looked for, in increasing order of precedence.
     *
     * <p>Worlds are not listed because UEE has no notion of a current save at this point; a world's
     * own packs are registered through the loader's resource system and reach UEE the same way a mod's
     * do. That is a limitation worth stating rather than a design choice.
     */
    private static List<DatapackSource> datapackSources(Path gameDir, LoaderAdapter current) {
        List<DatapackSource> sources = new java.util.ArrayList<>(4);
        for (String id : current.datapackIds()) {
            sources.add(DatapackSource.of(id, DatapackSource.Origin.MOD));
        }
        Path global = globalDir(gameDir, current);
        if (global != null) {
            for (GlobalPackDiscovery.Pack pack : GlobalPackDiscovery.packs(global)) {
                sources.add(new DatapackSource(pack.name(), pack.path(), DatapackSource.Origin.GLOBAL));
            }
        }
        return sources;
    }

    /**
     * Decides the global-datapack policy for this instance.
     *
     * <p>Exposed because the answer is not derivable from the config alone: whether UEE provides
     * global datapacks depends on which mods are loaded, and a user needs to be able to ask without
     * running an export.
     */
    public static GlobalPackPolicy globalPackPolicy(ExportConfig config) {
        LoaderAdapter current = adapter;
        if (current == null) {
            return GlobalPackPolicy.decide(GlobalPackPolicy.Mode.OFF, List.of(), null, 0);
        }
        Path gameDir = current.info().gameDirectory().isEmpty()
                ? null : Path.of(current.info().gameDirectory());
        Path ourDir = globalDir(gameDir, current);
        int ourPacks = GlobalPackDiscovery.packs(ourDir).size();
        List<String> modIds = current.mods().stream().map(m -> m.id()).toList();
        List<GlobalPackPolicy.Detected> detected = GlobalPackDiscovery.detect(gameDir, modIds);
        return GlobalPackPolicy.decide(
                GlobalPackPolicy.Mode.of(config.globalDatapacks()), detected,
                ourDir == null ? null : ourDir.toString(), ourPacks);
    }

    /**
     * The directory UEE reads global datapacks from.
     *
     * <p>A named sub-directory of the config directory rather than a new top-level one, so it sits
     * with every other setting and is easy to find.
     */
    public static Path globalDir(Path gameDirectory, LoaderAdapter current) {
        String override = current == null ? null : current.globalPackDirectory();
        return GlobalPackDiscovery.globalDir(gameDirectory,
                override == null || override.isEmpty() ? null : Path.of(override));
    }

    /**
     * Writes the current effective configuration to the standard path, with its comments.
     *
     * <p>Writes the annotated form rather than the bare one: a config file a user cannot read is
     * barely better than no config file, and comments are why a comment-capable format was chosen over
     * JSON in the first place.
     *
     * @return the file written
     */
    public static Path writeConfig(ExportConfig config) throws IOException {
        Path file = configFile();
        if (file == null) {
            throw new IllegalStateException(
                    "no adapter bound, so there is no game directory to write into");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, ConfigFile.renderAnnotated(config));
        return file;
    }

    /**
     * Runs the checks that need no collection, without exporting anything.
     *
     * <p>Answers "what is wrong with this instance" without the cost or the side effects of a full
     * export. Only the pre-collection checks can run this way — the ones that need registry
     * observations have nothing to read — which is the concrete benefit of the stage split.
     *
     * @param sink where findings go
     * @return how many were produced
     */
    public static int analyzeWithoutCollecting(Consumer<Finding> sink) throws IOException {
        LoaderAdapter current = requireAdapter();
        AnalysisContext context = new AnalysisContext(current.mods(), current.containers(),
                current.mixinConfigs(), false);
        return AnalysisEngine.standard().runWithoutCollection(context, sink::accept);
    }

    /** Resolves the category tokens a command line used. */
    public static ConfigFile kindsFrom(List<String> tokens) {
        return ConfigResolver.kindsFromTokens(tokens);
    }

    /** Resolves the format tokens a command line used. */
    public static ConfigFile formatsFrom(List<String> tokens) {
        return ConfigResolver.formatsFromTokens(tokens);
    }

    /** Every category token that means something on its own, for help output. */
    public static List<String> kindTokens() {
        return Tokens.kindTokens();
    }

    /** Every format token, for help output. */
    public static List<String> formatTokens() {
        return Tokens.formatTokens();
    }

    private static LoaderAdapter requireAdapter() {
        LoaderAdapter current = adapter;
        if (current == null) {
            throw new IllegalStateException(
                    "no loader adapter is bound; the loader entry point must call Uee.bind first");
        }
        return current;
    }
}
