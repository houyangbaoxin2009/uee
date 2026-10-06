package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.uee.delta.DeltaPlan;
import org.uee.delta.Fingerprint;
import org.uee.delta.Snapshot;

/**
 * Checks the delta machinery: fingerprints, the snapshot manifest, and the decisions taken against it.
 *
 * <h2>The check that matters most is the one against an outside authority</h2>
 *
 * <p>Fingerprints are checked against the published SHA-256 test vectors rather than against each other.
 * Comparing two of my own hashes proves only that the same code twice agrees with itself, which it would
 * even if the algorithm were wrong in the same way — the lesson from the PNG encoder, where the file
 * looked plausible to everything except a decoder that had not been written by the same hand.
 */
public final class DeltaTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/delta-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        fingerprints();
        snapshotRoundTrip(root.resolve("snap"));
        snapshotTolerance(root.resolve("tolerant"));
        theDecisions();
        removalsAndReport();
        aSecondRunOverUnchangedContent(root.resolve("idempotent"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- fingerprints

    private static void fingerprints() throws IOException {
        section("fingerprints, against the published vectors");

        // The published SHA-256 of the empty input and of "abc". Checking these means the algorithm is
        // SHA-256 and not merely something self-consistent.
        check("the empty input matches the published digest",
                Fingerprint.of(new byte[0]).equals(
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        check("'abc' matches the published digest",
                Fingerprint.of("abc").equals(
                        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        check("the hex form is the documented length",
                Fingerprint.of("x").length() == Fingerprint.HEX_LENGTH);
        check("and is lower-case hex",
                Fingerprint.isWellFormed(Fingerprint.of("x")));
        check("upper case is not well formed",
                !Fingerprint.isWellFormed(Fingerprint.of("x").toUpperCase()));
        check("a short string is not well formed", !Fingerprint.isWellFormed("abc"));
        check("and neither is null", !Fingerprint.isWellFormed(null));

        // The property the whole feature rests on: same bytes, same fingerprint; one byte different,
        // different fingerprint.
        check("the same bytes give the same fingerprint",
                Fingerprint.of("hello").equals(Fingerprint.of("hello")));
        check("one byte different gives a different fingerprint",
                !Fingerprint.of("hello").equals(Fingerprint.of("hellp")));
        check("and the difference is everywhere, not just at the end",
                !Fingerprint.of("a").equals(Fingerprint.of("b")));
        // Length is part of the content, not padding.
        check("trailing whitespace changes it",
                !Fingerprint.of("a").equals(Fingerprint.of("a ")));

        // A file and its bytes must agree, since one path is used for shards on disk and the other is
        // used in tests and comparisons.
        Path file = Files.createTempFile("uee-fp", ".bin");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        check("a file hashes as its bytes do",
                Fingerprint.of(file).equals(Fingerprint.of("abc")));
        Files.write(file, new byte[100000]);
        check("a large file works too", Fingerprint.of(file).length() == Fingerprint.HEX_LENGTH);
        Files.deleteIfExists(file);
    }

    // ---------------------------------------------------------------- the manifest

    private static void snapshotRoundTrip(Path root) throws IOException {
        section("the manifest round trip");

        Path file = root.resolve(Snapshot.FILE_NAME);
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("items/example/example-items.ndjson", Fingerprint.of("one"));
        entries.put("blocks/example/example-blocks.ndjson", Fingerprint.of("two"));
        entries.put("items/another/another-items.ndjson", Fingerprint.of("three"));

        Snapshot.write(file, entries, "written by a test\nsecond line");
        check("the file exists", Files.isRegularFile(file));
        check("no temporary file is left behind", !Files.exists(root.resolve(Snapshot.FILE_NAME + ".part")));

        Snapshot read = Snapshot.read(file);
        check("every entry comes back", read.size() == 3);
        check("with the right fingerprint",
                read.fingerprintOf("items/example/example-items.ndjson").equals(Fingerprint.of("one")));
        check("and contains() agrees", read.contains("blocks/example/example-blocks.ndjson"));
        check("an unknown path is absent", read.fingerprintOf("nothing") == null);
        check("and contains() agrees there too", !read.contains("nothing"));
        check("a clean read complains about nothing", read.complaints().isEmpty());

        // Sorted, so two runs over the same content produce byte-identical manifests and a diff shows
        // only real differences.
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> paths = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith("//") && !line.isBlank() && !line.equals(Snapshot.HEADER)) {
                // The path, not the whole line: a line begins with its fingerprint, so sorting lines
                // would be checking the wrong column.
                paths.add(line.substring(line.indexOf('\t') + 1));
            }
        }
        List<String> sortedPaths = new ArrayList<>(paths);
        java.util.Collections.sort(sortedPaths);
        check("the entries come out sorted by path", paths.equals(sortedPaths));
        check("and every entry survived the extraction", paths.size() == 3);
        check("the header is first", lines.get(0).equals(Snapshot.HEADER));
        check("the comment is recorded for a human",
                lines.stream().anyMatch(l -> l.startsWith("// written by a test")));

        // Writing the same map twice gives the same bytes, which is what makes the manifest diffable.
        Path again = root.resolve("again");
        Files.createDirectories(again);
        Snapshot.write(again.resolve(Snapshot.FILE_NAME), entries, "written by a test\nsecond line");
        check("the same content gives byte-identical manifests",
                java.util.Arrays.equals(Files.readAllBytes(file),
                        Files.readAllBytes(again.resolve(Snapshot.FILE_NAME))));

        // The manifest is a real file a person may open, and its own comments must not read as damage.
        check("the reader does not complain about the writer's comments", read.complaints().isEmpty());
        check("a comment is recognised", Snapshot.isComment("  // hello"));
        check("an entry is not", !Snapshot.isComment("abc\tdef"));
    }

    private static void snapshotTolerance(Path root) throws IOException {
        section("a missing manifest is normal, a damaged one is reported");

        // The first run has nothing to compare against. Absence is a state this format understands.
        Snapshot missing = Snapshot.read(root.resolve("nothing-here"));
        check("a missing manifest reads as empty", missing.size() == 0);
        check("and is not an error", missing.complaints().isEmpty());

        // A foreign or future file. Parsed optimistically it would produce a delta against a format whose
        // meaning may have changed, and a wrong "unchanged" is a silently missing artifact.
        Path wrongHeader = root.resolve("wrong-header");
        Files.createDirectories(root);
        Files.writeString(wrongHeader, "some-other-format 9\n" + Fingerprint.of("x") + "\tpath\n");
        Snapshot foreign = Snapshot.read(wrongHeader);
        check("a different header is treated as no manifest", foreign.size() == 0);
        check("with a reason given", foreign.complaints().size() == 1);
        check("and the reason names the header",
                foreign.complaints().get(0).contains("some-other-format"));

        Path emptyHeader = root.resolve("empty-file");
        Files.writeString(emptyHeader, "");
        check("an empty file reads as no manifest",
                Snapshot.read(emptyHeader).size() == 0);

        // A damaged manifest keeps what it could read and says what it could not.
        Path damaged = root.resolve("damaged");
        Files.writeString(damaged, Snapshot.HEADER + "\n"
                + "this line has no tab at all\n"
                + "nothexatall\tpath/one\n"
                + Fingerprint.of("good") + "\tpath/two\n"
                + "   \n"
                + Fingerprint.of("good2") + "\tpath/three\n");
        Snapshot partial = Snapshot.read(damaged);
        check("the readable entries survive", partial.size() == 2);
        check("the unreadable ones are reported",
                partial.complaints().size() == 2);
        check("one complaint is about the line that is not an entry",
                partial.complaints().stream().anyMatch(c -> c.contains("not an entry")));
        check("the other is about the fingerprint",
                partial.complaints().stream().anyMatch(c -> c.contains("not one")));
        check("a blank line is not a complaint",
                partial.complaints().stream().noneMatch(c -> c.contains("blank")));

        Path duplicate = root.resolve("duplicate");
        Files.writeString(duplicate, Snapshot.HEADER + "\n"
                + Fingerprint.of("first") + "\tsame/path\n"
                + Fingerprint.of("second") + "\tsame/path\n");
        Snapshot dup = Snapshot.read(duplicate);
        check("a repeated path keeps one entry", dup.size() == 1);
        check("the later one wins", dup.fingerprintOf("same/path").equals(Fingerprint.of("second")));
        check("and the repetition is reported", dup.complaints().size() == 1);
    }

    // ---------------------------------------------------------------- the decisions

    private static void theDecisions() {
        section("new, changed and unchanged");

        Map<String, String> before = new LinkedHashMap<>();
        before.put("items/a", Fingerprint.of("A"));
        before.put("items/b", Fingerprint.of("B"));
        before.put("items/gone", Fingerprint.of("G"));

        Snapshot previous = snapshotOf(before);
        DeltaPlan plan = new DeltaPlan(previous);

        check("a path the snapshot never had is new",
                plan.record("items/new", Fingerprint.of("N")) == DeltaPlan.Verdict.NEW);
        check("a path with the same content is unchanged",
                plan.record("items/a", Fingerprint.of("A")) == DeltaPlan.Verdict.UNCHANGED);
        check("a path with different content is changed",
                plan.record("items/b", Fingerprint.of("B2")) == DeltaPlan.Verdict.CHANGED);
        check("a path that disappeared is not recorded as produced",
                !plan.produced().contains("items/gone"));

        // The decision that saves the work, and the one that must not.
        check("an unchanged artifact already on disk need not be written",
                !plan.shouldWrite(DeltaPlan.Verdict.UNCHANGED, true));
        // A consumer with nothing to read is a different situation, and skipping it would produce a tidy
        // report about artifacts that are not there.
        check("an unchanged artifact whose file is missing must be written",
                plan.shouldWrite(DeltaPlan.Verdict.UNCHANGED, false));
        check("a new one is written", plan.shouldWrite(DeltaPlan.Verdict.NEW, false));
        check("and a changed one too, whatever is on disk",
                plan.shouldWrite(DeltaPlan.Verdict.CHANGED, true));
        check("a removed one is not written", plan.shouldWrite(DeltaPlan.Verdict.REMOVED, false));

        // Coming back is not the same as changing: the snapshot no longer describes it at all.
        DeltaPlan afterRemoval = new DeltaPlan(snapshotOf(Map.of()));
        check("a path that had been removed and returns is new, not changed",
                afterRemoval.record("items/a", Fingerprint.of("A")) == DeltaPlan.Verdict.NEW);

        DeltaPlan firstRun = DeltaPlan.firstRun();
        check("a first run calls everything new",
                firstRun.record("anything", Fingerprint.of("x")) == DeltaPlan.Verdict.NEW);
        check("and has nothing to compare against", firstRun.manifest().size() == 1);
    }

    private static void removalsAndReport() {
        section("removals, which can only be known at the end");

        Map<String, String> before = new LinkedHashMap<>();
        before.put("items/keep", Fingerprint.of("K"));
        before.put("items/drop", Fingerprint.of("D"));
        before.put("blocks/drop", Fingerprint.of("D2"));
        Snapshot previous = snapshotOf(before);

        DeltaPlan plan = new DeltaPlan(previous);
        plan.record("items/keep", Fingerprint.of("K"));
        plan.record("items/new", Fingerprint.of("N"));

        // Not known until now, which is why the report is produced at the end rather than streamed.
        DeltaPlan.Report report = plan.finish(2);
        check("two artifacts are reported removed", report.removed().size() == 2);
        check("and they are the right two",
                report.removed().equals(List.of("blocks/drop", "items/drop")));
        check("the removed list is sorted", report.removed().equals(sorted(report.removed())));
        check("the count of what was actually deleted is carried", report.deleted() == 2);

        // Only the artifact whose content differs. "items/keep" was recorded again with the same
        // fingerprint, which is exactly what "unchanged" is supposed to mean.
        check("the payload is only what actually differs",
                report.changedPaths(previous).equals(List.of("items/new")));
        check("the artifact count is what this run produced", report.artifactCount() == 2);
        // One artifact a consumer must read, plus two it must delete.
        check("the workload is the changed plus the removed",
                report.consumerWorkload(previous)
                        == report.changedPaths(previous).size() + report.removed().size());
        check("the summary reads as the numbers",
                report.summary(previous).equals("1 new or changed, 1 unchanged, 2 removed"));

        // A run with nothing to say says nothing.
        DeltaPlan quiet = new DeltaPlan(previous);
        quiet.record("items/keep", Fingerprint.of("K"));
        quiet.record("items/drop", Fingerprint.of("D"));
        quiet.record("blocks/drop", Fingerprint.of("D2"));
        DeltaPlan.Report still = quiet.finish(0);
        check("a run that changed nothing reports an empty delta", still.isEmpty(previous));
        check("and its workload is zero", still.consumerWorkload(previous) == 0);
        check("though it still produced every artifact", still.artifactCount() == 3);
        check("and its summary says so",
                still.summary(previous).equals("0 new or changed, 3 unchanged, 0 removed"));

        check("reading complaints are carried into the report",
                plan.finish(0).complaints().isEmpty());
    }

    /**
     * The headline behaviour: a second run over content that did not change does no work.
     *
     * <p>Checked end to end — write a manifest, read it, run a plan over the same artifacts — because the
     * property is about the three pieces together rather than about any one of them.
     */
    private static void aSecondRunOverUnchangedContent(Path root) throws IOException {
        section("a second run over unchanged content");

        Files.createDirectories(root);
        Path manifest = root.resolve(Snapshot.FILE_NAME);
        Path shardA = root.resolve("items.ndjson");
        Path shardB = root.resolve("blocks.ndjson");
        Files.writeString(shardA, "{\"a\":1}\n");
        Files.writeString(shardB, "{\"b\":2}\n");

        // The first run: nothing to compare against, so everything is written and the manifest is made.
        DeltaPlan first = DeltaPlan.firstRun();
        Map<String, String> produced = new LinkedHashMap<>();
        List<String> written = new ArrayList<>();
        for (Path shard : List.of(shardA, shardB)) {
            String fingerprint = Fingerprint.of(shard);
            DeltaPlan.Verdict verdict = first.record(shard.getFileName().toString(), fingerprint);
            if (first.shouldWrite(verdict, Files.exists(shard))) {
                written.add(shard.getFileName().toString());
            }
            produced.put(shard.getFileName().toString(), fingerprint);
        }
        check("a first run writes everything", written.size() == 2);
        Snapshot.write(manifest, produced, "run one");

        // The second run: same content. Nothing should be written.
        Snapshot snapshot = Snapshot.read(manifest);
        DeltaPlan second = new DeltaPlan(snapshot);
        List<String> rewritten = new ArrayList<>();
        for (Path shard : List.of(shardA, shardB)) {
            String fingerprint = Fingerprint.of(shard);
            DeltaPlan.Verdict verdict = second.record(shard.getFileName().toString(), fingerprint);
            if (second.shouldWrite(verdict, Files.exists(shard))) {
                rewritten.add(shard.getFileName().toString());
            }
        }
        DeltaPlan.Report report = second.finish(0);
        check("the second run writes nothing", rewritten.isEmpty());
        check("its delta is empty", report.isEmpty(snapshot));
        check("and the workload it hands a consumer is zero", report.consumerWorkload(snapshot) == 0);

        // Third: one shard changes. Only that one is rewritten.
        Files.writeString(shardA, "{\"a\":1}\n{\"a\":2}\n");
        DeltaPlan third = new DeltaPlan(Snapshot.read(manifest));
        List<String> rewrittenAgain = new ArrayList<>();
        for (Path shard : List.of(shardA, shardB)) {
            String fingerprint = Fingerprint.of(shard);
            DeltaPlan.Verdict verdict = third.record(shard.getFileName().toString(), fingerprint);
            if (third.shouldWrite(verdict, Files.exists(shard))) {
                rewrittenAgain.add(shard.getFileName().toString());
            }
        }
        check("only the changed shard is rewritten",
                rewrittenAgain.equals(List.of("items.ndjson")));
        check("and the delta names exactly it",
                third.finish(0).changedPaths(snapshot).equals(List.of("items.ndjson")));

        // The case a delta that trusted its own record would get wrong: the content did not change, but
        // the file a consumer would read is not there.
        DeltaPlan fourth = new DeltaPlan(Snapshot.read(manifest));
        DeltaPlan.Verdict unchanged = fourth.record("blocks.ndjson", Fingerprint.of(shardB));
        check("the shard that did not change classifies as unchanged",
                unchanged == DeltaPlan.Verdict.UNCHANGED);
        check("and is rewritten anyway when its file is missing",
                fourth.shouldWrite(unchanged, false));
        check("but not when it is there",
                !fourth.shouldWrite(unchanged, true));
    }

    // ---------------------------------------------------------------- helpers

    private static Snapshot snapshotOf(Map<String, String> entries) {
        try {
            Path file = Files.createTempFile("uee-snap", "");
            Snapshot.write(file, entries, null);
            Snapshot read = Snapshot.read(file);
            Files.deleteIfExists(file);
            return read;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        java.util.Collections.sort(out);
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

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
