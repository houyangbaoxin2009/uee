package org.uee;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.uee.analysis.Analysis;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.Finding;
import org.uee.config.ConfigResolver;
import org.uee.model.ModElement;
import org.uee.spi.LoaderAdapter;

/**
 * The analysis half, as a mod of its own.
 *
 * <h2>Where its facts come from</h2>
 *
 * <p>Not from one source. Some checks need what the exporter wrote to disk, which is the reason this mod
 * can be pointed at an export produced elsewhere; some need the game as it is running, because a container
 * that cannot be read is only visible from inside it; and some need neither and will grow a source of their
 * own. Which of those a check needs is a property of the check, so it is declared there and asked for here,
 * rather than decided once for the whole program.
 *
 * <p>For now the live source is the only one wired up, which is the one the checks that already exist use.
 * The rest arrive with the checks that need them.
 *
 * <h2>Why a facade at all</h2>
 *
 * <p>So that nothing above it has to know how a run is put together. The command layer asks for the check
 * ids or for a run, and this is the place that knows an adapter has to be bound and a context built.
 */
public final class Ueea {

    /** The mod id, which is also the name of its configuration file. */
    public static final String MOD_ID = org.uee.spi.ModIds.ANALYSIS;

    /** What a mods list shows. */
    public static final String NAME = "Universal Element Exporter - Analysis";

    /** What this mod is looking at. */
    private static volatile LoaderAdapter adapter;

    private Ueea() {
    }

    /** Told by a loader hook, once, on both sides. */
    public static void bind(LoaderAdapter loaderAdapter) {
        adapter = loaderAdapter;
    }

    /** What is bound, or null when nothing is. */
    public static LoaderAdapter adapter() {
        return adapter;
    }

    /**
     * The version this build is, read from the loader's own metadata.
     *
     * <p>Read rather than written down: a number in the source is a number that stops being true at the
     * first release and is not noticed, because nothing compares it with anything.
     */
    public static String modVersion() {
        LoaderAdapter current = adapter;
        if (current == null) {
            return "";
        }
        try {
            for (ModElement mod : current.mods()) {
                if (MOD_ID.equals(mod.id())) {
                    return mod.version() == null ? "" : mod.version();
                }
            }
        } catch (Throwable t) {
            // A report is not worth failing over; the version simply does not appear.
            return "";
        }
        return "";
    }

    /** This mod's configuration file, which is its own rather than the exporter's. */
    public static Path configFile() {
        LoaderAdapter current = adapter;
        if (current == null || current.info() == null || current.info().gameDirectory() == null) {
            return null;
        }
        return ConfigResolver.configFile(Path.of(current.info().gameDirectory()), MOD_ID);
    }

    /** Whether the exporter is loaded, which is what the checks that read its output will need. */
    public static boolean exporterPresent() {
        LoaderAdapter current = adapter;
        if (current == null) {
            return false;
        }
        try {
            for (ModElement mod : current.mods()) {
                if (org.uee.spi.ModIds.EXPORTER.equals(mod.id())) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    // ---------------------------------------------------------------- the checks

    /** Every check this mod ships, in run order. */
    public static List<Analysis> checks() {
        return AnalysisEngine.named(List.of()).analyses();
    }

    /** The same, narrowed to the named ones; unknown names select nothing. */
    public static List<Analysis> checksFor(Collection<String> selected) {
        return AnalysisEngine.named(selected).analyses();
    }

    /** Every check id, for a surface that has to say what the names are. */
    public static List<String> checkIds() {
        return AnalysisEngine.knownIds();
    }

    /**
     * Runs the named checks against what the game has in front of it.
     *
     * @param sink where findings go
     * @param selected the checks to run; empty means every one
     * @return how many findings were produced
     */
    public static int run(Consumer<Finding> sink, Collection<String> selected) throws IOException {
        LoaderAdapter current = requireAdapter();
        AnalysisContext context = new AnalysisContext(current.mods(), current.containers(),
                current.mixinConfigs(), false,
                current.info() == null ? null : current.info().loader(),
                current.info() == null ? null : current.info().minecraftVersion());
        return AnalysisEngine.named(selected).runWithoutCollection(context, sink::accept);
    }

    /** Findings worst first, and by kind within a severity, so two runs read the same way. */
    public static List<Finding> ordered(List<Finding> findings) {
        List<Finding> out = new ArrayList<>(findings);
        out.sort(Comparator.comparingInt((Finding f) -> f.severity().ordinal())
                .thenComparing(Finding::kind).thenComparing(Finding::subject));
        return out;
    }

    private static LoaderAdapter requireAdapter() {
        LoaderAdapter current = adapter;
        if (current == null) {
            throw new IllegalStateException("no loader adapter bound");
        }
        return current;
    }
}
