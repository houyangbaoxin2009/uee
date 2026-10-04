package org.uee.analysis;

import java.util.Map;
import java.util.TreeSet;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks which namespaces mods <em>claim</em>, and reports collisions.
 *
 * <p>Pre-collection: claims come from loader metadata, so this runs whether or not anything was
 * collected. It is separate from {@link OrphanNamespaceAnalysis}, which asks the opposite question
 * (namespaces present in the registries that no mod claims) and therefore can only run after
 * collection. Splitting them along that line is what keeps each one usable in the widest set of runs.
 */
public final class NamespaceClaimsAnalysis implements Analysis {

    @Override
    public String id() {
        return "namespace-claims";
    }

    @Override
    public String description() {
        return "namespaces mods claim, and namespaces claimed by more than one mod";
    }

    @Override
    public Stage stage() {
        return Stage.PRE_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        Map<String, TreeSet<String>> owners = DependencyAnalysis.namespaceOwners(ctx);

        for (Map.Entry<String, TreeSet<String>> e : owners.entrySet()) {
            TreeSet<String> who = e.getValue();
            boolean conflict = who.size() > 1;
            out.record(ElementKind.NAMESPACE, e.getKey(), "ns:" + e.getKey(),
                    who.toArray(new String[0]),
                    new String[] {
                            "owners", String.join(", ", who),
                            "ownerCount", Integer.toString(who.size()),
                            "conflict", Boolean.toString(conflict)});

            if (conflict) {
                // Two mods claiming one namespace is not automatically fatal, but it means resources
                // and registry names from both are competing for one id space, and that is a real
                // problem to know about before it manifests as an unexplained override.
                out.finding(new Finding("namespace_conflict", Finding.Severity.ERROR, e.getKey(),
                        "namespace " + e.getKey() + " is claimed by " + who.size() + " mods",
                        new String[] {"owners", String.join(", ", who),
                                "count", Integer.toString(who.size())}));
            }
        }

        // A mod that claims no namespace of its own is unusual — it may be a library that only ships
        // code, or its metadata may be incomplete. Worth noting, not worth failing.
        for (ModElement m : ctx.mods()) {
            if (m.claimedNamespaces().length == 0) {
                out.finding(new Finding("mod_claims_no_namespace", Finding.Severity.INFO, m.id(),
                        m.id() + " claims no namespace", null));
            }
        }
    }
}
