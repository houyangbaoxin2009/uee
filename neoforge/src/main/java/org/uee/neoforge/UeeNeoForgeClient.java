package org.uee.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.uee.Uee;
import org.uee.mc.ResourceManagerCapture;

/**
 * The client half of the NeoForge entry point.
 *
 * <h2>Why a second mod class rather than a branch</h2>
 *
 * <p>Assets can only be read from a client's resource manager, and the only way to be handed one is to be
 * a reload listener — see {@link ResourceManagerCapture} for why that is the sole door. Registering one
 * means naming a client-only event class, and naming it from a class that a dedicated server also loads is
 * how a mod acquires a crash that only happens on servers.
 *
 * <p>NeoForge lets a mod have more than one entry point and filter each by side, so the whole class is
 * declared client-only and the side check is the loader's rather than a condition of ours. That is better
 * than a runtime branch for the same reason it always is: the branch is a decision someone has to remember
 * to make, and the annotation is one the loader enforces.
 */
@Mod(value = Uee.MOD_ID, dist = Dist.CLIENT)
public final class UeeNeoForgeClient {

    public UeeNeoForgeClient(IEventBus modBus) {
        modBus.addListener(this::onRegisterClientReloadListeners);
    }

    /**
     * Registers the capture.
     *
     * <p>On the mod bus rather than the game bus: this is a lifecycle event of the mod's own loading, which
     * is what the mod bus carries. The capture itself does nothing with the manager beyond remembering it,
     * so nothing here touches the game's resource reload while it is in progress.
     */
    private void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ResourceManagerCapture(
                manager -> ((NeoForgeAdapter) Uee.adapter()).bindClientResources(manager)));
    }
}
