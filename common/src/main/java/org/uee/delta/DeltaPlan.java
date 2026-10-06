package org.uee.delta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, artifact by artifact, what a run needs to do relative to the previous one.
 *
 * <h2>What an artifact is, and why the unit is not a record</h2>
 *
 * <p>The unit is a shard: one file the export writes. That is not a simplification, it is what the design
 * asks for — shards are the thing that is resumable and the thing a fingerprint is taken over — and it is
 * also the only unit the architecture makes available. A writer streams records into a buffer and the
 * pipeline flushes that buffer to a file; the records are not delimited on the way out, so there is a
 * point in the pipeline that can say how big a shard is but none that can say where one record ended. A
 * per-record delta would need that boundary, and adding it would put a framing layer into every writer's
 * output format for the sake of one feature.
 *
 * <p>The trade is real and worth stating: a shard of twenty thousand records is rewritten when one record
 * in it changes. What the consumer gets is still the answer it wanted — which files to reprocess — and the
 * alternative, rewriting everything, is what this replaces.
 *
 * <h2>Four outcomes, and the two that are easy to confuse</h2>
 *
 * <p>A path can be new, changed, unchanged, or gone. Unchanged is the interesting one: it is the answer
 * that saves the work, and it is only sound because fingerprints are taken over output bytes. A path that
 * is unchanged <em>and</em> whose file is still there need not be written at all; unchanged with the file
 * missing must be written, because the consumer has nothing to read. Those are different situations and
 * the decision method asks for the second fact rather than assuming it, since assuming would produce a
 * report that says "unchanged" about a file that is not there.
 *
 * <h2>Removals are computed at the end, because they cannot be known earlier</h2>
 *
 * <p>An artifact is removed when the previous snapshot named it and this run did not. That cannot be known
 * until the run is over, which is why the plan produces its report at the end rather than streaming it.
 * The alternative — removing a file the moment its turn passes — would need the run's artifacts to be
 * visited in snapshot order, which nothing guarantees.
 */
public final class DeltaPlan {

    /** What a run should do about one artifact. */
    public enum Verdict {
        /** Not in the previous snapshot. */
        NEW,
        /** In the previous snapshot under the same path but with a different fingerprint. */
        CHANGED,
        /** In the previous snapshot with the same fingerprint. */
        UNCHANGED,
        /** In the previous snapshot, and not produced by this run. */
        REMOVED
    }

    private final Snapshot previous;
    private final Map<String, String> current = new LinkedHashMap<>();
    private final Set<String> produced = new LinkedHashSet<>();

    public DeltaPlan(Snapshot previous) {
        this.previous = previous == null ? Snapshot.empty() : previous;
    }

    /** The plan for a run with nothing to compare against. */
    public static DeltaPlan firstRun() {
        return new DeltaPlan(Snapshot.empty());
    }

    /**
     * Records an artifact this run produced and classifies it.
     *
     * <p>Called once per artifact, as it is written. The fingerprint is of the bytes written, which is what
     * makes the classification sound; see {@link Fingerprint}.
     */
    public Verdict record(String path, String fingerprint) {
        String prior = previous.fingerprintOf(path);
        current.put(path, fingerprint);
        produced.add(path);
        if (prior == null) {
            return Verdict.NEW;
        }
        return prior.equals(fingerprint) ? Verdict.UNCHANGED : Verdict.CHANGED;
    }

    /**
     * Whether an artifact has to be written, given whether its file is already on disk.
     *
     * <p>A new or changed artifact always is. So is an unchanged one whose file is missing — that is a
     * consumer with nothing to read, which is not the same situation as a consumer with the right bytes
     * already. A delta that skipped it would produce a tidy report about artifacts half of which are not
     * there.
     */
    public boolean shouldWrite(Verdict verdict, boolean targetExists) {
        return switch (verdict) {
            case NEW, CHANGED, REMOVED -> true;
            case UNCHANGED -> !targetExists;
        };
    }

    /**
     * Whether an artifact already on disk from a previous run should be deleted.
     *
     * <p>Only for one that this run did not produce. A changed artifact is written over; an unchanged one
     * is left exactly as it is, which is the point.
     */
    public boolean shouldDelete(Verdict verdict) {
        return verdict == Verdict.REMOVED;
    }

    /** The paths this run produced. */
    public Set<String> produced() {
        return Collections.unmodifiableSet(produced);
    }

    /** What the run produced, for the next run's snapshot. */
    public Map<String, String> manifest() {
        return Collections.unmodifiableMap(current);
    }

    /**
     * Finishes the run and reports what changed.
     *
     * @param deleted how many removed artifacts the caller actually managed to delete, so the report says
     *     what happened rather than what was intended
     */
    public Report finish(int deleted) {
        List<String> removed = new ArrayList<>();
        for (String path : previous.entries().keySet()) {
            if (!produced.contains(path)) {
                removed.add(path);
            }
        }
        Collections.sort(removed);
        return new Report(current, removed, deleted, previous.complaints());
    }

    /**
     * What a run did, relative to the previous one.
     *
     * <p>The changed and removed lists are the payload: they are what a consumer applies. The counts are
     * for a person reading the summary, and they are counted rather than derived from the list sizes
     * because a path can be new and also, in a previous life, removed — the two lists are not disjoint
     * descriptions of one set.
     */
    public record Report(Map<String, String> current, List<String> removed, int deleted,
            List<String> complaints) {

        public Report {
            current = Collections.unmodifiableMap(current);
            removed = List.copyOf(removed);
            complaints = List.copyOf(complaints);
        }

        /** The paths this run produced that differ from the previous run, sorted. */
        public List<String> changedPaths(Snapshot previous) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> entry : current.entrySet()) {
                String prior = previous.fingerprintOf(entry.getKey());
                if (prior == null || !prior.equals(entry.getValue())) {
                    out.add(entry.getKey());
                }
            }
            Collections.sort(out);
            return out;
        }

        /** Whether this run produced anything the previous one did not already have. */
        public boolean isEmpty(Snapshot previous) {
            return changedPaths(previous).isEmpty() && removed.isEmpty();
        }

        public int artifactCount() {
            return current.size();
        }

        /** A line for a summary, since a delta run's whole point is the numbers. */
        public String summary(Snapshot previous) {
            List<String> changed = changedPaths(previous);
            int unchanged = current.size() - changed.size();
            StringBuilder sb = new StringBuilder();
            sb.append(changed.size()).append(" new or changed");
            sb.append(", ").append(unchanged).append(" unchanged");
            sb.append(", ").append(removed.size()).append(" removed");
            return sb.toString();
        }

        /**
         * The same summary as the number of files a consumer has to look at.
         *
         * <p>Stated separately because it is the figure that matters and it is not the sum of the others:
         * an unchanged artifact costs nothing, which is the entire value of the exercise.
         */
        public int consumerWorkload(Snapshot previous) {
            return changedPaths(previous).size() + removed.size();
        }
    }
}
