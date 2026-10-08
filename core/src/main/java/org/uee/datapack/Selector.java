package org.uee.datapack;

import java.util.Locale;

/**
 * A declarative selection over collected elements.
 *
 * <p>This is what makes a datapack able to name a <em>new</em> collection target without new Java
 * code: the element model is fixed, but which elements go into an output is data.
 *
 * <p>Every field is optional and an empty selector accepts everything, so the simplest useful target
 * is "this whole category, in its own file" and the specific ones are refinements of that.
 *
 * <p>Matching is exact or prefix-based, deliberately without regular expressions. A pattern language
 * would be more expressive and would also let a datapack author write a pattern that backtracks
 * catastrophically on a large registry — a data file should not be able to make the exporter hang.
 *
 * @param namespace only elements in this namespace
 * @param id exact {@code namespace:path} match
 * @param tag the element must carry this tag id
 * @param prefix the path part must start with this
 * @param suffix the path part must end with this
 * @param contains the path part must contain this
 */
public record Selector(String namespace, String id, String tag, String prefix, String suffix,
        String contains) {

    /** A selector that accepts everything. */
    public static final Selector ALL = new Selector(null, null, null, null, null, null);

    public Selector {
        namespace = blankToNull(namespace);
        id = blankToNull(id);
        tag = blankToNull(tag);
        prefix = blankToNull(prefix);
        suffix = blankToNull(suffix);
        contains = blankToNull(contains);
    }

    /** Whether this selector constrains anything at all. */
    public boolean isUnconstrained() {
        return namespace == null && id == null && tag == null
                && prefix == null && suffix == null && contains == null;
    }

    /** How many constraints are set, for reporting which selector won. */
    public int specificity() {
        int n = 0;
        for (String s : new String[] {namespace, id, tag, prefix, suffix, contains}) {
            if (s != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * Tests one element.
     *
     * @param registryName {@code namespace:path}, or {@code null} for records without one
     * @param elementNamespace the element's namespace
     * @param tags tag ids the element carries, possibly empty
     */
    public boolean accepts(String registryName, String elementNamespace, String[] tags) {
        if (namespace != null && !namespace.equals(elementNamespace)) {
            return false;
        }
        if (id != null && !id.equals(registryName)) {
            return false;
        }
        if (tag != null && !hasTag(tags)) {
            return false;
        }
        if (prefix != null || suffix != null || contains != null) {
            String path = pathOf(registryName);
            if (path == null) {
                return false;
            }
            if (prefix != null && !path.startsWith(prefix)) {
                return false;
            }
            if (suffix != null && !path.endsWith(suffix)) {
                return false;
            }
            if (contains != null && !path.contains(contains)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasTag(String[] tags) {
        if (tags == null) {
            return false;
        }
        for (String t : tags) {
            if (tag.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /** The part of a registry name after the colon, or the whole string when there is no colon. */
    private static String pathOf(String registryName) {
        if (registryName == null) {
            return null;
        }
        int colon = registryName.indexOf(':');
        return colon < 0 ? registryName : registryName.substring(colon + 1);
    }

    /** A short human-readable description, for the command surface and for findings. */
    public String describe() {
        if (isUnconstrained()) {
            return "everything";
        }
        StringBuilder sb = new StringBuilder(48);
        append(sb, "namespace", namespace);
        append(sb, "id", id);
        append(sb, "tag", tag);
        append(sb, "prefix", prefix);
        append(sb, "suffix", suffix);
        append(sb, "contains", contains);
        return sb.toString();
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(value);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Normalises a namespace for comparison; namespaces are lower case by convention. */
    static String normalizeNamespace(String ns) {
        return ns == null ? null : ns.toLowerCase(Locale.ROOT);
    }
}
