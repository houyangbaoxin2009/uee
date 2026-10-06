package org.uee.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order entries were registered in, read from the numbers a registry assigned them.
 *
 * <h2>How order becomes visible</h2>
 *
 * <p>A registry hands out a sequential number as each entry is registered, and the numbers are fixed once
 * it is frozen. So the numbers <em>are</em> the registration order, and grouping them by namespace shows the
 * order in which namespaces were registered — vanilla first, then each mod's block of numbers. That is what
 * a reader wants to know when asking about order: not the number of the nine-thousandth item, but "the game
 * went first, then these three mods, in this order".
 *
 * <h2>Why a namespace's numbers are not assumed to be contiguous</h2>
 *
 * <p>They usually are, because a mod's entries are registered together. But a mod that registers in two
 * passes, or one whose registration is triggered later by another mod, gets two blocks with someone else
 * in between — and reporting only the lowest and highest number would describe that as one uninterrupted
 * run. So contiguity is measured and reported rather than assumed, because a span that looks continuous and
 * is not is exactly the kind of detail this section exists to surface.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>Not load order. A namespace's block position reflects when its entries were registered, which is
 * normally when the mod loaded, but a mod that defers registration moves without having loaded differently.
 * The numbers say what the registry did, and that is all they say.
 */
public final class RegistrationOrder {

    /**
     * One namespace's block of numbers.
     *
     * @param namespace the namespace, which is also the mod id
     * @param lowest the first number assigned to it
     * @param highest the last
     * @param count how many entries it has, which is not the span unless it is contiguous
     * @param contiguous whether its numbers form an unbroken run
     */
    public record Span(String namespace, int lowest, int highest, int count, boolean contiguous) {

        /** How many numbers the span covers, including any belonging to nobody else. */
        public int span() {
            return highest - lowest + 1;
        }

        /** A readable line, which is what a debug section is made of. */
        public String describe() {
            return namespace + " " + lowest + "-" + highest + " (" + count
                    + (contiguous ? "" : ", not contiguous") + ")";
        }
    }

    private final List<Span> spans;
    private final int entries;

    private RegistrationOrder(List<Span> spans, int entries) {
        this.spans = Collections.unmodifiableList(spans);
        this.entries = entries;
    }

    /**
     * Groups numbers by namespace and orders the groups.
     *
     * @param idToNamespace every numeric id the registry has assigned, and who owns it
     */
    public static RegistrationOrder of(Map<Integer, String> idToNamespace) {
        if (idToNamespace == null || idToNamespace.isEmpty()) {
            return new RegistrationOrder(List.of(), 0);
        }

        // Collected per namespace so the numbers can be counted rather than only bounded: a namespace's
        // lowest and highest are not enough to tell a contiguous block from two blocks.
        Map<String, List<Integer>> byNamespace = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : idToNamespace.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            byNamespace.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        List<Span> spans = new ArrayList<>(byNamespace.size());
        for (Map.Entry<String, List<Integer>> entry : byNamespace.entrySet()) {
            List<Integer> ids = entry.getValue();
            Collections.sort(ids);
            int lowest = ids.get(0);
            int highest = ids.get(ids.size() - 1);
            spans.add(new Span(entry.getKey(), lowest, highest, ids.size(),
                    // An unbroken run is exactly the count matching the span. A namespace with one entry
                    // is trivially contiguous, which falls out of the same comparison rather than needing
                    // its own case.
                    ids.size() == highest - lowest + 1));
        }

        // Ordered by where each namespace starts, since that is the question: who went first.
        spans.sort(java.util.Comparator.comparingInt(Span::lowest));

        // Counted from what was accepted rather than from the size of the map handed in, since an entry
        // with a null on either side was skipped and counting it would overstate the registry.
        int counted = 0;
        for (Span span : spans) {
            counted += span.count();
        }
        return new RegistrationOrder(spans, counted);
    }

    /** The namespaces in registration order. */
    public List<Span> spans() {
        return spans;
    }

    /** Namespaces whose numbers are not one unbroken run. */
    public List<Span> interleaved() {
        List<Span> out = new ArrayList<>();
        for (Span span : spans) {
            if (!span.contiguous()) {
                out.add(span);
            }
        }
        return out;
    }

    public int entries() {
        return entries;
    }

    public int namespaces() {
        return spans.size();
    }

    /**
     * The order as key/value pairs, one line per namespace plus the totals.
     *
     * <p>Keys are numbered rather than named, for the same reason the class loader levels are: a key has to
     * be unique and a namespace is already the first word of its own value, so naming the key after it would
     * only repeat it.
     */
    public Map<String, String> asPairs() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("entries", String.valueOf(entries));
        out.put("namespaces", String.valueOf(spans.size()));
        int n = 0;
        for (Span span : spans) {
            out.put(String.valueOf(n++), span.describe());
        }
        return out;
    }
}
