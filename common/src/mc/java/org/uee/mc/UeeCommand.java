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
import org.uee.model.ElementKind;
import org.uee.pipeline.ExportReport;

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
                .executes(ctx -> runDefault(ctx.getSource(), null, null))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource(), name)))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("kinds").executes(ctx -> listKinds(ctx.getSource())))
                .then(Commands.literal("listformats").executes(ctx -> listFormats(ctx.getSource())))

                .then(Commands.literal("export")
                        .executes(ctx -> runDefault(ctx.getSource(), null, null))
                        .then(Commands.argument("kinds", StringArgumentType.greedyString())
                                .executes(ctx -> runDefault(ctx.getSource(),
                                        arg(ctx, "kinds"), null))))

                .then(Commands.literal("formats")
                        .executes(ctx -> listFormats(ctx.getSource()))
                        .then(Commands.argument("formats", StringArgumentType.greedyString())
                                .executes(ctx -> runDefault(ctx.getSource(), null,
                                        arg(ctx, "formats")))))

                // Analyse without exporting: answers "what is wrong with this instance" cheaply.
                .then(Commands.literal("analyze").executes(ctx -> analyze(ctx.getSource())))

                // The two halves of a run, each on its own.
                .then(Commands.literal("data").executes(ctx -> runDefault(ctx.getSource(),
                        Tokens.DATA, null)))
                .then(Commands.literal("analysis").executes(ctx -> runDefault(ctx.getSource(),
                        Tokens.ANALYSIS, null)))

                .then(Commands.literal("config")
                        .executes(ctx -> configShow(ctx.getSource()))
                        .then(Commands.literal("show").executes(ctx -> configShow(ctx.getSource())))
                        .then(Commands.literal("path").executes(ctx -> configPath(ctx.getSource())))
                        .then(Commands.literal("save").executes(ctx -> configSave(ctx.getSource())))
                        .then(Commands.literal("template")
                                .executes(ctx -> configTemplate(ctx.getSource())))
                        .then(Commands.literal("reload").executes(ctx -> configReload(ctx.getSource()))));
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

        ConfigResolver.Resolved resolved;
        try {
            resolved = Uee.resolveForRun(overrides);
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
        Path root = config.outputDir();
        source.sendSuccess(() -> Component.literal(Uee.NAME + ": exporting "
                + config.kinds().size() + " category/categories as "
                + String.join(", ", config.formats())), false);

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
            showLocations(source, root, config);
            return report.artifacts().size();
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + " export failed: " + t));
            return 0;
        }
    }

    /** Emits clickable paths, so the output can be opened without retyping them. */
    private static void showLocations(CommandSourceStack source, Path root, ExportConfig config) {
        source.sendSuccess(() -> Component.literal("  data:     ").append(link(root)), false);
        if (config.analyze() && config.analysisSeparate()) {
            Path analysis = sibling(root, "-analysis");
            source.sendSuccess(() -> Component.literal("  analysis: ").append(link(analysis)), false);
        }
    }

    /** Where the analysis lands when it is kept apart, matching the pipeline's own rule. */
    static Path sibling(Path root, String suffix) {
        Path name = root.getFileName();
        return name == null ? root : root.resolveSibling(name + suffix);
    }

    private static Component link(Path path) {
        String text = path.toAbsolutePath().toString();
        return Component.literal(text)
                .withStyle(style -> style
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
            source.sendSuccess(() -> Component.literal("  config:   ").append(link(file)), false);
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
                source.sendSuccess(() -> Component.literal("  config:  ").append(link(file)), false);
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
        source.sendSuccess(() -> Component.literal("config file: ").append(link(file)), false);
        return 1;
    }

    private static int configSave(CommandSourceStack source) {
        try {
            ExportConfig config = Uee.resolveForRun(ConfigFile.empty()).config();
            Path file = Uee.writeConfig(config);
            source.sendSuccess(() -> Component.literal(Uee.NAME
                    + ": wrote the effective configuration to ").append(link(file)), false);
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
                    + ": wrote the annotated default configuration to ").append(link(file)), false);
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
                "  " + p + " kinds | formats       list the accepted tokens"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " status                loader, version, effective defaults"), false);
        source.sendSuccess(() -> Component.literal(
                "  " + p + " config show|path|save|template|reload"), false);
        source.sendSuccess(() -> Component.literal(
                "  categories: " + Tokens.groups() + ", or any name from " + p + " kinds"), false);
        return 1;
    }
}
