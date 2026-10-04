package org.uee;

import java.io.IOException;
import java.nio.file.Path;
import org.uee.config.ExportConfig;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.spi.LoaderAdapter;

/**
 * Entry point shared by every loader.
 *
 * <p>Each loader module's job is reduced to two things: build its {@link LoaderAdapter} and call
 * {@link #bind}. Everything after that — configuration, the pipeline, all writers — is identical
 * on all four loaders, which is the whole reason the SPI is kept thin.
 *
 * <p>This class deliberately has no Minecraft dependency, so it compiles and is exercised by the
 * harness without a game present.
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

    /**
     * Runs an export with the given configuration.
     *
     * @param config the resolved configuration
     * @param root where artifacts are written
     * @return what was produced, including any per-element failures
     * @throws IllegalStateException when no adapter is bound
     */
    public static ExportReport export(ExportConfig config, Path root) throws IOException {
        LoaderAdapter current = adapter;
        if (current == null) {
            throw new IllegalStateException("no loader adapter is bound; the loader entry point must call Uee.bind first");
        }
        return new Exporter(current, config, root).run();
    }

    /** Convenience overload exporting with default settings under the given directory. */
    public static ExportReport export(Path root) throws IOException {
        return export(ExportConfig.builder().outputDir(root).build(), root);
    }
}
