package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.uee.config.ConfigFile;
import org.uee.config.ExportConfig;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.util.OrderedWork;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;

/**
 * Checks the claim the pipeline is built on: peak memory does not grow with the pack.
 *
 * <h2>The claim</h2>
 *
 * <p>{@code Shard} states it as "peak memory is proportional to the number of <em>live</em> shards
 * times the watermark, not to the size of the pack". That is load-bearing for a tool whose job is to
 * stream an arbitrarily large pack to disk, and nothing had measured it. The Minecraft layer sat for
 * weeks with a stray brace nobody could see because it was never compiled; an unmeasured claim about
 * memory is the same kind of liability — plausible, load-bearing, and invisible until checked.
 *
 * <h2>What this measures, and the mistake it was written to avoid</h2>
 *
 * <p><b>Not</b> peak heap as reported by the JVM during a run. That figure tracks how lazy the
 * collector was, not how much the pipeline needed: on a large heap nothing applies pressure, so tens
 * of megabytes of uncollected garbage accumulate and are then reported as "memory used". Measuring it
 * that way made this pipeline look like it grew 19x with a 100x larger pack, which was wrong — the
 * first version of this test asserted that and failed.
 *
 * <p>What is measured instead is the <b>live set</b>: what survives a collection. That is the number
 * that decides whether a run fits on a machine, and it is the number the claim is about. Peak-during
 * is also reported, labelled as garbage, because it is worth seeing how far the collector lets itself
 * drift.
 *
 * <h2>The strongest form, checked by running it</h2>
 *
 * <p>The live set being flat is good evidence. Better is <b>completing the export under a heap far
 * smaller than the output</b> — which the test does by re-running its largest case in child JVMs with
 * a small fixed ceiling. Writing 110 MiB of artifacts inside a 64 MiB heap is the claim stated in a
 * way that cannot be satisfied by accident.
 */
public final class ScaleTest {

    private static int failures;

    private static final long MIB = 1L << 20;

    /**
     * A live set this small is the baseline of an empty JVM doing this work. Anything near it is
     * noise; the assertions use ratios against the smallest case rather than absolute values.
     */
    private static String mib(long bytes) {
        return String.format("%.1fMiB", bytes / (double) MIB);
    }

    private record Measurement(String label, int namespaces, long records, long liveBytes,
            long peakBytes, long millis, int artifacts, long written) {

        String describe() {
            return String.format("%-22s ns=%-4d rec=%-7d live=%-8s garbagePeak=%-8s time=%-6s "
                            + "files=%-4d out=%s",
                    label, namespaces, records, mib(liveBytes), mib(peakBytes), millis + "ms",
                    artifacts, mib(written));
        }
    }

