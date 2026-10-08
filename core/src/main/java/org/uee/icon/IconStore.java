package org.uee.icon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Where rendered icons live between the phase that renders them and the phase that writes them.
 *
 * <h2>Why this is on disk, and why that is not an implementation detail</h2>
 *
 * <p>Rendering and writing cannot happen in the same pass. Rendering needs the client's render thread
 * and therefore the game loop, one batch at a time; writing streams records to disk in a single pass
 * that must not depend on a game being open. And a record carries its icon inline as base64, so the icon
 * has to exist <em>before</em> the record is written.
 *
 * <p>So the icons must be held somewhere in between, and that somewhere cannot be memory: a large pack
 * has tens of thousands of items and a 128-pixel PNG is kilobytes, which adds up to hundreds of
 * megabytes — the exact figure the pipeline's memory model exists to keep it away from. On disk it is
 * also, at no extra cost, the resume state: an element is finished when its files are on disk, so an
 * interrupted run re-renders only what is missing rather than starting over. That is what makes
 * "resumable" a property of the store rather than a bookkeeping layer on top of it.
 *
 * <h2>Completion is per element, not per file</h2>
 *
 * <p>An item has two sizes and both are needed, so an element counts as done only when every size it is
 * configured for is present. A store that treated one file as completion would resume a run and produce
 * records with one icon missing and no way to tell.
 *
 * <h2>Writes are atomic</h2>
 *
 * <p>Each icon is written to a temporary file and moved into place. A run interrupted mid-write — which
 * is the normal case this store exists for, a player closing the game — must not leave a truncated PNG
 * that reads as a completed icon and is embedded as garbage.
 */
public final class IconStore {

    /** The directory an icon kind is kept under, one level per kind so a listing is cheap. */
    private final Path root;

    /** The sizes each kind is rendered at. Items want a large and a small; entities only a large. */
    private final java.util.Map<String, int[]> sizesByKind;

    public IconStore(Path root, java.util.Map<String, int[]> sizesByKind) {
        this.root = root;
        this.sizesByKind = java.util.Map.copyOf(sizesByKind);
    }

    /** The directory this store writes to, so a caller can report where a resumed run found things. */
    public Path root() {
        return root;
    }

    /** The sizes configured for a kind, or an empty array when the kind is not rendered at all. */
    public int[] sizes(String kind) {
        return sizesByKind.getOrDefault(kind, new int[0]);
    }

    /** Whether this kind is rendered at all. A kind with no sizes has nothing to store. */
    public boolean renders(String kind) {
        return sizesByKind.containsKey(kind) && sizesByKind.get(kind).length > 0;
    }

    /**
     * Stores one rendered size.
     *
     * <p>Written through a temporary file in the same directory and moved into place, so a partially
     * written file is never visible under its final name. The temporary name carries the thread so two
     * workers rendering the same pack concurrently cannot collide on it — rendering itself is
     * single-threaded, but the store does not depend on that being true to stay correct.
     */
    public void put(String kind, String id, int size, byte[] png) throws IOException {
        if (png == null || png.length == 0) {
            // A renderer that produced nothing has not produced an icon. Storing an empty file would
            // make the element look finished and then embed nothing.
            return;
        }
        Path target = fileOf(kind, id, size);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".part-"
                + Thread.currentThread().threadId());
        Files.write(temporary, png);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /** Whether every size this kind needs is present for {@code id}. */
    public boolean has(String kind, String id) {
        int[] sizes = sizes(kind);
        if (sizes.length == 0) {
            return false;
        }
        for (int size : sizes) {
            if (!Files.isRegularFile(fileOf(kind, id, size))) {
                return false;
            }
        }
        return true;
    }

    /** The rendered bytes for one size, or {@code null} when it was never rendered. */
    public byte[] get(String kind, String id, int size) throws IOException {
        Path file = fileOf(kind, id, size);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    /**
     * The ids from {@code candidates} that still need rendering.
     *
     * <p>This is the resume step, and the reason it reads the filesystem rather than a progress file: a
     * progress file can disagree with what is actually stored — after a crash, or if a user deletes an
     * icon to force a re-render — and the filesystem cannot. Checking is a stat per size, which is cheap
     * next to the render it avoids.
     */
    public List<String> pending(String kind, List<String> candidates) {
        List<String> out = new ArrayList<>(candidates.size());
        if (!renders(kind)) {
            return out;
        }
        for (String id : candidates) {
            if (!has(kind, id)) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Where one icon lives.
     *
     * <p>The id is split at its colon so the filesystem gets a directory per namespace and a file per
     * element, which keeps a single directory from holding every item in the game. An id without a
     * namespace — which should not occur, but would otherwise become a path — is filed under a literal
     * catch-all rather than being written to the store root.
     */
    private Path fileOf(String kind, String id, int size) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "unknown" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        return root.resolve(kind).resolve(sanitise(namespace)).resolve(sanitise(path))
                .resolve(size + ".png");
    }

    /**
     * Makes one path segment safe.
     *
     * <p>Replaced rather than rejected: an id with a character the filesystem dislikes is still an
     * element that needs an icon, and refusing to render it would lose the icon to a naming problem.
     * The mapping is not reversible, which is fine — the store is read by the same id it was written
     * with, and a collision would need two ids differing only in replaced characters.
     */
    private static String sanitise(String segment) {
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            boolean fine = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '/';
            sb.append(fine ? c : '_');
        }
        String out = sb.toString();
        return out.isEmpty() ? "unnamed" : out;
    }

    /** How many elements of a kind are fully rendered, for reporting progress on a resumed run. */
    public int completeCount(String kind, List<String> ids) {
        int done = 0;
        for (String id : ids) {
            if (has(kind, id)) {
                done++;
            }
        }
        return done;
    }
}
