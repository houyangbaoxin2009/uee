package org.uee.forge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.uee.Uee;
import org.uee.mc.ClientIcons;

/**
 * Forge's client-side wiring for the icon phase.
 *
 * <p>Annotated for the client only, so the class is never loaded on a dedicated server — which matters,
 * because it reaches code that drives the render pipeline and that code does not exist there.
 *
 * <p>Uses the phase-carrying tick event rather than a client-specific one, and checks the phase, so the
 * work happens once at the end of a tick rather than twice.
 */
@Mod.EventBusSubscriber(modid = "uee", value = Dist.CLIENT)
public final class UeeForgeClient {

    private UeeForgeClient() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClientIcons.tickIfRunning(Uee.adapter());
    }
}
