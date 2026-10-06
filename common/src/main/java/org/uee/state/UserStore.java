package org.uee.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.tielang.zd.ZdFooter;
import org.tielang.zd.ZdSegments;

/**
 * The user's own state, in one file outside every game instance.
 *
 * <h2>Why it is not in the instance</h2>
 *
 * <p>This program is used to look at packs, and looking at packs means changing which instance is running.
 * Anything kept inside an instance has to be installed again for the next one, which for a settings file
 * means copying it into every pack before it can be used — the thing that makes a tool tiring to use. So
 * this lives outside all of them, in one directory that follows the person rather than the pack.
 *
 * <p>The same reasoning applies to what it holds. A table of "which directories hold assets in this pack"
 * is knowledge about a pack, and a person who analyses twelve packs accumulates twelve pieces of it; making
 * them re-establish it per instance would be the same mistake one level down.
 *
 * <h2>One file, and why the shape is zd's rather than mine</h2>
 *
 * <p>Each member is its own zd payload, and the whole is a zd multi-segment document, one named custom
 * segment per member. That is not a convenience: zd already has a segment table with names, requires a name
 * for a custom segment and refuses duplicate ones, so the table of contents is zd's and not something
 * invented here. Hand-writing a directory table is exactly where a "member is missing and looks empty"
 * defect lives, and there is no reason to write one.
 *
 * <p>The document is then compressed as a whole, which is what makes it one small file rather than several.
 * The compressor is gzip — deflate with a two-byte magic. The magic is the point: with a bare deflate stream
 * a file that is not one of these produces garbage or fails, and neither is distinguishable from an empty
 * state, so pointing the tool at the wrong path would silently look like having no settings. With a magic,
 * "this is not my file" is a deterministic rejection, which is the same discipline zd applies to unknown
 * variants.
 *
 * <h2>Missing is normal, unreadable is reported</h2>
 *
 * <p>A first run has no file, so absence means empty state rather than an error. A file that exists and
 * cannot be read is reported and the run continues with what could be read: a partly readable state file is
 * worth more than none, and silently starting over would lose whatever the user had accumulated.
 */
public final class UserStore {

    /** Conventional file name. The extension is this project's own, so it is not mistaken for an archive. */
    public static final String FILE_NAME = "user.ueeuser";

    /** Environment variable that overrides the directory, for CI, portable setups and several personas. */
    public static final String DIR_ENV = "UEE_USER_DIR";

    /**
     * The member holding the settings, as td text.
     *
     * <p>One convention so a reader of the file knows what to look for: the settings member is a zd payload
     * whose rows are configuration text, and every other member is a declaration table under
     * {@link #TABLE_PREFIX}. The store itself does not enforce either name — it holds payloads — but a file
     * is easier to understand when its members are named by convention rather than arbitrarily.
     */
    public static final String SETTINGS = "settings";

    /** Prefix for a member holding one declaration table. */
    public static final String TABLE_PREFIX = "table:";

    private final Path file;
    private final Map<String, byte[]> members;
    private final List<String> complaints;

    private UserStore(Path file, Map<String, byte[]> members, List<String> complaints) {
        this.file = file;
        this.members = Collections.unmodifiableMap(members);
        this.complaints = List.copyOf(complaints);
    }

    // ---------------------------------------------------------------- where it lives

    /**
     * The default directory: a dot-directory in the user's home.
     *
     * <p>Chosen for what it is independent of rather than for platform convention. Every launcher, every
     * instance and every pack generation sits under the home directory and none of them owns it, so nothing
     * has to be reinstalled when the instance changes. A per-platform location would be three paths, three
     * pieces of documentation and a behaviour that depends on which machine is running — for a file that is
     * private state and that the user may want to copy deliberately.
     */
    public static Path defaultDir() {
        return Path.of(System.getProperty("user.home", "."), ".uee");
    }

