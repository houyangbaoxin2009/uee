package org.uee.analysis;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.uee.model.Dependency;
import org.uee.model.ModElement;

/**
 * Checks whether the mods in front of it can actually run together.
 *
 * <h2>What this adds over the dependency check</h2>
 *
 * <p>{@link DependencyAnalysis} answers "is everything here" — a mod that is absent, an id declared twice,
 * a pair that declares itself incompatible. Those are facts stated in metadata and read back out. This check
 * answers the questions nobody stated, which are the ones a pack author has to work out by hand:
 *
 * <ul>
 *   <li>a mod that <em>is</em> present but at a version outside the range that was asked for;</li>
 *   <li>a set of mods that require each other in a loop, so no load order exists;</li>
 *   <li>a jar built for a different loader than the one running;</li>
 *   <li>a jar built for a different game version than the one running.</li>
 * </ul>
 *
 * <p>The last two are possible only because the context carries what is running. Without that, "built for
 * Fabric" is a field with nothing to compare it to, which is why the field sat unread.
 *
 * <h2>Linear, and deliberately quiet when it cannot tell</h2>
 *
 * <p>One pass to index the mods, one walk of the dependency edges, one walk for cycles. Nothing is compared
 * against everything: a pack has hundreds of mods and thousands of edges, and an analysis that costs more
 * than the export it accompanies is one nobody leaves switched on.
 *
 * <p>And every verdict that rests on comparing versions is allowed to come back unresolved, reported as
 * such. Version strings are written by hand in a dozen styles and the game has changed its own scheme once,
 * so a comparator that always produces an answer would produce wrong ones quietly. An unresolved
 * comparison is worth less than a resolved one and much more than a false accusation.
 */
public final class CompatibilityAnalysis implements Analysis {

    @Override
    public String id() {
        return "compatibility";
    }

    @Override
    public String description() {
        return "present but out-of-range versions, requiring cycles, jars built for another loader "
                + "or another game version";
    }

    @Override
    public Stage stage() {
        return Stage.PRE_COLLECTION;
    }

    @Override
    public void run(AnalysisContext ctx, AnalysisOutput out) {
        Map<String, ModElement> byId = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            byId.putIfAbsent(m.id(), m);
        }

