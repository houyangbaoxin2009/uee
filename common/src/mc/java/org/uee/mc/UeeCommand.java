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
import org.uee.config.CommandSurface;
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
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setFields(arg(ctx, "names"), false)))))
                        .then(Commands.literal("exclude-fields")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setFields(arg(ctx, "names"), true)))))
                        .then(Commands.literal("tags")
                                .then(Commands.argument("tags", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setTags(arg(ctx, "tags"), false)))))
                        .then(Commands.literal("skip-tags")
                                .then(Commands.argument("tags", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setTags(arg(ctx, "tags"), true)))))
                        .then(Commands.literal("namespaces")
                                .then(Commands.argument("namespaces",
                                                StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setNamespaces(
                                                        arg(ctx, "namespaces"), false)))))
                        .then(Commands.literal("skip-namespaces")
                                .then(Commands.argument("namespaces",
                                                StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setNamespaces(
                                                        arg(ctx, "namespaces"), true)))))
                        .then(Commands.literal("skip-mods")
                                .then(Commands.argument("mods", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setSkipMods(arg(ctx, "mods"))))))
                        .then(Commands.literal("shards")
                                .then(Commands.argument("records",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(1))
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setShards(
                                                        com.mojang.brigadier.arguments
                                                                .IntegerArgumentType
                                                                .getInteger(ctx, "records"))))))
                        .then(Commands.literal("max-file-mb")
                                .then(Commands.argument("mb",
                                                com.mojang.brigadier.arguments.IntegerArgumentType
                                                        .integer(0))
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setMaxFileMb(
                                                        com.mojang.brigadier.arguments
                                                                .IntegerArgumentType
                                                                .getInteger(ctx, "mb"))))))
                        .then(Commands.literal("output")
                                .then(Commands.argument("dir", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setOutput(arg(ctx, "dir"))))))
                        .then(Commands.literal("quiet")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("quiet", true))))
                        .then(Commands.literal("noisy")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("quiet", false))))
                        .then(Commands.literal("dry-run")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("dry_run", true))))
                        .then(Commands.literal("icons")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("icons", true))))
                        .then(Commands.literal("no-icons")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("icons", false))))
                        .then(Commands.literal("assets")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("assets", true))))
                        .then(Commands.literal("no-assets")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("assets", false))))
                        .then(Commands.literal("delta")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("delta", true))))
                        .then(Commands.literal("no-delta")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("delta", false))))
                        .then(Commands.literal("auto-run")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("auto_run", true))))
                        .then(Commands.literal("no-auto-run")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setFlag("auto_run", false))))
                        .then(Commands.literal("persist")
                                .then(Commands.argument("keys", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setPersist(arg(ctx, "keys"))))))
                        .then(Commands.literal("language")
                                .executes(ctx -> applySetting(ctx.getSource(),
                                        () -> SURFACE.setLanguage("")))
                                .then(Commands.argument("locale", StringArgumentType.word())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setLanguage(arg(ctx, "locale"))))))
                        .then(Commands.literal("user-dir")
                                .then(Commands.argument("dir", StringArgumentType.greedyString())
                                        .executes(ctx -> applySetting(ctx.getSource(),
                                                () -> SURFACE.setUserDir(arg(ctx, "dir"))))))
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

                .then(Commands.literal("declare")
                        .then(Commands.literal("list")
                                .executes(ctx -> declareList(ctx.getSource())))
                        .then(Commands.literal("where")
                                .executes(ctx -> declareWhere(ctx.getSource())))
                        .then(Commands.literal("asset-kind")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> declareAssetKind(ctx.getSource(),
                                                arg(ctx, "name"), true))))
                        .then(Commands.literal("no-asset-kind")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> declareAssetKind(ctx.getSource(),
                                                arg(ctx, "name"), false))))
                        .then(Commands.literal("asset-root-file")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> declareAssetRootFile(ctx.getSource(),
                                                arg(ctx, "name"), true))))
                        .then(Commands.literal("no-asset-root-file")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> declareAssetRootFile(ctx.getSource(),
                                                arg(ctx, "name"), false)))))
                .then(Commands.literal("config")
                        .executes(ctx -> configShow(ctx.getSource()))
                        .then(Commands.literal("show").executes(ctx -> configShow(ctx.getSource())))
                        .then(Commands.literal("path").executes(ctx -> configPath(ctx.getSource())))
                        .then(Commands.literal("save").executes(ctx -> configSave(ctx.getSource())))
                        .then(Commands.literal("template")
                                .executes(ctx -> configTemplate(ctx.getSource())))
                        .then(Commands.literal("reload").executes(ctx -> configReload(ctx.getSource()))));
    }

    /**
     * Builds an override description from a category token list alone.
     *
     * <p>Delegates to the surface, which is where the vocabulary and its error messages live so they
     * are identical here and in a config file, and verifiable without a game.
     */
    private static ConfigFile kindsOnly(String kindsArg) {
        return CommandSurface.kinds(kindsArg);
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
            resolved = Uee.resolveForRun(SURFACE.layers(), overrides);
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
            resolved = Uee.resolveForRun(SURFACE.layers(), overrides);
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
        return CommandSurface.formats(formatsArg);
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
     * The click action attached to a path, chosen by asking the API rather than by trust.
     *
     * <h2>The defect this exists to prevent</h2>
     *
     * <p>{@code OPEN_FILE} is the obvious choice for a path and is the one action the chat codec refuses to
     * serialise for a server. Its rejection is not local: encoding fails for the <em>whole</em> message, so
     * the client receives nothing and reports that it could not send a chat message while the server logs an
     * encode failure. Every message carrying a path was therefore unsendable — the status readout, the
     * config paths, and every "wrote ... to <path>" acknowledgement — and what the user saw was a complaint
     * about chat with the path they had asked for missing entirely.
     *
     * <p>Asking {@link ClickEvent.Action#isAllowedFromServer()} rather than hard-coding a name is the point:
     * the API answers the exact question, so the answer cannot go stale, and the set of allowed actions is
     * also loader-dependent — one loader patches the check to permit {@code OPEN_FILE} on an integrated
     * server, which is why this failed on one loader and would have appeared to work on another.
     *
     * <p>Returns null when nothing may be attached, and then no interaction is attached at all. A message
     * with no click is worth less than one with a click; a message that cannot be sent is worth nothing.
     */
    public static ClickEvent.Action pathAction() {
        return ClickEvent.Action.COPY_TO_CLIPBOARD.isAllowedFromServer()
                ? ClickEvent.Action.COPY_TO_CLIPBOARD
                : null;
    }

    /**
     * A path, clickable when there is a client to click it and an action that may be used.
     *
     * <p>A function has no player behind it, and a console or the server log has nothing to click. In
     * those contexts the path is still the useful part, so it is emitted as plain text rather than
     * carrying an interaction that reaches nobody.
     */
    private static Component link(CommandSourceStack source, Path path) {
        String text = path.toAbsolutePath().toString();
        // MutableComponent, not Component: the interface has no withStyle, and literal() already
        // returns something that does.
        net.minecraft.network.chat.MutableComponent base = Component.literal(text);
        ClickEvent.Action action = pathAction();
        if (source.getEntity() == null || action == null) {
            return base;
        }
        return base.withStyle(style -> style
                .withClickEvent(new ClickEvent(action, text))
                // The hover says what the click does. It used to say "open / 打开" for a click that has
                // never been able to open anything, since the action was refused everywhere it mattered.
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("copy / 复制"))));
    }

    /**
     * Turns the command's tokens into a partial description.
     *
     * @return the description, or an empty one when nothing was typed
     */
    static ConfigFile overridesOf(String kindsArg, String formatsArg) {
        return CommandSurface.kindsAndFormats(kindsArg, formatsArg);
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
     * The command surface's logic, with no dependency on the game.
     *
     * <p>Held here only so the command tree can reach it; everything it does is verifiable without a
     * game, which is the point of it living outside this class.
     */
    private static final CommandSurface SURFACE = new CommandSurface();

    /**
     * Applies a setting and reports it, or reports why it was refused.
     *
     * <p>The one place the command layer touches the surface's outcome, so every verb reports the same
     * way — and so the wording lives in the surface where it can be tested, rather than being spelled
     * out again in each branch here.
     */
    private static int applySetting(CommandSourceStack source,
            java.util.function.Supplier<CommandSurface.Setting> work) {
        try {
            CommandSurface.Setting setting = work.get();
            // Through the catalogue rather than the English rendering, so the confirmation of a setting is
            // in the same language as the setting was asked for.
            report(source, org.uee.mc.Ui.setting(setting));
            // Every set verb funnels through here, so "declare it once and it sticks" happens without
            // touching each verb -- and no verb added later can quietly forget to keep its setting.
            keepDeclaredSettings(source);
            return setting.count();
        } catch (IllegalArgumentException e) {
            // The surface's messages are already phrased for a user and already say how to fix it.
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Writes the declared settings to the portable state, and says so when something was kept.
     *
     * <p>Silent when nothing is declared to stick: that is the default, and reporting it after every setting
     * would be noise. A failure is reported rather than swallowed, because a state file that cannot be
     * written means the declaration did not take effect — which the user would otherwise discover much later
     * and in the wrong context.
     */
    private static void keepDeclaredSettings(CommandSourceStack source) {
        try {
            org.uee.config.ExportConfig resolved = Uee.resolveForRun(ConfigFile.empty()).config();
            int kept = Uee.saveUserState(resolved);
            if (kept > 0) {
                report(source, "kept " + kept + " setting(s) in " + Uee.userStateFile(resolved));
            }
        } catch (java.io.IOException | IllegalStateException e) {
            report(source, "could not keep the declared settings: " + e);
        }
    }

    private static void report(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> Component.literal(Uee.NAME + ": ").append(message), false);
        source.sendSuccess(() -> Component.literal(
                "  in effect until the server restarts; /uee config save makes it permanent"), false);
    }

    private static void report(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(Uee.NAME + ": " + message), false);
        source.sendSuccess(() -> Component.literal(
                "  in effect until the server restarts; /uee config save makes it permanent"), false);
    }

    /** Lists what the long form can set, so it is discoverable without the documentation. */
    private static int listSettables(CommandSourceStack source) {
        java.util.List<String> log = SURFACE.log();
        source.sendSuccess(() -> Component.literal("settable / 可设置：" + log.size()
                + " override(s) this session"), false);
        for (String line : log) {
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
        return log.size();
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
            source.sendSuccess(() -> Component.literal("  " + qualified(strategy)
                    + "  (" + strategy.rules().size() + " rules"
                    + (strategy.needsCollection() ? ", needs collection" : "") + ")"), false);
            if (strategy.description() != null) {
                source.sendSuccess(() -> Component.literal("      " + strategy.description()), false);
            }
        }
        return catalog.strategies().size();
    }

    /**
     * A strategy's fully qualified id.
     *
     * <p>Composed here rather than asked of the strategy, which carries the two parts separately.
     */
    private static String qualified(StrategyDefinition strategy) {
        return strategy.namespace() == null || strategy.namespace().isEmpty()
                ? strategy.id() : strategy.namespace() + ":" + strategy.id();
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

    /**
     * Adds or removes a declared asset kind.
     *
     * <p>The kinds swept are a built-in list, and nothing in the resource API can report what a pack
     * actually contains — asking for everything is an invalid path whose rejection is swallowed, and nothing
     * enumerates a namespace. So the one person who can tell the tool that a pack keeps content somewhere
     * unusual is the person looking at it, and this is where they say so.
     *
     * <p>Refused rather than stored when the name could not be swept: a kind that cannot become a path would
     * be swept, find nothing, and be indistinguishable from a kind with no files in it. Telling the user at
     * the point of declaration is the only moment the mistake is visible.
     */
    private static int declareAssetKind(CommandSourceStack source, String name, boolean add) {
        String kind = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (add && !org.uee.asset.AssetSweep.isValidKind(kind)) {
            source.sendFailure(Component.literal(Uee.NAME + ": '" + name
                    + "' cannot be swept as a kind; it has to be one path segment of lower-case letters,"
                    + " digits, '_', '-' or '.', and must not begin with a dot"));
            return 0;
        }
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            java.util.List<String> kinds = new java.util.ArrayList<>(
                    Uee.declared(config, org.uee.asset.AssetSweep.TABLE));
            boolean changed = add ? addIfAbsent(kinds, kind) : kinds.remove(kind);
            if (!changed) {
                report(source, add ? "already declared: " + kind : "not declared: " + kind);
                return 0;
            }
            Uee.declare(config, org.uee.asset.AssetSweep.TABLE, kinds);
            int total = org.uee.asset.AssetSweep.merge(kinds).size();
            report(source, (add ? "declared asset kind " + kind : "removed asset kind " + kind)
                    + "; the sweep now covers " + total + " kind(s)");
            return 1;
        } catch (java.io.IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": could not write the declaration: " + e));
            return 0;
        }
    }

    /**
     * Adds or removes a declared asset root file.
     *
     * <p>The same shape as the kind verb, and for the same reason: a file at a namespace root has no prefix
     * that reaches it, so the tool cannot find it and a person can. The two differ only in which table they
     * write and what a legal name looks like, so both go through {@link declareInTable}.
     */
    private static int declareAssetRootFile(CommandSourceStack source, String name, boolean add) {
        return declareInTable(source, name, add, org.uee.asset.AssetSweep.ROOT_FILE_TABLE,
                "asset root file", org.uee.asset.AssetSweep::isValidRootFile,
                "it has to be a bare file name of lower-case letters, digits, '_', '-' or '.', with no"
                        + " directory separator, since it is fetched by name and not by prefix");
    }

    /**
     * Writes one declaration table: the shared part of every declare verb.
     *
     * <p>Shared rather than repeated because the parts that matter are the ones easy to get wrong — refusing
     * an unusable name before storing it, not losing the other tables, reporting the new total — and a
     * second copy of them would be a second place for them to drift.
     *
     * @param table which declaration table to write
     * @param what how to name the kind of thing, for the messages
     * @param valid what makes a name usable; an unusable one is refused rather than stored
     * @param why the reason given when a name is refused
     */
    private static int declareInTable(CommandSourceStack source, String name, boolean add,
            String table, String what, java.util.function.Predicate<String> valid, String why) {
        String value = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (add && !valid.test(value)) {
            source.sendFailure(Component.literal(Uee.NAME + ": '" + name + "' cannot be used as a "
                    + what + "; " + why));
            return 0;
        }
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            java.util.List<String> values = new java.util.ArrayList<>(Uee.declared(config, table));
            boolean changed = add ? addIfAbsent(values, value) : values.remove(value);
            if (!changed) {
                report(source, add ? "already declared: " + value : "not declared: " + value);
                return 0;
            }
            Uee.declare(config, table, values);
            report(source, (add ? "declared " + what + " " + value : "removed " + what + " " + value)
                    + "; " + table + " now holds " + values.size());
            return 1;
        } catch (java.io.IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": could not write the declaration: " + e));
            return 0;
        }
    }

    /** Adds a name if it is not there. Sorted insertion, so a table reads in a stable order. */
    private static boolean addIfAbsent(java.util.List<String> names, String name) {
        if (names.contains(name)) {
            return false;
        }
        names.add(name);
        java.util.Collections.sort(names);
        return true;
    }

    /**
     * What has been declared, and where it is kept.
     *
     * <p>Lists the built-in asset kinds as well as the declared ones, because "nine kinds" and "nine plus
     * what you added" are the two numbers that decide whether a sweep will find a pack's content, and a user
     * who has declared something wants to see both.
     */
    private static int declareList(CommandSourceStack source) {
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            java.util.List<String> declared = Uee.declared(config, org.uee.asset.AssetSweep.TABLE);
            report(source, "asset kinds: " + org.uee.asset.AssetSweep.KINDS.size() + " built in, "
                    + declared.size() + " declared");
            for (String kind : declared) {
                report(source, "  + " + kind);
            }
            if (declared.isEmpty()) {
                report(source, "  nothing declared; /uee declare asset-kind <name> adds one");
            }
            java.util.List<String> rootFiles = Uee.declared(config,
                    org.uee.asset.AssetSweep.ROOT_FILE_TABLE);
            report(source, "root files: " + org.uee.asset.AssetSweep.ROOT_FILES.size()
                    + " built in, " + rootFiles.size() + " declared");
            for (String file : rootFiles) {
                report(source, "  + " + file);
            }
            report(source, "the sweep covers "
                    + org.uee.asset.AssetSweep.merge(declared).size() + " kind(s) and "
                    + org.uee.asset.AssetSweep.mergeRootFiles(rootFiles).size() + " root file(s)");
            return declared.size() + rootFiles.size();
        } catch (java.io.IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
    }

    private static int declareWhere(CommandSourceStack source) {
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            Path file = Uee.userStateFile(config);
            report(source, "declarations and settings are kept in " + file);
            report(source, "set 'user_dir' or the " + org.uee.state.UserStore.DIR_ENV
                    + " environment variable to move it");
            return 1;
        } catch (java.io.IOException | IllegalStateException e) {
            source.sendFailure(Component.literal(Uee.NAME + ": " + e.getMessage()));
            return 0;
        }
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
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": reloaded ").append(link(source, file)),
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
