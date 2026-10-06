package org.uee.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chain of class loaders in play, and which of them are shared.
 *
 * <h2>What this is for</h2>
 *
 * <p>A modded game runs its classes through a chain of loaders that the plain JVM would not have: a
 * transforming loader that rewrites classes as they load, a loader per group of mods, and the ordinary
 * application and platform loaders beneath them. When two mods cannot see each other's classes, or when a
 * class appears to exist and is not found, the answer is almost always a boundary in this chain — and
 * nothing else in an export shows it.
 *
 * <h2>Version independent, and deliberately so</h2>
 *
 * <p>Nothing here is Minecraft's: a {@code ClassLoader} is Java's, and walking parents is Java's. So this
 * belongs to the layer the design calls version independent, and it works the same on all four loaders and
 * on none of them. That is also why it is a class of its own rather than a few lines in an adapter: the
 * interesting part is the graph, and a graph is easier to get right when it can be tested without a game.
 *
 * <h2>What it can and cannot say</h2>
 *
 * <p>It can say what the chain is, how deep it goes, and — the useful part — <b>which starting points
 * resolved to the same loader</b>. Two things sharing a loader share a namespace, so they can see each
 * other's classes; two things on different loaders cannot, unless a parent relationship happens to allow
 * it. Reporting that is the whole value.
 *
 * <p>It cannot say which mod each loader serves. The loaders' public APIs do not expose that: NeoForge's
 * {@code ModContainer} and Fabric's {@code ModContainer} both stop short of the loader that defined the
 * mod's classes. So the mapping is not guessed at — a fabricated mapping would be worse than none, because
 * it would look like an answer. What is reported instead is a count of how many distinct loaders the
 * starting points landed on, which is the fact that mapping would have been used to establish.
 */
public final class ClassLoaderChain {

    /**
     * One loader in the chain.
     *
     * @param depth distance from the starting point, zero being the point itself
     * @param name the loader's own name, which may legitimately be absent
     * @param implementation the class implementing it, which is what distinguishes one loader architecture
     *     from another
     * @param labels the starting points that resolved to this loader
     * @param bootstrap whether this is the bootstrap loader, which is represented by a null parent
     */
    public record Level(int depth, String name, String implementation, List<String> labels,
            boolean bootstrap) {

        public Level {
            labels = List.copyOf(labels);
        }

        /** Whether this level is one several starting points share. */
        public boolean isShared() {
            return labels.size() > 1;
        }

        /** A readable one-line description, for a report that has no columns to put fields in. */
        public String describe() {
            String shown = name == null || name.isEmpty() ? "(unnamed)" : name;
            StringBuilder sb = new StringBuilder();
            sb.append(depth).append(": ").append(shown);
            sb.append(" [").append(implementation).append(']');
            if (!labels.isEmpty()) {
                sb.append(" <- ").append(String.join(",", labels));
            }
            if (bootstrap) {
                sb.append(" (bootstrap)");
            }
            return sb.toString();
        }
    }

    private final List<Level> levels;
    private final int startingPoints;
    private final int distinctStartingLoaders;

    private ClassLoaderChain(List<Level> levels, int startingPoints, int distinctStartingLoaders) {
        this.levels = Collections.unmodifiableList(levels);
        this.startingPoints = startingPoints;
        this.distinctStartingLoaders = distinctStartingLoaders;
    }

    /**
     * How a loader's parent is found.
     *
     * <p>Injected so the traversal can be tested. A real chain always comes from
     * {@link ClassLoader#getParent()}, and a parent is fixed when the loader is constructed, so a cycle
     * cannot be built from real loaders — which would leave the guard against one as code nobody could
     * exercise. Taking the lookup as a parameter makes the guard testable rather than merely asserted.
     */
    @FunctionalInterface
    public interface ParentLookup {
        ClassLoader parentOf(ClassLoader loader);
    }

    /** The ordinary case: real parents. */
    public static ClassLoaderChain from(Map<String, ClassLoader> starting) {
        return from(starting, ClassLoader::getParent);
    }

