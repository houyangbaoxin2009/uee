package org.uee.neoforge;

import java.nio.file.Path;
import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import org.uee.Uee;
import org.uee.config.ExportConfig;
import org.uee.mc.GlobalPackRegistration;

/**
 * Registers UEE's global datapacks with NeoForge.
 *
 * <p><b>Compile-unverified.</b> Written against the stable parts of NeoForge's pack API and not yet
 * through a compiler. The shared decision lives in {@link GlobalPackRegistration}; this is only the
 * part that has to be loader-specific, which is the single call that adds a pack finder.
 *
 * <p>The decision can go the other way: when another mod already provides global datapacks, UEE
 * registers nothing and reports why. That is the whole point of routing this through the shared policy
 * rather than adding packs unconditionally — two mods registering the same packs would load them
 * twice.
 */
@EventBusSubscriber(modid = Uee.MOD_ID)
public final class NeoForgeGlobalPacks {

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
        if (!config.globalDatapacks().equalsIgnoreCase("auto")
                && !config.globalDatapacks().equalsIgnoreCase("on")
                && !config.globalDatapacks().equalsIgnoreCase("off")) {
            // An unreadable setting; the shared policy already degrades to auto.
            config = config.toBuilder().globalDatapacks("auto").build();
        }
        List<Path> packs = GlobalPackRegistration.packsFor(config);
        for (Path pack : packs) {
            try {
                register(event, pack);
            } catch (Throwable t) {
                // One pack that will not register must not stop the others, for the same reason one
                // bad element does not stop an export.
                org.uee.Uee.reportFailure("global datapack '" + pack + "' could not be registered", t);
            }
        }
    }

    /**
     * Adds one pack to the event.
     *
     * <p>Kept separate so the loader-specific call is in one place. The exact signature is the part
     * most likely to need adjusting against a real toolchain.
     */
    private static void register(AddPackFindersEvent event, Path pack) {
        event.addPackFinders(
                pack.toUri(),
                PackType.SERVER_DATA,
                "uee_global_" + pack.getFileName().toString().replaceAll("[^A-Za-z0-9_]", "_"),
                PackSource.BUILT_IN,
                false,
                net.minecraft.server.packs.repository.Pack.Position.TOP);
    }
}
