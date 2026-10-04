package org.uee.analysis;

import java.util.Map;
import java.util.TreeSet;
import org.uee.model.ModElement;

/**
 * Reports namespaces that appear in the registries but that no loaded mod claims.
 *
 * <p><b>Post-collection, and unavoidably so.</b> Whether a namespace is present is not knowable until
 * the registries have been walked, and the walk is what collection is. This check is the clearest
 * example of why the stage distinction has to exist: there is no way to answer it earlier, and running
 * it against an empty observation set would report "no orphans", which reads as a clean bill of health
 * rather than as "not asked".
 *
 * <p>An orphan namespace is usually datapack content, a generated namespace, or a library that ships
 * defaults under a shared id. It is informational: it explains why an export contains content no mod
 * in the list appears to own, which is otherwise a confusing thing to find in the output.
 */
public final class OrphanNamespaceAnalysis implements Analysis {

    @Override
    public String id() {
        return "orphan-namespaces";
    }

    @Override
    public String description() {
        return "namespaces present in the registries that no loaded mod claims";
    }

    @Override
    public Stage stage() {
        return Stage.POST_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        Map<String, TreeSet<String>> claimed = DependencyAnalysis.namespaceOwners(ctx);
        for (String ns : ctx.observedNamespaces()) {
            if (!claimed.containsKey(ns)) {
                out.finding(new Finding("orphan_namespace", Finding.Severity.INFO, ns,
                        "namespace " + ns + " appears in the registries but no loaded mod claims it",
                        new String[] {"hint", "datapack, generated content, or a shared library id"}));
            }
        }
    }
}
