package org.uee.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import org.uee.Ueea;
import org.uee.mc.UeeaCommand;

/**
 * The NeoForge entry point for the analysis half.
 *
 * <p>Glue only, and the same shape as the exporter's: bind an adapter, answer the command event. What it does
 * not do is the point -- no collection, no auto-run, no icon phase. Those belong to the mod that writes
 * things out.
 */
@Mod(value = Ueea.MOD_ID)
public final class UeeaNeoForge {

    public UeeaNeoForge(IEventBus modBus) {
        Ueea.bind(new NeoForgeAdapter());
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        UeeaCommand.register(event.getDispatcher());
    }
}
