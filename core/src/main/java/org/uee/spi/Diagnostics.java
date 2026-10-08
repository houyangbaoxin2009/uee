package org.uee.spi;

import java.util.function.BiConsumer;

/**
 * Where the shared code reports a failure it cannot do anything about.
 *
 * <h2>Why it is here and not on a mod</h2>
 *
 * <p>The code shared by every loader and by both mods has failures to report and no logger to report them
 * to. Naming one mod's facade to do it is what stops that code from being shared, so the sink lives beside
 * the interfaces instead, and whoever owns the logging installs it.
 *
 * <p>The default writes to the error stream rather than swallowing, because the failures that arrive here
 * are the ones with nowhere else to go: an icon that could not be read, a write that did not happen. A
 * silent default would turn all of them into an export that is quietly missing something.
 */
public final class Diagnostics {

    private Diagnostics() {
    }

    /** What is installed; the error stream until something replaces it. */
    private static volatile BiConsumer<String, Throwable> reporter = (message, error) -> {
        System.err.println("[uee] " + message);
        if (error != null) {
            error.printStackTrace();
        }
    };

    /** Installs a reporter. A null argument leaves the current one alone. */
    public static void reporter(BiConsumer<String, Throwable> replacement) {
        if (replacement != null) {
            reporter = replacement;
        }
    }

    /** Reports a failure that has already been handled as far as it can be. */
    public static void reportFailure(String message, Throwable error) {
        reporter.accept(message, error);
    }
}
