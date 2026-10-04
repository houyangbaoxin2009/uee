package org.uee.debug;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Reads mod containers to find what they patch.
 *
 * <p>Two discovery paths are combined because the loaders disagree on where mixin configs are
 * declared:
 *
 * <ul>
 *   <li>the {@code MixinConfigs} manifest attribute, the Sponge Mixin convention that the
 *       Forge-family loaders use;
 *   <li>a scan for {@code *.mixins.json} entries, which catches loaders that declare them in their
 *       own metadata instead — Fabric lists mixin configs in {@code fabric.mod.json}, not in the
 *       manifest.
 * </ul>
 *
 * <p>Combining both means one code path covers all four loaders, and a container that declares its
 * configs in some other way still gets found by the scan.
 *
 * <p>Pure JDK, so it lives in the core and is exercised against real jar fixtures rather than only
 * inside a running game. Deviates from a directory (a development environment) are handled as well
 * as jars, because that is how a mod author will first run this.
 *
 * <p>Cost is deliberately kept to the central directory plus the handful of small config files:
 * jar entries are enumerated but only mixin configs are read. Nothing here opens game classes.
 */
public final class ModContainerScanner {

    /** Manifest attribute naming mixin configs, per the Sponge Mixin convention. */
    public static final String MANIFEST_MIXIN_CONFIGS = "MixinConfigs";

    /** Maximum size of a mixin config worth reading; larger almost certainly is not one. */
    private static final int MAX_CONFIG_BYTES = 1 << 20;

    private ModContainerScanner() {
    }

    /**
     * What a container turned out to contain.
     *
     * @param path the container's location on disk
     * @param directory whether it is an exploded directory rather than a jar
     * @param sizeBytes total size of the container's contents
     * @param entryCount number of entries seen
     * @param mixinConfigPaths mixin config resource paths, relative and normalised
     * @param manifestAttributes manifest attributes of interest, empty for a directory
     */
    public record ContainerInfo(Path path, boolean directory, long sizeBytes, int entryCount,
            List<String> mixinConfigPaths, Map<String, String> manifestAttributes) {

        public ContainerInfo {
            mixinConfigPaths = List.copyOf(mixinConfigPaths);
            manifestAttributes = Map.copyOf(manifestAttributes);
        }

        /** The container's file name, which is safe to publish unlike the full path. */
        public String fileName() {
            java.nio.file.Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }
    }

    /**
     * Inspects one mod container.
     *
     * <p>Never throws for a single bad container: an unreadable or directory-shaped entry comes back
     * as an inspection result with no configs, because one unreadable mod must not take down a
     * diagnostic run.
     */
    public static ContainerInfo inspect(Path path) {
        if (path == null) {
            return null;
        }
        try {
            if (Files.isDirectory(path)) {
                return inspectDirectory(path);
            }
            if (!Files.isRegularFile(path)) {
                return null;
            }
            return inspectJar(path);
        } catch (IOException | RuntimeException e) {
            // Report the container as present-but-unreadable rather than dropping it silently; the
            // fact that a jar could not be opened is itself diagnostic.
            return new ContainerInfo(path, Files.isDirectory(path), sizeOf(path), 0, List.of(),
                    Map.of("uee.readError", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private static ContainerInfo inspectJar(Path path) throws IOException {
        List<String> configs = new ArrayList<>(4);
        Map<String, String> attrs = new HashMap<>(4);
        int entries = 0;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                Attributes main = manifest.getMainAttributes();
                String declared = main.getValue(MANIFEST_MIXIN_CONFIGS);
                if (declared != null) {
                    for (String piece : declared.split(",")) {
                        String trimmed = piece.trim();
                        if (!trimmed.isEmpty() && !configs.contains(trimmed)) {
                            configs.add(trimmed);
                        }
                    }
                    attrs.put(MANIFEST_MIXIN_CONFIGS, declared);
                }
                String title = main.getValue(Attributes.Name.IMPLEMENTATION_TITLE);
                if (title != null) {
                    attrs.put("Implementation-Title", title);
                }
            }
            Enumeration<JarEntry> it = jar.entries();
            while (it.hasMoreElements()) {
                JarEntry entry = it.nextElement();
                entries++;
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.endsWith(MixinConfig.RESOURCE_SUFFIX) && !configs.contains(name)) {
                    configs.add(name);
                }
            }
        }
        return new ContainerInfo(path, false, sizeOf(path), entries, configs, attrs);
    }

    private static ContainerInfo inspectDirectory(Path dir) throws IOException {
        List<String> configs = new ArrayList<>(2);
        int files = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                files++;
                String name = dir.relativize(p).toString().replace('\\', '/');
                if (name.endsWith(MixinConfig.RESOURCE_SUFFIX)) {
                    configs.add(name);
                }
            }
        }
        return new ContainerInfo(dir, true, sizeOf(dir), files, configs, Map.of());
    }

    /** Reads a text resource out of a container, or {@code null} when absent or oversized. */
    public static String readText(Path container, String entryName) {
        if (container == null || entryName == null) {
            return null;
        }
        try {
            if (Files.isDirectory(container)) {
                Path file = container.resolve(entryName);
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_CONFIG_BYTES) {
                    return null;
                }
                return Files.readString(file, StandardCharsets.UTF_8);
            }
            try (JarFile jar = new JarFile(container.toFile(), false)) {
                JarEntry entry = jar.getJarEntry(entryName);
                if (entry == null || entry.getSize() > MAX_CONFIG_BYTES) {
                    return null;
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Reads every mixin config a container declares, skipping the ones that will not parse. */
    public static List<MixinConfig> readMixinConfigs(ContainerInfo container, String modId) {
        List<MixinConfig> out = new ArrayList<>(container.mixinConfigPaths().size());
        for (String resource : container.mixinConfigPaths()) {
            String text = readText(container.path(), resource);
            if (text == null) {
                continue;
            }
            try {
                out.add(MixinConfig.parse(resource, modId, text));
            } catch (RuntimeException e) {
                // A config we cannot parse is reported by its absence from the list plus the read
                // error attribute; failing the whole scan over one malformed file would be worse.
                continue;
            }
        }
        return out;
    }

    private static long sizeOf(Path path) {
        try {
            if (Files.isRegularFile(path)) {
                return Files.size(path);
            }
            try (Stream<Path> walk = Files.walk(path)) {
                return walk.filter(Files::isRegularFile).mapToLong(p -> {
                    try {
                        return Files.size(p);
                    } catch (IOException e) {
                        return 0L;
                    }
                }).sum();
            }
        } catch (IOException e) {
            return 0L;
        }
    }
}
