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
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
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

    /** The bound adapter, or {@code null} when no loader has initialised yet. */
    public static LoaderAdapter adapter() {
        return adapter;
    }

    // ---------------------------------------------------------------- function API

    /**
     * Runs an export with the given configuration.
     *
     * @param config the resolved configuration
     * @param root where artifacts are written
     * @return what was produced, including any per-element failures and the finding count
     * @throws IllegalStateException when no adapter is bound
     */
    public static ExportReport export(ExportConfig config, Path root) throws IOException {
        LoaderAdapter current = requireAdapter();
        return new Exporter(current, config, root, AnalysisEngine.standard()).run();
    }

    /** Runs an export with a specific analysis set, for a caller that wants a subset of the checks. */
    public static ExportReport export(ExportConfig config, Path root, AnalysisEngine engine)
            throws IOException {
        return new Exporter(requireAdapter(), config, root, engine).run();
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
     * <p>Reads the config file from the adapter's game directory and layers the overrides on top, so
     * the command surface expresses a run in exactly the same terms as the file does.
     *
     * @throws IllegalStateException when no adapter is bound
     */
    public static ConfigResolver.Resolved resolveForRun(ConfigFile overrides) throws IOException {
        LoaderAdapter current = requireAdapter();
        Path gameDir = Path.of(current.info().gameDirectory());
        return ConfigResolver.resolveFrom(ConfigResolver.configDir(gameDir), overrides,
                ExportConfig.builder().outputDir(ExportConfig.defaultOutputDir(gameDir)).build());
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
