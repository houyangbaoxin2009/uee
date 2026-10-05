package org.uee.globalpack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds global datapack directories and counts the packs in them.
 *
 * <p>Two jobs, both file-system only and therefore testable without a game: locate UEE's own global
 * directory, and check which provider directories exist and how much is in them.
 *
 * <p>The pack count is what turns "the feature is on" into "the feature is doing something". A
 * directory that exists but is empty, and packs that exist but are in the wrong place, look identical
 * from a boolean and are the two things a user most needs told apart.
 */
public final class GlobalPackDiscovery {

    /** Where UEE reads global datapacks from, relative to the game directory. */
    public static final String DEFAULT_DIR = "config/uee/datapacks";

    /** Extensions a datapack can have: a directory, or a zip. */
    private static final List<String> PACK_EXTENSIONS = List.of(".zip");

    private GlobalPackDiscovery() {
    }

    /** A pack found in a global directory. */
    public record Pack(Path path, boolean directory, long sizeBytes) {

        public String name() {
            Path fileName = path.getFileName();
            return fileName == null ? path.toString() : fileName.toString();
        }
    }

    /**
     * Lists the packs directly under a global directory.
     *
     * <p>One level only, deliberately: a global datapack directory holds packs, and descending further
     * would count the contents of a pack as packs. A pack is either a directory or a {@code .zip}, and
     * anything else sitting there is not counted.
     */
    public static List<Pack> packs(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Pack> out = new ArrayList<>(4);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                if (Files.isDirectory(p)) {
                    out.add(new Pack(p, true, sizeOf(p)));
                } else if (isZip(p)) {
                    out.add(new Pack(p, false, sizeOf(p)));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** The packs in UEE's global directory for a game directory. */
    public static List<Pack> ourPacks(Path gameDirectory, Path override) {
        return packs(globalDir(gameDirectory, override));
    }

    /** The directory UEE reads global datapacks from. */
    public static Path globalDir(Path gameDirectory, Path override) {
        if (override != null) {
            return override;
        }
        return gameDirectory == null ? null : gameDirectory.resolve(DEFAULT_DIR);
    }

    /**
     * Which provider directories exist, and how many packs each holds.
     *
     * @param gameDirectory the game directory
     * @param loadedModIds ids of every loaded mod, used to decide which providers to look for
     */
    public static List<GlobalPackPolicy.Detected> detect(Path gameDirectory, List<String> loadedModIds) {
        if (gameDirectory == null || loadedModIds == null || loadedModIds.isEmpty()) {
            return List.of();
        }
        List<GlobalPackPolicy.Detected> out = new ArrayList<>(2);
        for (String modId : loadedModIds) {
            KnownProviders.Provider provider = KnownProviders.byModId(modId);
            if (provider == null) {
                continue;
            }
            List<String> present = new ArrayList<>(provider.directories().size());
            int count = 0;
            for (String relative : provider.directories()) {
                Path dir = gameDirectory.resolve(relative);
                if (Files.isDirectory(dir)) {
                    present.add(relative);
                    count += packs(dir).size();
                }
            }
            out.add(new GlobalPackPolicy.Detected(provider, present, count));
        }
        return out;
    }

    private static boolean isZip(Path p) {
        if (!Files.isRegularFile(p)) {
            return false;
        }
        String name = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String ext : PACK_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Total size of a pack, or 0 when it cannot be walked. */
    static long sizeOf(Path path) {
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
