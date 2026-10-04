package org.uee.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.spi.ElementSink;

/**
 * Turns a mod list plus container information into diagnostic findings.
 *
 * <p>This is the part of the debug surface that is genuinely additive. Existing exporters dump the
 * mod list and the registries; what they do not do is tell you what is <em>wrong</em> with the
 * instance. Everything here is derived from what the loaders already expose, so it works on all four
 * of them and needs no game classes — which also means it is testable on a hand-built mod list.
 *
 * <p>Findings are severity-tagged. {@code error} means the instance is broken or will misbehave,
 * {@code warn} means something is suspicious and worth a look, {@code info} is context that is
 * useful when reading the rest of the dump.
 *
 * <p>Complexity is linear in the number of mods and dependencies: every check uses a lookup rather
 * than a scan, because a large pack has hundreds of mods and nested comparison would make the
 * diagnostic slower than the export it accompanies.
 */
public final class ModAnalyzer {

    /** Severity tokens used in the {@code severity} field of a conflict record. */
    public static final String ERROR = "error";
    public static final String WARN = "warn";
    public static final String INFO = "info";

    private ModAnalyzer() {
    }

    /**
     * Everything the analysis needs, gathered by the caller.
     *
     * @param mods the loaded mods
     * @param containers mod id to container inspection, for the mods whose container could be read
     * @param mixinConfigs parsed mixin configs, in discovery order
     * @param observedNamespaces namespaces seen in the registries, used to find unclaimed ones
     * @param includePaths whether to record full container paths.
     *     <p>Off by default: the debug artifacts are meant to be published or attached to a bug
     *     report, and an absolute path discloses the user name and directory layout. Only the file
     *     name is written otherwise, which is enough to identify the mod.
     */
    public record Context(List<ModElement> mods,
            Map<String, ModContainerScanner.ContainerInfo> containers,
            List<MixinConfig> mixinConfigs,
            Set<String> observedNamespaces,
            boolean includePaths) {

        public Context {
            mods = mods == null ? List.of() : List.copyOf(mods);
            containers = containers == null ? Map.of() : Map.copyOf(containers);
            mixinConfigs = mixinConfigs == null ? List.of() : List.copyOf(mixinConfigs);
            observedNamespaces = observedNamespaces == null ? Set.of() : Set.copyOf(observedNamespaces);
        }

        public Context(List<ModElement> mods,
                Map<String, ModContainerScanner.ContainerInfo> containers,
                List<MixinConfig> mixinConfigs, Set<String> observedNamespaces) {
            this(mods, containers, mixinConfigs, observedNamespaces, false);
        }
    }

