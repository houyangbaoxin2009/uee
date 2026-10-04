package org.uee.analysis;

/**
 * The namespace findings that belong to the whole run rather than to one mod or namespace.
 *
 * <p>Kept here rather than reaching for {@code Exporter.GLOBAL_NAMESPACE} directly, so the analysis
 * module does not depend on the pipeline that happens to host it.
 */
final class Global {

    /** Namespace for cross-cutting records and findings. */
    static final String NAMESPACE = "_global";

    private Global() {
    }
}
