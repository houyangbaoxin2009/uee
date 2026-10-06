package org.uee.pipeline;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of one export run.
 *
 * <p>Reports what was written and, importantly, what failed. A non-empty failure list is not a
 * broken export: it is the crash-isolation contract working as designed — the run completed, and the
 * elements that could not be collected are named so they can be investigated individually instead
 * of taking the whole artifact down with them.
 */
public final class ExportReport {

    /**
     * One output file.
     *
     * @param namespace the shard's namespace
     * @param kind the element category
     * @param format the encoding
     * @param path the file's path
     * @param bytes size on disk
     * @param records records written
     * @param target the datapack target this artifact belongs to, or an empty string for a category's
     *     own output. Present because a target's output is a shard of the same category, and without
     *     this two artifacts of one category would be indistinguishable in the report.
     */
    public record Artifact(String namespace, String kind, String format, Path path, long bytes,
            long records, String target) {

        public Artifact {
            target = target == null ? "" : target;
        }

        /** Convenience for the category's own output, which has no target. */
        public Artifact(String namespace, String kind, String format, Path path, long bytes,
                long records) {
            this(namespace, kind, format, path, bytes, records, "");
        }

        /** Whether this artifact came from a datapack target rather than from the category itself. */
        public boolean fromTarget() {
            return !target.isEmpty();
        }
    }

    private final Path root;
    private final List<Artifact> artifacts;
    private final List<String> failures;
    private final long records;
    private final long bytes;
    private final long millis;
    private final int findings;

    /**
     * What this run did relative to the previous one, or {@code null} when no delta was asked for.
     *
     * <p>On the report rather than on the runner, because that is where a caller looks: the runner is
     * single-use and holding a reference to it just to ask what happened is the sort of thing that gets
     * forgotten when the interface is used from a distance.
     */
    private final String delta;

    /**
     * Builds a report.
     *
     * <p>Public because a report is a value and the job runner accepts one from a caller's own work:
     * without this, anyone using the programmatic interface could start a job but not supply its
     * result. The running total is derived from the artifacts rather than taken on trust, so a report
     * cannot disagree with its own contents.
     */
    public static ExportReport of(Path root, List<Artifact> artifacts, List<String> failures,
            long millis, int findings) {
        long records = 0;
        for (Artifact a : artifacts) {
            records += a.records();
        }
        return new ExportReport(root, artifacts, failures, records, millis, findings, null);
    }

    /** A report that also says what changed, for a run that was asked for a delta. */
    public static ExportReport of(Path root, List<Artifact> artifacts, List<String> failures,
            long millis, int findings, String deltaSummary) {
        long records = 0;
        for (Artifact a : artifacts) {
            records += a.records();
        }
        return new ExportReport(root, artifacts, failures, records, millis, findings, deltaSummary);
    }

    ExportReport(Path root, List<Artifact> artifacts, List<String> failures, long records,
            long millis, int findings, String delta) {
        this.root = root;
        this.artifacts = artifacts;
        this.failures = failures;
        this.records = records;
        this.millis = millis;
        this.findings = findings;
        this.delta = delta;
        long total = 0;
        for (Artifact a : artifacts) {
            total += a.bytes();
        }
        this.bytes = total;
    }

    public Path root() {
        return root;
    }

    /** What changed relative to the previous run, or {@code null} when no delta was asked for. */
    public String deltaSummary() {
        return delta;
    }

    public List<Artifact> artifacts() {
        return artifacts;
    }

    public List<String> failures() {
        return failures;
    }

    public long records() {
        return records;
    }

    public long bytes() {
        return bytes;
    }

    public long millis() {
        return millis;
    }

    public boolean successful() {
        return failures.isEmpty();
    }

    /** How many findings the analyses produced, whether or not they were written out. */
    public int findings() {
        return findings;
    }

    /** A short human-readable summary, in the same shape an import log reports progress. */
    public String summary() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(artifacts.size()).append(" files, ")
                .append(records).append(" records, ")
                .append(bytes / 1024).append(" KiB in ")
                .append(millis).append(" ms");
        if (delta != null) {
            // Appended rather than replacing the figures above: a delta run still produces files and
            // still takes time, and a reader wants both the size of the run and the size of the change.
            sb.append("; delta: ").append(delta);
        }
        if (findings > 0) {
            sb.append(", ").append(findings).append(" findings");
        }
        if (!failures.isEmpty()) {
            sb.append(", ").append(failures.size()).append(" failures");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return summary();
    }
}
