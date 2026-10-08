package org.uee.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue keys the checks are described under, as literals.
 *
 * <h2>Why a table rather than a prefix</h2>
 *
 * <p>Deriving the key from the id — {@code "uee.check." + id} — looks tidier and loses three things. The
 * scan that finds the keys the code uses cannot see a key that is not written down, so a description would
 * become invisible to the check that every entry is used and to the check that every used entry exists. A
 * check whose description was missing would then be listed with a raw key and nothing would say so. And the
 * set of ids stops being closed: a new check would silently have no description rather than failing.
 *
 * <p>Written out, the mapping is checkable in both directions: every check here has a description, and
 * every description here belongs to a check. Both are asserted without a game running.
 */
public final class CheckCatalogue {

    /**
     * The description key for each check, in the order the checks run.
     *
     * <p>Insertion order rather than sorted, because this is also the order a reader sees when asking what
     * the checks are, and that order is the run order.
     */
    private static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("containers", "uee.check.containers");
        KEYS.put("dependency-graph", "uee.check.dependency-graph");
        KEYS.put("compatibility", "uee.check.compatibility");
        KEYS.put("mixins", "uee.check.mixins");
        KEYS.put("namespace-claims", "uee.check.namespace-claims");
        KEYS.put("orphan-namespaces", "uee.check.orphan-namespaces");
        KEYS.put("coverage", "uee.check.coverage");
    }

    private CheckCatalogue() {
    }

    /** The catalogue key describing a check, or null when it has none. */
    public static String keyFor(String checkId) {
        return KEYS.get(checkId);
    }

    /** Every check id that has a description, in run order. */
    public static List<String> described() {
        return new ArrayList<>(KEYS.keySet());
    }

    /** Every description key, in run order. */
    public static List<String> keys() {
        return new ArrayList<>(KEYS.values());
    }
}
