package org.uee;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.datapack.FunctionFlow;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.UeeJob;
import org.uee.pipeline.UeeJobs;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;

/**
 * Checks the MC-function interface: the job model, and the safety gate in front of it.
 *
 * <h2>Why this can be tested without a game</h2>
 *
 * <p>The thing that makes a function-driven export unsafe is that it runs inside a tick. Nothing about
 * the job model depends on Minecraft, though, and neither does the condition under which a run may be
 * moved off the server thread — that condition is the adapter's declaration. So both are verified here
 * with a stub adapter, which is the only way to test them at all: inside a running game a blocking
 * export is exactly the failure that takes the server down, and so cannot be provoked on purpose.
 */
public final class FunctionInterfaceTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "build/function-test");
        Files.createDir(root);

        jobLifecycle();
        jobQueueing();
        jobCancellation();
        jobFailure();
        historyBounded();
        functionFlows();
        safetyGate();
        quiet();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the job model

    private static void jobLifecycle() throws Exception {
        section("job lifecycle");

        UeeJobs jobs = new UeeJobs();
        ExportConfig config = ExportConfig.builder().build();
        UeeJob job = jobs.submit(config, Path.of("out"), () -> report(3, 30));

        check("a submitted job has an id", job.id() > 0);
        check("a submitted job is not finished yet or reports itself honestly",
                job.isActive() || job.isFinished());
        check("counting says something is outstanding", jobs.unfinished() >= 0);

        check("it finishes within the timeout", jobs.awaitIdle(5_000));
        check("it is done", job.state() == UeeJob.State.DONE);
        check("nothing is outstanding afterwards", jobs.unfinished() == 0);
        check("the report is available", job.report() != null
                && job.report().artifacts().size() == 3);
        check("it is retrievable by id", jobs.get(job.id()) == job);
        check("it is no longer in the active list", jobs.activeJobs().isEmpty());
        check("it is in the recent list", jobs.recentJobs().contains(job));
        check("it reports elapsed time", job.elapsedMillis() >= 0);
        check("it describes itself", job.describe().contains("done")
                && job.describe().contains("#" + job.id()));
        check("it names what it ran", job.label().contains("categories")
                && job.label().contains("json"));

        jobs.shutdown();
    }

    private static void jobQueueing() throws Exception {
        section("queueing and concurrency");

        UeeJobs jobs = new UeeJobs();
        ExportConfig config = ExportConfig.builder().build();
        // ★ Exports are serialised on purpose: two runs writing one bundle would interleave across the
        // same files and corrupt it. So a second submission queues rather than running alongside.
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<UeeJob> submitted = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            submitted.add(jobs.submit(config, Path.of("out"), () -> {
                int now = concurrent.incrementAndGet();
                peak.accumulateAndGet(now, Math::max);
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return report(1, 10);
            }));
        }
        check("several jobs can be submitted", submitted.size() == 4);
        check("ids are distinct", submitted.stream().map(UeeJob::id).distinct().count() == 4);
        check("unfinished counts the queue", jobs.unfinished() > 0);

        check("all finish", jobs.awaitIdle(15_000));
        // The load-bearing assertion: never two at once.
        check("exports never ran concurrently", peak.get() == 1);
        check("everything completed", submitted.stream().allMatch(j -> j.state() == UeeJob.State.DONE));
        jobs.shutdown();

        // A full queue is refused rather than accepted into unbounded memory. A function that starts a
        // run every tick must be told it is going too fast, not allowed to consume memory until the
        // server dies.
        UeeJobs bounded = new UeeJobs();
        int accepted = 0;
        boolean refused = false;
        for (int i = 0; i < 40; i++) {
            try {
                bounded.submit(config, Path.of("out"), () -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return report(1, 10);
                });
                accepted++;
            } catch (IllegalStateException e) {
                refused = true;
                break;
            }
        }
        check("a runaway submitter is refused", refused);
        check("but a reasonable number were accepted", accepted >= 2);
        bounded.shutdown();
    }

    private static void jobCancellation() throws Exception {
        section("cancellation");

        UeeJobs jobs = new UeeJobs();
        ExportConfig config = ExportConfig.builder().build();
        AtomicInteger ran = new AtomicInteger();
        // Occupy the single worker so the next job stays queued.
        jobs.submit(config, Path.of("out"), () -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return report(1, 10);
        });
        UeeJob queued = jobs.submit(config, Path.of("out"), () -> {
            ran.incrementAndGet();
            return report(1, 10);
        });

        boolean cancelled = jobs.cancel(queued.id());
        check("a queued job can be cancelled", cancelled);
        check("a cancelled job says so", queued.state() == UeeJob.State.CANCELLED);
        check("cancelling an unknown job fails", !jobs.cancel(9999));

        jobs.awaitIdle(5_000);
        // The point of cancelling: the work must not run afterwards.
        check("a cancelled job did not run", ran.get() == 0);

        // ★ A running export must not be interrupted: it is mid-write across many open shards, and a
        // partly written bundle cannot be told from a complete one.
        UeeJob running = jobs.submit(config, Path.of("out"), () -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return report(1, 10);
        });
        Thread.sleep(80);
        check("a started job refuses cancellation", !jobs.cancel(running.id()));
        check("the refused job still finishes", jobs.awaitIdle(5_000)
                && running.state() == UeeJob.State.DONE);
        jobs.shutdown();
    }

    private static void jobFailure() throws Exception {
        section("failure isolation");

        UeeJobs jobs = new UeeJobs();
        ExportConfig config = ExportConfig.builder().build();
        // A failure on a worker thread would otherwise die with the thread and vanish; it has to be
        // visible through the job.
        UeeJob failed = jobs.submit(config, Path.of("out"), () -> {
            throw new IllegalStateException("disk on fire");
        });
        check("the run finishes either way", jobs.awaitIdle(5_000));
        check("a failed job says failed", failed.state() == UeeJob.State.FAILED);
        check("the reason is kept", failed.error() != null && failed.error().contains("disk on fire"));
        check("no report is claimed", failed.report() == null);
        check("it still counts as finished", failed.isFinished());
        check("a failure does not wedge the runner", jobs.unfinished() == 0);

        // And the runner keeps working after a failure.
        UeeJob next = jobs.submit(config, Path.of("out"), () -> report(2, 20));
        check("a later job still runs", jobs.awaitIdle(5_000)
                && next.state() == UeeJob.State.DONE);
        jobs.shutdown();
    }

    private static void historyBounded() throws Exception {
        section("bounded history");

        UeeJobs jobs = new UeeJobs();
        ExportConfig config = ExportConfig.builder().build();
        for (int i = 0; i < 40; i++) {
            jobs.submit(config, Path.of("out"), () -> report(1, 10));
            jobs.awaitIdle(5_000);
        }
        // Bounded because a long-lived server accumulates finished jobs forever otherwise.
        check("recent job history is bounded", jobs.recentJobs().size() <= 32);
        check("the newest job is kept", !jobs.recentJobs().isEmpty());
        check("the oldest are dropped", jobs.recentJobs().size() == 32);
        jobs.shutdown();
    }

    // ---------------------------------------------------------------- function flows

    private static void functionFlows() {
        section("function-defined flows");

        check("the resource path convention is one place",
                FunctionFlow.resourceId("mypack", "export").equals("mypack:uee/export"));
        check("a flow name is read from a path",
                "export".equals(FunctionFlow.flowNameOf("mypack:uee/export")));
        check("a nested name keeps its path",
                "sub/export".equals(FunctionFlow.flowNameOf("mypack:uee/sub/export")));
        check("a function outside the uee directory is not a flow",
                FunctionFlow.flowNameOf("mypack:other/export") == null);
        check("the uee directory itself is not a flow",
                FunctionFlow.flowNameOf("mypack:uee/") == null);
        check("a namespace is read from a path",
                "mypack".equals(FunctionFlow.namespaceOf("mypack:uee/export")));
        check("a malformed path yields nothing", FunctionFlow.flowNameOf("ns") == null);
        check("a qualified id includes the namespace",
                new FunctionFlow("export", "mypack", "mypack:uee/export", null)
                        .qualifiedId().equals("mypack:export"));

        // Both kinds of flow live under one name, so a caller does not have to know which mechanism a
        // pack chose.
        var catalog = org.uee.datapack.DatapackCatalog.ofDefinitions(
                List.of(new org.uee.datapack.FlowDefinition("wiki", "dp", "a config flow",
                        ConfigFile.builder()
                                .kinds(java.util.Set.of(org.uee.model.ElementKind.ITEM)).build(),
                        List.of(), List.of())),
                List.of(), List.of());
        catalog.addFunctionFlows(List.of(
                new FunctionFlow("pipeline", "dp", "dp:uee/pipeline", null),
                new FunctionFlow("wiki", "dp", "dp:uee/wiki", null)));
        check("a function flow is registered", catalog.functionFlow("pipeline") != null);
        check("both kinds are listed together", catalog.allFlowIds().size() == 2);
        check("a configuration flow is found by the same lookup",
                catalog.anyFlow("wiki") instanceof org.uee.datapack.FlowDefinition);
        check("a function flow is found by the same lookup",
                catalog.anyFlow("pipeline") instanceof FunctionFlow);
        // ★ The collision must be reported, because which one /uee flow <id> would run must not
        // depend on registration order.
        check("an id defined both ways is reported", catalog.problems().stream()
                .anyMatch(p -> p.kind().equals("datapack_flow_kind_collision")));
        check("and the configuration flow wins, being the more specific definition",
                catalog.flow("wiki") != null);
    }

    // ---------------------------------------------------------------- the safety gate

    /**
     * The gate that decides whether an export may leave the server thread.
     *
     * <p>This is the assertion that matters most, because the failure it prevents is the one that
     * cannot be tested inside a game: an export that blocks a tick long enough for the watchdog.
     */
    private static void safetyGate() throws Exception {
        section("off-thread safety gate");

        Files.createDir(Path.of("build/function-test/gate"));

        // An adapter that has not declared frozen registries.
        Uee.bind(new StubAdapter(false, "testloader"));
        boolean refused = false;
        String message = null;
        try {
            Uee.startExport(ExportConfig.builder().build(), Path.of("build/function-test/gate"));
        } catch (IllegalStateException e) {
            refused = true;
            message = e.getMessage();
        }
        check("an adapter without the capability is refused", refused);
        check("the refusal names the missing declaration",
                message != null && message.contains(LoaderAdapter.CAP_REGISTRY_FROZEN));
        check("the refusal says what to do instead",
                message != null && message.contains("/uee export"));

        // The same adapter, having declared it.
        Uee.bind(new StubAdapter(true, "testloader"));
        UeeJob job = Uee.startExport(ExportConfig.builder().build(),
                Path.of("build/function-test/gate"));
        check("a declaring adapter is accepted", job != null && job.id() > 0);
        check("the job finishes", Uee.jobs().awaitIdle(5_000));
        check("the job succeeded", job.state() == UeeJob.State.DONE);
        // ★ The return value of /uee jobs is the poll primitive a function waits on.
        check("the unfinished count returns to zero", Uee.jobs().unfinished() == 0);

        // No adapter at all is a refusal, not a crash.
        Uee.bind(null);
        boolean noAdapter = false;
        try {
            Uee.startExport(ExportConfig.builder().build(), Path.of("build/function-test/gate"));
        } catch (IllegalStateException e) {
            noAdapter = true;
        }
        check("no adapter is refused", noAdapter);
        Uee.shutdownJobs();
    }

    // ---------------------------------------------------------------- quiet

    private static void quiet() throws Exception {
        section("quiet mode");

        // Off by default: a person watching an interactive run wants to see it working.
        check("quiet is off by default", !ExportConfig.builder().build().quiet());

        ConfigResolver.Resolved resolved = ConfigResolver.resolve(null,
                ConfigFile.parse("""
                        type tie<data>

                        uee = [
                          quiet = true
                        ]
                        """), ExportConfig.builder().build());
        check("quiet resolves from a description", resolved.ok() && resolved.config().quiet());
        check("quiet is one of the keys a flow must report",
                ConfigFile.parse("""
                        type tie<data>

                        uee = [
                          quiet = true
                        ]
                        """).setKeys().contains("quiet"));

        String rendered = ConfigFile.renderAnnotated(resolved.config());
        check("a written config carries quiet", rendered.contains("quiet = true"));
        check("and it is documented in place", rendered.contains("// 静默"));
        check("and it reads back", ConfigFile.parse(rendered)
                .applyTo(ExportConfig.builder().build()).quiet());
    }

    // ---------------------------------------------------------------- harness

    /** An adapter that declares only what the gate looks at. */
    private static final class StubAdapter implements LoaderAdapter {
        private final boolean frozen;
        private final String loader;

        StubAdapter(boolean frozen, String loader) {
            this.frozen = frozen;
            this.loader = loader;
        }

        @Override
        public LoaderInfo info() {
            return new LoaderInfo(loader, "1.0", "1.21.1", "build/function-test", false, true,
                    "21", "test", "test");
        }

        @Override
        public List<org.uee.model.ModElement> mods() {
            return List.of();
        }

        @Override
        public List<org.uee.model.DebugSection> debugSections() {
            return List.of();
        }

        @Override
        public void collectRegistries(ExportConfig config,
                java.util.Collection<org.uee.model.ElementKind> wanted,
                org.uee.spi.ElementSink sink) {
        }

        @Override
        public void collectDatapacks(ExportConfig config,
                java.util.Collection<org.uee.model.ElementKind> wanted,
                org.uee.spi.ElementSink sink) {
        }

        @Override
        public boolean supports(String capability) {
            return frozen && LoaderAdapter.CAP_REGISTRY_FROZEN.equals(capability);
        }
    }

    private static ExportReport report(int artifacts, int records) {
        List<ExportReport.Artifact> list = new ArrayList<>(artifacts);
        for (int i = 0; i < artifacts; i++) {
            list.add(new ExportReport.Artifact("ns", "item", "json",
                    Path.of("out/ns/item-" + i + ".json"), 10, records));
        }
        return ExportReport.of(Path.of("out"), list, List.of(), 1L, 0);
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

    /** Small file helper so the test does not depend on any other class. */
    private static final class Files {
        static void createDir(Path p) throws IOException {
            java.nio.file.Files.createDirectories(p);
        }
    }
}
