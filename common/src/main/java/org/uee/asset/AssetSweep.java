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
 * So the list is a <b>default</b> rather than the whole story, and a user can declare more — see
 * {@link #TABLE} for where those declarations live and {@link #merge} for how the two combine.
 *
 * <p>Extra kinds are cheap, which is what makes accumulating them safe. A declared kind that matches nothing
 * costs one directory lookup that finds nothing, so a table grown across a dozen packs stays harmless on
 * each of them, and the failure it prevents — a kind present but never swept — is silent and loses files.
 * That asymmetry is the reason the list may be extended freely rather than carefully.
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

    /**
     * The declaration table holding additional root files.
     *
     * <p>Separate from the kinds because they are a different question: a kind is a directory to walk, a
     * root file is a single file with no directory to walk into. They share a mechanism — both are things
     * the sweep cannot find on its own and a person can — and nothing else, so they are declared separately
     * and counted separately.
     */
    public static final String ROOT_FILE_TABLE = "assetRootFiles";

    /**
     * The declaration table holding additional kinds.
     *
     * <p>A table rather than a configuration key, because this is knowledge about packs rather than a
     * preference: it accumulates, it belongs with the rest of what the user has established, and it should
     * follow them between instances for the same reason the settings do.
     */
    public static final String TABLE = "assetKinds";

    private AssetSweep() {
    }

    /**
     * Whether a name may be declared as a kind.
     *
     * <p>Checked before it is stored rather than when it is used, because the failure mode of a bad name is
     * silence: a kind that cannot be turned into a path would be swept, find nothing, and look exactly like
     * a kind with no files in it. Rejecting it at the point of declaration is the only place the user can be
     * told.
     *
     * <p>The rules are the ones a path segment has to satisfy for the sweep to reach anything: no separator,
     * no leading dot (which is not content), nothing empty, and it must survive being turned into a resource
     * path.
     */
    public static boolean isValidKind(String kind) {
        if (kind == null || kind.isEmpty()) {
            return false;
        }
        if (kind.contains("/") || kind.contains("\\") || kind.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < kind.length(); i++) {
            char c = kind.charAt(i);
            // The same set a resource location allows, lower case only: asset directories are named that way,
            // and accepting anything else would store a name that can never match.
            boolean fine = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == '.';
            if (!fine) {
                return false;
            }
        }
        return AssetPath.isCopyable(kind + "/probe") && AssetPath.outputPath("probe", kind + "/probe") != null;
    }

    /**
     * Whether a name may be declared as a root file.
     *
     * <p>A root file has to be a bare file name: it is fetched by whole resource location, and anything with
     * a separator would be a path the by-name route cannot express. The failure would again be silence — a
     * name that cannot be fetched finds nothing and looks like a file that is not there.
     */
    public static boolean isValidRootFile(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean fine = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || c == '.';
            if (!fine) {
                return false;
            }
        }
        // It has to be usable at a namespace root, which is what the by-name fetch builds.
        return AssetPath.isCopyable(name) && AssetPath.outputPath("probe", name) != null;
    }

    /**
     * The kinds to sweep: the built-in list with the declared ones added.
     *
     * <p>Built-ins first and in their own order, since they are what the list looked like before anyone
     * added to it and a report reads best that way; then the additions, sorted so that the result does not
     * depend on the order they were declared in. Duplicates are dropped, so declaring one of the built-ins
     * changes nothing rather than sweeping it twice.
     *
     * @param declared extra kinds, as read from {@link #TABLE}; null or empty for none
     */
    public static List<String> merge(List<String> declared) {
        java.util.LinkedHashSet<String> kinds = new java.util.LinkedHashSet<>(KINDS);
        if (declared != null) {
            java.util.TreeSet<String> additions = new java.util.TreeSet<>();
            for (String kind : declared) {
                // Names that cannot be swept are skipped rather than attempted: they would be written into
                // the report's basis as though they were swept, which would be a claim the sweep cannot
                // honour. The command refuses them at the point of declaration; this is the second line.
                if (isValidKind(kind) && !kinds.contains(kind)) {
                    additions.add(kind);
                }
            }
            kinds.addAll(additions);
        }
        return List.copyOf(kinds);
    }

    /** The prefix to list for a kind, which is the kind itself. Absent here this is where a pack's own would go. */
    public static String prefixOf(String kind) {
        return kind;
    }

    /**
     * The root files to fetch: the built-in list with the declared ones added.
     *
     * <p>Same rules as {@link #merge} — built-ins first, additions sorted, duplicates dropped — because it
     * is the same kind of list, and a second set of rules would be a second thing to get wrong.
     */
    public static List<String> mergeRootFiles(List<String> declared) {
        java.util.LinkedHashSet<String> files = new java.util.LinkedHashSet<>(ROOT_FILES);
        if (declared != null) {
            java.util.TreeSet<String> additions = new java.util.TreeSet<>();
            for (String name : declared) {
                if (isValidRootFile(name) && !files.contains(name)) {
                    additions.add(name);
                }
            }
            files.addAll(additions);
        }
        return List.copyOf(files);
    }

    /** A line naming the basis, for a report that has one line to spend on it. */
    public static String describe() {
        return describe(KINDS.size(), ROOT_FILES.size());
    }

    /**
     * The same line, for a sweep that covered more than the built-in kinds.
     *
     * <p>The total rather than the addition: a reader wants to know how much was asked for, and a report
     * that said "nine plus three" would need a second number to be useful.
     */
    public static String describe(int totalKinds) {
        return describe(totalKinds, ROOT_FILES.size());
    }

    /** The same line, for a sweep that covered more of either. */
    public static String describe(int totalKinds, int totalRootFiles) {
        return totalKinds + " kinds + " + totalRootFiles + " root file"
                + (totalRootFiles == 1 ? "" : "s");
    }
}
