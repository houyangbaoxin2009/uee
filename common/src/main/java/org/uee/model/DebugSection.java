package org.uee.model;

/**
 * One named block of environment/diagnostic information, kept in insertion order.
 *
 * <p>Keys and values are parallel arrays rather than a map: a map of a few dozen entries per section
 * would cost more than the payload itself, and ordering is meaningful in a debug dump.
 */
public record DebugSection(String name, String[] keys, String[] values) {

    public DebugSection {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("section name is required");
        }
        keys = keys == null ? new String[0] : keys;
        values = values == null ? new String[keys.length] : values;
        if (keys.length != values.length) {
            throw new IllegalArgumentException("keys/values length mismatch in section " + name);
        }
    }

    public int size() {
        return keys.length;
    }

    /** Builds a section from alternating key/value arguments. */
    public static DebugSection of(String name, String... pairs) {
        if ((pairs.length & 1) != 0) {
            throw new IllegalArgumentException("expected an even number of key/value arguments");
        }
        int n = pairs.length / 2;
        String[] k = new String[n];
        String[] v = new String[n];
        for (int i = 0; i < n; i++) {
            k[i] = pairs[i * 2];
            v[i] = pairs[i * 2 + 1];
        }
        return new DebugSection(name, k, v);
    }
}
