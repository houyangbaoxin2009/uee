package org.uee.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.uee.debug.MixinConfig;
import org.uee.debug.ModContainerScanner;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * The facts an analysis runs against.
 *
 * <p>This class is the seam that decouples collection from analysis. The collecting side knows nothing
 * about analyses: it records what it saw here and moves on. The analysing side knows nothing about
 * how collection happened: it reads these facts and produces findings. Either can change without the
 * other.
 *
 * <p>Facts fall into two groups, and the distinction is what makes "analyse without exporting"
 * possible:
 *
 * <h2>Known before collection (loader metadata only)</h2>
 * <ul>
 *   <li>the mod list, their declared dependencies and their containers
 *   <li>the mixin configs and the classes they patch
 *   <li>the namespaces mods <em>claim</em>
 * </ul>
 * These need no registry access, so an analysis over them runs even where collection cannot — for
 * instance before the registries are frozen, or on a build that cannot reach them at all.
 *
 * <h2>Known only after collection</h2>
 * <ul>
 *   <li>which namespaces actually <em>appear</em> in the registries, and under which category
 *   <li>how many elements each category produced
 *   <li>how many elements the namespace filter discarded
 * </ul>
 * These are observations made while streaming records out, so they cannot exist beforehand. Every
 * analysis that needs them declares {@link Analysis.Stage#POST_COLLECTION} and is skipped when no
 * collection happened.
 *
 * <p>Observation storage is bounded by (categories × namespaces), not by element count, so a large
 * pack costs the same as a small one. Nothing here retains the elements themselves — they are
 * streamed to disk and forgotten, which is what keeps peak memory independent of pack size.
 */
public final class AnalysisContext {

    private final List<ModElement> mods;
    private final Map<String, ModContainerScanner.ContainerInfo> containers;
    private final List<MixinConfig> mixins;
    private final boolean includePaths;

    private final Map<ElementKind, Integer> counted = new EnumMap<>(ElementKind.class);
    private final Map<ElementKind, Integer> filtered = new EnumMap<>(ElementKind.class);
    private final Map<ElementKind, Map<String, Integer>> byNamespace = new EnumMap<>(ElementKind.class);
    private final Set<String> observedNamespaces = new TreeSet<>();

    public AnalysisContext(List<ModElement> mods,
            Map<String, ModContainerScanner.ContainerInfo> containers,
            List<MixinConfig> mixins, boolean includePaths) {
        this.mods = mods == null ? List.of() : List.copyOf(mods);
        this.containers = containers == null ? Map.of() : Map.copyOf(containers);
        this.mixins = mixins == null ? List.of() : List.copyOf(mixins);
        this.includePaths = includePaths;
    }

    // ---------------------------------------------------------------- pre-collection facts

    /** The loaded mods. */
    public List<ModElement> mods() {
        return mods;
    }

    /** Mod id to container inspection, for the containers that could be read. */
    public Map<String, ModContainerScanner.ContainerInfo> containers() {
        return containers;
    }

    /** Parsed mixin configs, in discovery order. */
    public List<MixinConfig> mixins() {
        return mixins;
    }

    /**
     * Whether full container paths may be recorded.
     *
     * <p>Off by default: output is meant to be published, and an absolute path discloses the account
     * name and directory layout.
     */
    public boolean includePaths() {
        return includePaths;
    }

    /** Looks a mod up by id, or {@code null}. */
    public ModElement mod(String id) {
        if (id == null) {
            return null;
        }
        for (ModElement m : mods) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** Whether a mod id is present. */
    public boolean hasMod(String id) {
        return mod(id) != null;
    }

    // ---------------------------------------------------------------- observation

    /** Records that one element of this category in this namespace was written. */
    public void observe(ElementKind kind, String namespace) {
        counted.merge(kind, 1, Integer::sum);
        if (namespace != null && !namespace.isEmpty()) {
            byNamespace.computeIfAbsent(kind, k -> new TreeMap<>())
                    .merge(namespace, 1, Integer::sum);
            observedNamespaces.add(namespace);
        }
    }

    /** Records that one element was discarded by the namespace filter. */
    public void filter(ElementKind kind, String namespace) {
        filtered.merge(kind, 1, Integer::sum);
    }

    // ---------------------------------------------------------------- post-collection facts

    /** Elements written for a category. */
    public int counted(ElementKind kind) {
        return counted.getOrDefault(kind, 0);
    }

    /** Elements discarded by the filter for a category. */
    public int filtered(ElementKind kind) {
        return filtered.getOrDefault(kind, 0);
    }

    /** Elements written across every category. */
    public int totalCounted() {
        int sum = 0;
        for (int n : counted.values()) {
            sum += n;
        }
        return sum;
    }

    /** Total elements discarded by the filter. */
    public int totalFiltered() {
        int sum = 0;
        for (int n : filtered.values()) {
            sum += n;
        }
        return sum;
    }

    /** Categories that produced at least one element, in enum order. */
    public Set<ElementKind> countedKinds() {
        Set<ElementKind> out = new TreeSet<>();
        counted.forEach((k, n) -> {
            if (n > 0) {
                out.add(k);
            }
        });
        return out;
    }

    /** Every namespace seen in any registry, whether or not a mod claims it. */
    public Set<String> observedNamespaces() {
        return Collections.unmodifiableSet(observedNamespaces);
    }

    /** Namespaces seen for one category, in sorted order. */
    public Set<String> namespacesOf(ElementKind kind) {
        return Collections.unmodifiableSet(byNamespace.getOrDefault(kind, Map.of()).keySet());
    }

    /** Per-namespace element counts for a category, for coverage reporting. */
    public Map<String, Integer> countByNamespace(ElementKind kind) {
        Map<String, Integer> counts = byNamespace.get(kind);
        return counts == null ? Map.of() : Collections.unmodifiableMap(counts);
    }

    /** True when collection actually ran and produced observations. */
    public boolean collected() {
        return totalCounted() > 0 || totalFiltered() > 0;
    }
}
