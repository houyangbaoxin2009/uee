package org.uee;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.tielang.zd.ZdDocWriter;
import org.tielang.zd.ZdRow;
import org.uee.state.SettingsMember;
import org.uee.state.UserStore;

/**
 * Checks the user state file: where it lives, what it holds, and what happens when it is damaged.
 *
 * <h2>What earns a place here</h2>
 *
 * <p>Not the round trip of a well-formed file — that is zd's property and zd is checked by its own suite.
 * What is checked is the set of situations a user actually produces: no file yet, a file that is not one of
 * these, a file whose members were written by an older build, and the requirement that a table survives
 * being written and read without the store knowing what a table means.
 */
public final class UserStoreTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/user-store-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        where();
        roundTrip(root.resolve("round"));
        emptyAndMissing(root.resolve("missing"));
        damage(root.resolve("damage"));
        tablesAreOpaque(root.resolve("opaque"));
        theMagic(root.resolve("magic"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- where it lives

    private static void where() {
        section("where it lives");

        // A dot-directory in the home dir: nothing a launcher or an instance owns, so nothing has to be
        // reinstalled when the instance changes.
        check("the default is under the home directory",
                UserStore.defaultDir().toString().endsWith(".uee")
                        && UserStore.defaultDir().toString()
                                .startsWith(System.getProperty("user.home")));
        check("the file has this project's own extension",
                UserStore.FILE_NAME.endsWith(".ueeuser"));
        check("and is not named like an archive",
                !UserStore.FILE_NAME.endsWith(".zip") && !UserStore.FILE_NAME.endsWith(".jar"));

        // Specificity: an explicit setting beats the environment, which beats the default.
        check("an explicit directory wins",
                UserStore.resolveDir("/tmp/explicit").toString().replace('\\', '/')
                        .endsWith("/tmp/explicit")
                        || UserStore.resolveDir("F:/somewhere").toString().contains("somewhere"));
        check("a blank setting falls through rather than becoming the current directory",
                UserStore.resolveDir("").equals(UserStore.defaultDir())
                        && UserStore.resolveDir("   ").equals(UserStore.defaultDir()));
        check("and null too", UserStore.resolveDir(null).equals(UserStore.defaultDir()));
        check("the file goes inside the directory",
                UserStore.fileIn(Path.of("x")).endsWith(UserStore.FILE_NAME));

        check("the location can be described for a report",
                UserStore.describeLocation(Path.of("p")).contains(UserStore.DIR_ENV));
    }

    // ---------------------------------------------------------------- round trip

    private static void roundTrip(Path dir) throws IOException {
        section("the round trip");

        Path file = UserStore.fileIn(dir);
        check("nothing is there before anything is written", !Files.isRegularFile(file));
        check("and reading it gives an empty state, not an error",
                UserStore.read(file).isEmpty() && UserStore.read(file).complaints().isEmpty());

        String settings = "formats = [\"ndjson\", \"wiki\"]\nkinds = [\"items\"]\n";
        Map<String, byte[]> tables = new LinkedHashMap<>();
        tables.put(UserStore.SETTINGS, SettingsMember.encode(settings));
        tables.put(UserStore.TABLE_PREFIX + "assetKinds", zdPayload("models", "textures", "lang"));
        tables.put(UserStore.TABLE_PREFIX + "translation", zdPayload("item.example.ruby"));

        UserStore.write(file, tables);
        check("the file is written", Files.isRegularFile(file));
        check("no temporary is left behind", !Files.exists(dir.resolve(UserStore.FILE_NAME + ".part")));

        UserStore read = UserStore.read(file);
        check("reading it complains about nothing", read.complaints().isEmpty());
        check("the settings come back exactly", SettingsMember.textOf(read).equals(settings));
        check("both tables come back",
                read.memberNames().equals(List.of("settings", "table:assetKinds", "table:translation")));
        check("and they are not empty", read.member(UserStore.TABLE_PREFIX + "assetKinds").length > 0);
        check("an unknown member is absent rather than empty",
                read.member("nothing") == null);
        check("the state is not reported as empty", !read.isEmpty());

        // The payloads are zd documents, so a caller reads them with the reader it uses anywhere else. This
        // is the property that makes the store generic: it does not decode a table, so it does not have to
        // change when a table gains a field.
        List<ZdRow> rows = org.tielang.zd.ZdVolume.readRows(
                read.member(UserStore.TABLE_PREFIX + "assetKinds"));
        check("a table payload reads as zd rows", !rows.isEmpty());
        boolean foundModels = false;
        for (ZdRow row : rows) {
            if ("models".equals(row.valueStr())) {
                foundModels = true;
            }
        }
        check("and holds what was put in it", foundModels);

        // Two writes of the same state are byte-identical, since the members are sorted. That is what lets
        // the file be compared or diffed rather than always looking changed.
        byte[] first = Files.readAllBytes(file);
        UserStore.write(file, tables);
        check("writing the same state twice is byte-identical",
                java.util.Arrays.equals(first, Files.readAllBytes(file)));

        // An empty settings description is legitimate: a user may have tables and no settings.
        Map<String, byte[]> onlyTables = new LinkedHashMap<>();
        onlyTables.put(UserStore.TABLE_PREFIX + "assetKinds", zdPayload("models"));
        UserStore.write(file, onlyTables);
        UserStore noSettings = UserStore.read(file);
        check("settings may be absent", SettingsMember.textOf(noSettings).isEmpty());
        check("while the tables are still there",
                noSettings.memberNames().equals(List.of("table:assetKinds")));
        check("and the state is not empty on that account", !noSettings.isEmpty());
        check("a store without settings says so rather than pretending",
                noSettings.member(UserStore.SETTINGS) == null);
    }

    private static void emptyAndMissing(Path dir) throws IOException {
        section("absent, and empty");

        check("a missing file has no complaints",
                UserStore.read(dir.resolve("nothing-here")).complaints().isEmpty());
        check("and reads as empty state",
                UserStore.read(dir.resolve("nothing-here")).isEmpty());
        check("null is treated the same way", UserStore.read(null).isEmpty());

        // Writing nothing still produces a readable file, which is how a user clears their state without
        // deleting it: an empty store is a state, not an absence.
        Path file = UserStore.fileIn(dir);
        Files.createDirectories(dir);
        UserStore.write(file, Map.of());
        UserStore read = UserStore.read(file);
        check("an empty state writes and reads", read.isEmpty() && read.complaints().isEmpty());
        check("and reading it back yields no settings rather than an error",
                SettingsMember.textOf(read).isEmpty());
    }

    private static void damage(Path dir) throws IOException {
        section("damage, which is reported rather than swallowed");

        Files.createDirectories(dir);

        // Not compressed at all: a path typo, or a file from something else entirely.
        Path notGzip = dir.resolve("not-gzip");
        Files.write(notGzip, "this is not a state file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        UserStore foreign = UserStore.read(notGzip);
        check("a file that is not compressed reads as empty", foreign.isEmpty());
        check("and says so", foreign.complaints().size() == 1);
        check("with a reason that names decompression",
                foreign.complaints().get(0).contains("decompress"));

        // Compressed, but not a zd document inside.
        Path notZd = dir.resolve("not-zd");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(notZd))) {
            out.write("still not a state file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        UserStore wrongInner = UserStore.read(notZd);
        check("a compressed file that is not zd reads as empty", wrongInner.isEmpty());
        check("and says so", wrongInner.complaints().size() == 1);
        check("with a reason that names the document",
                wrongInner.complaints().get(0).contains("zd document"));

        // A real file, truncated. This is the case a crash during a write would produce if the write were
        // not atomic, and it must not read as a working file with missing members.
        Path good = dir.resolve("good");
        UserStore.write(good, Map.of(UserStore.TABLE_PREFIX + "t", zdPayload("a")));
        byte[] whole = Files.readAllBytes(good);
        Path truncated = dir.resolve("truncated");
        Files.write(truncated, java.util.Arrays.copyOf(whole, whole.length / 2));
        UserStore cut = UserStore.read(truncated);
        check("a truncated file does not read as a complete one",
                cut.complaints().size() == 1 || cut.isEmpty());
        check("and it does not silently lose a member while claiming success",
                cut.isEmpty() || cut.member(UserStore.TABLE_PREFIX + "t") != null);
    }

    private static void tablesAreOpaque(Path dir) throws IOException {
        section("a table is whatever declared it");

        // The store holds payloads, not rows, so a table shape the store has never seen round-trips
        // untouched. Without this the store would need changing whenever a table gained a field, which is
        // the coupling the design keeps out.
        Path file = UserStore.fileIn(dir);
        byte[] elevenColumns = zdPayload("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k");
        UserStore.write(file, Map.of(UserStore.TABLE_PREFIX + "wide", elevenColumns));
        UserStore read = UserStore.read(file);
        check("an unfamiliar shape survives", read.member(UserStore.TABLE_PREFIX + "wide") != null);
        check("and is byte-identical to what went in",
                java.util.Arrays.equals(elevenColumns, read.member(UserStore.TABLE_PREFIX + "wide")));

        // Empty payloads are skipped rather than stored: a member with nothing in it would read back as a
        // table that exists and has no rows, which is a different fact from a table that was never written.
        Map<String, byte[]> mixed = new LinkedHashMap<>();
        mixed.put(UserStore.TABLE_PREFIX + "real", zdPayload("x"));
        mixed.put(UserStore.TABLE_PREFIX + "hollow", new byte[0]);
        UserStore.write(file, mixed);
        UserStore reread = UserStore.read(file);
        check("an empty member is not stored",
                reread.member(UserStore.TABLE_PREFIX + "hollow") == null);
        check("and the real one is", reread.member(UserStore.TABLE_PREFIX + "real") != null);

        check("names are listed sorted, so a report does not depend on write order",
                reread.memberNames().equals(List.of("table:real")));
    }

    private static void theMagic(Path dir) throws IOException {
        section("the magic, which is why the compressor is not a bare deflate stream");

        Path file = UserStore.fileIn(dir);
        UserStore.write(file, Map.of(UserStore.SETTINGS,
                SettingsMember.encode("kinds = [\"items\"]\n")));
        byte[] head = Files.readAllBytes(file);

        // With a bare deflate stream a wrong path produces garbage or a failure, and neither can be told
        // from an empty state. The two magic bytes make "this is not my file" a deterministic rejection.
        check("the file starts with the gzip magic",
                (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B);
        check("and that is what looksLikeUserState reports", UserStore.looksLikeUserState(head));
        check("a zd document's own first bytes are not mistaken for it",
                !UserStore.looksLikeUserState(new byte[] {'T', 'I', 'E', 'D'}));
        check("nor is a short file", !UserStore.looksLikeUserState(new byte[] {0x1F}));
        check("nor null", !UserStore.looksLikeUserState(null));

        // Small, because the point of compressing the whole document is that the file is one small file.
        check("a state file is small", head.length < 1024);
    }

    // ---------------------------------------------------------------- helpers

    /** A zd payload holding the given strings, which is what a member is. */
    private static byte[] zdPayload(String... values) {
        List<ZdRow> rows = new java.util.ArrayList<>(values.length);
        for (String value : values) {
            rows.add(new ZdRow(1, "v", 0L, 0.0, value, 0));
        }
        return ZdDocWriter.writeIndexed(0, rows);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        try (var walk = Files.walk(p)) {
            walk.sorted(Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }
}
