package org.uee;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.uee.analysis.ClassLoaderChain;

/**
 * Checks the class loader chain.
 *
 * <h2>Tested against real loaders rather than fixtures</h2>
 *
 * <p>A class loader has no accessible constructor worth faking and a hand-written stand-in would test the
 * stand-in. The JDK supplies a real chain — every VM has an application loader, a platform loader and the
 * bootstrap loader above it — so the walk is checked against that, and the interesting cases are built by
 * composing {@link URLClassLoader}s whose parents are chosen, which is what a mod loader does.
 *
 * <p>The properties that matter are not "does it walk": they are that a shared loader is recognised as
 * shared (that is the fact a conflict investigation needs), that the chain terminates visibly, and that a
 * malformed parent relationship cannot hang it.
 */
public final class ClassLoaderTest {

    private static int failures;

    public static void main(String[] args) {
        theRealChain();
        sharing();
        theTerminalLevel();
        orderAndDepth();
        theReport();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the real chain

    private static void theRealChain() {
        section("the chain this JVM already has");

        Map<String, ClassLoader> starting = new LinkedHashMap<>();
        starting.put("this", ClassLoaderTest.class.getClassLoader());
        ClassLoaderChain chain = ClassLoaderChain.from(starting);

        check("the chain is not empty", !chain.levels().isEmpty());
        check("one starting point was recorded", chain.startingPoints() == 1);

        // Every JVM has an application loader above the test code's loader, or is one itself. Asserting the
        // chain has a second level rather than a specific class name keeps this true across JDKs.
        check("it goes up more than one level", chain.levels().size() >= 2);

        // The last level is the bootstrap loader, which is what terminates every chain.
        ClassLoaderChain.Level last = chain.levels().get(chain.levels().size() - 1);
        check("it ends at the bootstrap loader", last.bootstrap());
        check("which is named", "bootstrap".equals(last.name()));
        check("and is deeper than everything before it",
                last.depth() > chain.levels().get(chain.levels().size() - 2).depth());

        // Every level but the terminal one names the class implementing it, which is what distinguishes one
        // loader architecture from another.
        boolean implemented = true;
        for (ClassLoaderChain.Level level : chain.levels()) {
            if (level.implementation() == null || level.implementation().isEmpty()) {
                implemented = false;
            }
        }
        check("every level says what implements it", implemented);

        // The starting point is level zero and carries its label, which is the only thing that answers
        // "which of these is mine".
        ClassLoaderChain.Level first = chain.levels().get(0);
        check("the starting point is depth zero", first.depth() == 0);
        check("and carries the label it was given", first.labels().equals(List.of("this")));
        check("and is not marked shared", !first.isShared());

        check("the empty case is handled", ClassLoaderChain.from(Map.of()).levels().isEmpty());
        check("and null too", ClassLoaderChain.from(null).levels().isEmpty());
    }

    // ---------------------------------------------------------------- sharing

    private static void sharing() {
        section("sharing, which is what the chain is for");

        // Two things on one loader share a namespace and can see each other's classes; two on different
        // loaders cannot. That distinction is the entire reason to report a chain.
        URL[] empty = new URL[0];
        URLClassLoader shared = new URLClassLoader("shared-loader", empty,
                ClassLoaderTest.class.getClassLoader());

        Map<String, ClassLoader> both = new LinkedHashMap<>();
        both.put("modA", shared);
        both.put("modB", shared);
        ClassLoaderChain chain = ClassLoaderChain.from(both);

        check("two starting points on one loader are two starting points", chain.startingPoints() == 2);
        check("but one distinct loader for the starting points", chain.distinctStartingLoaders() == 1);
        check("so both are counted as shared", chain.sharedStartingPoints() == 2);
        check("and there is one shared level", chain.sharedLevels().size() == 1);
        check("which carries both labels",
                chain.sharedLevels().get(0).labels().equals(List.of("modA", "modB")));
        check("in the order they were given",
                chain.sharedLevels().get(0).labels().get(0).equals("modA"));

        // Two loaders of the same kind but different objects are different loaders. Identity, not equality:
        // a loader is not required to have a meaningful equals, and two loaders with one name are two.
        URLClassLoader other = new URLClassLoader("shared-loader", empty,
                ClassLoaderTest.class.getClassLoader());
        Map<String, ClassLoader> separate = new LinkedHashMap<>();
        separate.put("modA", shared);
        separate.put("modC", other);
        ClassLoaderChain distinct = ClassLoaderChain.from(separate);
        check("two loaders with the same name are still two loaders",
                distinct.distinctStartingLoaders() == 2);
        check("so neither is shared", distinct.sharedStartingPoints() == 0);
        check("and there are no shared levels", distinct.sharedLevels().isEmpty());

        // The name a loader gives itself is reported when it has one.
        boolean namedShared = false;
        for (ClassLoaderChain.Level level : distinct.levels()) {
            if ("shared-loader".equals(level.name())) {
                namedShared = true;
            }
        }
        check("a loader's own name is reported", namedShared);

        // The bootstrap loader has no name, and that is normalised rather than being a failure to ask.
        boolean unnamedHandled = true;
        for (ClassLoaderChain.Level level : chain.levels()) {
            if (level.name() == null && !level.bootstrap()) {
                unnamedHandled = false;
            }
        }
        check("an unnamed loader is only the bootstrap entry", unnamedHandled);
    }

    private static void theTerminalLevel() {
        section("the chain terminates visibly");

        // A chain is followed to a loader with no parent, and the absence of that parent is recorded as a
        // level rather than left out, so "reached the top" does not look like "the walk stopped early".
        URLClassLoader child = new URLClassLoader("child", new URL[0], null);
        ClassLoaderChain chain = ClassLoaderChain.from(Map.of("orphan", child));

        check("a loader with no parent still produces a terminal level",
                chain.levels().get(chain.levels().size() - 1).bootstrap());
        check("the orphan itself is level zero",
                chain.levels().get(0).labels().equals(List.of("orphan")));
        check("and the terminal level is directly above it",
                chain.levels().get(chain.levels().size() - 1).depth() == 1);
        check("no level is below zero", chain.levels().get(0).depth() == 0);

        // A cycle cannot be produced by a correct JVM -- a parent is fixed at construction -- so one is
        // built by supplying the lookup instead. Without the guard in the walk this would not fail, it would
        // hang, which is why the guard is worth having and why it is worth being able to test.
        URLClassLoader ringA = new URLClassLoader("ring-a", new URL[0], null);
        URLClassLoader ringB = new URLClassLoader("ring-b", new URL[0], null);
        Map<String, ClassLoader> entry = new LinkedHashMap<>();
        entry.put("entry", ringA);
        ClassLoaderChain ring = ClassLoaderChain.from(entry, loader -> {
            if (loader == ringA) {
                return ringB;
            }
            if (loader == ringB) {
                return ringA;
            }
            return null;
        });
        check("a cycle terminates instead of hanging", !ring.levels().isEmpty());
        // One starting point that walks into a two-member ring: one starting loader, two levels. The two
        // numbers being different is the point of keeping them apart.
        check("there is one starting loader", ring.distinctStartingLoaders() == 1);
        check("but the walk recorded both members of the ring", ring.levels().size() == 2);
        check("with no terminal entry, since neither parent was null", !ring.levels().get(
                ring.levels().size() - 1).bootstrap());
    }

    private static void orderAndDepth() {
        section("order and depth");

        URL[] empty = new URL[0];
        URLClassLoader top = new URLClassLoader("top", empty, null);
        URLClassLoader middle = new URLClassLoader("middle", empty, top);
        URLClassLoader bottom = new URLClassLoader("bottom", empty, middle);

        ClassLoaderChain chain = ClassLoaderChain.from(Map.of("start", bottom));
        List<String> names = new ArrayList<>();
        for (ClassLoaderChain.Level level : chain.levels()) {
            names.add(level.name());
        }
        // Shallowest first, so the chain reads from the thing being asked about up to the JDK.
        check("the chain reads from the starting point upward",
                names.indexOf("bottom") < names.indexOf("middle"));
        check("and middle before top", names.indexOf("middle") < names.indexOf("top"));
        check("with the bootstrap entry last",
                chain.levels().get(chain.levels().size() - 1).bootstrap());

        // Depths increase monotonically, which is what makes the numbering usable as a key.
        boolean increasing = true;
        for (int i = 1; i < chain.levels().size(); i++) {
            if (chain.levels().get(i).depth() <= chain.levels().get(i - 1).depth()) {
                increasing = false;
            }
        }
        check("depths increase", increasing);

        // A loader reached from two starting points keeps the shallower depth: the reader wants the shortest
        // route to it, not whichever walk happened to arrive first.
        Map<String, ClassLoader> twoRoutes = new LinkedHashMap<>();
        twoRoutes.put("near", middle);
        twoRoutes.put("far", bottom);
        ClassLoaderChain merged = ClassLoaderChain.from(twoRoutes);
        int middleDepth = -1;
        for (ClassLoaderChain.Level level : merged.levels()) {
            if ("middle".equals(level.name())) {
                middleDepth = level.depth();
            }
        }
        check("a loader reached twice keeps the shallower depth", middleDepth == 0);
        // Two starting points at different levels of one chain are two starting points on two
        // loaders: sharing is about landing on the *same* loader, which is not what happened here.
        check("and neither of the two is shared with the other",
                merged.sharedStartingPoints() == 0);
        check("each is its own depth zero",
                merged.levels().stream().filter(l -> l.depth() == 0).count() == 2);
    }

    private static void theReport() {
        section("the report a section is built from");

        Map<String, ClassLoader> starting = new LinkedHashMap<>();
        starting.put("uee", ClassLoaderTest.class.getClassLoader());
        ClassLoaderChain chain = ClassLoaderChain.from(starting);
        Map<String, String> pairs = chain.asPairs();

        // The counts come first so a reader sees the shape before the detail.
        check("the counts are reported", pairs.containsKey("startingPoints")
                && pairs.containsKey("distinctStartingLoaders")
                && pairs.containsKey("sharedStartingPoints"));
        check("the starting point count is right", "1".equals(pairs.get("startingPoints")));
        check("every level appears under its depth",
                pairs.size() == 3 + chain.levels().size());
        check("and a level reads as a description",
                pairs.get("0") != null && pairs.get("0").startsWith("0: ")
                        && pairs.get("0").contains("<- uee"));
        check("the bootstrap entry says so",
                pairs.get(String.valueOf(chain.levels().size() - 1)).contains("bootstrap"));

        // A shared level is described with all its labels, since that is the line a reader is looking for.
        URLClassLoader shared = new URLClassLoader("s", new URL[0], null);
        Map<String, ClassLoader> two = new LinkedHashMap<>();
        two.put("a", shared);
        two.put("b", shared);
        Map<String, String> sharedPairs = ClassLoaderChain.from(two).asPairs();
        check("a shared level names both labels",
                sharedPairs.get("0").contains("<- a,b"));
        check("and the shared count is reported",
                "2".equals(sharedPairs.get("sharedStartingPoints")));
        check("while distinct loaders for the starting points is one",
                "1".equals(sharedPairs.get("distinctStartingLoaders")));
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
