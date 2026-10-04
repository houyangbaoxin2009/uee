package org.uee.quilt;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.QuiltModInitializer;
import org.quiltmc.loader.api.EnvironmentType;
import org.uee.Uee;
import org.uee.mc.UeeCommand;

/**
 * Quilt entry point.
 *
 * <p>Glue only. The lifecycle and command callbacks are Fabric's, which Quilt dispatches for
 * compatibility — so apart from the initializer interface this is the same shape as the Fabric
 * entry point.
 */
public final class UeeQuilt implements QuiltModInitializer {

    private final QuiltAdapter adapter = new QuiltAdapter();

    @Override
    public void onInitialize(ModContainer mod) {
        Uee.bind(adapter);

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            adapter.bindResources(server.getResourceManager());
            adapter.bindRecipes(server.getRecipeManager().getRecipes());
        });

        if (QuiltLoader.getEnvironmentType() == EnvironmentType.CLIENT) {
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