    /**
     * Walks the chain from the given starting points.
     *
     * <p>Starting points are labelled rather than anonymous, because a chain of four loaders with no labels
     * cannot be used for anything: the question a reader has is "which of these is mine", and a label is the
     * only thing that answers it.
     *
     * <p>Two things are handled rather than assumed. A parent may be null, which is not a gap in the data
     * but the bootstrap loader — every chain ends there. And a chain may contain a cycle, which no correct
     * JVM produces but which a malformed one would, and following it would hang rather than fail; a loader
     * already seen is therefore recognised by identity and not walked again.
     *
     * @param starting points by label; the label appears on the first level it identifies
     */
    public static ClassLoaderChain from(Map<String, ClassLoader> starting, ParentLookup parents) {
        if (starting == null || starting.isEmpty()) {
            return new ClassLoaderChain(List.of(), 0, 0);
        }

        // Identity, not equality: two loaders with the same name are still two loaders, and a loader is not
        // required to have any meaningful equals.
        Map<ClassLoader, LinkedHashSet<String>> labels = new java.util.IdentityHashMap<>();
        Map<ClassLoader, Integer> depths = new java.util.IdentityHashMap<>();
        List<ClassLoader> order = new ArrayList<>();

        for (Map.Entry<String, ClassLoader> entry : starting.entrySet()) {
            ClassLoader loader = entry.getValue();
            labels.computeIfAbsent(loader, k -> new LinkedHashSet<>()).add(entry.getKey());
            if (!depths.containsKey(loader)) {
                depths.put(loader, 0);
                order.add(loader);
            }
            // Walk up, recording depths only the first time a loader is reached: a loader reached from two
            // starting points keeps the shallower depth, which is the one a reader wants.
            ClassLoader current = loader;
            int depth = 0;
            while (current != null) {
                ClassLoader parent = parents.parentOf(current);
                if (parent == null) {
                    // The bootstrap loader is not an object. Its absence is the fact, and it is recorded as
                    // a level rather than omitted so the chain visibly terminates somewhere.
                    break;
                }
                depth++;
                Integer seen = depths.get(parent);
                if (seen == null) {
                    depths.put(parent, depth);
                    order.add(parent);
                } else if (seen <= depth) {
                    // Already recorded at this depth or shallower, so following it adds nothing and a cycle
                    // would go round forever.
                    break;
                } else {
                    depths.put(parent, depth);
                }
                current = parent;
            }
        }

        List<Level> levels = new ArrayList<>(order.size() + 1);
        for (ClassLoader loader : order) {
            levels.add(new Level(depths.get(loader), safeName(loader),
                    loader.getClass().getName(),
                    new ArrayList<>(labels.getOrDefault(loader, new LinkedHashSet<>())), false));
        }

        // The terminal level. Every chain has one, and naming it makes "reached the top" visible rather
        // than looking like the walk stopped early.
        int deepest = 0;
        for (Level level : levels) {
            deepest = Math.max(deepest, level.depth());
        }
        boolean reachesBootstrap = false;
        for (ClassLoader loader : order) {
            if (parents.parentOf(loader) == null) {
                reachesBootstrap = true;
            }
        }
        if (reachesBootstrap || levels.isEmpty()) {
            levels.add(new Level(deepest + 1, "bootstrap", "jdk.internal.loader.BootLoader", List.of(),
                    true));
        }

        levels.sort(java.util.Comparator.comparingInt(Level::depth));
        // The starting points only, which is the count the class documented and the one worth reporting.
        Set<ClassLoader> landed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        landed.addAll(starting.values());
        return new ClassLoaderChain(levels, starting.size(), landed.size());
    }

    /**
     * The loader's name, or null.
     *
     * <p>Java 9 gave class loaders names and allowed them to be absent, so a null here is a loader that has
     * not named itself rather than a failure to ask. It is normalised to null and rendered as "(unnamed)"
     * where it is shown.
     */
    private static String safeName(ClassLoader loader) {
        try {
            return loader.getName();
        } catch (Throwable t) {
            // A loader from a shaded or broken class path can throw from its own name lookup, and a debug
            // section is the last place that should fail the export.
            return null;
        }
    }

    /** The chain, shallowest first, ending at the bootstrap loader. */
    public List<Level> levels() {
        return levels;
    }

    /** How many starting points were given. */
    public int startingPoints() {
        return startingPoints;
    }

    /**
     * How many distinct loaders the starting points landed on.
     *
     * <p>Not the length of the chain, which is {@code levels().size()} and includes every ancestor. This is
     * the number that answers the question the chain was built for: if it is one and there were several
     * starting points, everything the run asked about shares a loader and therefore shares a namespace. If
     * it equals the number of starting points, nothing is shared.
     */
    public int distinctStartingLoaders() {
        return distinctStartingLoaders;
    }

    /** How many starting points landed on the same loader as another starting point. */
    public int sharedStartingPoints() {
        int shared = 0;
        for (Level level : levels) {
            if (level.isShared()) {
                shared += level.labels().size();
            }
        }
        return shared;
    }

    /** The levels several starting points share, which is the conflict-relevant part. */
    public List<Level> sharedLevels() {
        List<Level> out = new ArrayList<>();
        for (Level level : levels) {
            if (level.isShared()) {
                out.add(level);
            }
        }
        return out;
    }

    /**
     * The chain as key/value pairs, deepest last, for a report.
     *
     * <p>The levels are numbered rather than named, because a key has to be unique and a loader's name is
     * not: several loaders are legitimately unnamed, and two can share a name.
     */
    public Map<String, String> asPairs() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("startingPoints", String.valueOf(startingPoints));
        out.put("distinctStartingLoaders", String.valueOf(distinctStartingLoaders));
        out.put("sharedStartingPoints", String.valueOf(sharedStartingPoints()));
        for (Level level : levels) {
            out.put(String.valueOf(level.depth()), level.describe());
        }
        return out;
    }
}
