package org.uee.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.uee.Uee;
import org.uee.config.ExportConfig;
import org.uee.config.WikiOptions;
import org.uee.pipeline.ExportReport;
import org.uee.write.WriterFactory;

/**
 * The {@code /uee} command tree.
 *
 * <p>Shared across loaders because Brigadier and the command source are game classes, and the only
 * loader-specific part is how the dispatcher is obtained — which each loader's entry point does in
 * one line. Keeping the tree here means the four loaders cannot drift in command behaviour.
 */
public final class UeeCommand {

    private UeeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build("uee"));
    }

    /** Also registered under a short alias so the command is quick to type in game. */
    public static void registerWithAlias(CommandDispatcher<CommandSourceStack> dispatcher, String alias) {
        dispatcher.register(build("uee"));
        dispatcher.register(build(alias));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("export")
                        .executes(ctx -> export(ctx.getSource(), ExportConfig.builder()
                                .formats(ExportConfig.NDJSON, WriterFactory.WIKI)
                                .wiki(WikiOptions.builder().enabled(true).build())
                                .build(), "wikimode")))
                .then(Commands.literal("export-all")
                        .executes(ctx -> export(ctx.getSource(), ExportConfig.builder()
                                .formats(ExportConfig.NDJSON, ExportConfig.JSON, ExportConfig.TD,
                                        ExportConfig.ZD, ExportConfig.YAML, ExportConfig.TOML,
                                        ExportConfig.XML, WriterFactory.WIKI)
                                .wiki(WikiOptions.builder().enabled(true).build())
                                .build(), "all formats")))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource())));
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
        return 1;
    }

    private static int export(CommandSourceStack source, ExportConfig config, String what) {
        var adapter = Uee.adapter();
        if (adapter == null) {
            source.sendFailure(Component.literal(Uee.NAME + ": no loader adapter bound"));
            return 0;
        }
        Path root = Path.of(adapter.info().gameDirectory(), "exports", Uee.MOD_ID);
        try {
            ExportReport report = Uee.export(config, root);
            source.sendSuccess(() -> Component.literal(Uee.NAME + ": exported " + report.summary()
                    + " -> " + root), false);
            if (!report.successful()) {
                source.sendSuccess(() -> Component.literal(
                        Uee.NAME + ": " + report.failures().size()
                                + " element(s) failed; see the failure list in the output directory"),
                        false);
            }
            return report.artifacts().size();
        } catch (Throwable t) {
            source.sendFailure(Component.literal(Uee.NAME + " export failed: " + t));
            return 0;
        }
    }
}
