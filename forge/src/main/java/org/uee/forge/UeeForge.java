package org.uee.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;
import org.uee.Uee;
import org.uee.mc.UeeCommand;

/**
 * Forge entry point.
 *
 * <p>Glue only, and identical in shape to the other loaders': bind, capture the handles that only
 * exist once a game is running, register the shared command tree.
 */
@Mod(Uee.MOD_ID)
public final class UeeForge {

    private final ForgeAdapter adapter = new ForgeAdapter();

    public UeeForge() {
        Uee.bind(adapter);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        adapter.bindResources(event.getServer().getResourceManager());
        adapter.bindRegistryAccess(event.getServer().registryAccess());
        adapter.bindRecipes(event.getServer().getRecipeManager().getRecipes());
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        UeeCommand.register(event.getDispatcher());
    }
}
