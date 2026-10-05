package org.uee.neoforge;

import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.FolderRepositorySource;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.validation.DirectoryValidator;
import org.uee.Uee;
import org.uee.analysis.Finding;
import org.uee.config.ExportConfig;
import org.uee.mc.GlobalPackRegistration;

/**
 * Registers UEE's global datapacks with NeoForge.
 *
 * <p><b>Verified by compilation</b> against NeoForge 21.1.249. Every API used here was read off the
 * compiled classes rather than assumed — which is how it emerged that {@code addPackFinders} could not
 * express a directory outside the mod's own resources, and that the vanilla folder scanner is the type
 * for the job. The shared decision lives in {@link GlobalPackRegistration}; this is only the part that
 * has to be loader-specific.
 *
 * <p>The decision can go the other way: when another mod already provides global datapacks, UEE
 * registers nothing and reports why. That is the whole point of routing this through the shared policy
 * rather than adding packs unconditionally — two mods registering the same packs would load them
 * twice.
 */
@EventBusSubscriber(modid = Uee.MOD_ID)
public final class NeoForgeGlobalPacks {

    /** The mod's logger, so a policy note lands in the log rather than on stderr. */
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private NeoForgeGlobalPacks() {
    }

    @SubscribeEvent
    public static void onAddPackFinders(AddPackFindersEvent event) {
        // Data packs only. A global resource pack would change what the game looks like, which is not
        // what this feature is for and not something a user would expect UEE to do.
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        ExportConfig config = GlobalPackRegistration.resolveForHook();
        GlobalPackRegistration.Decision decision = GlobalPackRegistration.decide(config);
        for (Finding finding : decision.findings()) {
            report(finding);
        }
        if (!decision.shouldRegister()) {
            // Either another mod provides global datapacks, or there is nothing to load. Both are
            // normal, and both were already explained by the findings above.
            return;
        }
        Path directory = decision.directory();
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try {
            registerDirectory(event, directory);
        } catch (Throwable t) {
            // Registration failing must not take the game down; the run continues without the packs.
            Uee.reportFailure("global datapacks in " + directory + " could not be registered", t);
        }
    }

    /**
     * Adds one directory of packs to the event.
     *
     * <h2>Why not {@code addPackFinders}</h2>
     *
     * <p>Despite the name, {@code AddPackFindersEvent.addPackFinders} does not add a finder for an
     * arbitrary location: it takes a {@code ResourceLocation} that names a pack <em>inside the calling
     * mod's own resources</em>. Global datapacks live in the game's config directory and belong to no
     * mod, so that method cannot express them — this was found by reading the compiled signature
     * rather than by assuming the name meant what it sounded like.
     *
     * <p>{@code FolderRepositorySource} is the vanilla type that scans a directory and produces packs
     * from it, which is exactly the operation wanted, and reusing it means the pack discovery, the
     * metadata reading and the validation all behave the way a world's own {@code datapacks/} folder
     * does.
     */
    private static void registerDirectory(AddPackFindersEvent event, Path directory) {
        event.addRepositorySource(new FolderRepositorySource(
                directory,
                PackType.SERVER_DATA,
                PackSource.BUILT_IN,
                // The validator exists to reject symlinks that escape a world's own directory. A
                // config directory is the user's own, so nothing is forbidden here.
                new DirectoryValidator(path -> false)));
    }

    /** Sends a policy finding somewhere a user will see it. */
    private static void report(Finding finding) {
        if (finding.isError()) {
            Uee.reportFailure(finding.message(), null);
        } else {
            // Not an error: informational findings still need to reach the log, or a deferral would
            // be invisible, which is the whole failure mode the policy exists to avoid.
            LOGGER.info("[{}] {}", Uee.NAME, finding.message());
        }
    }

}
