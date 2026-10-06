package org.uee;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.uee.analysis.RegistrationOrder;

/**
 * Checks the registration order derivation from a registry's numbers.
 *
 * <h2>Why the numbers are the thing to test against</h2>
 *
 * <p>The derivation is pure arithmetic on a map, so it is checked directly rather than through a game. What
 * is worth checking is not the sorting but the two cases a reader would be misled by: a namespace whose
 * numbers are split in two, which must not be reported as one continuous run, and the order of the groups,
 * which is the whole point of the section.
 */
public final class RegistrationOrderTest {

    private static int failures;

    public static void main(String[] args) {
        theOrder();
        contiguity();
        edges();
        theReport();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** A registry as it usually looks: the game's entries, then each mod's block. */
    private static Map<Integer, String> typical() {
        Map<Integer, String> ids = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            ids.put(i, "minecraft");
        }
        for (int i = 5; i < 8; i++) {
            ids.put(i, "alpha");
        }
        for (int i = 8; i < 10; i++) {
            ids.put(i, "beta");
        }
        return ids;
    }

    private static void theOrder() {
        section("the order");

        RegistrationOrder order = RegistrationOrder.of(typical());
        check("every namespace is a group", order.namespaces() == 3);
        check("and every entry is counted", order.entries() == 10);

        // Ordered by where each namespace starts, which is the question being asked: who went first.
        check("the game comes first", order.spans().get(0).namespace().equals("minecraft"));
        check("then alpha", order.spans().get(1).namespace().equals("alpha"));
        check("then beta", order.spans().get(2).namespace().equals("beta"));

        check("the first block starts at zero", order.spans().get(0).lowest() == 0);
        check("and ends where the next begins", order.spans().get(0).highest() == 4);
        check("the second block is where the first left off", order.spans().get(1).lowest() == 5);
        check("and the third follows it", order.spans().get(2).lowest() == 8);

        // The map's iteration order must not matter: a registry's numbers are the order, not the order the
        // caller happened to hand them over in.
        Map<Integer, String> shuffled = new LinkedHashMap<>();
        shuffled.put(9, "beta");
        shuffled.put(0, "minecraft");
        shuffled.put(7, "alpha");
        shuffled.put(5, "alpha");
        shuffled.put(3, "minecraft");
        RegistrationOrder same = RegistrationOrder.of(shuffled);
        check("the order does not depend on the map's iteration order",
                same.spans().get(0).namespace().equals("minecraft")
                        && same.spans().get(1).namespace().equals("alpha"));
        check("and a gap is still a gap",
                same.entries() == 5 && same.namespaces() == 3);
    }

    private static void contiguity() {
        section("contiguity, which is not assumed");

        // A mod that registered in two passes, or that was triggered later by another: two blocks with
        // someone else in between. Reporting only the lowest and highest number would describe this as one
        // unbroken run of nine, which it is not.
        Map<Integer, String> split = new LinkedHashMap<>();
        split.put(0, "minecraft");
        split.put(1, "early");
        split.put(2, "later");
        split.put(3, "early");
        split.put(4, "later");
        split.put(5, "early");

        RegistrationOrder order = RegistrationOrder.of(split);
        RegistrationOrder.Span early = null;
        for (RegistrationOrder.Span span : order.spans()) {
            if (span.namespace().equals("early")) {
                early = span;
            }
        }
        check("the split namespace is found", early != null);
        check("its numbers are not reported as contiguous", !early.contiguous());
        check("its lowest is right", early.lowest() == 1);
        check("its highest is right", early.highest() == 5);
        check("and its count is the number of entries, not the span",
                early.count() == 3 && early.span() == 5);
        // Both namespaces caught in the middle are non-contiguous, not just the one being looked at: the
        // interloper is split by the same interleaving. The first version of this expected one and was
        // simply wrong about its own fixture.
        check("both interleaved namespaces are listed", order.interleaved().size() == 2);
        check("and the game, which has one entry, is not among them",
                order.interleaved().stream().noneMatch(s2 -> s2.namespace().equals("minecraft")));

        // A single entry is trivially contiguous, which must fall out of the arithmetic rather than
        // needing a case of its own.
        RegistrationOrder one = RegistrationOrder.of(Map.of(7, "solo"));
        check("a single entry is contiguous", one.spans().get(0).contiguous());
        check("with a span of one", one.spans().get(0).span() == 1);
        check("and it is not listed as interleaved", one.interleaved().isEmpty());

        // And a genuinely unbroken run is not flagged.
        check("a normal block is contiguous",
                RegistrationOrder.of(typical()).interleaved().isEmpty());
    }

    private static void edges() {
        section("the edges");

        check("nothing in gives nothing out", RegistrationOrder.of(Map.of()).spans().isEmpty());
        check("and null too", RegistrationOrder.of(null).spans().isEmpty());
        check("with no entries counted", RegistrationOrder.of(null).entries() == 0);

        // Nulls are skipped rather than throwing: a debug section is the last place that should fail the
        // export, and a registry that reported a null id or namespace has told us something either way.
        Map<Integer, String> withNulls = new LinkedHashMap<>();
        withNulls.put(0, "minecraft");
        withNulls.put(null, "someone");
        withNulls.put(1, null);
        RegistrationOrder order = RegistrationOrder.of(withNulls);
        check("a null id or namespace is skipped", order.entries() == 1);
        check("and only the real entry is reported", order.namespaces() == 1);

        // A registry whose numbers start above zero, which is normal for one that reserves the low numbers.
        RegistrationOrder offset = RegistrationOrder.of(Map.of(100, "example", 101, "example"));
        check("a block that does not start at zero works", offset.spans().get(0).lowest() == 100);
        check("and is contiguous", offset.spans().get(0).contiguous());
    }

    private static void theReport() {
        section("the report a section is built from");

        Map<String, String> pairs = RegistrationOrder.of(typical()).asPairs();
        check("the totals are reported", pairs.containsKey("entries")
                && pairs.containsKey("namespaces"));
        check("the entry count is right", "10".equals(pairs.get("entries")));
        check("the namespace count is right", "3".equals(pairs.get("namespaces")));
        check("one line per namespace, numbered", pairs.size() == 2 + 3);
        check("the first line names the game and its range",
                pairs.get("0").equals("minecraft 0-4 (5)"));
        check("a split namespace says so",
                pairs.get("1") != null);

        Map<String, String> splitPairs = RegistrationOrder.of(Map.of(0, "a", 1, "b", 2, "a")).asPairs();
        check("an interleaved namespace is marked in the line",
                splitPairs.get("0").contains("not contiguous"));
        check("and its count is not its span",
                splitPairs.get("0").equals("a 0-2 (2, not contiguous)"));
        // The namespace in the middle is interleaved too, with one entry whose span covers both neighbours.
        check("so is the one in the middle",
                splitPairs.get("1").equals("b 1-1 (1)"));
    }

    // ---------------------------------------------------------------- harness

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