    public static void main(String[] args) throws Exception {
        // Child mode: one export, one line, used by the small-heap check below.
        if (args.length >= 4 && args[0].equals("--single")) {
            Path dir = Path.of(args[3]);
            ExportReport report = runOnce(dir, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
            System.out.println("records=" + report.records() + " files=" + report.artifacts().size());
            return;
        }

        Path root = Path.of(args.length > 0 ? args[0] : "build/scale-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        System.out.println("=== export scaling ===");
        System.out.println("heap max: " + mib(Runtime.getRuntime().maxMemory()));

        List<Measurement> byRecords = new ArrayList<>();
        for (int perNamespace : new int[] {2_000, 20_000, 100_000}) {
            byRecords.add(measure(root.resolve("rec-" + perNamespace), "100k/ns=" + perNamespace,
                    4, perNamespace));
        }

        List<Measurement> byNamespaces = new ArrayList<>();
        for (int namespaces : new int[] {8, 64, 256}) {
            byNamespaces.add(measure(root.resolve("ns-" + namespaces), "namespaces=" + namespaces,
                    namespaces, 2_000));
        }

        System.out.println();
        System.out.println("=== results ===");
        System.out.println("  (live = survives a collection: what a run actually needs)");
        System.out.println("  (garbagePeak = how far the collector let itself drift before collecting)");
        for (Measurement m : byRecords) {
            System.out.println("  " + m.describe());
        }
        System.out.println();
        for (Measurement m : byNamespaces) {
            System.out.println("  " + m.describe());
        }

        System.out.println();
        section("the claim: more records must not cost live memory");

        double flat = ratio(byRecords.get(0).liveBytes, byRecords.get(2).liveBytes);
        check("50x the records (8k -> 400k) grows the live set by under 1.5x (was "
                + String.format("%.2f", flat) + "x)", flat < 1.5);

        check("and the live set stays a small constant, not a share of the output: "
                        + mib(byRecords.get(2).liveBytes) + " live against "
                        + mib(byRecords.get(2).written) + " written",
                byRecords.get(2).liveBytes < 8 * MIB);

        check("the work is linear in the records, not worse",
                byRecords.get(0).millis > 0
                        && (double) byRecords.get(2).millis / byRecords.get(0).millis < 100.0);

        section("the other axis, and the honest limit of the design");

        // Namespace count is the axis the design says *should* cost memory: each namespace and
        // category is its own live shard holding its own buffer, so the ceiling in principle scales
        // with the number of live shards rather than with the pack.
        //
        // Asserted in absolute terms rather than as a ratio between the cases. A ratio of two readings
        // that are both below what this measurement can resolve is not evidence of anything -- the
        // first version of this check divided two near-zero numbers and reported a scary 70x that
        // meant nothing.
        Measurement big = byNamespaces.get(2);
        System.out.println("  " + big.namespaces + " namespaces / " + big.records + " records / "
                + mib(big.written) + " written costs " + mib(big.liveBytes) + " live");
        System.out.println("  per-shard buffers are flushed every 256 records, so a live shard holds a");
        System.out.println("  flush worth and not a shard worth -- which is why this axis is cheap in");
        System.out.println("  practice despite being the one the design expects to pay on");

        check("even 256 namespaces keeps the live set under 4 MiB", big.liveBytes < 4 * MIB);
        check("and under 5% of what it writes", big.written == 0
                || big.liveBytes * 20 < big.written);

        section("the claim, stated so it cannot pass by accident");
        smallHeapCheck(root, 110_000, 4);

        section("the output is still correct at scale");
        correctnessAtScale(root.resolve("rec-100000"));

        section("parallel preparation: same output, bounded memory");
        parallelPreparation();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * Measures the skeleton the datapack collection runs on, on work shaped like the real thing.
     *
     * <h2>What is asserted strictly, and what only reported</h2>
     *
     * <p>Strictly: the results are identical whatever the thread count, and the results alive at once stay
     * bounded no matter how much input there is. Those are invariants, and a violation is a bug.
     *
     * <p>Only reported: the speedup. A timing assertion would compare two wall-clock readings on whatever
     * machine happens to be running the build, and on a shared or busy one the parallel run can lose to a
     * loaded neighbour — which would fail the build for a reason that has nothing to do with the code.
     * A test that fails for reasons outside its subject teaches people to ignore it, so the number is
     * printed and the assertion only catches a gross regression: parallelism that makes things far
     * slower is a bug, and that much is visible above the noise.
     *
     * <p>The work is JSON parsing, because that is what preparing a datapack record actually costs; a
     * synthetic loop would time the skeleton against itself and prove nothing about the real path.
     */
    private static void parallelPreparation() {
        List<String> documents = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            documents.add(syntheticLootTable(i));
        }
        long bytes = documents.stream().mapToLong(d -> d.length()).sum();
        System.out.println("  " + documents.size() + " documents, " + mib(bytes) + " of json");

        int cpus = Runtime.getRuntime().availableProcessors();
        int threads = Math.max(2, Math.min(cpus, 8));

        // Warm the parser so the comparison is between thread counts and not between interpreted and
        // compiled code, and take the best of several rounds.
        //
        // Best-of rather than an average, because this is a measurement of what the code can do and not
        // of what the machine was doing at the time: interference from a busy neighbour, a garbage
        // collection, or a scheduling hiccup can only ever add time, never remove it. A single cold
        // reading of this same workload reported 1.8x where the best of three reports about 4x, which is
        // the difference between a wrong conclusion and a right one.
        for (int i = 0; i < 400; i++) {
            org.uee.datapack.LootTableFile.parse(documents.get(i));
        }

        long singleMs = Long.MAX_VALUE;
        long parallelMs = Long.MAX_VALUE;
        List<Integer> single = List.of();
        List<Integer> parallel = List.of();
        for (int round = 0; round < 3; round++) {
            long s0 = System.nanoTime();
            single = OrderedWork.map(documents, 1, doc ->
                    org.uee.datapack.LootTableFile.parse(doc).items().size());
            singleMs = Math.min(singleMs, (System.nanoTime() - s0) / 1_000_000);

            long p0 = System.nanoTime();
            parallel = OrderedWork.map(documents, threads, doc ->
                    org.uee.datapack.LootTableFile.parse(doc).items().size());
            parallelMs = Math.min(parallelMs, (System.nanoTime() - p0) / 1_000_000);
        }

        System.out.println("  1 thread          : " + singleMs + "ms");
        System.out.println("  " + threads + " threads         : " + parallelMs + "ms  ("
                + String.format("%.2f", singleMs / (double) Math.max(1, parallelMs)) + "x)");

        check("the results are identical whatever the thread count", single.equals(parallel));
        check("and there are as many results as documents",
                single.size() == documents.size() && parallel.size() == documents.size());

        // The window test needs the consumer to be counted, which map() does not expose -- so the bound is
        // checked on its own pass with a window of one per thread, the tightest allowed setting.
        AtomicInteger tightAlive = new AtomicInteger();
        AtomicInteger tightPeak = new AtomicInteger();
        OrderedWork.run(documents.subList(0, 2000), threads, 1, "tight", doc -> {
            int now = tightAlive.incrementAndGet();
            tightPeak.accumulateAndGet(now, Math::max);
            org.uee.datapack.LootTableFile.parse(doc);
            return doc.length();
        }, v -> tightAlive.decrementAndGet());
        check("with a window of one per thread, at most " + threads + " results are alive at once (was "
                + tightPeak.get() + ")", tightPeak.get() <= threads);
        check("and everything was consumed", tightAlive.get() == 0);

        check("parallelism does not make preparation far slower (a gross regression only)",
            parallelMs < singleMs * 3 + 200);
    }

    /** A loot table shaped like the real ones: nested entries, a function, a condition. */
    private static String syntheticLootTable(int i) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"type\":\"minecraft:block\",\"random_sequence\":\"test:blocks/t")
                .append(i).append("\",\"pools\":[");
        for (int pool = 0; pool < 3; pool++) {
            if (pool > 0) {
                sb.append(',');
            }
            sb.append("{\"rolls\":1.0,\"conditions\":[{\"condition\":\"minecraft:survives_explosion\"}],"
                    + "\"entries\":[");
            for (int e = 0; e < 3; e++) {
                if (e > 0) {
                    sb.append(',');
                }
                sb.append("{\"type\":\"minecraft:item\",\"name\":\"test:item_").append(i)
                        .append('_').append(e)
                        .append("\",\"functions\":[{\"function\":\"minecraft:set_count\","
                                + "\"count\":{\"type\":\"minecraft:uniform\",\"min\":1.0,"
                                + "\"max\":3.0}}]}");
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /**
     * Re-runs the largest case in a child JVM with a small heap.
     *
     * <p>This is the claim in its strongest form: if peak memory really is independent of the pack,
     * then a run that produces far more output than the heap allows must still complete. A design that
     * buffered the pack, or that held an accumulator proportional to the records, would fail here
     * rather than merely measuring badly.
     *
     * <p>A child process rather than a smaller heap in this JVM, because a ceiling can only be chosen
     * at startup — and because a fresh JVM is also a check that nothing depends on state left behind.
     */
    private static void smallHeapCheck(Path root, int recordsPerNamespace, int namespaces)
            throws IOException, InterruptedException {
        Path dir = root.resolve("small-heap");
        long outMiB = 0;
        for (int heapMb : new int[] {48, 64}) {
            deleteRecursively(dir);
            Files.createDirectories(dir);
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xmx" + heapMb + "m");
            command.add("-Dfile.encoding=UTF-8");
            command.add("-cp");
            command.add(System.getProperty("java.class.path"));
            command.add(ScaleTest.class.getName());
            command.add("--single");
            command.add(String.valueOf(namespaces));
            command.add(String.valueOf(recordsPerNamespace));
            command.add(dir.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8).trim();
            int status = process.waitFor();

            long written = 0;
            for (Path p : listFiles(dir)) {
                written += Files.size(p);
            }
            outMiB = Math.max(outMiB, written);

            check("writes " + mib(written) + " of artifacts inside a " + heapMb + "MiB heap",
                    status == 0 && written > 0);
            if (status != 0) {
                // Printed rather than swallowed: when this fails the child's own message is the
                // useful part, and a bare "check failed" would hide an OOM or a stack trace.
                System.out.println("      child exited " + status + ": " + lastLines(output, 6));
            }
        }
        check("the artifacts exceed the heap that produced them, so the check is not vacuous",
                outMiB > 64 * MIB);
    }

    /** Reads the artifacts back, because speed must not be bought by truncating them. */
    private static void correctnessAtScale(Path dir) throws IOException {
        List<Path> json = listFiles(dir).stream()
                .filter(p -> p.toString().endsWith(".json"))
                .toList();
        check("the largest case produced json output", !json.isEmpty());
        boolean allParse = true;
        long bytes = 0;
        for (Path p : json) {
            bytes += Files.size(p);
            try {
                org.uee.util.JsonReader.parse(Files.readString(p, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                allParse = false;
                System.out.println("      unparseable at scale: " + p.getFileName() + " - "
                        + e.getMessage());
                break;
            }
        }
        check("every file at scale is a whole document", allParse);
        System.out.println("      " + json.size() + " files, " + mib(bytes) + " on disk");
    }

    // ---------------------------------------------------------------- measurement

    private static Measurement measure(Path root, String label, int namespaces, int perNamespace)
            throws IOException, InterruptedException {
        deleteRecursively(root);
        Files.createDirectories(root);

        Fixture fixture = new Fixture(namespaces, perNamespace);
        Uee.bind(fixture);
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.ITEM)
                .analyze(false)
                .build();

        settle();
        long baseline = used();

        // Sampling the drift, not the requirement: reported as context, never asserted on.
        long[] peak = {used()};
        boolean[] running = {true};
        Thread sampler = new Thread(() -> {
            while (running[0]) {
                peak[0] = Math.max(peak[0], used());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "heap-sampler");
        sampler.setDaemon(true);

        long start = System.nanoTime();
        sampler.start();
        ExportReport report = new Exporter(fixture, config, root).run();
        long millis = (System.nanoTime() - start) / 1_000_000;
        running[0] = false;
        sampler.join(200);

        // The live set: taken after the run, once the collector has had a chance, and above the
        // baseline this JVM already occupies.
        settle();
        long live = Math.max(0, used() - baseline);

        long written = 0;
        for (ExportReport.Artifact a : report.artifacts()) {
            written += a.bytes();
        }
        return new Measurement(label, namespaces, report.records(), live, peak[0] - baseline,
                millis, report.artifacts().size(), written);
    }

    private static ExportReport runOnce(Path root, int namespaces, int perNamespace)
            throws IOException {
        deleteRecursively(root);
        Files.createDirectories(root);
        Fixture fixture = new Fixture(namespaces, perNamespace);
        Uee.bind(fixture);
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.ITEM)
                .analyze(false)
                .build();
        return new Exporter(fixture, config, root).run();
    }

    private static double ratio(long from, long to) {
        return from == 0 ? 0 : (double) to / from;
    }

    private static long used() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** Gives the collector a chance before a baseline or a live-set reading is taken. */
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(80);
        }
    }

    private static String lastLines(String text, int n) {
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, lines.length - n); i < lines.length; i++) {
            sb.append("\n        ").append(lines[i]);
        }
        return sb.toString();
    }

    private static List<Path> listFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /**
     * A pack of a chosen size.
     *
     * <p>Generated rather than read from disk so the axes can be varied independently and a difference
     * between two cases is the shape of the pack and not the contents of it. The contents vary by band
     * so the runs are not one repeated string, which would flatter any pool or dictionary.
     */
    private static final class Fixture implements LoaderAdapter {

        private final int namespaces;
        private final int perNamespace;

        Fixture(int namespaces, int perNamespace) {
            this.namespaces = namespaces;
            this.perNamespace = perNamespace;
        }

        @Override
        public LoaderInfo info() {
            return new LoaderInfo("scale", "1.0", "1.21.1", "build/scale-test", false, true,
                    "21", "scale", "scale");
        }

        @Override
        public List<ModElement> mods() {
            List<ModElement> out = new ArrayList<>(namespaces);
            for (int i = 0; i < namespaces; i++) {
                out.add(new ModElement("mod" + i, "Mod " + i, "1.0.0", "mod" + i, "neoforge",
                        "1.21.1", new String[] {"Someone"}, "TPL-2.3", "generated",
                        new Dependency[0], new String[0], "mod" + i + "-1.0.0.jar"));
            }
            return out;
        }

        @Override
        public List<DebugSection> debugSections() {
            return List.of();
        }

        @Override
        public void collectRegistries(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
            if (!wanted.contains(ElementKind.ITEM)) {
                return;
            }
            for (int ns = 0; ns < namespaces; ns++) {
                String namespace = "ns" + ns;
                for (int i = 0; i < perNamespace; i++) {
                    sink.item(new ItemElement(
                            namespace + ":item_" + i, namespace, "item." + namespace + ".item_" + i,
                            "物品" + i, "Item " + i, 64, 0,
                            new String[] {"c:band" + (i % 8), namespace + ":band" + (i % 4)},
                            new String[] {"tab" + (i % 4)}, null, null, false));
                }
            }
        }

        @Override
        public void collectDatapacks(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
        }

        @Override
        public List<org.uee.debug.MixinConfig> mixinConfigs() {
            return List.of();
        }

        @Override
        public boolean supports(String capability) {
            return LoaderAdapter.CAP_REGISTRY_FROZEN.equals(capability);
        }
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
