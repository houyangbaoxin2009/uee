package org.uee.mc;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import org.uee.Ueea;
import org.uee.analysis.Analysis;
import org.uee.analysis.Finding;

/**
 * The command this mod answers to.
 *
 * <h2>Only its own</h2>
 *
 * <p>Everything here belongs to the analysis: what it checks, what it found, and where it read from. The
 * exporter's command keeps what belongs to exporting, and neither registers anything of the other's --
 * which is the point of the two being two, rather than one command with a flag that changes what it means.
 */
public final class UeeaCommand {

    /** Permission level that may run this. */
    private static final int PERMISSION = 2;

    /** How many findings are printed before the rest are summarised. */
    private static final int FINDINGS_SHOWN = 20;

    private UeeaCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Ueea.MOD_ID)
                .requires(source -> source.hasPermission(PERMISSION))
                // Bare: what this is, which is the question a reader has the first time.
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("help").executes(ctx -> help(ctx.getSource())))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("checks").executes(ctx -> listChecks(ctx.getSource())))
                .then(Commands.literal("analyze").executes(ctx -> analyze(ctx.getSource(), List.of())))
                .then(Commands.literal("check")
                        .then(Commands.argument("names", StringArgumentType.greedyString())
                                .executes(ctx -> analyze(ctx.getSource(),
                                        names(StringArgumentType.getString(ctx, "names")))))));
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(UeeaUi::headline, false);
        source.sendSuccess(() -> Component.literal(UeeaUi.get("status.identity")), false);
        var adapter = Ueea.adapter();
        if (adapter == null || adapter.info() == null) {
            source.sendSuccess(() -> Component.literal(UeeaUi.get("status.noGame")), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(UeeaUi.get("status.loader",
                adapter.info().loader() + " " + adapter.info().loaderVersion(),
                adapter.info().minecraftVersion())), false);
        source.sendSuccess(() -> Component.literal(UeeaUi.get("status.checks",
                Integer.toString(Ueea.checkIds().size()))), false);
        source.sendSuccess(() -> Component.literal(UeeaUi.get("status.exporter",
                UeeaUi.get(Ueea.exporterPresent() ? "value.present" : "value.absent"))), false);
        var file = Ueea.configFile();
        source.sendSuccess(() -> Component.literal(UeeaUi.get("status.config",
                file == null ? UeeaUi.get("value.nowhere") : file.toAbsolutePath().toString())), false);
        return Ueea.checkIds().size();
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(UeeaUi::headline, false);
        source.sendSuccess(() -> Component.literal(UeeaUi.get("help.line",
                "/" + Ueea.MOD_ID)), false);
        return 1;
    }

    private static int listChecks(CommandSourceStack source) {
        for (Analysis check : Ueea.checks()) {
            source.sendSuccess(() -> Component.literal(UeeaUi.get("checks.line",
                    check.id(), check.description())), false);
        }
        return Ueea.checkIds().size();
    }

    /**
     * Runs the named checks, or every one of them.
     *
     * <p>An unknown name is refused with the list of real ones. A selection that quietly ran nothing, or
     * ran everything, looks exactly like a clean result, which is the wrong thing to leave a reader with.
     */
    private static int analyze(CommandSourceStack source, List<String> wanted) {
        if (Ueea.adapter() == null) {
            source.sendFailure(Component.literal(UeeaUi.get("err.noGame")));
            return 0;
        }
        List<String> known = Ueea.checkIds();
        for (String name : wanted) {
            if (!known.contains(name)) {
                source.sendFailure(Component.literal(UeeaUi.get("err.unknownCheck", name,
                        String.join(", ", known))));
                return 0;
            }
        }
        List<Finding> findings = new ArrayList<>(16);
        try {
            Ueea.run(findings::add, wanted);
        } catch (Throwable t) {
            source.sendFailure(Component.literal(UeeaUi.get("err.failed", String.valueOf(t))));
            return 0;
        }
        if (findings.isEmpty()) {
            source.sendSuccess(() -> Component.literal(UeeaUi.get("finding.none")), false);
            return 0;
        }
        long errors = findings.stream().filter(Finding::isError).count();
        source.sendSuccess(() -> Component.literal(UeeaUi.get("finding.count",
                Integer.toString(findings.size()), Long.toString(errors))), false);
        Ueea.ordered(findings).stream().limit(FINDINGS_SHOWN).forEach(f -> source.sendSuccess(
                () -> Component.literal(UeeaUi.get("finding.entry", f.severity().token(), f.message())),
                false));
        if (findings.size() > FINDINGS_SHOWN) {
            source.sendSuccess(() -> Component.literal(UeeaUi.get("finding.more",
                    Integer.toString(findings.size() - FINDINGS_SHOWN))), false);
        }
        // The error count, so a function storing the result gets a number it can test rather than one that
        // fails on an observation.
        return (int) errors;
    }

    /** Splits a name list on commas and spaces. */
    private static List<String> names(String text) {
        List<String> out = new ArrayList<>(4);
        for (String token : text.split("[,\\s]+")) {
            String name = token.trim().toLowerCase(java.util.Locale.ROOT);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }
}