    /** Runs every check and emits the findings. */
    public static void analyze(Context ctx, ElementSink sink) {
        Map<String, ModElement> byId = new HashMap<>(ctx.mods().size() * 2);
        for (ModElement m : ctx.mods()) {
            byId.putIfAbsent(m.id(), m);
        }

        Map<String, List<String>> requiredBy = new HashMap<>(ctx.mods().size() * 2);
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependencies()) {
                if (d.kind() == Dependency.Kind.REQUIRED) {
                    requiredBy.computeIfAbsent(d.id(), k -> new ArrayList<>(4)).add(m.id());
                }
            }
        }

        emitModSummaries(ctx, sink);
        emitDependencies(ctx, byId, requiredBy, sink);
        emitNamespaceOwnership(ctx, sink);
        emitMixinRecords(ctx, sink);
        emitConflicts(ctx, byId, sink);
    }

    // ---------------------------------------------------------------- mod summaries

    private static void emitModSummaries(Context ctx, ElementSink sink) {
        for (ModElement m : ctx.mods()) {
            ModContainerScanner.ContainerInfo container = ctx.containers().get(m.id());
            List<String> extra = new ArrayList<>(16);
            kv(extra, "loader", m.loader());
            kv(extra, "version", m.version());
            kv(extra, "namespace", m.namespace());
            kv(extra, "minecraftVersion", m.minecraftVersion());
            if (m.license() != null) {
                kv(extra, "license", m.license());
            }
            if (container != null) {
                // Only the file name by default: the artifact is meant to be shared, and an absolute
                // path would disclose the user's name and directory layout.
                kv(extra, "container", container.fileName());
                if (ctx.includePaths()) {
                    kv(extra, "containerPath", container.path().toString());
                }
                kv(extra, "containerKind", container.directory() ? "directory" : "jar");
                kv(extra, "containerBytes", Long.toString(container.sizeBytes()));
                kv(extra, "containerEntries", Integer.toString(container.entryCount()));
                if (container.mixinConfigPaths().size() > 0) {
                    kv(extra, "mixinConfigCount", Integer.toString(container.mixinConfigPaths().size()));
                }
                for (Map.Entry<String, String> attr : new TreeMap<>(container.manifestAttributes()).entrySet()) {
                    kv(extra, "manifest." + attr.getKey(), attr.getValue());
                }
            }
            sink.generic(ElementKind.MOD, m.namespace(), "mod:" + m.id(), m.name(), null,
                    m.authors(), extra.toArray(new String[0]));
        }
    }

    // ---------------------------------------------------------------- dependency graph

    private static void emitDependencies(Context ctx, Map<String, ModElement> byId,
            Map<String, List<String>> requiredBy, ElementSink sink) {
        for (ModElement m : ctx.mods()) {
            List<String> extra = new ArrayList<>(12);
            List<String> dependsOn = new ArrayList<>(m.dependencies().length);
            List<String> optional = new ArrayList<>(4);
            List<String> incompatible = new ArrayList<>(4);
            List<String> missing = new ArrayList<>(4);
            for (Dependency d : m.dependencies()) {
                String token = d.hasVersionRange() ? d.id() + "@" + d.versionRange() : d.id();
                switch (d.kind()) {
                    case REQUIRED -> {
                        dependsOn.add(token);
                        if (!byId.containsKey(d.id())) {
                            missing.add(d.id());
                        }
                    }
                    case OPTIONAL -> optional.add(token);
                    case INCOMPATIBLE -> incompatible.add(token);
                    default -> {
                        // Embedded and unknown kinds are still worth listing; the field makes the
                        // distinction visible without a separate record per kind.
                        kv(extra, "other." + d.id(), d.kind().name().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            }
            if (!dependsOn.isEmpty()) {
                kv(extra, "dependsOn", String.join(", ", dependsOn));
            }
            if (!optional.isEmpty()) {
                kv(extra, "optionalOn", String.join(", ", optional));
            }
            if (!incompatible.isEmpty()) {
                kv(extra, "declaresIncompatible", String.join(", ", incompatible));
            }
            List<String> rev = requiredBy.get(m.id());
            if (rev != null && !rev.isEmpty()) {
                rev.sort(String::compareTo);
                kv(extra, "requiredBy", String.join(", ", rev));
            }
            kv(extra, "dependentsCount", Integer.toString(rev == null ? 0 : rev.size()));
            kv(extra, "dependencyCount", Integer.toString(m.dependencies().length));
            kv(extra, "missingRequired", Integer.toString(missing.size()));

            sink.generic(ElementKind.DEPENDENCY, m.namespace(), "dep:" + m.id(), null, null,
                    dependsOn.toArray(new String[0]), extra.toArray(new String[0]));
        }
    }

    // ---------------------------------------------------------------- namespace ownership

    private static void emitNamespaceOwnership(Context ctx, ElementSink sink) {
        Map<String, Set<String>> owners = new TreeMap<>();
        for (ModElement m : ctx.mods()) {
            for (String ns : m.claimedNamespaces()) {
                owners.computeIfAbsent(ns, k -> new TreeSet<>()).add(m.id());
            }
        }
        for (Map.Entry<String, Set<String>> e : owners.entrySet()) {
            Set<String> who = e.getValue();
            boolean conflict = who.size() > 1;
            String[] extra = new String[] {
                    "owners", String.join(", ", who),
                    "ownerCount", Integer.toString(who.size()),
                    "conflict", Boolean.toString(conflict),
                    "observedInRegistries", Boolean.toString(ctx.observedNamespaces().contains(e.getKey()))};
            sink.generic(ElementKind.NAMESPACE, ctx.observedNamespaces().contains(e.getKey())
                            ? e.getKey() : underscore(), "ns:" + e.getKey(), null, null,
                    who.toArray(new String[0]), extra);
        }
    }

    // ---------------------------------------------------------------- mixins

    private static void emitMixinRecords(Context ctx, ElementSink sink) {
        for (MixinConfig cfg : ctx.mixinConfigs()) {
            ModElement owner = findMod(ctx, cfg.modId());
            String namespace = owner != null ? owner.namespace() : underscore();
            String[] targets = cfg.targetClasses();
            String[] extra = new String[] {
                    "modId", nullToEmpty(cfg.modId()),
                    "config", nullToEmpty(cfg.source()),
                    "package", nullToEmpty(cfg.packageName()),
                    "compatibilityLevel", nullToEmpty(cfg.compatibilityLevel()),
                    "refmap", nullToEmpty(cfg.refmap()),
                    "required", Boolean.toString(cfg.required()),
                    "mixinClasses", Integer.toString(cfg.entries().size()),
                    "targetClasses", Integer.toString(targets.length),
                    "targets", String.join(", ", targets)};
            sink.generic(ElementKind.MIXIN, namespace, "mixin:" + cfg.source(), null, null,
                    targets, extra);
        }
    }

    // ---------------------------------------------------------------- conflict findings

    private static void emitConflicts(Context ctx, Map<String, ModElement> byId, ElementSink sink) {
        List<String[]> findings = new ArrayList<>(16);

        // Declared dependencies that are absent.
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependenciesOf(Dependency.Kind.REQUIRED)) {
                if (!byId.containsKey(d.id())) {
                    findings.add(new String[] {
                            "missing_dependency", ERROR, m.id(),
                            m.id() + " requires " + d.id() + " but it is not loaded",
                            "target=" + d.id()});
                }
            }
        }

        // Explicit incompatibilities where both sides are actually present.
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependenciesOf(Dependency.Kind.INCOMPATIBLE)) {
                if (byId.containsKey(d.id())) {
                    boolean mutual = byId.get(d.id()).dependencies().length > 0
                            && byId.get(d.id()).dependenciesOf(Dependency.Kind.INCOMPATIBLE).stream()
                                    .anyMatch(x -> x.id().equals(m.id()));
                    findings.add(new String[] {
                            "declared_incompatibility", ERROR, m.id(),
                            m.id() + " declares " + d.id() + " incompatible, and both are loaded",
                            "target=" + d.id() + " mutual=" + mutual});
                }
            }
        }

        // One-sided tension: A calls B incompatible while B requires A.
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependenciesOf(Dependency.Kind.INCOMPATIBLE)) {
                ModElement target = byId.get(d.id());
                if (target == null) {
                    continue;
                }
                boolean targetNeedsMe = target.dependenciesOf(Dependency.Kind.REQUIRED).stream()
                        .anyMatch(x -> x.id().equals(m.id()));
                if (targetNeedsMe) {
                    findings.add(new String[] {
                            "contradictory_declaration", ERROR, m.id(),
                            m.id() + " calls " + d.id() + " incompatible, but " + d.id()
                                    + " requires " + m.id(),
                            "target=" + d.id()});
                }
            }
        }

        // Duplicate mod ids across containers.
        Map<String, Integer> idCount = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            idCount.merge(m.id(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : idCount.entrySet()) {
            if (e.getValue() > 1) {
                findings.add(new String[] {
                        "duplicate_mod_id", ERROR, e.getKey(),
                        e.getKey() + " is declared by " + e.getValue() + " containers",
                        "count=" + e.getValue()});
            }
        }

        // Namespaces claimed by more than one mod.
        Map<String, Set<String>> nsOwners = new HashMap<>();
        for (ModElement m : ctx.mods()) {
            for (String ns : m.claimedNamespaces()) {
                nsOwners.computeIfAbsent(ns, k -> new TreeSet<>()).add(m.id());
            }
        }
        for (Map.Entry<String, Set<String>> e : new TreeMap<>(nsOwners).entrySet()) {
            if (e.getValue().size() > 1) {
                findings.add(new String[] {
                        "namespace_conflict", ERROR, e.getKey(),
                        "namespace " + e.getKey() + " is claimed by "
                                + String.join(", ", e.getValue()),
                        "owners=" + e.getValue().size()});
            }
        }

        // Namespaces seen in the registries that no mod claims — usually datapack content.
        for (String ns : new TreeSet<>(ctx.observedNamespaces())) {
            if (!nsOwners.containsKey(ns)) {
                findings.add(new String[] {
                        "orphan_namespace", INFO, ns,
                        "namespace " + ns + " appears in the registries but no loaded mod claims it",
                        "hint=datapack or generated content"});
            }
        }

        // Target classes patched by more than one mod — the substance of most mod conflicts.
        Map<String, Set<String>> targetOwners = new TreeMap<>();
        for (MixinConfig cfg : ctx.mixinConfigs()) {
            for (String target : cfg.targetClasses()) {
                targetOwners.computeIfAbsent(target, k -> new TreeSet<>())
                        .add(cfg.modId() == null ? cfg.source() : cfg.modId());
            }
        }
        for (Map.Entry<String, Set<String>> e : targetOwners.entrySet()) {
            if (e.getValue().size() > 1) {
                findings.add(new String[] {
                        "shared_mixin_target", WARN, e.getKey(),
                        e.getKey() + " is patched by " + e.getValue().size() + " mods",
                        "owners=" + String.join(", ", e.getValue())});
            }
        }

        // Containers that could not be read.
        for (Map.Entry<String, ModContainerScanner.ContainerInfo> e : ctx.containers().entrySet()) {
            String err = e.getValue().manifestAttributes().get("uee.readError");
            if (err != null) {
                findings.add(new String[] {
                        "unreadable_container", WARN, e.getKey(),
                        "container of " + e.getKey() + " could not be read", "error=" + err});
            }
        }

        for (String[] f : findings) {
            sink.generic(ElementKind.CONFLICT, underscore(), "finding:" + f[3],
                    f[3], null, new String[0],
                    // "findingKind" rather than "kind": the record already carries a structural
                    // "kind" field naming its category, and two fields with the same name produce a
                    // duplicate JSON key that a consumer resolves arbitrarily.
                    new String[] {"findingKind", f[0], "severity", f[1], "subject", f[2],
                            "detail", f[4]});
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Namespace used for collection-wide findings.
     *
     * <p>Underscore rather than something mod-shaped, so a cross-cutting finding cannot land in a
     * mod's own shard and be mistaken for that mod's content.
     */
    private static String underscore() {
        return org.uee.pipeline.Exporter.GLOBAL_NAMESPACE;
    }

    private static ModElement findMod(Context ctx, String id) {
        if (id == null) {
            return null;
        }
        for (ModElement m : ctx.mods()) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void kv(List<String> out, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        out.add(key);
        out.add(value);
    }

    /** Distinct observed namespaces outside the vanilla one, for callers that need the set. */
    public static Set<String> distinct(String[] namespaces) {
        Set<String> set = new LinkedHashSet<>(namespaces == null ? 0 : namespaces.length);
        if (namespaces != null) {
            for (String ns : namespaces) {
                if (ns != null && !ns.isEmpty()) {
                    set.add(ns);
                }
            }
        }
        return set;
    }

    /** Normalises a registry name to its namespace, for building the observed set. */
    public static String nsOf(String registryName) {
        return ItemElement.namespaceOf(registryName);
    }
}