        rangesNotSatisfied(ctx, byId, out);
        requiringCycles(ctx, byId, out);
        builtForSomethingElse(ctx, out);
    }

    // ---------------------------------------------------------------- versions out of range

    /**
     * A dependency that is present, but not at a version the declarer asked for.
     *
     * <p>The loader has already had its say — a run happens because the game started — so this is reported
     * as a warning rather than as an error. It is still worth saying: loaders differ in how strictly they
     * enforce a range, optional dependencies are not enforced at all, and a pack that is being assembled
     * before it is launched has had no enforcement yet.
     */
    private static void rangesNotSatisfied(AnalysisContext ctx, Map<String, ModElement> byId,
            AnalysisOutput out) {
        for (ModElement m : ctx.mods()) {
            for (Dependency d : m.dependencies()) {
                if (d.kind() == Dependency.Kind.INCOMPATIBLE) {
                    // Not a version question; DependencyAnalysis owns declared incompatibilities.
                    continue;
                }
                if (!d.hasVersionRange() || d.versionRange().isBlank()) {
                    continue;
                }
                ModElement present = byId.get(d.id());
                if (present == null) {
                    // Absent is DependencyAnalysis's finding, not this one.
                    continue;
                }
                VersionRanges.Verdict verdict = VersionRanges.check(present.version(), d.versionRange());
                if (verdict == VersionRanges.Verdict.VIOLATED) {
                    out.finding(new Finding("version_out_of_range", Finding.Severity.WARN, present.id(),
                            "requires " + d.versionRange() + " but " + present.version() + " is present",
                            Finding.detail(
                                    "required_by", m.id(),
                                    "dependency_kind", d.kind().name().toLowerCase(Locale.ROOT),
                                    "declared_range", d.versionRange(),
                                    "present_version", present.version())));
                } else if (verdict == VersionRanges.Verdict.UNKNOWN) {
                    // Said out loud rather than omitted, so that a clean report is not mistaken for a clean
                    // answer: this comparison was never made.
                    out.finding(new Finding("version_range_unreadable", Finding.Severity.INFO, present.id(),
                            "requires " + d.versionRange() + ", which could not be compared with "
                                    + present.version(),
                            Finding.detail("required_by", m.id(), "declared_range", d.versionRange(),
                                    "present_version", present.version())));
                }
            }
        }
    }

    // ---------------------------------------------------------------- cycles

    /**
     * Mods that require each other in a loop.
     *
     * <p>Depth-first with three colours, which finds every cycle in one walk. A cycle is reported once, at
     * the point where it closes, and described by the loop itself rather than by the pair of edges that
     * happened to be visited first — the loop is the thing that has no answer, and naming only two of its
     * members would send a reader looking for a pair where there is a ring.
     */
    private static void requiringCycles(AnalysisContext ctx, Map<String, ModElement> byId,
            AnalysisOutput out) {
        Map<String, List<String>> requires = new LinkedHashMap<>();
        for (ModElement m : ctx.mods()) {
            List<String> targets = new ArrayList<>(4);
            for (Dependency d : m.dependencies()) {
                // Only requirements can make a load order impossible. An optional edge that points back is
                // not a cycle, because nothing has to happen in any order for it.
                if (d.kind() == Dependency.Kind.REQUIRED && byId.containsKey(d.id())
                        && !d.id().equals(m.id())) {
                    targets.add(d.id());
                }
            }
            requires.put(m.id(), targets);
        }

        Set<String> done = new LinkedHashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String id : requires.keySet()) {
            if (!done.contains(id)) {
                walk(id, requires, done, onPath, path, reported, out);
            }
        }
    }

    private static void walk(String id, Map<String, List<String>> requires, Set<String> done,
            Set<String> onPath, Deque<String> path, Set<String> reported, AnalysisOutput out) {
        onPath.add(id);
        path.addLast(id);
        for (String next : requires.getOrDefault(id, List.of())) {
            if (onPath.contains(next)) {
                // The cycle is the tail of the path starting at the first occurrence of `next`.
                List<String> cycle = new ArrayList<>(4);
                boolean started = false;
                for (String step : path) {
                    if (step.equals(next)) {
                        started = true;
                    }
                    if (started) {
                        cycle.add(step);
                    }
                }
                String key = String.join(">", cycle);
                if (reported.add(key)) {
                    out.finding(new Finding("requiring_cycle", Finding.Severity.WARN, next,
                            "these mods require each other in a loop, so no load order satisfies them",
                            Finding.detail("loop", String.join(" -> ", cycle) + " -> " + next,
                                    "length", Integer.toString(cycle.size()))));
                }
            } else if (!done.contains(next)) {
                walk(next, requires, done, onPath, path, reported, out);
            }
        }
        path.removeLast();
        onPath.remove(id);
        done.add(id);
    }

    // ---------------------------------------------------------------- built for something else

    /** A jar that was built for a loader or a game version other than the one running. */
    private static void builtForSomethingElse(AnalysisContext ctx, AnalysisOutput out) {
        String runningLoader = ctx.runningLoader();
        String runningMinecraft = ctx.runningMinecraft();
        // Grouped by the version declared, so that a pack where most mods were built for the previous patch
        // produces one line rather than one per mod.
        Map<String, List<String>> builtForOther = new LinkedHashMap<>();

        for (ModElement m : ctx.mods()) {
            if (runningLoader != null && m.loader() != null && !m.loader().isBlank()
                    && !canLoad(runningLoader, m.loader())) {
                out.finding(new Finding("built_for_another_loader", Finding.Severity.WARN, m.id(),
                        "built for " + m.loader() + ", but " + runningLoader + " is running",
                        Finding.detail("built_for", m.loader(), "running", runningLoader,
                                "container", m.sourceFile() == null ? "" : m.sourceFile())));
            }

            if (runningMinecraft == null || m.minecraftVersion() == null
                    || m.minecraftVersion().isBlank()) {
                continue;
            }
            String declared = m.minecraftVersion().trim();
            if (declared.indexOf('[') >= 0 || declared.indexOf('(') >= 0) {
                // A declared range is a claim that can be tested.
                if (VersionRanges.check(runningMinecraft, declared) == VersionRanges.Verdict.VIOLATED) {
                    out.finding(new Finding("built_for_another_game_version", Finding.Severity.WARN,
                            m.id(), "declares game version " + declared + ", but " + runningMinecraft
                                    + " is running",
                            Finding.detail("declared", declared, "running", runningMinecraft,
                                    "container", m.sourceFile() == null ? "" : m.sourceFile())));
                }
            } else if (!declared.equals(runningMinecraft)) {
                // A single version is not a range, and a mod built for one version routinely runs on the next,
                // so this is an observation and not a problem. Collected rather than emitted, because one line
                // per mod turns a report into a list: a pack with seventy mods would produce seventy of these
                // and bury the warnings that matter.
                builtForOther.putIfAbsent(declared, new ArrayList<>(4));
                builtForOther.get(declared).add(m.id());
            }
        }

        // One line per declared version, naming how many and which: the same information with the repetition
        // taken out, which is what makes it readable.
        for (Map.Entry<String, List<String>> e : builtForOther.entrySet()) {
            List<String> ids = e.getValue();
            java.util.Collections.sort(ids);
            out.finding(new Finding("built_for_another_game_version", Finding.Severity.INFO, e.getKey(),
                    ids.size() + " mod(s) built for game version " + e.getKey() + ", running "
                            + runningMinecraft,
                    Finding.detail("declared", e.getKey(), "running", runningMinecraft,
                            "count", Integer.toString(ids.size()),
                            "mods", String.join(", ", ids))));
        }
    }

    /**
     * Whether a loader can run a jar built for another one.
     *
     * <p>Stated as a table rather than inferred from the names, because the answer is not a hierarchy: Quilt
     * loads Fabric mods and the reverse is not true, and NeoForge does <em>not</em> load Forge mods despite
     * having forked from it. Guessing from the strings would get both of those backwards.
     */
    private static boolean canLoad(String running, String builtFor) {
        String r = running.toLowerCase(Locale.ROOT);
        String b = builtFor.toLowerCase(Locale.ROOT);
        if (r.equals(b)) {
            return true;
        }
        return switch (r) {
            case "quilt" -> b.equals("fabric");
            // Fabric and Forge cannot load each other's jars, and NeoForge and Forge are not interchangeable.
            default -> false;
        };
    }
}
