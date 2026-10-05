package org.uee.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.uee.Uee;
import org.uee.analysis.Finding;
import org.uee.config.ConfigFile;
import org.uee.config.ConfigResolver;
import org.uee.config.ExportConfig;
import org.uee.config.Tokens;
import org.uee.datapack.DatapackCatalog;
import org.uee.datapack.FunctionFlow;
import org.uee.datapack.DatapackSource;
import org.uee.datapack.FlowDefinition;
import org.uee.datapack.StrategyDefinition;
import org.uee.datapack.TargetDefinition;
import org.uee.globalpack.GlobalPackPolicy;
import org.uee.model.ElementKind;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.UeeJob;
import org.uee.pipeline.UeeJobs;

/**
 * The {@code /uee} command tree, shared by all four loaders.
 *
 * <p>One command with no arguments is the whole entry point for the common case:
 *
 * <pre>
 * /uee
 * </pre>
 *
 * runs the default export — JSON, every category in the default set, and the analysis as its own
 * package. Everything else exists to change one aspect of that, so a user who never learns the
 * subcommands still gets a useful result.
 *
 * <p>This surface is a thin parser over the same vocabulary the config file uses, and both produce a
 * {@link ConfigFile} that {@link ConfigResolver} layers onto the defaults. That is why
 * {@code /uee export items,blocks} and {@code kinds = ["items","blocks"]} cannot mean different
 * things: by the time anything acts on them they are the same description.
 *
 * <h2>Designed to be called from an MC function</h2>
 *
 * <p>These commands are meant to be written into {@code .mcfunction} files, which imposes three
 * requirements that a chat-only command would not have:
 *
 * <ul>
 *   <li><b>It must not block the tick.</b> A function runs inside a tick, and an export that takes
 *       seconds there will trip the server watchdog. Collection and writing are one streaming pass, so
 *       there is no way to move only the writing off the thread — which means <b>every export is
 *       asynchronous</b>. See the next section.
 *   <li><b>It must have a usable result code.</b> A function cannot receive a value, but
 *       {@code execute store result} and {@code execute store success} read the command's return
 *       value. Every verb here therefore returns something meaningful, documented per verb below.
 *   <li><b>It must be quiet on request.</b> A function logs every command's output, so a verbose
 *       export in a tick loop buries the log. {@code quiet = true} keeps one summary line.
 * </ul>
 *
 * <h2>Why the export verbs are asynchronous</h2>
 *
 * <p>An export reads the registries and writes artifacts in one pass, so the whole duration is on the
 * calling thread. A synchronous export therefore holds the server thread for its entire run, and on a
 * large pack that is long enough to trip the watchdog. Since the short form of the command has to be
 * the safe one, <b>the short form starts a job and returns</b>:
 *
 * <pre>
 * /uee                     start the default export, return at once
 * /uee export items        start an export of one category, return at once
 * /uee export sync items   export on this thread, for when you know it is small
 * </pre>
 *
 * <p>A caller with a player behind it is told the result when the run finishes, so the short form does
 * not cost them the summary — they simply are not made to wait for it. A caller with nobody behind it
 * (a function, a command block, the console) polls {@code /uee jobs} instead.
 *
 * <p>The one deliberate exception is {@code /uee analyze}: it reads loader metadata only, does no
 * registry walk and writes nothing, so it is cheap enough to answer inline and much more useful that
 * way.
 *
 * <h2>Result codes</h2>
 *
 * <pre>
 * /uee …                      1 when a job was started, 0 when refused
 * /uee export sync …          files written; 0 on failure
 * /uee jobs                   unfinished jobs — poll this until 0
 * /uee job &lt;id&gt;               files written if done, 1 if still running, 0 otherwise
 * /uee job &lt;id&gt; cancel        1 if cancelled, 0 if it had already started
 * /uee analyze                findings produced
 * /uee flow &lt;name&gt;            as /uee export, or 1 for a function flow that was started
 * /uee flows | targets | …    count listed
 * </pre>
 *
 * <p>The one that matters most is {@code /uee jobs}: waiting for it to reach zero is how a function
 * waits for a run to finish, and it is why the interface is a pollable count rather than a callback.
 */
public final class UeeCommand {

    /** Permission level required. Exporting writes files and reads mod jars, so it is not for everyone. */
    private static final int PERMISSION = 2;

    /** How many findings {@code /uee analyze} prints before summarising the rest. */
    private static final int FINDINGS_SHOWN = 20;

