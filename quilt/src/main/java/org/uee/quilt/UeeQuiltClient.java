package org.uee.quilt;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.uee.Uee;
import org.uee.mc.ClientIcons;

/**
 * Quilt's client entry point.
 *
 * <p>Implements Fabric's client initialiser, as Quilt's own loader does not provide one in this version:
 * it runs Fabric mods natively and ships Fabric's entry points as the interfaces to implement. The
 * declaration in {@code quilt.mod.json} is what keeps this out of a dedicated server.
 */
public final class UeeQuiltClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientIcons.tickIfRunning(Uee.adapter()));
    }
}
