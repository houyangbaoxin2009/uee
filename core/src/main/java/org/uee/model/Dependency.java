package org.uee.model;

/**
 * One dependency entry declared by a mod.
 *
 * <p>Structured rather than a {@code "id@range"} string, because the analysis that matters depends
 * on the kind: a required-but-absent dependency is a broken instance, while an explicitly declared
 * incompatibility is a mod author telling you two mods do not mix. Both are invisible if the entry
 * is only carried as text.
 *
 * <p>Also structurally informative when a pair of mods disagree — A declaring B incompatible while B
 * declares A required is a real, reportable tension that no string-scanning export can show.
 */
public record Dependency(String id, String versionRange, Kind kind) {

    /** What the declaring mod says about this dependency. */
    public enum Kind {
        /** Must be present, or the game will not start. */
        REQUIRED,
        /** Happy to have, works without. */
        OPTIONAL,
        /** Declared as incompatible — an explicit conflict statement. */
        INCOMPATIBLE,
        /** Bundled inside the declaring mod rather than provided separately. */
        EMBEDDED,
        /** A dependency whose declared kind could not be determined. */
        UNKNOWN;

        /** Parses a loader-neutral token, falling back to {@link #UNKNOWN}. */
        public static Kind of(String token) {
            if (token == null) {
                return UNKNOWN;
            }
            return switch (token.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "required", "require", "depends", "mandatory" -> REQUIRED;
                case "optional", "recommends", "recommend", "suggests", "suggest", "breaks_but_ok" -> OPTIONAL;
                case "incompatible", "breaks", "conflicts", "conflict" -> INCOMPATIBLE;
                case "embedded", "include", "included", "bundled" -> EMBEDDED;
                default -> UNKNOWN;
            };
        }
    }

    public Dependency {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("dependency id is required");
        }
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    /** A required dependency with no version constraint. */
    public static Dependency required(String id) {
        return new Dependency(id, null, Kind.REQUIRED);
    }

    /**
     * Parses the {@code "id@range"} form the loaders' metadata is flattened into, keeping whatever
     * kind was already known.
     */
    public static Dependency parse(String text, Kind kind) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int at = text.indexOf('@');
        if (at < 0) {
            return new Dependency(text, null, kind);
        }
        return new Dependency(text.substring(0, at), text.substring(at + 1), kind);
    }

    public boolean hasVersionRange() {
        return versionRange != null && !versionRange.isEmpty();
    }
}
