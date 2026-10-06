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
            // Data-loaded registries -- enchantments, biomes, damage types -- only exist while a server
            // does, so this is the moment they become collectable.
            adapter.bindRegistryAccess(server.registryAccess());
        // Once everything is bound, the one place an unprompted export could happen. Off unless the
        // configuration asks for it, and submitted as a job when it is, so startup is not held up.
        org.uee.Uee.maybeAutoRun();
        });

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                // The client's manager, kept apart from the server's: it is the only one that sees
                // assets, and it cannot see data. Binding it over the server's would trade one side's
                // reach for the other's.
                adapter.bindClientResources(client.getResourceManager());
                // The client's own registry access: an integrated server holds the data-loaded
                // registries, and a client connected to a server has its synced copy.
                adapter.bindRegistryAccess(client.level == null ? null
                        : client.level.registryAccess());
                if (client.getConnection() != null) {
                    adapter.bindRecipes(client.getConnection().getRecipeManager().getRecipes());
                }
            });
        }

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> UeeCommand.register(dispatcher));
    }
}
