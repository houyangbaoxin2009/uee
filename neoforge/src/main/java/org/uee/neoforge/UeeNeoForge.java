package org.uee.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.uee.Uee;
import org.uee.mc.UeeCommand;

/**
 * NeoForge entry point.
 *
 * <p>Glue only: bind the adapter, capture resources and recipes on the server-started event, and
 * register the shared command tree.
 */
@Mod(Uee.MOD_ID)
public final class UeeNeoForge {

    private final NeoForgeAdapter adapter = new NeoForgeAdapter();

    public UeeNeoForge(IEventBus modBus) {
        Uee.bind(adapter);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter.bindResources(event.getServer().getResourceManager());
        adapter.bindRecipes(event.getServer().getRecipeManager().getRecipes());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        UeeCommand.register(event.getDispatcher());
    }
}
