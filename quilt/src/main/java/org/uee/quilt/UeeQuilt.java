package org.uee.quilt;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.uee.Uee;
import org.uee.mc.UeeCommand;

/**
 * Quilt entry point.
 *
 * <p>Glue only, and deliberately identical in shape to the Fabric entry point: Quilt runs Fabric mods
 * natively, so its loader ships Fabric's {@code ModInitializer} and {@code FabricLoader} as the
 * interfaces to implement and ask. Reaching for a Quilt-named equivalent would mean a type that does
 * not exist — Quilt has no {@code QuiltModInitializer} in the version this builds against.
 */
public final class UeeQuilt implements ModInitializer {

    private final QuiltAdapter adapter = new QuiltAdapter();

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
