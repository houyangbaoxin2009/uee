package org.uee.delta;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What a previous run produced, as artifact path to content fingerprint.
 *
 * <h2>What it is for</h2>
 *
 * <p>A delta needs something to be a delta against. This is that: after each run the exporter writes what
 * it produced and what each artifact hashed to, and the next run reads it back to find out which artifacts
 * are new, which changed and which are gone. Without it, "incremental" means "ask the consumer to work out
 * what changed", which is the thing the consumer was hoping not to do.
 *
 * <h2>The file is a format, so it is written to be read</h2>
 *
 * <p>One entry per line, fingerprint first, a tab, then the relative path. The fingerprint is fixed-width,
 * so the tab is not strictly needed — but a path may contain a space or a colon and taking the first tab
 * rather than the last space means the path can be anything. Sorted by path, so two runs over the same
 * content produce byte-identical manifests and a diff between them shows only real differences.
 *
 * <p>A leading header line names the format and its version. It is checked on read, and a manifest from a
 * different version is treated as absent rather than parsed optimistically: a delta computed against a
 * format that changed meaning would produce a wrong answer, and a wrong "unchanged" is a silently missing
 * artifact. Starting over costs one full run; being wrong costs trust in every run after it.
 *
 * <h2>A missing manifest is normal, a damaged one is reported</h2>
 *
 * <p>The first run has nothing to compare against, so absence means "everything is new" rather than an
 * error. A manifest that exists but cannot be read is a different situation and is reported: skipping it
 * would silently turn an incremental run into a full one, and the count of artifacts would look wrong to
 * anyone comparing runs without anything to point at.
 */
public final class Snapshot {

    /** The header a manifest starts with, so a truncated or foreign file is recognisable. */
    public static final String HEADER = "uee-snapshot 1";

    /** Conventional file name, placed in the export's root directory. */
    public static final String FILE_NAME = ".uee-snapshot";

    private final Map<String, String> entries;

    /** Anything that went wrong while reading, so a caller can report it rather than guess. */
    private final List<String> complaints;

    private Snapshot(Map<String, String> entries, List<String> complaints) {
        this.entries = Collections.unmodifiableMap(entries);
        this.complaints = List.copyOf(complaints);
    }

    /** An empty snapshot: the state before the first run. */
    public static Snapshot empty() {
        return new Snapshot(new LinkedHashMap<>(), List.of());
    }

    /** The fingerprint recorded for an artifact, or {@code null} when it is not in the snapshot. */
    public String fingerprintOf(String path) {
        return entries.get(path);
    }

    /** Whether the snapshot knows this artifact at all. */
    public boolean contains(String path) {
        return entries.containsKey(path);
    }

    public int size() {
        return entries.size();
    }

    public Map<String, String> entries() {
        return entries;
    }

    /** What went wrong while reading, if anything. Empty for a clean read or no file at all. */
    public List<String> complaints() {
        return complaints;
    }

    /**
     * Reads a manifest, or returns an empty snapshot when there is none.
     *
     * <p>Problems short of a missing file are collected rather than thrown, and the entries that could be
     * read are kept: a manifest with one unreadable line is still worth more than none, and the caller is
     * told which line so it can be looked at. Throwing would abandon a run that could have continued;
     * silently skipping would hide it.
     */
    public static Snapshot read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        List<String> complaints = new ArrayList<>();
        Map<String, String> entries = new LinkedHashMap<>();

        BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
        try {
            String header = reader.readLine();
            if (header == null) {
                complaints.add("the manifest is empty, so this run is treated as the first");
                return new Snapshot(entries, complaints);
            }
            if (!header.trim().equals(HEADER)) {
                // A different version, or something else entirely. Treated as absent rather than parsed:
                // see the class note on why a wrong "unchanged" is the expensive mistake.
                complaints.add("the manifest header is '" + header.trim() + "' rather than '" + HEADER
                        + "', so this run is treated as the first");
                return new Snapshot(entries, complaints);
            }

            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                String trimmed = line.trim();
                // A comment is not an unreadable entry: the writer emits them, so the reader has to know
                // them. Checking this before the tab test matters -- a comment has no tab either, and the
                // first version complained about every one of its own comments.
                if (trimmed.isEmpty() || isComment(trimmed)) {
                    continue;
                }
                int tab = trimmed.indexOf('\t');
                if (tab <= 0 || tab == trimmed.length() - 1) {
                    complaints.add("line " + number + " is not an entry, and was skipped");
                    continue;
                }
                String fingerprint = trimmed.substring(0, tab);
                String path = trimmed.substring(tab + 1);
                if (!Fingerprint.isWellFormed(fingerprint)) {
                    complaints.add("line " + number + " has a fingerprint that is not one, and was"
                            + " skipped");
                    continue;
                }
                // A duplicate path can only come from a damaged file. The later line wins and the fact is
                // reported, rather than one of the two being dropped in silence.
                if (entries.put(path, fingerprint) != null) {
                    complaints.add("line " + number + " repeats an earlier path, and the later entry wins");
                }
            }
        } finally {
            reader.close();
        }
        return new Snapshot(entries, complaints);
    }

    /**
     * Writes a manifest, sorted by path, so two runs over the same content produce identical files.
     *
     * <p>Written to a temporary file and moved into place, for the same reason an icon is: a run
     * interrupted while writing this file would leave a manifest that disagrees with what is on disk, and
     * the next run would then compute a delta against a state that never existed. Absence is a state this
     * format understands; half a file is not.
     */
    public static void write(Path file, Map<String, String> entries, String comment) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        try (Writer out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            out.write(HEADER);
            out.write('\n');
            if (comment != null && !comment.isEmpty()) {
                // Recorded as a comment inside the header block rather than as a field, since it is for a
                // person reading the file and nothing depends on it.
                for (String line : comment.split("\n")) {
                    out.write("// ");
                    out.write(line);
                    out.write('\n');
                }
            }
            for (Map.Entry<String, String> entry : new TreeMap<>(entries).entrySet()) {
                out.write(entry.getValue());
                out.write('\t');
                out.write(entry.getKey());
                out.write('\n');
            }
        }
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Whether a line is a comment rather than an entry.
     *
     * <p>The comment marker is the double slash, the same one the configuration format uses, so a reader
     * of one already knows the other.
     */
    public static boolean isComment(String line) {
        return line != null && line.trim().startsWith("//");
    }
}
