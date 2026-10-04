package org.uee.model;

/**
 * A loaded mod's metadata — the layer-A record.
 *
 * <p>Layer A reads loader metadata only and calls no Minecraft code, which is why it can cover every
 * game version from a single build (the path the aligned {@code Loaded Mods Exporter} validates).
 * UEE additionally records the detected loader, the source container and structured dependency
 * entries, which is where it goes beyond that tool's mod-id/name/version triple.
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
        String[] dependencies,
        String[] providers,
        String sourceFile) {

    public ModElement {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("mod id is required");
        }
        authors = authors == null ? new String[0] : authors;
        dependencies = dependencies == null ? new String[0] : dependencies;
        providers = providers == null ? new String[0] : providers;
        namespace = namespace == null || namespace.isEmpty() ? id : namespace;
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
}
