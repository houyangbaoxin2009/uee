package org.uee.pipeline;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.uee.config.ExportConfig;

/**
 * Runs exports off the calling thread so a function-driven run does not block the server tick.
 *
 * <h2>Serialised on purpose</h2>
 *
 * <p>Exports execute one at a time. Two runs writing the same bundle would interleave their writes
 * across the same shard files, and the result would be a corrupt bundle rather than a slow one — so
 * concurrency here would buy nothing and cost correctness. A second submission queues.
 *
 * <p>A bounded queue, because the alternative is a caller enqueueing unboundedly. A function in a tick
 * loop that starts an export every tick should be told it is going too fast, not allowed to consume
 * memory until the server dies.
 *
 * <h2>A pollable primitive</h2>
 *
 * <p>{@link #unfinished()} is the number a function reads through {@code execute store result} to wait
 * for completion. That is the whole reason the interface is a job registry rather than a callback: an
 * MC function cannot be handed a value, but it can read a number and branch on it.
 */
public final class UeeJobs {

    /**
     * How many finished jobs are remembered.
     *
     * <p>Bounded because a long-lived server accumulates them forever otherwise, and the only reason
     * to keep one is so a function can read the result of a run that has just ended.
     */
    private static final int HISTORY = 32;

    /** How many jobs may be queued behind the running one before submissions are refused. */
    private static final int MAX_QUEUED = 8;

    /** How long shutdown waits for the running job before giving up on it. */
    private static final long SHUTDOWN_WAIT_SECONDS = 30;

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Deque<UeeJob> active = new ArrayDeque<>(4);
    private final Deque<UeeJob> history = new ArrayDeque<>(HISTORY);
    private final Object lock = new Object();
    private ExecutorService worker;
    private boolean shutDown;

    /** Submits a run. The work happens on a worker thread; the caller gets the job immediately. */
    public UeeJob submit(ExportConfig config, Path root, Supplier<ExportReport> work) {
        return submit(config, root, work, null);
    }

    /**
     * Submits a run and arranges to be told when it finishes.
     *
     * <p>The notifier exists so a player who started a run can be given its result without the run
     * having blocked anything. The core has no idea who or what is being notified — a command passes a
     * lambda that resolves its own recipient — which is what keeps this usable with no game present.
     */
    public UeeJob submit(ExportConfig config, Path root, Supplier<ExportReport> work,
            java.util.function.Consumer<UeeJob> whenFinished) {
        synchronized (lock) {
            if (shutDown) {
                throw new IllegalStateException("the job runner is shut down");
            }
            if (active.size() >= MAX_QUEUED + 1) {
                throw new IllegalStateException("too many exports are already queued ("
                        + active.size() + "); wait for /uee jobs to reach 0");
            }
            UeeJob job = new UeeJob(nextId.getAndIncrement(), config, root);
            job.notifyWith(whenFinished);
            active.addLast(job);
            executor().submit(() -> run(job, work));
            return job;
        }
    }

    /** The number of jobs that have not finished. Zero means idle. */
    public int unfinished() {
        synchronized (lock) {
            return active.size();
        }
    }

    /** Active jobs, oldest first. */
    public List<UeeJob> activeJobs() {
        synchronized (lock) {
            return List.copyOf(active);
        }
    }

    /** Recent finished jobs, newest first. */
    public List<UeeJob> recentJobs() {
        synchronized (lock) {
            return List.copyOf(history);
        }
    }

    /** A job by id, whether active or recently finished, or {@code null}. */
    public UeeJob get(int id) {
        synchronized (lock) {
            for (UeeJob job : active) {
                if (job.id() == id) {
                    return job;
                }
            }
            for (UeeJob job : history) {
                if (job.id() == id) {
                    return job;
                }
            }
            return null;
        }
    }

    /** Cancels a queued job. Returns false when it had already started or finished. */
    public boolean cancel(int id) {
        UeeJob job;
        synchronized (lock) {
            job = get(id);
        }
        if (job == null || !job.cancel()) {
            return false;
        }
        // A cancelled job never runs, so nothing else will move it out of `active`.
        synchronized (lock) {
            active.remove(job);
            remember(job);
        }
        return true;
    }

    /** Stops accepting work and waits briefly for the running job. */
    public void shutdown() {
        ExecutorService service;
        synchronized (lock) {
            if (shutDown) {
                return;
            }
            shutDown = true;
            service = worker;
            worker = null;
        }
        if (service == null) {
            return;
        }
        service.shutdown();
        try {
            if (!service.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- internals

    private void run(UeeJob job, Supplier<ExportReport> work) {
        if (job.state() == UeeJob.State.CANCELLED) {
            finish(job);
            return;
        }
        job.markRunning();
        try {
            ExportReport report = work.get();
            job.markDone(report);
        } catch (Throwable t) {
            // Anything thrown here would otherwise die with the worker thread and vanish; the job is
            // where a caller can see it.
            job.markFailed(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            finish(job);
            // Announced after the job leaves the active set, so a notifier that inspects
            // /uee jobs sees a consistent picture rather than itself.
            job.announce();
        }
    }

    private void finish(UeeJob job) {
        synchronized (lock) {
            active.remove(job);
            remember(job);
            lock.notifyAll();
        }
    }

    private void remember(UeeJob job) {
        history.addFirst(job);
        while (history.size() > HISTORY) {
            history.removeLast();
        }
    }

    private ExecutorService executor() {
        if (worker == null || worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor(dedicated());
        }
        return worker;
    }

    /**
     * A daemon thread named for its purpose.
     *
     * <p>Daemon so a stuck export cannot keep the JVM alive after the game has decided to exit, and
     * named so a thread dump during a long export says what it was doing.
     */
    private static ThreadFactory dedicated() {
        return runnable -> {
            Thread thread = new Thread(runnable, "uee-export");
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Waits until nothing is active, up to a timeout. For tests and for shutdown; not for functions. */
    public boolean awaitIdle(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        synchronized (lock) {
            while (!active.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                lock.wait(Math.min(remaining, 100));
            }
            return true;
        }
    }

    /** Every job ever remembered, for tests. */
    List<UeeJob> all() {
        List<UeeJob> out = new ArrayList<>(active.size() + history.size());
        synchronized (lock) {
            out.addAll(active);
            out.addAll(history);
        }
        return out;
    }
}
