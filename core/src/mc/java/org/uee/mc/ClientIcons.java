package org.uee.mc;

import java.nio.file.Path;
import org.uee.Uee;
import org.uee.config.ExportConfig;
import org.uee.spi.LoaderAdapter;

/**
 * The client-side entry point for the icon phase, shared by all four loaders.
 *
 * <h2>Why the request is separate from the running</h2>
 *
 * <p>A request can arrive from anywhere — a command, run on whatever thread the game dispatched it on —
 * but the phase can only be started and advanced on the render thread, because that is where rendering
 * lives. So a request is recorded and the client tick picks it up. That is the same handover the rest of
 * the project uses for work that crosses a thread boundary, and it is the reason nothing here has to be
 * called at a particular moment.
 *
 * <h2>Nothing happens unless asked</h2>
 *
 * <p>The tick handler is registered unconditionally, but it does nothing until a request exists and
 * nothing once the phase is complete. An install that never exports pays one null check per tick.
 */
public final class ClientIcons {

    /** A request waiting for the next client tick. Volatile because it is written from another thread. */
    private static volatile ExportConfig pendingConfig;

    private static volatile Path pendingRoot;

    private ClientIcons() {
    }

    /**
     * Asks for icons to be rendered into {@code root}.
     *
     * <p>Returns immediately; the work starts on the next client tick. A second request while one is
     * running is dropped rather than queued, since two phases would render the same files at once.
     *
     * @return whether the request was accepted
     */
    public static boolean request(ExportConfig config, Path root) {
        if (!config.icons()) {
            return false;
        }
        if (IconPhaseDriver.isRunning()) {
            return false;
        }
        pendingRoot = root;
        pendingConfig = config;
        return true;
    }

    /**
     * Advances the phase, starting it if a request is waiting.
     *
     * <p>Called from the client tick of every loader. Passing the adapter rather than looking it up keeps
     * this class free of any dependency on how an adapter is bound, which is loader business.
     */
    public static void tickIfRunning(LoaderAdapter adapter) {
        if (adapter == null) {
            return;
        }
        ExportConfig requested = pendingConfig;
        if (requested != null) {
            // Cleared before starting, so a start that does not happen is not retried every tick forever.
            pendingConfig = null;
            Path root = pendingRoot;
            pendingRoot = null;
            if (root != null) {
                IconPhaseDriver.start(adapter, requested, root, IconPhaseDriver.DEFAULT_BUDGET);
            }
        }
        IconPhaseDriver driver = IconPhaseDriver.current();
        if (driver != null) {
            driver.tick();
        }
    }

    /** A line describing the phase, for a command to show, or null when nothing is running. */
    public static String progress() {
        return IconPhaseDriver.progress();
    }

    /** Stops a running phase, keeping what has been rendered so a later run resumes from it. */
    public static void cancel() {
        IconPhaseDriver.cancel();
    }

    /** Whether this side can render at all, which a dedicated server cannot. */
    public static boolean available() {
        return Uee.adapter() instanceof AbstractMinecraftAdapter;
    }
}
