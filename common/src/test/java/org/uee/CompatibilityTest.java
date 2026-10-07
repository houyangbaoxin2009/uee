package org.uee;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.uee.analysis.Analysis;
import org.uee.analysis.AnalysisOutput;
import org.uee.analysis.AnalysisContext;
import org.uee.analysis.AnalysisEngine;
import org.uee.analysis.CompatibilityAnalysis;
import org.uee.analysis.Finding;
import org.uee.analysis.FindingSink;
import org.uee.analysis.VersionRanges;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ModElement;

/**
 * Checks the compatibility analysis, and the version comparison underneath it.
 *
 * <h2>Why the comparison gets most of the attention</h2>
 *
 * <p>"This version is outside the range that was asked for" is an accusation, and a wrong one sends a pack
 * author to fix something that is not broken. So the comparison is three-valued and this file spends its
 * effort on the third value: the cases where it must refuse to answer. A comparator that always answers
 * would pass every check about the first two values and quietly lie.
 */
public final class CompatibilityTest {

    private static int failures;

    public static void main(String[] args) {
        versionComparison();
        rangesOutOfRange();
        requiringCycles();
        builtForSomethingElse();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the comparison itself

    private static void versionComparison() {
        section("version comparison");

        // The forms the loaders document.
        satisfied("1.2.3", "[1.0,2.0)");
        satisfied("1.0", "[1.0,2.0)");
        satisfied("1.2.3", "[1.2.3]");
        satisfied("1.2.3", "*");
        satisfied("9.9", "[1.0,)");
        satisfied("1.21.1", "1.21.x");
        satisfied("1.21.10", "1.21.x");
        satisfied("1.21.4", "1.21.+");
        satisfied("2.0", "(,3.0]");
        // Several intervals: any of them may match.
        satisfied("3.5", "[1.0,2.0),[3.0,4.0)");

        violated("2.5", "[1.0,2.0)");
        violated("0.9", "[1.0,2.0)");
        violated("2.0", "[1.0,2.0)");
        violated("1.0", "(1.0,2.0)");
        violated("3.0", "(,3.0)");
        violated("1.20.1", "1.21.x");
        violated("1.22.0", "1.21.x");
        violated("2.5", "[1.0,2.0),[3.0,4.0)");
        satisfied("1.5", "[1.0,2.0)");
        // Numeric ordering, so that ten comes after nine rather than before it.
        violated("1.9", "[1.10,2.0)");
        // A missing part counts as zero, so 1.21.1 is past an upper bound of 1.21.
        violated("1.21.1", "[1.20,1.21)");
        satisfied("1.0", "[1.0,2.0]");
        satisfied("1.0.0", "[1.0,2.0]");

        // The answer that has to exist: styles that cannot be ordered must not be guessed at.
        unknown("1.2.3-beta.1", "[1.2.3,1.2.4]");
        // Comparable after all: a missing part counts as zero, so 1.2 is below 1.2.3.
        violated("1.2", "[1.2.3,1.2.4]");
        // Two intervals need brackets to be two intervals; a bare comma is not a form anyone wrote.
        unknown("1.2.3", "1.2.3-rc1,1.2.4");
        unknown("1.2.3", "");
        unknown(null, "[1.0,2.0]");
        unknown("1.2.3", "[1.0,2.0");
        unknown("1.2.3", "1.0,");
        // A qualifier only unsettles the comparison when the parts before it are equal; here 1.2.3 is
        // plainly inside [1.2.0,1.3.0], pre-release or not.
        satisfied("v1.2.3-rc", "[1.2.0,1.3.0]");

        // A qualifier on both sides is comparable as text, since that is all it can be -- but only up to the
        // bound. Past it there is a qualifier against a release, which is where semver says pre-releases come
        // first and where this refuses to say anything, because being wrong here accuses a working pack.
        satisfied("1.2.3-rc1", "[1.2.3-rc1,1.2.3-rc1]");
        unknown("1.2.3-rc1", "[1.2.3-rc1,1.2.3]");
    }

    private static void satisfied(String version, String range) {
        check("satisfied: " + version + " in " + range,
                VersionRanges.check(version, range) == VersionRanges.Verdict.SATISFIED);
    }

    private static void violated(String version, String range) {
        check("violated: " + version + " not in " + range,
                VersionRanges.check(version, range) == VersionRanges.Verdict.VIOLATED);
    }

    private static void unknown(String version, String range) {
        check("refused to answer: " + version + " vs " + range,
                VersionRanges.check(version, range) == VersionRanges.Verdict.UNKNOWN);
    }

    // ---------------------------------------------------------------- out-of-range dependencies

    private static void rangesOutOfRange() {
        section("versions out of range");

        List<ModElement> mods = List.of(
                mod("lib", "lib", "1.0", "lib", "fabric", "1.21.1",
                        new Dependency("user", "[2.0,)", Dependency.Kind.REQUIRED)),
                mod("user", "user", "1.0", "user", "fabric", "1.21.1",
                        new Dependency("lib", "[2.0,)", Dependency.Kind.REQUIRED)));

        List<Finding> findings = run(mods, "fabric", "1.21.1");

        // Both edges point at a mod that is present but older than asked for.
        long outOfRange = findings.stream().filter(f -> f.kind().equals("version_out_of_range")).count();
        check("an out-of-range dependency is reported for each direction", outOfRange == 2);
        Finding one = findings.stream()
                .filter(f -> f.kind().equals("version_out_of_range")).findFirst().orElseThrow();
        check("as a warning rather than an error, since the loader already allowed it", !one.isError());
        check("naming the range and the version present",
                one.message().contains("[2.0,)") && one.message().contains("1.0"));
        check("and saying who asked for it",
                String.join(" ", one.detail()).contains("required_by"));

        // An unreadable range is said out loud rather than dropped, so a clean report is not mistaken for a
        // clean answer.
        List<ModElement> odd = List.of(
                mod("lib", "lib", "1.2.3-beta.1", "lib", "fabric", "1.21.1"),
                mod("user", "user", "1.0", "user", "fabric", "1.21.1",
                        new Dependency("lib", "[1.2.3,1.2.4]", Dependency.Kind.REQUIRED)));
        List<Finding> unreadable = run(odd, "fabric", "1.21.1");
        check("an unreadable comparison is reported as such",
                unreadable.stream().anyMatch(f -> f.kind().equals("version_range_unreadable")));
        check("and is not reported as a violation",
                unreadable.stream().noneMatch(f -> f.kind().equals("version_out_of_range")));

        // A satisfied range says nothing at all.
        List<ModElement> fine = List.of(
                mod("lib", "lib", "2.5", "lib", "fabric", "1.21.1"),
                mod("user", "user", "1.0", "user", "fabric", "1.21.1",
                        new Dependency("lib", "[2.0,)", Dependency.Kind.REQUIRED)));
        check("a satisfied range produces no finding", run(fine, "fabric", "1.21.1").isEmpty());
    }

    // ---------------------------------------------------------------- cycles

    private static void requiringCycles() {
        section("requiring cycles");

        List<ModElement> ring = List.of(
                mod("a", "a", "1.0", "a", "fabric", "1.21.1",
                        new Dependency("b", null, Dependency.Kind.REQUIRED)),
                mod("b", "b", "1.0", "b", "fabric", "1.21.1",
                        new Dependency("c", null, Dependency.Kind.REQUIRED)),
                mod("c", "c", "1.0", "c", "fabric", "1.21.1",
                        new Dependency("a", null, Dependency.Kind.REQUIRED)));

        List<Finding> findings = run(ring, "fabric", "1.21.1");
        List<Finding> cycles = findings.stream().filter(f -> f.kind().equals("requiring_cycle")).toList();
        check("a ring of three is found once, not three times", cycles.size() == 1);
        check("and described as the loop rather than as a pair",
                String.join(" ", cycles.get(0).detail()).contains("a -> b -> c -> a"));

        // An optional edge back is not a cycle: nothing has to happen in an order for it.
        List<ModElement> optionalBack = List.of(
                mod("a", "a", "1.0", "a", "fabric", "1.21.1",
                        new Dependency("b", null, Dependency.Kind.REQUIRED)),
                mod("b", "b", "1.0", "b", "fabric", "1.21.1",
                        new Dependency("a", null, Dependency.Kind.OPTIONAL)));
        check("an optional edge back is not a cycle",
                run(optionalBack, "fabric", "1.21.1").stream()
                        .noneMatch(f -> f.kind().equals("requiring_cycle")));

        // A diamond is not a cycle either.
        List<ModElement> diamond = List.of(
                mod("top", "top", "1.0", "top", "fabric", "1.21.1",
                        new Dependency("l", null, Dependency.Kind.REQUIRED),
                        new Dependency("r", null, Dependency.Kind.REQUIRED)),
                mod("l", "l", "1.0", "l", "fabric", "1.21.1",
                        new Dependency("bottom", null, Dependency.Kind.REQUIRED)),
                mod("r", "r", "1.0", "r", "fabric", "1.21.1",
                        new Dependency("bottom", null, Dependency.Kind.REQUIRED)),
                mod("bottom", "bottom", "1.0", "bottom", "fabric", "1.21.1"));
        check("a diamond is not a cycle", run(diamond, "fabric", "1.21.1").stream()
                .noneMatch(f -> f.kind().equals("requiring_cycle")));
    }

    // ---------------------------------------------------------------- built for something else

    private static void builtForSomethingElse() {
        section("built for something else");

        List<ModElement> mods = List.of(
                mod("fabricmod", "fabricmod", "1.0", "fm", "fabric", "[1.20,1.21)"),
                mod("forgemod", "forgemod", "1.0", "fg", "forge", "[1.21,1.22)"));

        List<Finding> runningNeo = run(mods, "neoforge", "1.21.1");
        check("a Fabric jar in a NeoForge pack is reported",
                runningNeo.stream().anyMatch(f -> f.kind().equals("built_for_another_loader")
                        && f.subject().equals("fabricmod")));
        // ★ NeoForge forked from Forge and does not load its jars: the table says so rather than inferring
        // from the names, which would get this backwards.
        check("and so is a Forge jar in a NeoForge pack",
                runningNeo.stream().anyMatch(f -> f.kind().equals("built_for_another_loader")
                        && f.subject().equals("forgemod")));

        // Quilt loads Fabric mods, so that pairing must stay quiet.
        List<Finding> runningQuilt = run(List.of(
                mod("fabricmod", "fabricmod", "1.0", "fm", "fabric", "[1.21,1.22)")),
                "quilt", "1.21.1");
        check("a Fabric jar under Quilt is not reported",
                runningQuilt.stream().noneMatch(f -> f.kind().equals("built_for_another_loader")));

        // Game versions: a range that excludes what is running is a warning; a single version that merely
        // differs is an observation, because mods routinely run on the next version.
        List<Finding> game = run(List.of(
                mod("old", "old", "1.0", "o", "fabric", "[1.20,1.21)"),
                mod("near", "near", "1.0", "n", "fabric", "1.20.4")),
                "fabric", "1.21.1");
        Finding warned = game.stream()
                .filter(f -> f.kind().equals("built_for_another_game_version")
                        && f.subject().equals("old")).findFirst().orElseThrow();
        check("a range that excludes the running game version is a warning",
                warned.severity() == Finding.Severity.WARN);
        Finding noted = game.stream()
                .filter(f -> f.kind().equals("built_for_another_game_version")
                        && f.subject().equals("1.20.4")).findFirst().orElseThrow();
        check("a differing single version is only an observation",
                noted.severity() == Finding.Severity.INFO);
        // Grouped, so that a pack of seventy mods built for the previous patch is one line and not seventy.
        check("and the observations are grouped by declared version",
                String.join(" ", noted.detail()).contains("near"));
        check("one observation, not one per mod", game.stream()
                .filter(f -> f.kind().equals("built_for_another_game_version")).count() == 2);

        // With nothing running there is nothing to compare against, and silence is not the same as a pass --
        // but it is the only honest answer.
        check("nothing running means nothing claimed",
                run(mods, null, null).stream()
                        .noneMatch(f -> f.kind().startsWith("built_for_")));
    }

    // ---------------------------------------------------------------- fixture and harness

    /** A mod with no authors, licence or description: none of those are read by this check. */
    private static ModElement mod(String id, String name, String version, String namespace,
            String loader, String minecraftVersion, Dependency... dependencies) {
        return new ModElement(id, name, version, namespace, loader, minecraftVersion,
                new String[0], "", "", dependencies, new String[0], id + ".jar", id + ".jar");
    }

    /** Runs the check over these mods and collects what it says. */
    private static List<Finding> run(List<ModElement> mods, String runningLoader, String runningMinecraft) {
        AnalysisContext ctx = new AnalysisContext(mods, Map.of(), List.of(), false,
                runningLoader, runningMinecraft);
        List<Finding> out = new ArrayList<>();
        new CompatibilityAnalysis().run(ctx, new AnalysisOutput() {
            @Override
            public void finding(Finding finding) {
                out.add(finding);
            }

            @Override
            public void record(ElementKind kind, String namespace, String key, String[] listValues,
                    String[] extra) {
                // The compatibility check states findings only.
            }
        });
        return out;
    }

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
