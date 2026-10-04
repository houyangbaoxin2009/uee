package org.uee.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.uee.model.ElementKind;

/**
 * Runs the registered checks.
 *
 * <p>The engine is deliberately the only place that knows the full set, and it runs checks by stage.
 * That is what lets the same engine serve both modes: an export runs every stage, while a
 * data-free analysis run stops after {@link Analysis.Stage#PRE_COLLECTION}.
 *
 * <p>Check order is deterministic (registration order within a stage), so two runs over the same facts
 * produce byte-identical findings — a property that matters as soon as the output is diffed or
 * committed.
 */
public final class AnalysisEngine {

    private final List<Analysis> analyses;

    private AnalysisEngine(List<Analysis> analyses) {
        this.analyses = List.copyOf(analyses);
    }

    /** The standard set of checks, in the order they should run. */
    public static AnalysisEngine standard() {
        List<Analysis> all = new ArrayList<>(8);
        // Pre-collection: loader metadata and mod containers only.
        all.add(new ContainerAnalysis());
        all.add(new DependencyAnalysis());
        all.add(new MixinAnalysis());
        all.add(new NamespaceClaimsAnalysis());
        // Post-collection: needs the registry walk to have happened.
        all.add(new OrphanNamespaceAnalysis());
        all.add(new CoverageAnalysis());
        return new AnalysisEngine(all);
    }

    /** An engine with no checks, for runs that do not want analysis at all. */
    public static AnalysisEngine none() {
        return new AnalysisEngine(List.of());
    }

    /** An engine with a caller-supplied set, for tests and for selective runs. */
    public static AnalysisEngine of(List<Analysis> analyses) {
        return new AnalysisEngine(analyses);
    }

    /** Every registered check, in run order. */
    public List<Analysis> analyses() {
        return analyses;
    }

    /** Checks that can run at the given stage or earlier. */
    public List<Analysis> analysesUpTo(Analysis.Stage stage) {
        List<Analysis> out = new ArrayList<>(analyses.size());
        for (Analysis a : analyses) {
            if (a.stage().ordinal() <= stage.ordinal()) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Runs every check at or before {@code upTo}.
     *
     * @return the number of findings emitted
     */
    public int run(Analysis.Stage upTo, AnalysisContext context, AnalysisOutput out) {
        int[] count = {0};
        AnalysisOutput counting = new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                count[0]++;
                out.finding(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key,
                    String[] listValues, String[] extra) {
                out.record(kind, namespace, key, listValues, extra);
            }
        };
        for (Analysis a : analysesUpTo(upTo)) {
            try {
                a.run(context, counting);
            } catch (RuntimeException e) {
                // A broken check must not take the export down with it; report the failure as a
                // finding so it is visible rather than silently absent.
                counting.finding(new Finding("analysis_failed", Finding.Severity.WARN, a.id(),
                        "check '" + a.id() + "' failed: " + e, null));
            }
        }
        return count[0];
    }

    /**
     * Runs only the checks that need no collected data, discarding their records.
     *
     * <p>Used by the "diagnose without exporting" path, where findings are the whole point and there
     * is no output directory for records to go to.
     */
    public int runWithoutCollection(AnalysisContext context, FindingSink sink) {
        AnalysisOutput findingsOnly = new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                sink.finding(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key,
                    String[] listValues, String[] extra) {
                // Discarded: nothing was asked to be exported.
            }
        };
        return run(Analysis.Stage.PRE_COLLECTION, context, findingsOnly);
    }

    /** The ids of every registered check, for diagnostics and for the command surface. */
    public Set<String> ids() {
        Set<String> out = new TreeSet<>();
        for (Analysis a : analyses) {
            out.add(a.id());
        }
        return out;
    }

    /** The stages that at least one registered check needs. */
    public Set<Analysis.Stage> stages() {
        Set<Analysis.Stage> out = EnumSet.noneOf(Analysis.Stage.class);
        for (Analysis a : analyses) {
            out.add(a.stage());
        }
        return Collections.unmodifiableSet(out);
    }
}
