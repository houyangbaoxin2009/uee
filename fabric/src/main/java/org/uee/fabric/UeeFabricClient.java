package org.uee.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.uee.Uee;
import org.uee.mc.ClientIcons;

/**
 * Fabric's client entry point.
 *
 * <p>Exists only to give the icon phase somewhere to be driven from: rendering needs the client, so a
 * dedicated-server install must never load this class. Fabric guarantees that by only loading entry
 * points declared under {@code client}, which is why this is a separate class from
 * {@link UeeFabric} rather than a branch inside it — a branch would still be one class, and the class
 * would still be loaded.
 */
public final class UeeFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // The tick handler is where the phase makes progress; it does nothing at all unless a job has
        // been started, which happens on request rather than at startup.
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientIcons.tickIfRunning(Uee.adapter()));
    }
}
