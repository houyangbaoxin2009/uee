package org.uee.config;

import java.util.Set;
import java.util.TreeSet;

/**
 * Which fields a record should carry.
 *
 * <h2>Why projection exists</h2>
 *
 * <p>Different consumers want different amounts of a record. A wiki importer wants names, stack sizes
 * and tags; a diffing tool wants identity and nothing else, so that a change in an unimportant field
 * does not show up as a change; someone building a lookup table wants the registry name and one
 * localized name. Without projection the only option is to take everything and throw most of it away,
 * which is both slower and harder to read.
 *
 * <h2>Identity is never droppable</h2>
 *
 * <p>A record with no registry name is not a smaller record, it is an unusable one — nothing could
 * refer to it. So the identity fields are always written and are not part of the mask. This is
 * enforced in {@link #isIdentity}, not merely documented, because a consumer that asked for
 * "no fields" should get records that are still joinable rather than a file of empty objects.
 *
 * <h2>Empty means everything</h2>
 *
 * <p>An empty include set is "all fields", which is the default and what every existing consumer
 * expects. That direction matters: a projection that defaulted to "nothing" would make an unrelated
 * config change silently drop data.
 */
public final class FieldMask {

    /**
     * Fields that identify a record and are never removed.
     *
     * <p>Kept together so a new element type has one place to register what makes it joinable.
     */
    private static final Set<String> IDENTITY = Set.of(
            "registryName", "namespace", "name", "englishName", "id", "type", "kind", "key");

    private final Set<String> include;
    private final Set<String> exclude;

    private FieldMask(Set<String> include, Set<String> exclude) {
        this.include = Set.copyOf(include);
        this.exclude = Set.copyOf(exclude);
    }

    /** No projection: every field is written. */
    public static FieldMask all() {
        return new FieldMask(Set.of(), Set.of());
    }

    /**
     * Builds a mask.
     *
     * <p>An {@code exclude} entry wins over an {@code include} entry, so "everything except the
     * icons" is expressible without listing the other twenty fields.
     */
    public static FieldMask of(Set<String> include, Set<String> exclude) {
        return new FieldMask(include == null ? Set.of() : include,
                exclude == null ? Set.of() : exclude);
    }

    /** Whether any projection is in effect. */
    public boolean isAll() {
        return include.isEmpty() && exclude.isEmpty();
    }

    /** Whether a field was named by the caller at all, for reporting. */
    public boolean mentions(String field) {
        return include.contains(field) || exclude.contains(field);
    }

    /**
     * Whether a field should be written.
     *
     * <p>This is the method every writer calls, and it answers yes for identity fields without
     * consulting the mask — see the class comment.
     */
    public boolean has(String field) {
        if (field == null) {
            return true;
        }
        if (isIdentity(field)) {
            return true;
        }
        if (exclude.contains(field)) {
            return false;
        }
        return include.isEmpty() || include.contains(field);
    }

    /** Whether a field is part of a record's identity, and therefore always written. */
    public static boolean isIdentity(String field) {
        return IDENTITY.contains(field);
    }

    /** Every field name this class treats as identity, for diagnostics. */
    public static Set<String> identityFields() {
        return new TreeSet<>(IDENTITY);
    }

    /** The fields the caller asked to keep, or empty for all. */
    public Set<String> include() {
        return include;
    }

    /** The fields the caller asked to drop. */
    public Set<String> exclude() {
        return exclude;
    }

    /**
     * Names the identity fields that a projection tried to remove.
     *
     * <p>Used to warn rather than to refuse: the request is satisfiable except for these, and silently
     * ignoring part of what was asked for is the kind of thing that should be said out loud.
     */
    public Set<String> ignoredIdentityRequests() {
        Set<String> out = new TreeSet<>();
        for (String field : IDENTITY) {
            if (exclude.contains(field)) {
                out.add(field);
            }
        }
        if (!include.isEmpty()) {
            for (String field : IDENTITY) {
                if (!include.contains(field)) {
                    out.add(field);
                }
            }
        }
        return out;
    }

    /**
     * Equality over the two sets.
     *
     * <p>Written out because this is a value: a configuration is compared with another to tell whether
     * a round trip changed anything, and reference equality would report a difference every time.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof FieldMask m && include.equals(m.include) && exclude.equals(m.exclude);
    }

    @Override
    public int hashCode() {
        return include.hashCode() * 31 + exclude.hashCode();
    }

    @Override
    public String toString() {
        return "FieldMask[" + describe() + "]";
    }

    /** A short description, for reporting what a run will do. */
    public String describe() {
        if (isAll()) {
            return "all fields";
        }
        StringBuilder sb = new StringBuilder(48);
        if (!include.isEmpty()) {
            sb.append("only ").append(String.join(", ", include));
        }
        if (!exclude.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("except ").append(String.join(", ", exclude));
        }
        return sb.toString();
    }
}
