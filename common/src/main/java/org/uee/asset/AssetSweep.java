package org.uee.asset;

import java.util.List;

/**
 * What the asset sweep asks for, and why it has to be written down.
 *
 * <h2>The API cannot ask for everything</h2>
 *
 * <p>The obvious implementation — list the namespace root and take whatever is there — does not work, and
 * fails in the worst way. {@code ResourceManager.listResources} takes a path <em>relative to a namespace</em>
 * and validates it; the empty string is rejected as an invalid path, and the rejection is <b>logged and
 * swallowed</b> rather than thrown. A caller asking for everything therefore gets an empty map and no error,
 * which reads exactly like a pack that has no assets.
 *
 * <p>The same reason rules out walking directories: nothing in {@code ResourceManager} or
 * {@code PackResources} enumerates a namespace's contents. There is no "list the root" call to find.
 *
 * <p>So the sweep names what it asks for. That is a real limitation and it is stated rather than hidden:
 * <b>a pack that puts content in a directory this list does not name is not copied</b>, and no amount of
 * checking would reveal it, because from the API's point of view those files are simply not asked about.
 * What the sweep can do is say which kinds it swept, so the basis is visible in the report instead of
 * having to be inferred from a shortfall.
 *
 * <h2>The list is measured, not assumed</h2>
 *
 * <p>These are the directories the shipped client actually has under {@code assets/minecraft}, found by
 * looking at all eight and a half thousand of its asset files. A guess would have been close — and would
 * have been missing {@code texts}, which holds four files and is exactly the sort of small directory a guess
 * leaves out.
 *
 * <p>Vanilla is not the limit, which is the awkward part: a pack may add a kind, and it will not be swept.
 * The alternative would be to accept an arbitrary list from configuration so a pack can declare its own, and
 * that belongs with the rest of the filtering options rather than being invented here.
 *
 * <h2>Root files are a separate matter</h2>
 *
 * <p>One asset is not in a directory at all: {@code sounds.json} sits directly under the namespace, and it
 * is the index that ties sound events to the files. A kind sweep cannot reach it — the prefix would be a
 * file name — but {@code getResource} takes a whole resource location and needs no path, so root files are
 * fetched by name instead. That list is short because there is only one thing in it, and it is written
 * down for the same reason the kinds are.
 */
public final class AssetSweep {

    /**
     * The directories swept, in the order the sweep visits them.
     *
     * <p>Ordered as found rather than alphabetically, and sorted by nothing: the collector sorts what it
     * finds afterwards, so this order only decides the order of reads and not of output.
     */
    public static final List<String> KINDS = List.of(
            "models",
            "textures",
            "blockstates",
            "shaders",
            "particles",
            "atlases",
            "font",
            "texts",
            "lang");

    /**
     * Files that live directly under a namespace rather than in a directory.
     *
     * <p>Fetched by name, since there is no prefix that reaches them. {@code sounds.json} is the one the
     * shipped client has; the pack build's marker is deliberately absent, since it is not content.
     */
    public static final List<String> ROOT_FILES = List.of("sounds.json");

    private AssetSweep() {
    }

    /** The prefix to list for a kind, which is the kind itself. Absent here this is where a pack's own would go. */
    public static String prefixOf(String kind) {
        return kind;
    }

    /** A line naming the basis, for a report that has one line to spend on it. */
    public static String describe() {
        return KINDS.size() + " kinds + " + ROOT_FILES.size() + " root file"
                + (ROOT_FILES.size() == 1 ? "" : "s");
    }
}
