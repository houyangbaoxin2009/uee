package org.uee.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Runs per-item work across threads and hands the results to one consumer in the input order.
 *
 * <h2>Why this shape and not a thread pool per record</h2>
 *
 * <p>An export has two halves with opposite needs. Preparing a record — reading a file, parsing it,
 * building the model — is pure CPU work on data that nothing else touches, so it parallelises freely.
 * Emitting one is not: records go into per-shard buffers that are written in sequence, and two threads
 * appending to one shard would interleave and corrupt it. Only the first half can be spread out, and the
 * second has to stay single-threaded.
 *
 * <h2>The three properties this exists to guarantee</h2>
 *
 * <ul>
 *   <li><b>Output does not depend on the thread count.</b> Results are consumed strictly in the order
 *       the items were given, so a run with four threads produces the same bytes as a run with one. This
 *       is not a nicety: the export is diffed and compared byte-for-byte across runs, and an
 *       order-dependent output would make every comparison meaningless. It is checked by a test that
 *       diffs the two.
 *   <li><b>Memory is bounded by the window, not by the input.</b> At most {@code window} results are
 *       alive at once. The consumer takes one before the next is submitted, so a producer running ahead
 *       cannot accumulate work. This is the same discipline the shard buffers follow, for the same
 *       reason: a tool that streams an arbitrarily large pack must not hold it.
 *   <li><b>It cannot deadlock.</b> The consumer always waits on the head of a queue that was submitted
 *       before the wait began, so the future it needs is always already running. A design where workers
 *       block on submitting would deadlock the moment the window filled with work the consumer is not
 *       yet allowed to take.
 * </ul>
 *
 * <h2>Cost when it is not worth it</h2>
 *
 * <p>Below two threads, or for a single item, the work runs inline: handing one item to a pool costs
 * more than doing it. A pool is created only when there is something to spread out, and it is shut down
 * on the way out even if the work throws.
 */
public final class OrderedWork {

    /**
     * In-flight results per thread.
     *
     * <p>A deeper window absorbs the difference between the slowest and fastest item, which matters when
     * parsing a big table next to a tiny one; a shallower one bounds memory more tightly. Sixty-four
     * records of a few hundred bytes each is tens of kilobytes per thread, which is nothing against the
     * shard buffers, so the window is set where it helps throughput rather than where it is smallest.
     */
    private static final int DEFAULT_WINDOW_PER_THREAD = 64;

    private OrderedWork() {
    }

    /**
     * Applies {@code work} to every item and passes the results to {@code consume}, in order.
     *
     * @param items the work, in the order its results must appear
     * @param threads how many workers, or 1 to run inline
     * @param work must be safe to call from several threads at once, and must not touch the sink
     * @param consume called from the calling thread only, in item order
     */
    public static <I, O> void run(List<I> items, int threads, String name,
            Function<I, O> work, Consumer<O> consume) {
        run(items, threads, DEFAULT_WINDOW_PER_THREAD, name, work, consume);
    }

    /**
     * As {@link #run(List, int, String, Function, Consumer)}, with an explicit window.
     *
     * <p>The window is per thread, so the number of results alive at once is
     * {@code threads * windowPerThread}. Exposed because a caller that knows its items are large can
     * narrow it, and a test can set it to one to prove that ordering and bounded memory hold at the
     * tightest possible setting.
     */
    public static <I, O> void run(List<I> items, int threads, int windowPerThread, String name,
            Function<I, O> work, Consumer<O> consume) {
        int n = items.size();
        if (n == 0) {
            return;
        }
        if (threads <= 1 || n == 1) {
            // Inline. Not a fallback so much as the right answer: a pool costs more than it saves for
            // one item, and this keeps the single-threaded path free of anything concurrent to reason
            // about.
            for (I item : items) {
                consume.accept(work.apply(item));
            }
            return;
        }

        int window = Math.max(1, windowPerThread) * threads;
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonThreads(name));
        Deque<Future<O>> inFlight = new ArrayDeque<>(window + 1);
        try {
            for (I item : items) {
                // Keep the window full rather than draining it in batches: the next item starts as soon
                // as there is room, so no worker idles waiting for the slowest of a batch.
                while (inFlight.size() >= window) {
                    consume.accept(await(inFlight.removeFirst()));
                }
                inFlight.addLast(pool.submit(() -> work.apply(item)));
            }
            while (!inFlight.isEmpty()) {
                consume.accept(await(inFlight.removeFirst()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Takes a finished result, turning a failure into a {@link RuntimeException}.
     *
     * <p>Rethrown rather than swallowed: the pipeline already decides per item whether a failure is
     * survivable, and it can only do that if it sees the error. Hiding one here would turn a reportable
     * problem into a record that silently never appeared.
     */
    private static <O> O await(Future<O> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while collecting", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause);
        }
    }

    /** Daemon threads named for what they are doing, so a thread dump during an export is readable. */
    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "uee-" + name + "-" + counter.incrementAndGet());
            // Daemon so a misbehaving export cannot keep the game process alive. The pool is shut down
            // explicitly on the way out; this is the second line of defence.
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * How many workers to use for {@code items}.
     *
     * <p>Asked rather than assumed because the useful answer depends on the work: a hundred thousand
     * files across eight threads is worth it, and four files across eight threads is not. The caller
     * passes the configured thread count and this decides whether it can be used.
     */
    public static int usefulThreads(int configured, int itemCount) {
        if (configured <= 1 || itemCount <= 1) {
            return 1;
        }
        // Each worker should get several items or the setup costs more than the parallelism saves.
        return Math.max(1, Math.min(configured, itemCount / 4));
    }

    /**
     * Collects into a list, for callers that want the results rather than a stream.
     *
     * <p>Present because it makes the ordering guarantee easy to test on its own, without a sink.
     */
    public static <I, O> List<O> map(List<I> items, int threads, Function<I, O> work) {
        List<O> out = new ArrayList<>(items.size());
        run(items, threads, "map", work, out::add);
        return out;
    }
}
