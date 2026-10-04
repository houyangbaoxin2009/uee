package org.uee.fabric;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.uee.Uee;
import org.uee.mc.UeeCommand;

/**
 * Fabric entry point.
 *
 * <p>Three responsibilities, all of them loader glue: bind the adapter, hand it the resource manager
 * and recipe list once they exist, and register the command tree. The export itself is loader-neutral
 * and lives in the core.
 *
 * <p>Resources and recipes are captured on the lifecycle events rather than at init, because neither
 * exists yet during mod initialisation, and because capturing them late means an export run from a
 * loaded world sees the actual content of that world.
 */
public final class UeeFabric implements ModInitializer {

    private final FabricAdapter adapter = new FabricAdapter();

    @Override
    public void onInitialize() {
        Uee.bind(adapter);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            adapter.bindResources(server.getResourceManager());
            adapter.bindRecipes(server.getRecipeManager().getRecipes());
        });

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                adapter.bindResources(client.getResourceManager());
                if (client.getConnection() != null) {
                    adapter.bindRecipes(client.getConnection().getRecipeManager().getRecipes());
                }
            });
        }

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> UeeCommand.register(dispatcher));
    }
}
