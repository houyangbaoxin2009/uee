package org.uee.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

import org.uee.Ueea;
import org.uee.mc.UeeaCommand;

/**
 * The Forge entry point for the analysis half.
 *
 * <p>The same glue as the NeoForge side, written the way this loader expects it. Nothing here collects, so
 * there is no server-started hook to install.
 */
@Mod(Ueea.MOD_ID)
public final class UeeaForge {

    public UeeaForge() {
        Ueea.bind(new ForgeAdapter());
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        UeeaCommand.register(event.getDispatcher());
    }
}
