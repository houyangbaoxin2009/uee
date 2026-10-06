package org.uee.forge;

import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.uee.Uee;
import org.uee.mc.ResourceManagerCapture;

/**
 * The client half of the Forge entry point.
 *
 * <h2>Why this is a class of its own</h2>
 *
 * <p>Forge's {@code @Mod} takes no side filter, so the side has to be decided at runtime — and a runtime
 * decision is exactly where a dedicated server acquires a crash, because naming a client-only class from a
 * class the server loads can fail before any condition is evaluated. Putting the client code in its own
 * class and only calling into it behind a side check is the arrangement that avoids it: the class is
 * resolved when it is first used, and on a server it never is.
 *
 * <p>NeoForge does the same thing with an annotation, which is why that side has no guard: a filter the
 * loader enforces cannot be forgotten.
 */
public final class UeeForgeClient {

    private UeeForgeClient() {
    }

    /** Registers the capture. Called only on a client. */
    static void init(IEventBus modBus) {
        modBus.addListener(UeeForgeClient::onRegisterClientReloadListeners);
    }

    private static void onRegisterClientReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new ResourceManagerCapture(
                manager -> ((ForgeAdapter) Uee.adapter()).bindClientResources(manager)));
    }
}
