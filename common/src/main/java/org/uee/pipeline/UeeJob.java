package org.uee.pipeline;

import java.nio.file.Path;
import org.uee.config.ExportConfig;

/**
 * One export run that may still be in progress.
 *
 * <h2>Why jobs exist</h2>
 *
 * <p>An export started from an MC function runs inside a server tick. Doing the work synchronously
 * there is not merely slow, it is unsafe: a large pack can hold the tick past the watchdog limit and
 * take the server down. So a function-facing run is started and then polled, and this type is what
 * can be polled.
 *
 * <p>This is also why the interface is a job rather than a callback: a function cannot receive a
 * value, but it <em>can</em> read a number through {@code execute store result}. A count of unfinished
 * jobs is therefore the composable primitive — a tick loop that waits for zero — whereas a callback
 * would have nothing to attach to.
 *
 * @see UeeJobs
 */
public final class UeeJob {

    /** Where a job is in its life. */
    public enum State {
        /** Accepted, not started. Kept distinct from RUNNING so a queued job is not mistaken for work. */
        QUEUED,
        /** Running on a worker thread. */
        RUNNING,
        /** Finished successfully. */
        DONE,
        /** Finished with an error. */
        FAILED,
        /** Cancelled before it started; a run already writing cannot be stopped safely. */
        CANCELLED;

        /** Whether the job has finished, however it ended. */
        public boolean isFinished() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }

        public String token() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final int id;
    private final String label;
    private final Path root;
    private final String kinds;
    private final String formats;
    private final long submittedAt;
    private volatile State state = State.QUEUED;
    private volatile long startedAt;
    private volatile long finishedAt;
    private volatile ExportReport report;
    private volatile String error;

    UeeJob(int id, ExportConfig config, Path root) {
        this.id = id;
        this.root = root;
        // Captured at submission rather than read later: the configuration is immutable, but holding
        // the values means a finished job can still describe what it did after the caller has moved
        // on, which is what makes /uee job <id> useful.
        this.kinds = config.kinds().size() + " categories";
        this.formats = String.join(", ", config.formats());
        this.label = "export " + kinds + " as " + formats;
        this.submittedAt = System.currentTimeMillis();
    }

    public int id() {
        return id;
    }

    public State state() {
        return state;
    }

    /** A one-line description of what the job is doing, for logs and for the command surface. */
    public String label() {
        return label;
    }

    /** Where the job writes. */
    public Path root() {
        return root;
    }

    public String kinds() {
        return kinds;
    }

    public String formats() {
        return formats;
    }

    public long submittedAt() {
        return submittedAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public long finishedAt() {
        return finishedAt;
    }

    /** The report, once the job is {@link State#DONE}, otherwise {@code null}. */
    public ExportReport report() {
        return report;
    }

    /** The failure, once the job is {@link State#FAILED}, otherwise {@code null}. */
    public String error() {
        return error;
    }

    /** Whether the job has finished, however it ended. */
    public boolean isFinished() {
        return state.isFinished();
    }

    /** Whether the job is still occupying a slot. */
    public boolean isActive() {
        return !isFinished();
    }

    /** How long the job has been running, or took in total. */
    public long elapsedMillis() {
        long from = startedAt == 0 ? submittedAt : startedAt;
        long to = finishedAt == 0 ? System.currentTimeMillis() : finishedAt;
        return Math.max(0, to - from);
    }

    /** A one-line status, for a poll response. */
    public String describe() {
        StringBuilder sb = new StringBuilder(96);
        sb.append('#').append(id).append(' ').append(state.token()).append(" — ").append(label);
        sb.append(" (").append(elapsedMillis()).append(" ms)");
        if (report != null) {
            sb.append(", ").append(report.artifacts().size()).append(" files, ")
                    .append(report.records()).append(" records");
        }
        if (error != null) {
            sb.append(", error: ").append(error);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- transitions, package-private

    void markRunning() {
        if (state == State.QUEUED) {
            startedAt = System.currentTimeMillis();
            state = State.RUNNING;
        }
    }

    void markDone(ExportReport finished) {
        this.report = finished;
        this.finishedAt = System.currentTimeMillis();
        this.state = State.DONE;
    }

    void markFailed(String message) {
        this.error = message;
        this.finishedAt = System.currentTimeMillis();
        this.state = State.FAILED;
    }

    /** Cancels, unless the job has already finished or is past the point where stopping is safe. */
    boolean cancel() {
        if (state == State.QUEUED) {
            state = State.CANCELLED;
            finishedAt = System.currentTimeMillis();
            return true;
        }
        // A running export is mid-write across many open shards. Interrupting it would leave a partly
        // written bundle with no way to tell which files are complete, so it is left to finish.
        return false;
    }
}
