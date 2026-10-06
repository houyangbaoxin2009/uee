package org.uee.mc;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Catches the client's resource manager as the game hands it round.
 *
 * <h2>Why a reload listener, of all things</h2>
 *
 * <p>The client's resource manager is the only object that can see {@code assets/}, and there is no way to
 * ask for it. The event a loader offers when client resources are being prepared — NeoForge's and Forge's
 * {@code RegisterClientReloadListenersEvent} — hands out a loader to register with and nothing else: its
 * only method is {@code registerReloadListener}. The manager itself arrives later, as the second parameter
 * of a listener's {@code reload}. So a listener is not a workaround here, it is the only door: to be given
 * the manager, be something the manager is given to.
 *
 * <h2>What it does, which is nothing</h2>
 *
 * <p>It captures and returns. No work, no preparation phase, no apply phase — so it completes immediately
 * rather than waiting on the preparation barrier, which exists for listeners that do asynchronous work and
 * would only add a round trip to a listener that does none.
 *
 * <p>It is deliberately dumb: the alternative would be to collect assets from inside the reload, which
 * would put an export in the middle of the game's resource reload, where the registries are frozen
 * differently and the phase boundary the design draws would be broken. Capturing an object for later is
 * not the same act as using it, and only one of them belongs here.
 *
 * <h2>It is reusable, and it is also a test fixture</h2>
 *
 * <p>It takes a consumer rather than an adapter, so the same class serves a loader registering it and a
 * test calling it directly. That second use is the one that matters for confidence: the client path is
 * otherwise unreachable without a client, and a test can drive this listener with a manager of its own
 * construction to exercise the whole path — binding, then collecting — with no game window anywhere.
 */
public final class ResourceManagerCapture implements PreparableReloadListener {

    private final Consumer<ResourceManager> onManager;

    /**
     * @param onManager what to do with the manager; called each time resources reload, since the object
     *     differs between reloads and a stale one would read a pack set that no longer exists
     */
    public ResourceManagerCapture(Consumer<ResourceManager> onManager) {
        this.onManager = onManager;
    }

    @Override
    public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
            ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
            java.util.concurrent.Executor backgroundExecutor, java.util.concurrent.Executor gameExecutor) {
        onManager.accept(manager);
        // Completed rather than barrier-awaited: this listener has nothing to prepare, so there is nothing
        // for the barrier to order. A listener that did have something would wait, and would need the
        // barrier to keep its two phases apart.
        return CompletableFuture.completedFuture(null);
    }

    /**
     * A name that says what this is in a reload log, since it appears in one.
     */
    @Override
    public String getName() {
        return "uee: client resource manager capture";
    }
}
