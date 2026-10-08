package org.uee.asset;

/**
 * Where an asset goes, and which ones are assets at all.
 *
 * <h2>Copied, never decoded</h2>
 *
 * <p>An asset is copied byte for byte. Nothing here opens a PNG to find out its size, re-encodes an OGG, or
 * normalises a JSON file's whitespace. The design says so and the reason is sharper than it first looks:
 * decoding is a performance sink, and — more importantly — it <em>changes the hash</em>. A fingerprint is
 * taken over the bytes that land on disk, so a re-encoded texture would be a different artifact every time
 * the encoder's version changed, and the delta that exists to avoid rewriting would report every asset as
 * changed on every run. Verbatim copying is what makes the delta work on assets at all.
 *
 * <h2>The one thing that is not a plain join</h2>
 *
 * <p>A resource path arriving here is already relative to the namespace's asset root — the game hands over
 * {@code minecraft:textures/block/stone.png}, not the pack's internal {@code assets/minecraft/...} form — so
 * the output path is assembled rather than derived. Doing it in one place means the record's path, the
 * file's path and the snapshot's key cannot disagree, which is the mistake that would make a delta
 * recognise nothing and rewrite everything.
 *
 * <h2>Files that are not content</h2>
 *
 * <p>One file in the shipped client is named {@code .mcassetsroot}: a marker the pack build leaves behind,
 * with no namespace and nothing in it. Copying it would put a stray file in the output and, worse, give the
 * manifest an entry whose namespace is not a namespace — so it is skipped by name. Anything else
 * dot-prefixed is skipped for the same reason, since a dotfile in an asset tree is a tool's business
 * rather than content's.
 */
public final class AssetPath {

    /** Directory the copied assets live under, named as the game names it. */
    public static final String DIRECTORY = "assets";

    private AssetPath() {
    }

    /**
     * The output path for an asset, relative to the export root.
     *
     * <p>{@code assets/<namespace>/<path>} — the same shape the game uses, so a consumer can map an output
     * file back to the resource it came from without a lookup table, and so a directory of copied assets
     * can be dropped into a pack unchanged.
     */
    public static String outputPath(String namespace, String path) {
        if (namespace == null || namespace.isEmpty() || path == null || path.isEmpty()) {
            return null;
        }
        String cleaned = path.startsWith("/") ? path.substring(1) : path;
        if (cleaned.isEmpty() || !isCopyable(cleaned)) {
            return null;
        }
        return DIRECTORY + "/" + namespace + "/" + cleaned;
    }

    /**
     * Whether a resource path is content worth copying.
     *
     * <p>Path segments are checked rather than only the file name, because a directory beginning with a dot
     * is a tool's working directory and everything below it is its business.
     */
    public static boolean isCopyable(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String cleaned = path.startsWith("/") ? path.substring(1) : path;
        if (cleaned.isEmpty() || cleaned.endsWith("/")) {
            return false;
        }
        int start = 0;
        while (start < cleaned.length()) {
            int slash = cleaned.indexOf('/', start);
            String segment = slash < 0 ? cleaned.substring(start) : cleaned.substring(start, slash);
            if (segment.isEmpty() || segment.charAt(0) == '.') {
                return false;
            }
            if (slash < 0) {
                break;
            }
            start = slash + 1;
        }
        return true;
    }

    /**
     * Whether a namespace is one to copy assets for.
     *
     * <p>A resource location's namespace has to be a plain identifier. This is not pedantry: the pack build
     * marker arrives with a namespace that is not one, and a manifest keyed on it would be an entry nobody
     * could ever match against anything.
     */
    public static boolean isCopyableNamespace(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean fine = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-';
            if (!fine) {
                return false;
            }
        }
        return true;
    }
}
