package org.uee.analysis;

/**
 * One check over an {@link AnalysisContext}.
 *
 * <p>Each check is its own class rather than a branch inside one large method. That matters for two
 * reasons beyond tidiness: a check can be tested in isolation against a hand-built context, and a
 * check declares for itself whether it needs collected data, so adding one cannot silently depend on
 * observations that may not exist.
 */
public interface Analysis {

    /**
     * When a check can run.
     *
     * <p>Making this explicit is the point: a check that reads registry observations would produce
     * nonsense if it ran before collection, and one that only reads loader metadata should still be
     * usable in a run where collection never happened.
     */
    enum Stage {
        /** Needs only loader metadata and mod containers; runs with or without collection. */
        PRE_COLLECTION,
        /** Needs observations made while records were written; skipped when nothing was collected. */
        POST_COLLECTION
    }

    /** A stable machine-readable id, used in output and for selecting a subset. */
    String id();

    /** A one-line description, shown by the command surface. */
    String description();

    /** The earliest stage at which this check can produce a meaningful result. */
    Stage stage();

    /** Runs the check, emitting findings and any structured records it produces. */
    void run(AnalysisContext context, AnalysisOutput out);
}
