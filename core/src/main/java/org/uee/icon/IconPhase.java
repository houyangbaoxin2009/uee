package org.uee.icon;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Renders icons in bounded batches, across as many frames as it takes.
 *
 * <h2>Why it cannot just render everything</h2>
 *
 * <p>Rendering has to happen on the client's render thread, inside the game loop, and a large pack has
 * tens of thousands of icons. Doing them in one go would freeze the client for the duration — which is
 * precisely the behaviour of the tools this exists to improve on, and the reason the design keeps
 * rendering out of the data phase entirely. So the phase does a bounded amount of work per call and hands
 * control back, and whoever owns the frame loop calls it again next frame.
 *
 * <h2>What "resumable" costs, and why it is nearly zero</h2>
 *
 * <p>The resume state is the {@link IconStore} itself: an element is done when its files are on disk, so
 * a run that stops — the player closes the game, the client is closed, a crash — resumes by asking the
 * store what is missing. There is no progress file to keep in step with reality and no bookkeeping that
 * can disagree with what was actually rendered. The one thing that is kept in memory is the work list,
 * and it is derived from the store on construction rather than being maintained.
 *
 * <h2>Batches are counted in elements, not sizes</h2>
 *
 * <p>The budget is spent per element, and every size an element needs is rendered in the same call. A
 * budget counted per size would be a lie about progress: an item half done is not an item done, and a
 * run stopped between its large and small icon resumes as unfinished either way.
 *
 * <h2>One failure does not end the phase</h2>
 *
 * <p>A renderer that throws, or returns nothing, is recorded and skipped. Some things genuinely cannot
 * be rendered — a mod's item with a model the renderer does not understand, an entity type with no
 * renderer registered — and stopping the whole phase for one of them would mean the user never gets
 * icons for anything. What was skipped is reported, so it is visible rather than silent.
 */
public final class IconPhase {

    /**
     * Does the rendering for one element.
     *
     * <p>A single callback rather than one per size: the expensive part is setting up the render — a
     * stack, a pose, a target buffer — and doing it once per element and reading both sizes out is
     * cheaper than doing it twice. It is also where a caller puts anything it needs to do on the render
     * thread, which is why every size is asked for in one call.
     */
    @FunctionalInterface
    public interface Renderer {

        /**
         * Renders {@code id} at each requested size.
         *
         * @return the renderings by size; a size that could not be produced may be absent, and returning
         *     an empty map means nothing could be rendered for this element
         */
        Map<Integer, byte[]> render(String kind, String id, int[] sizes) throws Exception;
    }

    private record Work(String kind, String id) {
    }

    private final IconStore store;
    private final Renderer renderer;
    private final List<Work> outstanding = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();

    /** Elements finished before this run started, so progress can be reported against the whole job. */
    private final int alreadyDone;
    private final int total;

    private int doneThisRun;

    public IconPhase(IconStore store, Renderer renderer, Map<String, List<String>> candidates) {
        this.store = store;
        this.renderer = renderer;
        // The order is the caller's, and it is preserved: it was chosen to match the order records will
        // be written in, and keeping it means a run that stops part-way has rendered a prefix rather
        // than a scatter, which is what makes the partial output usable on its own.
        int count = 0;
        int complete = 0;
        for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
            String kind = entry.getKey();
            if (!store.renders(kind)) {
                continue;
            }
            for (String id : entry.getValue()) {
                count++;
                if (store.has(kind, id)) {
                    complete++;
                } else {
                    outstanding.add(new Work(kind, id));
                }
            }
        }
        this.total = count;
        this.alreadyDone = complete;
    }

    /** Elements still to render. Zero means the phase is finished. */
    public int remaining() {
        return outstanding.size();
    }

    /** Elements covered by this phase, including the ones a previous run had already rendered. */
    public int total() {
        return total;
    }

    /** How many elements are now rendered, counting what a previous run left behind. */
    public int done() {
        return alreadyDone + doneThisRun;
    }

    /** Whether every element has an icon. */
    public boolean isComplete() {
        return outstanding.isEmpty();
    }

    /**
     * Elements this run could not render.
     *
     * <p>Reported rather than hidden: "the export has icons for everything except these eleven" is a
     * usable answer, while quietly leaving eleven elements without icons is not.
     */
    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    /** A line for a progress readout, since this phase can span many frames. */
    public String progressLine() {
        return done() + "/" + total + " icons"
                + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped");
    }

    /**
     * Renders up to {@code budget} elements and returns how many were finished.
     *
     * <p>One call is one frame's worth of work. Nothing here waits, sleeps or schedules: the caller owns
     * the frame loop, so it decides the budget and knows when it is running late. A budget of zero or
     * less is treated as one rather than as "do nothing", so a misconfigured budget cannot stall the
     * phase forever without any sign of why.
     *
     * @return the number of elements rendered, which is what the caller needs to decide whether to keep
     *     going this frame
     */
    public int advance(int budget) throws IOException {
        int limit = Math.max(1, budget);
        int rendered = 0;
        while (rendered < limit && !outstanding.isEmpty()) {
            Work work = outstanding.remove(0);
            int[] sizes = store.sizes(work.kind());
            Map<Integer, byte[]> produced;
            try {
                produced = renderer.render(work.kind(), work.id(), sizes);
            } catch (Exception e) {
                // Counted as skipped rather than aborting: see the class note. The element stays
                // unfinished, so the next run tries again.
                skipped.add(work.kind() + "/" + work.id() + ": " + e);
                rendered++;
                continue;
            }
            if (produced == null || produced.isEmpty()) {
                skipped.add(work.kind() + "/" + work.id() + ": nothing rendered");
                rendered++;
                continue;
            }
            boolean storedAny = false;
            for (int size : sizes) {
                byte[] png = produced.get(size);
                if (png == null) {
                    continue;
                }
                store.put(work.kind(), work.id(), size, png);
                storedAny = true;
            }
            if (!storedAny) {
                // The renderer answered but produced none of the sizes asked for, so nothing was
                // stored and the element is still unfinished.
                skipped.add(work.kind() + "/" + work.id() + ": no requested size was produced");
            } else {
                doneThisRun++;
            }
            rendered++;
        }
        return rendered;
    }

    /**
     * Runs the phase to completion, one batch per call to {@code betweenBatches}.
     *
     * <p>For a caller that has no frame loop — a test, or a command run outside a client — and also the
     * honest way to express "keep going until it is done": the loop condition is the phase's own, so a
     * batch that renders nothing usable still makes progress because it removes the element from the
     * outstanding list.
     *
     * <p>{@code betweenBatches} is called with the phase after each batch, which is where a caller
     * reports progress or checks whether it has been asked to stop. Returning {@code false} stops, and
     * what has been rendered so far is kept, which is exactly what resuming is for.
     *
     * @return how many batches ran
     */
    public int runBatches(int budget, java.util.function.Predicate<IconPhase> betweenBatches)
            throws IOException {
        int batches = 0;
        while (!isComplete()) {
            advance(budget);
            batches++;
            if (betweenBatches != null && !betweenBatches.test(this)) {
                break;
            }
        }
        return batches;
    }

    /** The per-kind work this phase was built from, for a caller that wants to log its shape. */
    public Map<String, Integer> totalsByKind() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Work work : outstanding) {
            out.merge(work.kind(), 1, Integer::sum);
        }
        return out;
    }

    /** Calls {@code sink} once per outstanding element, for a caller that wants to see the work list. */
    public void forEachOutstanding(Consumer<String> sink) {
        for (Work work : outstanding) {
            sink.accept(work.kind() + "/" + work.id());
        }
    }
}
