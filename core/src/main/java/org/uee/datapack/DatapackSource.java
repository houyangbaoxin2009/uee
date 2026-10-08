package org.uee.datapack;

import java.nio.file.Path;
import java.util.Locale;

/**
 * A place UEE definitions can come from.
 *
 * <p>Origin matters beyond bookkeeping: when two datapacks define the same flow id the winner has to
 * be decided, and "the one the user put in the global directory" should beat "one that arrived inside
 * a mod jar". Ordering by origin makes that deterministic instead of depending on directory listing
 * order, which differs between filesystems.
 *
 * @param id the datapack's id: its directory name, or the mod id for one found in a mod
 * @param path the datapack's root, or {@code null} when it was not read from a directory
 * @param origin where it came from
 */
public record DatapackSource(String id, Path path, Origin origin) {

    /** Where a datapack was found, in increasing order of precedence. */
    public enum Origin {
        /** Inside a mod's own resources; lowest precedence, since a mod cannot know the user's intent. */
        MOD("mod", 0),
        /** A world's own {@code datapacks/} directory. */
        WORLD("world", 1),
        /** The global directory UEE or another mod provides. */
        GLOBAL("global", 2);

        private final String token;
        private final int precedence;

        Origin(String token, int precedence) {
            this.token = token;
            this.precedence = precedence;
        }

        public String token() {
            return token;
        }

        /** Higher wins when two sources define the same id. */
        public int precedence() {
            return precedence;
        }

        public static Origin of(String token) {
            if (token == null) {
                return MOD;
            }
            String t = token.trim().toLowerCase(Locale.ROOT);
            for (Origin o : values()) {
                if (o.token.equals(t)) {
                    return o;
                }
            }
            return MOD;
        }
    }

    public DatapackSource {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("datapack id is required");
        }
        origin = origin == null ? Origin.MOD : origin;
    }

    /** A source that was not read from a directory, e.g. one discovered inside a mod's resources. */
    public static DatapackSource of(String id, Origin origin) {
        return new DatapackSource(id, null, origin);
    }

    public boolean hasPath() {
        return path != null;
    }
}
