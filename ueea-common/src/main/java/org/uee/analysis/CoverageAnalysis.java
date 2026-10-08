package org.uee.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.uee.model.ElementKind;

/**
 * Reports what the collection actually produced.
 *
 * <p><b>Post-collection, unavoidably.</b> These are the observations themselves — how many elements
 * each category yielded, which namespaces they came from, how many the filter dropped. None of it can
 * exist before the records have been streamed, which is exactly why the stage split is needed rather
 * than merely tidy.
 *
 * <p>This analysis exists because an export that silently produces less than expected is the hardest
 * kind of failure to notice. A namespace filter that is too narrow, a category that a loader does not
 * support, a registry walk that threw partway — all three look identical from the outside: a smaller
 * directory. Reporting the counts, and reporting the filter drops separately from the successes,
 * makes each case visible on its own.
 */
public final class CoverageAnalysis implements Analysis {

    @Override
    public String id() {
        return "coverage";
    }

    @Override
    public String description() {
        return "what collection produced per category and namespace, and what the filter discarded";
    }

    @Override
    public Stage stage() {
        return Stage.POST_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        if (!ctx.collected()) {
            // Running anyway would emit a tidy table of zeroes, which reads as "this pack is empty"
            // rather than "collection did not run". Saying nothing is the honest option.
            return;
        }

        int total = ctx.totalCounted();
        int dropped = ctx.totalFiltered();

        for (ElementKind kind : ElementKind.values()) {
            int counted = ctx.counted(kind);
            int filtered = ctx.filtered(kind);
            if (counted == 0 && filtered == 0) {
                continue;
            }
            List<String> extra = new ArrayList<>(8);
            extra.add("counted");
            extra.add(Integer.toString(counted));
            extra.add("filtered");
            extra.add(Integer.toString(filtered));
            extra.add("namespaces");
            extra.add(Integer.toString(ctx.namespacesOf(kind).size()));
            extra.add("source");
            extra.add(kind.isAnalysis() ? "analysis" : "registry");

            Map<String, Integer> byNs = ctx.countByNamespace(kind);
            if (!byNs.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Map.Entry<String, Integer> e : byNs.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    sb.append(e.getKey()).append('=').append(e.getValue());
                    first = false;
                }
                extra.add("byNamespace");
                extra.add(sb.toString());
            }

            out.record(kind, Global.NAMESPACE, "coverage:" + kind.singular(),
                    ctx.namespacesOf(kind).toArray(new String[0]), extra.toArray(new String[0]));
        }

        // A filter that discarded something is worth stating: the user asked for a restriction and it
        // did something, and how much it did is the difference between a deliberate narrow export and
        // an accidental one.
        if (dropped > 0) {
            out.finding(new Finding("filter_discarded_elements", Finding.Severity.INFO,
                    Global.NAMESPACE,
                    dropped + " element(s) were discarded by the namespace filter, out of "
                            + (dropped + total),
                    new String[] {"discarded", Integer.toString(dropped),
                            "kept", Integer.toString(total)}));
        }

        out.finding(new Finding("collection_summary", Finding.Severity.INFO, Global.NAMESPACE,
                "collection produced " + total + " record(s) across "
                        + ctx.countedKinds().size() + " category/categories",
                new String[] {"total", Integer.toString(total),
                        "namespaces", Integer.toString(ctx.observedNamespaces().size()),
                        "categories", Integer.toString(ctx.countedKinds().size())}));
    }
}
