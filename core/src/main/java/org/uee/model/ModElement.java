package org.uee.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A loaded mod's metadata — the layer-A record.
 *
 * <p>Layer A reads loader metadata only and calls no Minecraft code, which is why it can cover every
 * game version from a single build (the path the aligned {@code Loaded Mods Exporter} validates).
 * UEE additionally records the detected loader, the source container, structured dependency entries
 * and the container's size, which is where it goes beyond that tool's mod-id/name/version triple.
 *
 * @param sourceFile the container's file name — safe to publish, and the only form written to output
 * @param containerPath the container's full path on disk, used to open it for container scanning;
 *     deliberately <em>not</em> written to output by default, because these artifacts are meant to
 *     be shared and a home directory path identifies the user
 */
public record ModElement(
        String id,
        String name,
        String version,
        String namespace,
        String loader,
        String minecraftVersion,
        String[] authors,
        String license,
        String description,
        Dependency[] dependencies,
        String[] providers,
        String sourceFile,
        String containerPath) {

    private static final String[] NO_STRINGS = new String[0];
    private static final Dependency[] NO_DEPS = new Dependency[0];

    public ModElement {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("mod id is required");
        }
        authors = authors == null ? NO_STRINGS : authors;
        dependencies = dependencies == null ? NO_DEPS : dependencies;
        providers = providers == null ? NO_STRINGS : providers;
        namespace = namespace == null || namespace.isEmpty() ? id : namespace;
    }

    /** Convenience constructor for callers with no container path to report. */
    public ModElement(String id, String name, String version, String namespace, String loader,
            String minecraftVersion, String[] authors, String license, String description,
            Dependency[] dependencies, String[] providers, String sourceFile) {
        this(id, name, version, namespace, loader, minecraftVersion, authors, license, description,
                dependencies, providers, sourceFile, null);
    }

    /** The full container path as a {@link java.nio.file.Path}, or {@code null} when unavailable. */
    public java.nio.file.Path container() {
        return containerPath == null || containerPath.isEmpty()
                ? null : java.nio.file.Path.of(containerPath);
    }

    /**
     * Returns whether this mod provides the given namespace. A mod may declare extra namespaces
     * beyond its own id, and a datapack-driven namespace may be provided by a mod of another id.
     */
    public boolean providesNamespace(String ns) {
        if (ns == null) {
            return false;
        }
        if (ns.equals(namespace) || ns.equals(id)) {
            return true;
        }
        for (String p : providers) {
            if (ns.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /** Every namespace this mod claims, its own id first. */
    public String[] claimedNamespaces() {
        if (providers.length == 0) {
            return new String[] {namespace};
        }
        String[] out = new String[providers.length + 1];
        out[0] = namespace;
        System.arraycopy(providers, 0, out, 1, providers.length);
        return out;
    }

    /** Dependencies of a given kind. */
    public List<Dependency> dependenciesOf(Dependency.Kind kind) {
        List<Dependency> out = new ArrayList<>(dependencies.length);
        for (Dependency d : dependencies) {
            if (d.kind() == kind) {
                out.add(d);
            }
        }
        return out;
    }
}
