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

    /** One written file. */
    public record Artifact(String namespace, String kind, String format, Path path, long bytes,
            long records) {
    }

    private final Path root;
    private final List<Artifact> artifacts;
    private final List<String> failures;
    private final long records;
    private final long bytes;
    private final long millis;

    ExportReport(Path root, List<Artifact> artifacts, List<String> failures, long records,
            long millis) {
        this.root = root;
        this.artifacts = artifacts;
        this.failures = failures;
        this.records = records;
        this.millis = millis;
        long total = 0;
        for (Artifact a : artifacts) {
            total += a.bytes();
        }
        this.bytes = total;
    }

    public Path root() {
        return root;
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

    /** A short human-readable summary, in the same shape an import log reports progress. */
    public String summary() {
        StringBuilder sb = new StringBuilder(128);
        sb.append(artifacts.size()).append(" files, ")
                .append(records).append(" records, ")
                .append(bytes / 1024).append(" KiB in ")
                .append(millis).append(" ms");
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
