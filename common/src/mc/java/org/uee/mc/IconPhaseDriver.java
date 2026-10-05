package org.uee.mc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.uee.config.ExportConfig;
import org.uee.icon.IconPhase;
import org.uee.icon.IconStore;
import org.uee.model.ElementKind;
import org.uee.spi.LoaderAdapter;

/**
 * Runs the icon phase from the client's frame loop.
 *
 * <h2>What it is for</h2>
 *
 * <p>The phase itself does a bounded amount of work per call and knows nothing about frames; this is what
 * calls it, once per client tick, and stops when the work is done. That split is the same one the rest of
 * the project uses — the part that needs a game is a thin shell around a part that does not — and it is
 * what lets the batching, the budget and the resuming be tested without one.
 *
 * <h2>One job at a time</h2>
 *
 * <p>A second start while one is running is refused rather than queued. Two phases would render the same
 * items into the same files, and the two render passes would be fighting over the same global render
 * state — so the honest answer is that the caller has to wait, and {@link #isRunning()} is how it finds
 * out.
 *
 * <h2>The budget, and why it is a budget rather than a thread count</h2>
 *
 * <p>Rendering cannot be spread across threads at all: it drives a global state machine that belongs to
 * the render thread. What can be spread is <em>when</em> it happens, which is what the per-tick budget
 * does — a client that renders a few icons per frame stays responsive while the phase runs, and the
 * number is configurable because the right figure depends on the machine.
 */
public final class IconPhaseDriver {

    /**
     * Icons rendered per tick at the default.
     *
     * <p>Chosen to be noticeable in total and invisible per frame: at twenty ticks a second this finishes
     * a thousand icons in about a second of wall time, spread across fifty frames, none of which stalls.
     */
    public static final int DEFAULT_BUDGET = 4;

    private static IconPhaseDriver active;

    private final IconPhase phase;
    private final IconStore store;
    private final LoaderAdapter adapter;
    private final int budget;
    private boolean bound;

    private IconPhaseDriver(IconPhase phase, IconStore store, LoaderAdapter adapter, int budget) {
        this.phase = phase;
        this.store = store;
        this.adapter = adapter;
        this.budget = budget;
    }

    /** Whether a job is in progress, so a second request can be refused rather than allowed to collide. */
    public static synchronized boolean isRunning() {
        return active != null && !active.phase.isComplete();
    }

    /** A line describing the running job, or null when there is none. */
    public static synchronized String progress() {
        return active == null ? null : active.phase.progressLine();
    }

    /** The job in progress, or null when there is none. */
    public static synchronized IconPhaseDriver current() {
        return active == null || active.phase.isComplete() ? null : active;
    }

    /** The icons rendered by the running or last job, for reporting where they went. */
    public static synchronized Path iconsDirectory() {
        return active == null ? null : active.store.root();
    }

    /**
     * Starts a job, or returns null when one is already running.
     *
     * <p>The work list is read from the live registries on the calling thread, which is the client thread:
     * enumerating registries and filtering by namespace is the same work the data phase does, and it is
     * cheap next to the rendering that follows.
     *
     * @return the running job, or {@code null} when icons are disabled or a job is already in progress
     */
    public static synchronized IconPhaseDriver start(LoaderAdapter adapter, ExportConfig config,
            Path iconsRoot, int budget) {
        if (!config.icons() || isRunning()) {
            return null;
        }
        IconStore store = new IconStore(iconsRoot, sizesFor());
        Map<String, List<String>> candidates = workList(config);
        IconRenderer renderer = new IconRenderer();
        IconPhase phase = new IconPhase(store, (kind, id, sizes) -> {
            ResourceLocation location = ResourceLocation.parse(id);
            // Entity icons are the ones that need a world, and the renderer returns nothing when there
            // is none; the phase records those as skipped, which is the honest report.
            return ElementKind.ENTITY.singular().equals(kind)
                    ? renderer.renderEntity(location, sizes)
                    : renderer.renderItem(location, sizes);
        }, candidates);
        active = new IconPhaseDriver(phase, store, adapter, budget);
        return active;
    }

    /**
     * The sizes each kind is rendered at.
     *
     * <p>Items get a large and a small because a wiki shows both — one in the infobox, one inline. Entities
     * get only the large because there is nowhere a small one would be used, and rendering a size nobody
     * reads costs a render pass per mob.
     */
    private static Map<String, int[]> sizesFor() {
        Map<String, int[]> sizes = new LinkedHashMap<>(2);
        sizes.put(ElementKind.ITEM.singular(),
                new int[] {AbstractMinecraftAdapter.ICON_LARGE, AbstractMinecraftAdapter.ICON_SMALL});
        sizes.put(ElementKind.ENTITY.singular(), new int[] {AbstractMinecraftAdapter.ICON_LARGE});
        return sizes;
    }

    /**
     * Everything that could need an icon, in the order the data phase will collect it.
     *
     * <p>Sorted, for the reason the collection passes are sorted: the phase's own order comes from this
     * list, and an order that depended on a hash map would make a resumed run's partial output differ
     * from a complete one for no reason.
     */
    private static Map<String, List<String>> workList(ExportConfig config) {
        Map<String, List<String>> out = new LinkedHashMap<>(2);
        out.put(ElementKind.ITEM.singular(), idsOf(BuiltInRegistries.ITEM.keySet(), config));
        out.put(ElementKind.ENTITY.singular(), idsOf(BuiltInRegistries.ENTITY_TYPE.keySet(), config));
        return out;
    }

    private static List<String> idsOf(Collection<ResourceLocation> ids, ExportConfig config) {
        List<String> out = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            if (config.acceptsNamespace(id.getNamespace())) {
                out.add(id.toString());
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * One frame's worth of work.
     *
     * <p>Called from the client tick. When the phase finishes, the store is bound to the adapter so that
     * the data phase reads icons from it — the binding is what joins the two phases, and it happens here
     * because this is the only place that knows the rendering is done.
     */
    public void tick() {
        if (phase.isComplete()) {
            bind();
            return;
        }
        try {
            phase.advance(budget);
        } catch (IOException e) {
            // A store that cannot be written to is not something the frame loop can fix, and taking the
            // client down over it would be worse than finishing with fewer icons.
            org.uee.Uee.reportFailure("icons could not be written to " + store.root(), e);
        }
        if (phase.isComplete()) {
            bind();
        }
    }

    /** Binds the finished store to the adapter, once. */
    private void bind() {
        if (!bound) {
            bound = true;
            if (adapter instanceof AbstractMinecraftAdapter minecraft) {
                minecraft.bindIcons(store);
            }
        }
    }

    /** The phase, for a caller that wants to report on it. */
    public IconPhase phase() {
        return phase;
    }

    /** Stops the job, leaving what has been rendered in place so a later run resumes from it. */
    public static synchronized void cancel() {
        active = null;
    }
}