    private UeeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("uee"));
    }

    /** Registers a short alias alongside the full command. */
    public static void registerWithAlias(CommandDispatcher<CommandSourceStack> dispatcher,
            String alias) {
        dispatcher.register(build("uee"));
        dispatcher.register(build(alias));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(PERMISSION))
                // Bare /uee: the one-key default, placed first so it reads as the primary action.
                // Starts a job rather than blocking the tick; see the class doc.
                .executes(ctx -> runAsync(ctx.getSource(), ConfigFile.empty()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource(), name)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("kinds").executes(ctx -> listKinds(ctx.getSource())))
                .then(Commands.literal("listformats").executes(ctx -> listFormats(ctx.getSource())))

                .then(Commands.literal("export")
                        // The short form is the safe form: start a job and return. See the class doc.
                        .executes(ctx -> runAsync(ctx.getSource(), ConfigFile.empty()))
                        // Explicitly on this thread, for when the caller knows the run is small.
                        .then(Commands.literal("sync")
                                .executes(ctx -> runWith(ctx.getSource(), ConfigFile.empty()))
                                .then(Commands.argument("kinds", StringArgumentType.greedyString())
                                        .executes(ctx -> runWith(ctx.getSource(),
                                                kindsOnly(arg(ctx, "kinds"))))))
                        .then(Commands.argument("kinds", StringArgumentType.greedyString())
                                .executes(ctx -> runAsync(ctx.getSource(),
                                        kindsOnly(arg(ctx, "kinds"))))))

                // ── The long form: one verb per aspect, each also reachable from the config file.
                // Anything set here is an override on top of the file, so a one-off run does not
                // require editing anything.
                .then(Commands.literal("set")
                        .then(Commands.literal("fields")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(ctx -> setFields(ctx.getSource(),
                                                arg(ctx, "names")))))
                        .then(Commands.literal("exclude-fields")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(ctx -> setExcludeFields(ctx.getSource(),
                                                arg(ctx, "names")))))
                        .then(Commands.literal("tags")
                                .then(Commands.argument("tags", StringArgumentType.greedyString())
                                        .executes(ctx -> setTags(ctx.getSource(),
                                                arg(ctx, "tags"), false))))
                        .then(Commands.literal("skip-tags")
                                .then(Commands.argument("tags", StringArgumentType.greedyString())
                                        .executes(ctx -> setTags(ctx.getSource(),
                                                arg(ctx, "tags"), true))))
                        .then(Commands.literal("namespaces")
                                .then(Commands.argument("namespaces",
                                                StringArgumentType.greedyString())
                                        .executes(ctx -> setNamespaces(ctx.getSource(),
                                                arg(ctx, "namespaces"), false))))
                        .then(Commands.literal("skip-namespaces")
                                .then(Commands.argument("namespaces",
                                                StringArgumentType.greedyString())
                                        .executes(ctx -> setNamespaces(ctx.getSource(),
                                                arg(ctx, "namespaces"), true))))
                        .then(Commands.literal("skip-mods")
                                .then(Commands.argument("mods", StringArgumentType.greedyString())
                                        .executes(ctx -> setSkipMods(ctx.getSource(),
                                                arg(ctx, "mods")))))
                        .then(Commands.literal("shards")
                                .then(Commands.argument("records",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(1))
                                        .executes(ctx -> setShards(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "records")))))
                        .then(Commands.literal("max-file-mb")
                                .then(Commands.argument("mb",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(0))
                                        .executes(ctx -> setMaxFile(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "mb")))))
                        .then(Commands.literal("output")
                                .then(Commands.argument("dir", StringArgumentType.greedyString())
                                        .executes(ctx -> setOutput(ctx.getSource(),
                                                arg(ctx, "dir")))))
                        .then(Commands.literal("quiet")
                                .executes(ctx -> setFlag(ctx.getSource(), "quiet", true)))
                        .then(Commands.literal("noisy")
                                .executes(ctx -> setFlag(ctx.getSource(), "quiet", false)))
                        .then(Commands.literal("dry-run")
                                .executes(ctx -> setFlag(ctx.getSource(), "dry_run", true)))
                        .then(Commands.literal("icons")
                                .executes(ctx -> setFlag(ctx.getSource(), "icons", true)))
                        .then(Commands.literal("no-icons")
                                .executes(ctx -> setFlag(ctx.getSource(), "icons", false)))
                        // The list of what can be set, so the long form is discoverable without the
                        // documentation.
                        .executes(ctx -> listSettables(ctx.getSource())))

                // Job inspection. /uee jobs returns the unfinished count, which is how a function
                // waits: execute store result ... run uee jobs, and loop until it reaches zero.
                .then(Commands.literal("jobs").executes(ctx -> jobs(ctx.getSource())))
                .then(Commands.literal("job")
                        .then(Commands.argument("id", com.mojang.brigadier.arguments.IntegerArgumentType
                                        .integer(1))
                                .executes(ctx -> jobDetail(ctx.getSource(),
                                        com.mojang.brigadier.arguments.IntegerArgumentType
                                                .getInteger(ctx, "id")))
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> cancelJob(ctx.getSource(),
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .getInteger(ctx, "id"))))))

                .then(Commands.literal("formats")
                        .executes(ctx -> listFormats(ctx.getSource()))
                        .then(Commands.argument("formats", StringArgumentType.greedyString())
                                .executes(ctx -> runAsyncWithFormats(ctx.getSource(),
                                        arg(ctx, "formats")))))

                // Analyse without exporting: answers "what is wrong with this instance" cheaply.
                .then(Commands.literal("analyze")
                        .executes(ctx -> analyzeWithPolicy(ctx.getSource())))

                // The two halves of a run, each on its own.
                .then(Commands.literal("data").executes(ctx -> runAsync(ctx.getSource(),
                        kindsOnly(Tokens.DATA))))
                .then(Commands.literal("analysis").executes(ctx -> runAsync(ctx.getSource(),
                        kindsOnly(Tokens.ANALYSIS))))

                // The fourth interface: what datapacks define, and running a flow they declare.
                .then(Commands.literal("flows")
                        .executes(ctx -> listFlows(ctx.getSource())))
                .then(Commands.literal("flow")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> runFlow(ctx.getSource(), arg(ctx, "name")))))
                .then(Commands.literal("targets")
                        .executes(ctx -> listTargets(ctx.getSource())))
                .then(Commands.literal("strategies")
                        .executes(ctx -> listStrategies(ctx.getSource())))
                .then(Commands.literal("datapacks")
                        .executes(ctx -> listDatapacks(ctx.getSource())))
                .then(Commands.literal("globalpack")
                        .executes(ctx -> globalPack(ctx.getSource())))

                .then(Commands.literal("config")
                        .executes(ctx -> configShow(ctx.getSource()))
                        .then(Commands.literal("show").executes(ctx -> configShow(ctx.getSource())))
                        .then(Commands.literal("path").executes(ctx -> configPath(ctx.getSource())))
                        .then(Commands.literal("save").executes(ctx -> configSave(ctx.getSource())))
                        .then(Commands.literal("template")
                                .executes(ctx -> configTemplate(ctx.getSource())))
                        .then(Commands.literal("reload").executes(ctx -> configReload(ctx.getSource()))));
    }

    /** Builds an override description from a category token list alone, for the async verb. */
    private static ConfigFile kindsOnly(String kindsArg) {
        if (kindsArg == null || kindsArg.isBlank()) {
            return ConfigFile.empty();
        }
        java.util.Set<ElementKind> kinds = Tokens.kinds(split(kindsArg));
        if (kinds == null) {
            throw new IllegalArgumentException("unknown category in '" + kindsArg
                    + "'; see /uee kinds");
        }
        return ConfigFile.builder().kinds(kinds).build();
    }

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- the export path

    /**
     * Resolves a run from the config file plus the command's overrides, then runs it.
     *
     * <p>Everything the user typed becomes one partial {@link ConfigFile} and the resolution happens
     * in one place, so a command and a config file cannot disagree about what a setting means.
     */
    private static int runDefault(CommandSourceStack source, String kindsArg, String formatsArg) {
        ConfigFile overrides;
        try {
            overrides = overridesOf(kindsArg, formatsArg);
        } catch (IllegalArgumentException e) {
            // Name the offending token rather than the parse failure: the user needs to know which
            // word was wrong, so the exception carries it.
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
        return runWith(source, overrides);
    }

    /**
     * Resolves and runs, given the caller's partial description.
     *
     * <p>Every command verb funnels here, including {@code /uee flow}, so the token-parsing verbs and
     * the flow verb cannot diverge in how a run is resolved.
     */
    private static int runWith(CommandSourceStack source, ConfigFile overrides) {
        ConfigResolver.Resolved resolved;
        try {
            resolved = Uee.resolveForRun(sessionLayers(), overrides);
        } catch (IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": could not read the config file: " + e));
            return 0;
        }
        if (!resolved.ok()) {
            for (String error : resolved.errors()) {
                source.sendFailure(Component.literal(Uee.NAME + ": " + error));
            }
            return 0;
        }
        for (String warning : resolved.warnings()) {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": warning: " + warning), false);
        }

        ExportConfig config = resolved.config();
        boolean quiet = config.quiet();
        Path root = config.outputDir();
        if (!quiet) {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": exporting "
                    + config.kinds().size() + " category/categories as "
                    + String.join(", ", config.formats())), false);
        }

        long started = System.nanoTime();
        try {
            ExportReport report = Uee.export(config, root);
            long millis = (System.nanoTime() - started) / 1_000_000L;
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + report.artifacts().size()
                    + " files, " + report.records() + " records, " + report.findings()
                    + " findings in " + millis + " ms"), false);
            if (!report.failures().isEmpty()) {
                // Not a failure of the run: the isolation contract means these are recorded and the
                // export continues, so this is reported as information with a pointer to the list.
                source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + report.failures().size()
                        + " element(s) could not be collected; the run continued and the list is in"
                        + " the output"), false);
            }
            if (!quiet) {
                showLocations(source, root, config);
            }
            // The return value is the contract with a function: files written, so that
            // `execute store result` yields a number and `store success` gates on non-zero.
            return report.artifacts().size();
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + " export failed: " + t));
            return 0;
        }
    }

    // ---------------------------------------------------------------- the asynchronous path

    /**
     * Starts an export on a worker thread and returns immediately.
     *
     * <p>This is the form a function should use. A synchronous export inside a tick holds the server
     * thread for as long as the run takes, which for a large pack is long enough to trip the
     * watchdog; this returns in the same tick and the caller polls {@code /uee jobs}.
     *
     * <p>Refuses when the adapter has not declared that its registries may be read off-thread. That
     * refusal is the honest answer: the alternative is to trade a stalled tick for intermittent
     * corruption, and a clear error is better than either.
     */
    private static int runAsync(CommandSourceStack source, ConfigFile overrides) {
        ConfigResolver.Resolved resolved;
        try {
            resolved = Uee.resolveForRun(sessionLayers(), overrides);
        } catch (IllegalStateException | IOException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
        if (!resolved.ok()) {
            for (String error : resolved.errors()) {
                source.sendFailure(Component.literal(Uee.NAME + ": " + error));
            }
            return 0;
        }
        for (String warning : resolved.warnings()) {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": warning: " + warning), false);
        }

        ExportConfig config = resolved.config();
        try {
            UeeJob job = Uee.startExport(config, config.outputDir(),
                    completionNotifier(source, config));
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": started job #" + job.id()
                    + " (" + job.label() + ")"), false);
            // Said differently depending on whether anyone will be told the result: a player does not
            // need instructions for a message they are about to receive, and a function does need
            // them because nothing else will arrive.
            if (source.getEntity() != null) {
                source.sendSuccess(() -> Component.literal(
                        "  you will be told when it finishes"), false);
            } else {
                source.sendSuccess(() -> Component.literal(
                        "  poll /uee jobs until it reaches 0"), false);
            }
            return 1;
        } catch (IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + " could not start the export: " + t));
            return 0;
        }
    }

    /** Builds the override description for a formats-only invocation. */
    private static ConfigFile formatsOnly(String formatsArg) {
        java.util.Set<String> formats = Tokens.formats(split(formatsArg));
        if (formats == null) {
            throw new IllegalArgumentException("unknown format in '" + formatsArg
                    + "'; see /uee formats");
        }
        return ConfigFile.builder().formats(formats).build();
    }

    private static int runAsyncWithFormats(CommandSourceStack source, String formatsArg) {
        try {
            return runAsync(source, formatsOnly(formatsArg));
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Arranges for the caller to be told when the run finishes, when there is someone to tell.
     *
     * <p>Resolved from the source at completion time rather than captured, because a source can be
     * invalidated — a player may have logged out — and holding a stale one is worse than saying
     * nothing. When there is nobody behind the call, which is the case for a function or a command
     * block, no notifier is attached and the run is polled instead.
     */
    private static java.util.function.Consumer<UeeJob> completionNotifier(CommandSourceStack source,
            ExportConfig config) {
        net.minecraft.world.entity.Entity entity = source.getEntity();
        if (entity == null) {
            return null;
        }
        java.util.UUID who = entity.getUUID();
        var server = source.getServer();
        return job -> {
            var player = server.getPlayerList().getPlayer(who);
            if (player == null) {
                // Gone. The artifacts are on disk and the summary is in the log; nothing else to do.
                return;
            }
            player.sendSystemMessage(Component.literal(Uee.NAME + ": job #" + job.id() + " "
                    + job.outcome()));
            if (job.report() != null) {
                player.sendSystemMessage(Component.literal("  ").append(link(source, job.root())));
            }
        };
    }

    /**
     * Reports unfinished jobs, and is the primitive a function polls.
     *
     * <p>The return value is the count, so a function waits with
     * {@code execute store result score … run uee jobs} and loops until it is zero. An MC function
     * cannot be handed a value and cannot block, so a polled count is the only shape that composes —
     * which is the reason jobs exist at all.
     */
    private static int jobs(CommandSourceStack source) {
        UeeJobs runner = Uee.jobs();
        int unfinished = runner.unfinished();
        if (unfinished == 0) {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": idle"), false);
        } else {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + unfinished
                    + " job(s) in progress"), false);
            for (UeeJob job : runner.activeJobs()) {
                source.sendSuccess(() -> Component.literal("  " + job.describe()), false);
            }
        }
        if (!runner.recentJobs().isEmpty()) {
            source.sendSuccess(() -> Component.literal("  recent:"), false);
            runner.recentJobs().stream().limit(5).forEach(job -> source.sendSuccess(
                    () -> Component.literal("    " + job.describe()), false));
        }
        return unfinished;
    }

    private static int jobDetail(CommandSourceStack source, int id) {
        UeeJob job = Uee.jobs().get(id);
        if (job == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no job #" + id));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("  " + job.describe()), false);
        source.sendSuccess(() -> Component.literal("    state " + job.state().token()
                + ", kinds " + job.kinds() + ", formats " + job.formats()), false);
        ExportReport report = job.report();
        if (report != null) {
            source.sendSuccess(() -> Component.literal("    wrote to " + report.root()), false);
        }
        return 1;
    }

    private static int cancelJob(CommandSourceStack source, int id) {
        UeeJob job = Uee.jobs().get(id);
        if (job == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no job #" + id));
            return 0;
        }
        if (Uee.jobs().cancel(id)) {
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": cancelled job #" + id), false);
            return 1;
        }
        // A running export is mid-write across many open shards; stopping it would leave a bundle
        // whose completeness cannot be determined, so this is refused rather than done half-way.
        source.sendFailure(Component.literal(Uee.NAME + ": job #" + id + " has already started;"
                + " an export in progress is not interrupted because a partly written bundle cannot"
                + " be told from a complete one"));
        return 0;
    }

    /** Emits clickable paths, so the output can be opened without retyping them. */
    private static void showLocations(CommandSourceStack source, Path root, ExportConfig config) {
        source.sendSuccess(() -> Component.literal("  data:     ").append(link(source, root)), false);
        if (config.analyze() && config.analysisSeparate()) {
            Path analysis = sibling(root, "-analysis");
            source.sendSuccess(() -> Component.literal("  analysis: ").append(link(source, analysis)), false);
        }
    }

    /** Where the analysis lands when it is kept apart, matching the pipeline's own rule. */
    static Path sibling(Path root, String suffix) {
        Path name = root.getFileName();
        return name == null ? root : root.resolveSibling(name + suffix);
    }

    /**
     * A path, clickable when there is a client to click it.
     *
     * <p>A function has no player behind it, and a console or the server log has nothing to click. In
     * those contexts the path is still the useful part, so it is emitted as plain text rather than
     * carrying an interaction that reaches nobody.
     */
    private static Component link(CommandSourceStack source, Path path) {
        String text = path.toAbsolutePath().toString();
        boolean interactive = source.getEntity() != null;
        Component base = Component.literal(text);
        if (!interactive) {
            return base;
        }
        return base.withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, text))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("open / 打开"))));
    }

    /**
     * Turns the command's tokens into a partial description.
     *
     * @return the description, or an empty one when nothing was typed
     */
    static ConfigFile overridesOf(String kindsArg, String formatsArg) {
        ConfigFile.Builder b = ConfigFile.builder();
        boolean anything = false;

        if (kindsArg != null && !kindsArg.isBlank()) {
            List<String> tokens = split(kindsArg);
            Set<ElementKind> kinds = Tokens.kinds(tokens);
            if (kinds == null) {
                throw new IllegalArgumentException(
                        "unknown category in '" + kindsArg + "'; see /uee kinds");
            }
            b.kinds(kinds);
            anything = true;
        }
        if (formatsArg != null && !formatsArg.isBlank()) {
            List<String> tokens = split(formatsArg);
            Set<String> formats = Tokens.formats(tokens);
            if (formats == null) {
                throw new IllegalArgumentException(
                        "unknown format in '" + formatsArg + "'; see /uee formats");
            }
            b.formats(formats);
            anything = true;
        }
        return anything ? b.build() : ConfigFile.empty();
    }

    private static List<String> split(String arg) {
        List<String> out = new ArrayList<>(4);
        for (String piece : arg.split("[,\\s]+")) {
            if (!piece.isBlank()) {
                out.add(piece.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- other verbs

    private static int analyze(CommandSourceStack source) {
        if (Uee.adapter() == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no loader adapter bound"));
            return 0;
        }
        List<Finding> findings = new ArrayList<>(16);
        try {
            Uee.analyzeWithoutCollecting(findings::add);
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + " analysis failed: " + t));
            return 0;
        }
        if (findings.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    Uee.NAME + ": nothing found by the checks that need no collection"), false);
            return 0;
        }
        int errors = (int) findings.stream().filter(Finding::isError).count();
        source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + findings.size()
                + " finding(s), " + errors + " error(s)"), false);
        // Errors first: a missing dependency matters more than a large container, and a list that
        // buries the former under the latter does not get read.
        findings.stream()
                .sorted(Comparator.comparing(f -> f.severity().ordinal()))
                .limit(FINDINGS_SHOWN)
                .forEach(f -> source.sendSuccess(() -> Component.literal(
                        "  [" + f.severity().token() + "] " + f.message()), false));
        if (findings.size() > FINDINGS_SHOWN) {
            int remaining = findings.size() - FINDINGS_SHOWN;
            source.sendSuccess(() -> Component.literal("  ... and " + remaining
                    + " more; /uee export analysis writes them all out"), false);
        }
        return findings.size();
    }

    private static int status(CommandSourceStack source) {
        var adapter = Uee.adapter();
        if (adapter == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no loader adapter bound"));
            return 0;
        }
        var info = adapter.info();
        source.sendSuccess(() -> Component.literal(Uee.NAME + " " + info.loader() + " "
                + info.loaderVersion() + " / Minecraft " + info.minecraftVersion()
                + " / " + adapter.mods().size() + " mods"), false);

        // Show what a one-key run would do, so the effective state is visible before anything runs.
        try {
            ConfigResolver.Resolved r = Uee.resolveForRun(ConfigFile.empty());
            ExportConfig c = r.config();
            source.sendSuccess(() -> Component.literal("  default export: "
                    + String.join(", ", c.formats()) + " / " + c.kinds().size()
                    + " categories / analysis " + (c.analyze()
                            ? (c.analysisSeparate() ? "in its own package" : "bundled") : "off")),
                    false);
            for (String warning : r.warnings()) {
                source.sendSuccess(() -> Component.literal("  warning: " + warning), false);
            }
        } catch (IOException e) {
            source.sendSuccess(() -> Component.literal("  config file unreadable: " + e), false);
        }
        Path file = Uee.configFile();
        if (file != null) {
            source.sendSuccess(() -> Component.literal("  config:   ").append(link(source, file)), false);
        }
        return 1;
    }

    private static int listKinds(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("categories / 内容类别"), false);
        source.sendSuccess(() -> Component.literal("  groups:   " + Tokens.groups()), false);
        source.sendSuccess(() -> Component.literal("  data:     " + kindNames(true)), false);
        source.sendSuccess(() -> Component.literal("  analysis: " + kindNames(false)), false);
        source.sendSuccess(() -> Component.literal(
                "  name a category in either its singular or its plural form"), false);
        source.sendSuccess(() -> Component.literal("  e.g. /uee export items,blocks entity"), false);
        return 1;
    }

    private static String kindNames(boolean data) {
        StringBuilder sb = new StringBuilder(128);
        for (ElementKind k : ElementKind.values()) {
            if (k.isAnalysis() != data) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k.plural());
        }
        return sb.toString();
    }

    private static int listFormats(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "formats: " + String.join(" ", Uee.formatTokens())), false);
        source.sendSuccess(() -> Component.literal(
                "  json grouped document · ndjson one record per line · wiki importer projection"),
                false);
        source.sendSuccess(() -> Component.literal(
                "  td / zd tie ecosystem · yaml / toml / xml generic interchange"), false);
        source.sendSuccess(() -> Component.literal("  e.g. /uee formats json,wiki"), false);
        return 1;
    }

    // ---------------------------------------------------------------- the long form

    /**
     * Settings made this session, each as its own partial description, in the order they were made.
     *
     * <p>A stack rather than one merged description. Each {@code /uee set …} contributes a layer, and
     * the resolver already knows how to apply a stack — so a session setting is not a special case in
     * the resolution path, it is just one more layer. Merging them here would be a second
     * implementation of layering, and the two would drift.
     *
     * <p>Held in memory rather than written to the config file, so experimenting does not quietly
     * become permanent. {@code /uee config save} is how a setting that turned out to be right gets
     * written down — an explicit step, because a command that silently edited the config would make
     * "what am I actually running" unanswerable.
     */
    private static final java.util.List<ConfigFile> SESSION = new java.util.ArrayList<>(8);
    private static final java.util.List<String> SESSION_LOG = new java.util.ArrayList<>(8);

    private static void remember(String what, ConfigFile layer) {
        SESSION.add(layer);
        SESSION_LOG.add(what);
    }

    /**
     * The layers this session has accumulated.
     *
     * <p>Ordered oldest first, so a later setting overrides an earlier one — which is what someone
     * typing one {@code set} after another expects.
     */
    static java.util.List<ConfigFile> sessionLayers() {
        return java.util.List.copyOf(SESSION);
    }

    private static int setFields(CommandSourceStack source, String names) {
        java.util.Set<String> fields = splitToSet(names);
        if (fields.isEmpty()) {
            source.sendFailure(Component.literal(Uee.NAME + ": /uee set fields <name> <name> …"));
            return 0;
        }
        remember("fields " + String.join(",", fields),
                ConfigFile.builder().includeFields(fields).build());
        report(source, "only these fields: " + String.join(", ", fields)
                + " (identity fields are always kept)");
        return fields.size();
    }

    private static int setExcludeFields(CommandSourceStack source, String names) {
        java.util.Set<String> fields = splitToSet(names);
        if (fields.isEmpty()) {
            source.sendFailure(Component.literal(Uee.NAME
                    + ": /uee set exclude-fields <name> <name> …"));
            return 0;
        }
        remember("exclude fields " + String.join(",", fields),
                ConfigFile.builder().excludeFields(fields).build());
        report(source, "dropping these fields: " + String.join(", ", fields));
        return fields.size();
    }

    private static int setTags(CommandSourceStack source, String tags, boolean exclude) {
        java.util.Set<String> set = splitToSet(tags);
        if (set.isEmpty()) {
            source.sendFailure(Component.literal(Uee.NAME
                    + ": /uee set tags <tag> … or /uee set skip-tags <tag> …"));
            return 0;
        }
        if (exclude) {
            remember("skip tags " + String.join(",", set),
                    ConfigFile.builder().excludeTags(set).build());
            report(source, "skipping elements tagged " + String.join(", ", set));
        } else {
            remember("tags " + String.join(",", set),
                    ConfigFile.builder().includeTags(set).build());
            report(source, "only elements tagged " + String.join(", ", set)
                    + " (untagged elements are excluded)");
        }
        return set.size();
    }

    private static int setNamespaces(CommandSourceStack source, String namespaces, boolean exclude) {
        java.util.Set<String> set = splitToSet(namespaces);
        if (set.isEmpty()) {
            source.sendFailure(Component.literal(Uee.NAME
                    + ": /uee set namespaces <ns> … or /uee set skip-namespaces <ns> …"));
            return 0;
        }
        ConfigFile.Builder b = ConfigFile.builder();
        if (exclude) {
            for (String ns : set) {
                b.excludeNamespace(ns);
            }
            remember("skip namespaces " + String.join(",", set), b.build());
            report(source, "skipping namespaces " + String.join(", ", set));
        } else {
            for (String ns : set) {
                b.includeNamespace(ns);
            }
            remember("namespaces " + String.join(",", set), b.build());
            report(source, "only namespaces " + String.join(", ", set));
        }
        return set.size();
    }

    private static int setSkipMods(CommandSourceStack source, String mods) {
        java.util.Set<String> set = splitToSet(mods);
        if (set.isEmpty()) {
            source.sendFailure(Component.literal(Uee.NAME + ": /uee set skip-mods <mod> …"));
            return 0;
        }
        ConfigFile.Builder b = ConfigFile.builder();
        for (String id : set) {
            b.excludeMod(id);
        }
        remember("skip mods " + String.join(",", set), b.build());
        report(source, "skipping mods " + String.join(", ", set));
        return set.size();
    }

    private static int setShards(CommandSourceStack source, int records) {
        remember("shard size " + records, ConfigFile.builder().shardSize(records).build());
        report(source, "shard size: " + records + " records");
        return records;
    }

    private static int setMaxFile(CommandSourceStack source, int mb) {
        remember("max file " + mb + "MiB", ConfigFile.builder().maxFileMb(mb).build());
        report(source, mb == 0
                ? "no byte-based file limit; shards split by record count"
                : "shards split at about " + mb + " MiB (a whole record may overshoot)");
        return mb;
    }

    private static int setOutput(CommandSourceStack source, String dir) {
        if (dir == null || dir.isBlank()) {
            source.sendFailure(Component.literal(Uee.NAME + ": /uee set output <dir>"));
            return 0;
        }
        remember("output " + dir, ConfigFile.builder().output(Path.of(dir)).build());
        report(source, "output directory: " + dir);
        return 1;
    }

    /** Handles the boolean toggles, which all have the same shape. */
    private static int setFlag(CommandSourceStack source, String key, boolean value) {
        ConfigFile.Builder b = ConfigFile.builder();
        switch (key) {
            case "quiet" -> b.quiet(value);
            case "dry_run" -> b.dryRun(value);
            case "icons" -> b.icons(value);
            default -> {
                source.sendFailure(Component.literal(Uee.NAME + ": " + key + " is not settable"));
                return 0;
            }
        }
        remember(key + "=" + value, b.build());
        report(source, switch (key) {
            case "quiet" -> value ? "quiet: one summary line only" : "verbose again";
            case "dry_run" -> value
                    ? "dry run: nothing will be written, but the plan is real"
                    : "writing again";
            default -> "icons: " + value;
        });
        return 1;
    }

    private static java.util.Set<String> splitToSet(String arg) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (arg == null) {
            return out;
        }
        for (String piece : arg.split("[,\s]+")) {
            if (!piece.isBlank()) {
                out.add(piece.trim());
            }
        }
        return out;
    }

    private static void report(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + message), false);
        source.sendSuccess(() -> Component.literal(
                "  in effect until the server restarts; /uee config save makes it permanent"), false);
    }

    /** Lists what the long form can set, so it is discoverable without the documentation. */
    private static int listSettables(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("settable / 可设置：" + SESSION_LOG.size()
                + " override(s) this session"), false);
        for (String line : SESSION_LOG) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        source.sendSuccess(() -> Component.literal("  /uee set fields <name> …"), false);
        source.sendSuccess(() -> Component.literal("  /uee set exclude-fields <name> …"), false);
        source.sendSuccess(() -> Component.literal("  /uee set tags | skip-tags <tag> …"), false);
        source.sendSuccess(() -> Component.literal(
                "  /uee set namespaces | skip-namespaces <ns> …"), false);
        source.sendSuccess(() -> Component.literal("  /uee set skip-mods <mod> …"), false);
        source.sendSuccess(() -> Component.literal("  /uee set shards <records>"), false);
        source.sendSuccess(() -> Component.literal("  /uee set max-file-mb <n>   (0 = by count)"),
                false);
        source.sendSuccess(() -> Component.literal("  /uee set output <dir>"), false);
        source.sendSuccess(() -> Component.literal(
                "  /uee set quiet | noisy | dry-run | icons | no-icons"), false);
        source.sendSuccess(() -> Component.literal(
                "  every one of these is also a config file key; /uee config template lists them"),
                false);
        return SESSION_LOG.size();
    }

    // ---------------------------------------------------------------- the fourth interface

    /**
     * Runs a named datapack flow.
     *
     * <p>The flow is put into the override layer as a name, not expanded here: resolution is the
     * resolver's job, and expanding it in the command would be the second path this design exists to
     * avoid. A flow the resolver cannot find is reported there, with the rest of the caller's request.
     */
    private static int runFlow(CommandSourceStack source, String name) {
        if (name == null || name.isBlank()) {
            source.sendFailure(Component.literal(Uee.NAME + ": /uee flow <name>"));
            return 0;
        }
        // Two kinds of flow live under one name. A function flow is a datapack function, so the game
        // runs it — UEE only has to find it and hand it over, which keeps a pack free to write a flow
        // as a sequence of steps rather than only as a configuration.
        FunctionFlow functionFlow = Uee.catalog().functionFlow(name);
        if (functionFlow != null) {
            return runFunctionFlow(source, functionFlow);
        }
        return runWith(source, ConfigFile.builder().flow(name).build());
    }

    /**
     * Runs a flow that is an MC function.
     *
     * <p>UEE deliberately does not interpret the function: the game already has a function runner, and
     * reimplementing it would mean a second, worse one that cannot express {@code execute},
     * {@code data}, macros or anything else a pack might use. All that is needed is to hand the
     * resource id over.
     */
    private static int runFunctionFlow(CommandSourceStack source, FunctionFlow flow) {
        try {
            McFunctions.run(source, flow.resourceId());
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": ran flow "
                    + flow.qualifiedId() + " (" + flow.resourceId() + ")"), false);
            return 1;
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + ": flow " + flow.qualifiedId()
                    + " could not be run: " + t));
            return 0;
        }
    }

    private static int listFlows(CommandSourceStack source) {
        DatapackCatalog catalog = Uee.catalog();
        if (catalog.allFlowIds().isEmpty()) {
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": no datapack defines a flow"), false);
            source.sendSuccess(() -> Component.literal(
                    "  a configuration flow goes at data/<namespace>/uee/flows/<id>.json"), false);
            source.sendSuccess(() -> Component.literal(
                    "  a function flow goes at " + Uee.functionFlowPattern()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("flows / 流程 (" + catalog.allFlowIds().size()
                + ")"), false);
        for (FlowDefinition flow : catalog.flows().values()) {
            source.sendSuccess(() -> Component.literal("  " + flow.qualifiedId() + "  [config]"
                    + (flow.description() == null ? "" : "  — " + flow.description())), false);
            source.sendSuccess(() -> Component.literal("      sets: "
                    + (flow.touchedKeys().isEmpty() ? "(defaults only)"
                            : String.join(", ", flow.touchedKeys()))
                    + (flow.hasTargets() ? "  targets: " + String.join(", ", flow.targets()) : "")),
                    false);
        }
        for (FunctionFlow flow : catalog.functionFlows().values()) {
            source.sendSuccess(() -> Component.literal("  " + flow.qualifiedId() + "  [function]  "
                    + flow.resourceId()
                    + (flow.description() == null ? "" : "  — " + flow.description())), false);
        }
        return catalog.allFlowIds().size();
    }

    private static int listTargets(CommandSourceStack source) {
        DatapackCatalog catalog = Uee.catalog();
        if (catalog.targets().isEmpty()) {
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": no datapack defines a collection target"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "collection targets / 采集对象 (" + catalog.targets().size() + ")"), false);
        for (TargetDefinition target : catalog.targets().values()) {
            source.sendSuccess(() -> Component.literal("  " + target.namespace() + ":" + target.id()
                    + "  [" + target.category().plural() + "]  " + target.selector().describe()),
                    false);
        }
        return catalog.targets().size();
    }

    private static int listStrategies(CommandSourceStack source) {
        DatapackCatalog catalog = Uee.catalog();
        if (catalog.strategies().isEmpty()) {
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": no datapack defines an analysis strategy"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "analysis strategies / 分析策略 (" + catalog.strategies().size() + ")"), false);
        for (StrategyDefinition strategy : catalog.strategies().values()) {
            source.sendSuccess(() -> Component.literal("  " + strategy.qualifiedId()
                    + "  (" + strategy.rules().size() + " rules"
                    + (strategy.needsCollection() ? ", needs collection" : "") + ")"), false);
            if (strategy.description() != null) {
                source.sendSuccess(() -> Component.literal("      " + strategy.description()), false);
            }
        }
        return catalog.strategies().size();
    }

    private static int listDatapacks(CommandSourceStack source) {
        DatapackCatalog catalog = Uee.catalog();
        source.sendSuccess(() -> Component.literal("datapacks defining UEE content ("
                + catalog.sources().size() + ")"), false);
        if (catalog.sources().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  none; /uee flow and /uee targets will be empty"), false);
        }
        for (DatapackSource datapack : catalog.sources().values()) {
            source.sendSuccess(() -> Component.literal("  " + datapack.id()
                    + "  (" + datapack.origin().token() + ")"
                    + (datapack.path() == null ? "" : "  " + datapack.path())), false);
        }
        if (!catalog.problems().isEmpty()) {
            source.sendSuccess(() -> Component.literal("  problems / 问题:"), false);
            catalog.problems().forEach(p -> source.sendSuccess(() -> Component.literal(
                    "    [" + p.severity().token() + "] " + p.message()), false));
        }
        return catalog.sources().size();
    }

    /**
     * Reports the global-datapack decision.
     *
     * <p>Worth its own verb because the answer is not in the config: whether UEE provides global
     * datapacks depends on which mods are loaded, and "why is my pack not loading" is the question
     * this verb answers.
     */
    private static int globalPack(CommandSourceStack source) {
        ExportConfig config;
        try {
            config = Uee.resolveForRun(ConfigFile.empty()).config();
        } catch (IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e));
            return 0;
        }
        GlobalPackPolicy policy = Uee.globalPackPolicy(config);
        source.sendSuccess(() -> Component.literal("global datapacks / 全局数据包"), false);
        source.sendSuccess(() -> Component.literal("  " + policy.describe()), false);
        source.sendSuccess(() -> Component.literal("  UEE directory: " + policy.ourDirectory()
                + "  (" + policy.ourPackCount() + " pack(s))"), false);
        if (policy.deferred()) {
            source.sendSuccess(() -> Component.literal("  delegated to: "
                    + policy.deferredTo().name() + " (" + policy.deferredTo().modId() + ")"), false);
            source.sendSuccess(() -> Component.literal("  its directories: "
                    + String.join(", ", policy.detected().get(0).directories())), false);
        }
        for (Finding finding : policy.findings()) {
            source.sendSuccess(() -> Component.literal(
                    "  [" + finding.severity().token() + "] " + finding.message()), false);
        }
        return 1;
    }

    /** Runs the metadata-only checks, plus the global-datapack decision, without exporting. */
    private static int analyzeWithPolicy(CommandSourceStack source) {
        int n = analyze(source);
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            for (Finding finding : Uee.globalPackPolicy(config).findings()) {
                source.sendSuccess(() -> Component.literal(
                        "  [" + finding.severity().token() + "] " + finding.message()), false);
            }
        } catch (IOException | IllegalStateException e) {
            // The analysis already reported what it could; a config problem here is not new news.
        }
        return n;
    }

    // ---------------------------------------------------------------- config verbs

    private static int configShow(CommandSourceStack source) {
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            source.sendSuccess(() -> Component.literal("effective configuration / 生效配置"), false);
            source.sendSuccess(() -> Component.literal("  output:  " + config.outputDir()), false);
            source.sendSuccess(() -> Component.literal(
                    "  formats: " + String.join(", ", config.formats())), false);
            source.sendSuccess(() -> Component.literal(
                    "  kinds:   " + config.kinds().size() + " categories"), false);
            source.sendSuccess(() -> Component.literal("  analyze: " + config.analyze()
                    + (config.analyze() ? " (separate=" + config.analysisSeparate() + ")" : "")),
                    false);
            source.sendSuccess(() -> Component.literal("  layout:  packagePerKind="
                    + config.packagePerKind() + " shardByNamespace=" + config.shardByNamespace()),
                    false);
            source.sendSuccess(() -> Component.literal("  icons:   " + config.icons()), false);
            Path file = Uee.configFile();
            if (file != null) {
                source.sendSuccess(() -> Component.literal("  config:  ").append(link(source, file)), false);
            }
            return 1;
        } catch (IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e));
            return 0;
        }
    }

    private static int configPath(CommandSourceStack source) {
        Path file = Uee.configFile();
        if (file == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no adapter bound"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("config file: ").append(link(source, file)), false);
        return 1;
    }

    private static int configSave(CommandSourceStack source) {
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            Path file = Uee.writeConfig(config);
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": wrote the effective configuration to ").append(link(source, file)), false);
            return 1;
        } catch (IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e));
            return 0;
        }
    }

    private static int configTemplate(CommandSourceStack source) {
        Path file = Uee.configFile();
        if (file == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no adapter bound"));
            return 0;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ConfigFile.template());
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": wrote the annotated default configuration to ").append(link(source, file)), false);
            source.sendSuccess(() -> Component.literal(
                    "  every key is documented in place; edit it and run /uee config reload"), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e));
            return 0;
        }
    }

    private static int configReload(CommandSourceStack source) {
        Path file = Uee.configFile();
        if (file == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no adapter bound"));
            return 0;
        }
        try {
            ConfigResolver.Resolved r = Uee.resolveForRun(ConfigFile.empty());
            if (!r.ok()) {
                for (String error : r.errors()) {
                    source.sendFailure(Component.literal(Uee.NAME + ": " + error));
                }
                return 0;
            }
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": reloaded ").append(link(file)),
                    false);
            for (String warning : r.warnings()) {
                source.sendSuccess(() -> Component.literal("  warning: " + warning), false);
            }
            source.sendSuccess(() -> Component.literal("  formats: "
                    + String.join(", ", r.config().formats()) + " / "
                    + r.config().kinds().size() + " categories"), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": could not read " + file + ": " + e));
            return 0;
        }
    }

    // ---------------------------------------------------------------- help

    private static int help(CommandSourceStack source, String name) {
        String p = "/" + name;
        source.sendSuccess(() -> Component.literal(Uee.NAME + " — multi-loader element exporter"),
                false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + "                      export with the default settings"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " export [kinds]       choose which categories to export"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " formats <formats>    choose the output format(s)"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " data | analysis       only one half of a run"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " analyze               run the checks that need no export"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " flow <name>           run a datapack-defined flow"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " export async [kinds]  start an export and return at once"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " jobs                  unfinished jobs; poll until 0"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " job <id> [cancel]     inspect or cancel a job"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " flows | targets | strategies | datapacks"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " globalpack            who provides global datapacks"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " kinds | formats       list the accepted tokens"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " status                loader, version, effective defaults"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " set <what> …        override a setting for this session"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " config show|path|save|template|reload"), false);
        source.sendSuccess(() -> Component.literal(
                "  categories: " + Tokens.groups() + ", or any name from " + p + " kinds"), false);
        source.sendSuccess(() -> Component.literal(
                "  callable from " + Uee.functionFlowPattern() + "; use 'export async' there so the"
                        + " tick is not held"), false);
        return 1;
    }
}
