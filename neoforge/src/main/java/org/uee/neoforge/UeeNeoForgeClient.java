package org.uee.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.uee.Uee;
import org.uee.mc.ClientIcons;

/**
 * NeoForge's client-side wiring for the icon phase.
 *
 * <p>Annotated for the client only, so the class is never loaded on a dedicated server — which matters,
 * because it reaches code that drives the render pipeline and that code does not exist there.
 */
@EventBusSubscriber(modid = "uee", value = Dist.CLIENT)
public final class UeeNeoForgeClient {

    private UeeNeoForgeClient() {
    }

    /**
     * Advances the icon phase, one batch per tick.
     *
     * <p>After the tick rather than before: rendering a batch changes render state, and doing it at the
     * end of a tick means the state it leaves is the state the next frame starts with, rather than
     * something the rest of tick handling has to cope with mid-way.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientIcons.tickIfRunning(Uee.adapter());
    }
}
