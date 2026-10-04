package org.uee.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.uee.debug.ModContainerScanner;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks the dependency graph.
 *
 * <p>Reads loader metadata only, so it runs whether or not anything was collected. That is deliberate:
 * a broken dependency graph is worth knowing about even in a run that exports nothing.
 *
 * <p>The graph is indexed once into lookup maps rather than scanned per dependency. A large pack has
 * hundreds of mods and thousands of edges, and comparing every edge against every mod would make the
 * diagnostic cost more than the export it accompanies.
 */
public final class DependencyAnalysis implements Analysis {

    @Override
    public String id() {
        return "dependency-graph";
    }

    @Override
    public String description() {
        return "missing requirements, declared incompatibilities, contradictory declarations, duplicate ids";
    }

    @Override
    public Stage stage() {
        return Stage.PRE_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        Map<String, ModElement> byId = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            byId.putIfAbsent(m.id(), m);
        }

        // Reverse edges, computed once: "who requires me" is asked for every mod.
        Map<String, List<String>> requiredBy = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependencies()) {
                if (d.kind() == Dependency.Kind.REQUIRED) {
                    requiredBy.computeIfAbsent(d.id(), k -> new ArrayList<>(4)).add(m.id());
                }
            }
        }

        // One structured record per mod: the graph, as data rather than as prose.
        for (ModElement m : ctx.mods()) {
            emitModRecord(ctx, m, requiredBy, out);
        }

        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependenciesOf(Dependency.Kind.REQUIRED)) {
                if (!byId.containsKey(d.id())) {
                    out.finding(new Finding("missing_dependency", Finding.Severity.ERROR, m.id(),
                            m.id() + " requires " + d.id() + " but it is not loaded",
                            new String[] {"target", d.id(),
                                    "range", d.hasVersionRange() ? d.versionRange() : ""}));
                }
            }
            for (Dependency d : m.dependenciesOf(Dependency.Kind.INCOMPATIBLE)) {
                ModElement target = byId.get(d.id());
                if (target == null) {
                    continue;
                }
                boolean mutual = target.dependenciesOf(Dependency.Kind.INCOMPATIBLE).stream()
                        .anyMatch(x -> x.id().equals(m.id()));
                out.finding(new Finding("declared_incompatibility", Finding.Severity.ERROR, m.id(),
                        m.id() + " declares " + d.id() + " incompatible, and both are loaded",
                        new String[] {"target", d.id(), "mutual", Boolean.toString(mutual)}));

                // The interesting asymmetric case: A calls B incompatible while B requires A. Neither
                // side is wrong on its own terms, which is why no single-mod view can see it.
                boolean targetNeedsMe = target.dependenciesOf(Dependency.Kind.REQUIRED).stream()
                        .anyMatch(x -> x.id().equals(m.id()));
                if (targetNeedsMe) {
                    out.finding(new Finding("contradictory_declaration", Finding.Severity.ERROR,
                            m.id(),
                            m.id() + " calls " + d.id() + " incompatible, but " + d.id()
                                    + " requires " + m.id(),
                            new String[] {"target", d.id()}));
                }
            }
        }

        Map<String, Integer> idCount = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            idCount.merge(m.id(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : idCount.entrySet()) {
            if (e.getValue() > 1) {
                out.finding(new Finding("duplicate_mod_id", Finding.Severity.ERROR, e.getKey(),
                        e.getKey() + " is declared by " + e.getValue() + " containers",
                        new String[] {"count", Integer.toString(e.getValue())}));
            }
        }
    }

    /** Emits the per-mod dependency record: what it needs, what needs it, what it declares broken. */
    private void emitModRecord(AnalysisContext ctx, ModElement m,
            Map<String, List<String>> requiredBy, AnalysisOutput out) {
        List<String> dependsOn = new ArrayList<>(m.dependencies().length);
        List<String> optionalOn = new ArrayList<>(4);
        List<String> declaresIncompatible = new ArrayList<>(4);
        int missing = 0;
        for (Dependency d : m.dependencies()) {
            String token = d.hasVersionRange() ? d.id() + "@" + d.versionRange() : d.id();
            switch (d.kind()) {
                case REQUIRED -> {
                    dependsOn.add(token);
                    if (!ctx.hasMod(d.id())) {
                        missing++;
                    }
                }
                case OPTIONAL -> optionalOn.add(token);
                case INCOMPATIBLE -> declaresIncompatible.add(token);
                default -> {
                    // Embedded and unknown kinds carry no edge worth graphing.
                }
            }
        }
        List<String> rev = requiredBy.get(m.id());
        if (rev != null) {
            rev.sort(String::compareTo);
        }

        List<String> extra = new ArrayList<>(16);
        kv(extra, "loader", m.loader());
        kv(extra, "version", m.version());
        kv(extra, "namespace", m.namespace());
        kv(extra, "minecraftVersion", m.minecraftVersion());
        if (m.license() != null) {
            kv(extra, "license", m.license());
        }
        kv(extra, "dependencyCount", Integer.toString(m.dependencies().length));
        kv(extra, "dependentsCount", Integer.toString(rev == null ? 0 : rev.size()));
        kv(extra, "missingRequired", Integer.toString(missing));
        if (!dependsOn.isEmpty()) {
            kv(extra, "dependsOn", String.join(", ", dependsOn));
        }
        if (!optionalOn.isEmpty()) {
            kv(extra, "optionalOn", String.join(", ", optionalOn));
        }
        if (!declaresIncompatible.isEmpty()) {
            kv(extra, "declaresIncompatible", String.join(", ", declaresIncompatible));
        }
        if (rev != null && !rev.isEmpty()) {
            kv(extra, "requiredBy", String.join(", ", rev));
        }
        emitContainer(ctx, m, extra);

        out.record(ElementKind.DEPENDENCY, m.namespace(), "dep:" + m.id(),
                dependsOn.toArray(new String[0]), extra.toArray(new String[0]));
    }

    /** Adds the container facts, withholding the absolute path unless it was opted into. */
    static void emitContainer(AnalysisContext ctx, ModElement m, List<String> extra) {
        ModContainerScanner.ContainerInfo container = ctx.containers().get(m.id());
        if (container == null) {
            return;
        }
        kv(extra, "container", container.fileName());
        if (ctx.includePaths()) {
            kv(extra, "containerPath", container.path().toString());
        }
        kv(extra, "containerKind", container.directory() ? "directory" : "jar");
        kv(extra, "containerBytes", Long.toString(container.sizeBytes()));
        kv(extra, "containerEntries", Integer.toString(container.entryCount()));
        for (Map.Entry<String, String> attr : new TreeMap<>(container.manifestAttributes()).entrySet()) {
            kv(extra, "manifest." + attr.getKey(), attr.getValue());
        }
    }

    static void kv(List<String> out, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        out.add(key);
        out.add(value);
    }

    /** Namespaces claimed by at least one mod, in sorted order, for the checks that compare claims. */
    static Map<String, TreeSet<String>> namespaceOwners(AnalysisContext ctx) {
        Map<String, TreeSet<String>> owners = new TreeMap<>();
        for (ModElement m : ctx.mods()) {
            for (String ns : m.claimedNamespaces()) {
                owners.computeIfAbsent(ns, k -> new TreeSet<>()).add(m.id());
            }
        }
        return owners;
    }
}
