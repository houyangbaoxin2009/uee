package org.uee.debug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.uee.model.Dependency;
import org.uee.util.JsonReader;

/**
 * Reads a mod's own descriptor file out of its container.
 *
 * <p>Used in preference to a loader's metadata API where the descriptor is a JSON document, because
 * the file on disk is a stable, documented format while the loader API that parses it changes shape
 * between loader versions. Reading the file also means this path is testable with a jar fixture and
 * no game at all.
 *
 * <p>Covers {@code fabric.mod.json} and {@code quilt.mod.json}. The Forge-family descriptors are
 * TOML, which the loaders' own APIs already expose reliably, so they are not handled here.
 *
 * <p>Reads the full dependency vocabulary rather than only the required set. The distinction between
 * {@code depends}, {@code recommends}, {@code suggests}, {@code conflicts} and {@code breaks} is what
 * makes a real conflict report possible, and flattening them to one list would throw that away.
 */
public final class ModDescriptorReader {

    /** Descriptor file names this reader understands, in the order they are tried. */
    public static final String FABRIC_DESCRIPTOR = "fabric.mod.json";
    public static final String QUILT_DESCRIPTOR = "quilt.mod.json";

    private ModDescriptorReader() {
    }

    /**
     * What a descriptor declared. Absent fields are {@code null} or empty.
     *
     * @param source the descriptor file name the values came from
     * @param id the mod id
     * @param name the display name
     * @param version the declared version
     * @param description the declared description
     * @param license the declared license
     * @param authors declared authors
     * @param dependencies every declared dependency, with its kind
     * @param providers extra namespaces the mod satisfies
     * @param mixinConfigs mixin config resources the mod declares, which is more precise than
     *     scanning the container for them
     */
    public record Descriptor(String source, String id, String name, String version,
            String description, String license, String[] authors, Dependency[] dependencies,
            String[] providers, String[] mixinConfigs) {

        public Descriptor {
            authors = authors == null ? new String[0] : authors;
            dependencies = dependencies == null ? new Dependency[0] : dependencies;
            providers = providers == null ? new String[0] : providers;
            mixinConfigs = mixinConfigs == null ? new String[0] : mixinConfigs;
        }
    }

    /**
     * Reads a container's descriptor, or returns {@code null} when it declares none this reader
     * understands.
     *
     * <p>Never throws for a malformed descriptor: a third-party mod with a broken file is reported as
     * unreadable by the caller's fallback rather than aborting the scan.
     */
    public static Descriptor read(Path container) {
        if (container == null) {
            return null;
        }
        String text = ModContainerScanner.readText(container, FABRIC_DESCRIPTOR);
        if (text != null) {
            try {
                return parseFabric(text);
            } catch (RuntimeException e) {
                return null;
            }
        }
        text = ModContainerScanner.readText(container, QUILT_DESCRIPTOR);
        if (text != null) {
            try {
                return parseQuilt(text);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    /** Parses a {@code fabric.mod.json}. */
    public static Descriptor parseFabric(String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        String id = JsonReader.str(root, "id");
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("fabric.mod.json has no 'id'");
        }
        List<Dependency> deps = new ArrayList<>(8);
        // Each key names a different relationship, which is the whole reason the response differs
        // from a flat required-list.
        addAll(deps, root, "depends", Dependency.Kind.REQUIRED);
        addAll(deps, root, "recommends", Dependency.Kind.OPTIONAL);
        addAll(deps, root, "suggests", Dependency.Kind.OPTIONAL);
        addAll(deps, root, "conflicts", Dependency.Kind.INCOMPATIBLE);
        addAll(deps, root, "breaks", Dependency.Kind.INCOMPATIBLE);

        return new Descriptor(FABRIC_DESCRIPTOR, id, JsonReader.str(root, "name"),
                JsonReader.str(root, "version"), JsonReader.str(root, "description"),
                JsonReader.str(root, "license"), JsonReader.strArray(root, "authors"),
                deps.toArray(new Dependency[0]), new String[0],
                JsonReader.strArray(root, "mixins"));
    }

    /** Parses a {@code quilt.mod.json}, which nests everything under {@code quilt_loader}. */
    @SuppressWarnings("unchecked")
    public static Descriptor parseQuilt(String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        Object loaderObj = root.get("quilt_loader");
        Map<String, Object> loader = loaderObj instanceof Map<?, ?> m
                ? (Map<String, Object>) m : root;
        String id = JsonReader.str(loader, "id");
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("quilt.mod.json has no 'quilt_loader.id'");
        }
        List<Dependency> deps = new ArrayList<>(8);
        Object depends = loader.get("depends");
        if (depends instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) raw;
                String depId = JsonReader.str(entry, "id");
                if (depId == null) {
                    continue;
                }
                Object versions = entry.get("versions");
                deps.add(new Dependency(depId, versions == null ? null : versions.toString(),
                        Dependency.Kind.REQUIRED));
            }
        }
        // Quilt declares extra namespaces a mod satisfies, used for namespace ownership.
        List<String> provides = new ArrayList<>(2);
        Object providesObj = loader.get("provides");
        if (providesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    String providedId = JsonReader.str((Map<String, Object>) raw, "id");
                    if (providedId != null) {
                        provides.add(providedId);
                    }
                } else if (item instanceof String s) {
                    provides.add(s);
                }
            }
        }

        Map<String, Object> metadata = loader.get("metadata") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        return new Descriptor(QUILT_DESCRIPTOR, id, JsonReader.str(metadata, "name"),
                JsonReader.str(loader, "version"), JsonReader.str(metadata, "description"),
                JsonReader.str(metadata, "license"), new String[0],
                deps.toArray(new Dependency[0]), provides.toArray(new String[0]),
                JsonReader.strArray(metadata, "mixins"));
    }

    /** Reads a {@code {modId: "versionRange"}} map into dependency entries. */
    @SuppressWarnings("unchecked")
    private static void addAll(List<Dependency> out, Map<String, Object> root, String key,
            Dependency.Kind kind) {
        Object value = root.get(key);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : ((Map<String, Object>) map).entrySet()) {
                String depId = String.valueOf(e.getKey());
                if (depId.isEmpty()) {
                    continue;
                }
                Object range = e.getValue();
                out.add(new Dependency(depId, range == null ? null : range.toString(), kind));
            }
        } else if (value instanceof List<?> list) {
            // Fabric also accepts a plain array, meaning "any of these".
            for (Object item : list) {
                if (item instanceof String s && !s.isEmpty()) {
                    out.add(new Dependency(s, null, kind));
                }
            }
        }
    }
}
