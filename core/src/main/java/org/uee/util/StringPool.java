package org.uee.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Interns the high-repetition strings of an export.
 *
 * <p>A Minecraft export is dominated by a small vocabulary repeated tens of thousands of times:
 * namespaces, loader names, registry paths, tag ids, creative tab ids. Interning replaces each
 * occurrence with a small integer, which is what makes the columnar backends (and any
 * string-pool-aware consumer downstream) cheap.
 *
 * <p>Scope is one export run: the pool is discarded once the artifact is written, so it never
 * becomes a long-lived cache.
 */
public final class StringPool {

    private final HashMap<String, Integer> index = new HashMap<>(1024);
    private final List<String> values = new ArrayList<>(1024);

    /** Returns the stable id for {@code s}, adding it if unseen. */
    public int intern(String s) {
        if (s == null) {
            return -1;
        }
        Integer id = index.get(s);
        if (id != null) {
            return id;
        }
        int next = values.size();
        index.put(s, next);
        values.add(s);
        return next;
    }

    /** Returns the id for {@code s} only if it was already interned, else {@code -1}. */
    public int idOf(String s) {
        Integer id = index.get(s);
        return id == null ? -1 : id;
    }

    public String value(int id) {
        return values.get(id);
    }

    public int size() {
        return values.size();
    }

    public List<String> values() {
        return values;
    }

    /**
     * Returns an array of interned strings for the given values, resolving each to its canonical
     * instance so that identity comparison is valid downstream. Ids are used where the backend
     * supports them; this accessor serves writers that need the text.
     */
    public String[] canonical(String[] in) {
        if (in == null || in.length == 0) {
            return in;
        }
        String[] out = new String[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = values.get(intern(in[i]));
        }
        return out;
    }
}