    /**
     * The directory to use, in order of decreasing specificity.
     *
     * @param configured an explicit setting, which wins; blank or null to fall through
     */
    public static Path resolveDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String fromEnv = System.getenv(DIR_ENV);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv);
        }
        return defaultDir();
    }

    /** The file inside a directory. */
    public static Path fileIn(Path dir) {
        return dir.resolve(FILE_NAME);
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads the store, or returns an empty one when there is nothing to read.
     *
     * <p>Never throws for a missing file, a file that is not one of these, or a member that cannot be read.
     * Each of those is a different fact and all three are collected in {@link #complaints()} rather than
     * being flattened into "no settings", because a user whose accumulated tables have not loaded needs to
     * know whether that is because they have none or because the file is damaged.
     */
    public static UserStore read(Path file) {
        List<String> complaints = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new UserStore(file, new LinkedHashMap<>(), complaints);
        }

        byte[] document;
        try {
            document = gunzip(Files.readAllBytes(file));
        } catch (IOException e) {
            complaints.add("the state file could not be decompressed, so this run starts from nothing: "
                    + e.getMessage());
            return new UserStore(file, new LinkedHashMap<>(), complaints);
        }

        Map<String, byte[]> members = new LinkedHashMap<>();
        List<ZdSegments.Slice> slices;
        try {
            slices = ZdSegments.read(document);
        } catch (RuntimeException e) {
            complaints.add("the state file is not a readable zd document, so this run starts from nothing: "
                    + e.getMessage());
            return new UserStore(file, new LinkedHashMap<>(), complaints);
        }

        for (ZdSegments.Slice slice : slices) {
            if (slice.type() != ZdFooter.SEG_CUSTOM) {
                // Not a member: the footer's own segments live in the same table, and a reader that took
                // every segment as a member would offer the index as one.
                continue;
            }
            members.put(slice.name(), slice.payload());
        }
        return new UserStore(file, members, complaints);
    }

    // ---------------------------------------------------------------- accessors

    /** The path this was read from, or would be written to. */
    public Path file() {
        return file;
    }

    /** Whether there is anything here at all. */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** The member names held, sorted, so a report does not depend on write order. */
    public List<String> memberNames() {
        List<String> names = new ArrayList<>(members.keySet());
        Collections.sort(names);
        return names;
    }

    /**
     * One member's zd payload, or {@code null} when it is not there.
     *
     * <p>Returned as the payload rather than as decoded rows: what a member means is the business of whatever
     * declared it, and a store that decoded them would have to be changed every time one gained a field. The
     * payload is a zd document, so a caller reads it with the reader it would use anywhere else.
     *
     * <p>{@code null} rather than an empty array, because "this member is absent" and "this member is empty"
     * are different facts and only one of them is a reason to fall back to a default.
     */
    public byte[] member(String name) {
        return members.get(name);
    }

    /** The members, name to payload. */
    public Map<String, byte[]> members() {
        return members;
    }

    /** Anything that went wrong while reading, so a caller can report it rather than guess. */
    public List<String> complaints() {
        return complaints;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes the store, replacing whatever was there.
     *
     * <p>Through a temporary and a move, like every other file this program writes: an interrupted write
     * would otherwise leave a state file that is half of two states, and there is nothing sensible to be
     * recovered from that. Absence is a state this format understands; half a file is not.
     */
    public static void write(Path file, Map<String, byte[]> members) throws IOException {
        List<ZdSegments.Slice> slices = new ArrayList<>();
        if (members != null) {
            // Sorted, so two writes of the same state produce the same bytes and the file can be compared.
            for (String name : new java.util.TreeSet<>(members.keySet())) {
                byte[] payload = members.get(name);
                if (name == null || name.isEmpty() || payload == null || payload.length == 0) {
                    // An empty member is skipped rather than stored: a member with nothing in it would read
                    // back as something present and empty, which is a different fact from not present.
                    continue;
                }
                // zd refuses a custom segment without a name and refuses duplicate names, so those two
                // rules are enforced by the format rather than re-checked here.
                slices.add(new ZdSegments.Slice(ZdFooter.SEG_CUSTOM, name, payload));
            }
        }

        byte[] document = ZdSegments.assemble(0, slices);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = file.resolveSibling(file.getFileName() + ".part");
        Files.write(temporary, gzip(document));
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------------------------------------------------------------- the compression

    /**
     * Compresses the document.
     *
     * <p>gzip rather than a bare deflate stream for the magic, as the class note explains: a private state
     * file is fetched by path, and a path typo has to be distinguishable from an empty state.
     */
    private static byte[] gzip(byte[] document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(document.length / 2 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(document);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] compressed) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return gz.readAllBytes();
        }
    }

    /** The first two bytes of the file, which say whether it is one of these at all. */
    public static boolean looksLikeUserState(byte[] head) {
        return head != null && head.length >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
    }

    /** For a report that has one line to spend on where state is read from. */
    public static String describeLocation(Path file) {
        return "user state at " + file + " (set " + DIR_ENV + " or the 'user_dir' key to move it)";
    }
}
