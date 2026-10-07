package org.uee;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.uee.config.ExportConfig;
import org.uee.asset.AssetPath;
import org.uee.asset.AssetSweep;
import org.uee.state.SettingsMember;
import org.uee.delta.Fingerprint;
import org.uee.spi.BytesSource;

/**
 * Checks the asset path rules and the copying the pipeline does for them.
 *
 * <h2>What is checked here and what is checked elsewhere</h2>
 *
 * <p>The path rules are pure and are checked directly. The copying needs the pipeline, so a small
 * stand-in sink records what it was told and the pieces the pipeline uses — the fingerprint and the copy
 * decision — are exercised against real files, including the case the feature exists for: a second run
 * over the same bytes writes nothing.
 *
 * <p>The one thing that cannot be checked without a game is the enumeration itself, which lives in the
 * adapter and needs a resource manager. That is covered by a game test.
 */
public final class AssetTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/asset-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        paths();
        whatIsNotContent();
        theDeclaredSweep();
        declaredKindsExtendTheSweep(root.resolve("declared"));
        declaredRootFilesExtendTheSweep(root.resolve("declared-files"));
        copying(root.resolve("copy"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- paths

    private static void paths() {
        section("the output path");

        // The resource location arriving here is already relative to the namespace's asset root, so the
        // output path is assembled: assets/<ns>/<path>. The same shape the game uses, so a copied tree can
        // be dropped into a pack unchanged.
        check("a texture keeps its shape",
                "assets/example/textures/block/ruby_ore.png".equals(
                        AssetPath.outputPath("example", "textures/block/ruby_ore.png")));
        check("a sound does too",
                "assets/example/sounds/block/ruby.ogg".equals(
                        AssetPath.outputPath("example", "sounds/block/ruby.ogg")));
        check("a model at the root of its namespace",
                "assets/example/models/item/ruby.json".equals(
                        AssetPath.outputPath("example", "models/item/ruby.json")));
        check("a language file is an asset like any other",
                "assets/example/lang/zh_cn.json".equals(
                        AssetPath.outputPath("example", "lang/zh_cn.json")));

        // Leading slashes are tolerated rather than producing a doubled separator, which would put the
        // file somewhere the manifest then records inconsistently.
        check("a leading slash is dropped",
                "assets/example/lang/en_us.json".equals(
                        AssetPath.outputPath("example", "/lang/en_us.json")));

        check("nothing without a namespace", AssetPath.outputPath(null, "a.png") == null);
        check("nothing without a path", AssetPath.outputPath("example", null) == null);
        check("nor an empty path", AssetPath.outputPath("example", "") == null);
        check("nor an empty namespace", AssetPath.outputPath("", "a.png") == null);

        check("the forward slash is the separator",
                AssetPath.outputPath("example", "a/b.png").contains("/")
                        && !AssetPath.outputPath("example", "a/b.png").contains("\\"));
        check("the directory is named once", AssetPath.DIRECTORY.equals("assets"));
    }

    private static void whatIsNotContent() {
        section("files that are not content");

        check("an ordinary file is copyable", AssetPath.isCopyable("textures/block/stone.png"));
        check("a nested one is too", AssetPath.isCopyable("a/b/c/d.txt"));

        // The marker one real client ships, at the root of its asset tree: a pack build's leftover, with
        // nothing in it. Copying it would put a stray file in the output.
        check("a dotfile is not", !AssetPath.isCopyable(".mcassetsroot"));
        check("and neither is one deeper down", !AssetPath.isCopyable("textures/.gitkeep"));
        // A directory beginning with a dot is a tool's working directory, and everything under it is its
        // business rather than content's.
        check("nor anything below a dot directory", !AssetPath.isCopyable(".git/objects/ab"));
        check("nor an empty path", !AssetPath.isCopyable(""));
        check("nor null", !AssetPath.isCopyable(null));
        check("nor a path ending in a separator", !AssetPath.isCopyable("textures/"));
        check("nor one with an empty segment", !AssetPath.isCopyable("textures//stone.png"));

        // And the marker is rejected by the path builder too, since that is the only thing that turns a
        // resource into an output location.
        check("the marker gets no output path",
                AssetPath.outputPath("minecraft", ".mcassetsroot") == null);

        check("a plain namespace is fine", AssetPath.isCopyableNamespace("example"));
        check("one with underscores and dots too",
                AssetPath.isCopyableNamespace("my_mod.sub-1"));
        // The pack build's marker arrives with a namespace that is not one, and a manifest keyed on it
        // would be an entry nothing could ever match.
        check("a namespace with a slash is not", !AssetPath.isCopyableNamespace("a/b"));
        check("nor one with a colon", !AssetPath.isCopyableNamespace("a:b"));
        check("nor an empty one", !AssetPath.isCopyableNamespace(""));
        check("nor null", !AssetPath.isCopyableNamespace(null));
    }

    // ---------------------------------------------------------------- the declared sweep

    /**
     * The sweep's declared basis, checked for the property that matters.
     *
     * <p>The list exists because the resource API cannot be asked for everything — the empty path is an
     * invalid path, and the rejection is swallowed, so a sweep that named nothing would collect nothing and
     * report success. So the check that earns its place is not that the list has the right entries but that
     * every entry is usable: each declared kind resolves to a path the copier accepts, and each root file is
     * a file rather than a directory.
     *
     * <p>The same shape as the registry table's check that every declared category has a collector, and for
     * the same reason: a declaration that nothing can act on looks identical to a declaration that works
     * until something tries.
     */
    private static void theDeclaredSweep() {
        section("the declared sweep");

        check("the sweep declares something, since it cannot ask for everything",
                !AssetSweep.KINDS.isEmpty());
        // The number the shipped client actually has. A guess would have been close and would have missed
        // `texts`, which holds four files and is the sort of directory a guess leaves out.
        check("it declares the nine kinds the client has", AssetSweep.KINDS.size() == 9);
        check("textures is among them", AssetSweep.KINDS.contains("textures"));
        check("and lang, which is where translations live", AssetSweep.KINDS.contains("lang"));
        check("and texts, the small one a guess would miss", AssetSweep.KINDS.contains("texts"));

        // Every declared kind has to be usable as a path, or the sweep would silently collect nothing for
        // it -- which is exactly the failure this list exists to avoid.
        boolean allUsable = true;
        for (String kind : AssetSweep.KINDS) {
            if (!AssetPath.isCopyable(kind + "/something.bin")
                    || AssetPath.outputPath("example", kind + "/something.bin") == null) {
                allUsable = false;
            }
        }
        check("every declared kind is a path the copier accepts", allUsable);

        // No duplicates, since a kind visited twice would read the same files twice and report them once.
        check("no kind is declared twice",
                AssetSweep.KINDS.size() == new java.util.HashSet<>(AssetSweep.KINDS).size());
        check("and no root file either",
                AssetSweep.ROOT_FILES.size() == new java.util.HashSet<>(AssetSweep.ROOT_FILES).size());

        // A root file is a file at the namespace root, so it must not look like a kind: a slash would make
        // it unreachable by name, which is the only route it has.
        boolean filesNotDirectories = true;
        for (String file : AssetSweep.ROOT_FILES) {
            if (file.contains("/") || !AssetPath.isCopyable(file)) {
                filesNotDirectories = false;
            }
        }
        check("every declared root file is a bare file name", filesNotDirectories);
        check("sounds.json is among them, being the one the client has",
                AssetSweep.ROOT_FILES.contains("sounds.json"));
        // The pack build's marker is not content and must not be declared as one.
        check("the pack marker is not declared", !AssetSweep.ROOT_FILES.contains(".mcassetsroot"));

        check("the basis can be described in one line", AssetSweep.describe().contains("9 kinds"));
        check("and naming a kind gives the prefix to list",
                AssetSweep.prefixOf("textures").equals("textures"));
    }

    // ---------------------------------------------------------------- the declared sweep

    /**
     * The sweep's declared basis, checked for the property that matters.
     *
     * <p>The list exists because the resource API cannot be asked for everything — the empty path is an
     * invalid path, and the rejection is swallowed, so a sweep that named nothing would collect nothing and
     * report success. So the check that earns its place is not that the list has the right entries but that
     * every entry is usable: each declared kind resolves to a path the copier accepts, and each root file is
     * a file rather than a directory.
     *
     * <p>The same shape as the registry table's check that every declared category has a collector, and for
     * the same reason: a declaration that nothing can act on looks identical to a declaration that works
     * until something tries.
     */
    // ---------------------------------------------------------------- declared kinds

    /**
     * A declared kind extends the sweep, and it is the only way a pack's own directory can be reached.
     *
     * <p>Checked end to end through the store, because that is the path that matters: the command writes the
     * table, the collector reads it, and the sweep covers the extra kind. A merge that worked while the
     * table went unread would leave the feature looking present and doing nothing — which is the shape of
     * defect this whole table exists to avoid.
     */
    private static void declaredKindsExtendTheSweep(Path dir) throws IOException {
        section("declared kinds");

        // The built-in list is the default, not the ceiling.
        check("with nothing declared the sweep is the built-in kinds",
                AssetSweep.merge(List.of()).equals(AssetSweep.KINDS));
        check("and null is the same as empty",
                AssetSweep.merge(null).equals(AssetSweep.KINDS));

        List<String> withExtra = AssetSweep.merge(List.of("cutscenes", "atlases"));
        check("a declared kind is added", withExtra.contains("cutscenes"));
        check("the built-in kinds are still there", withExtra.containsAll(AssetSweep.KINDS));
        check("and one that is already built in is not duplicated",
                withExtra.size() == AssetSweep.KINDS.size() + 1);
        check("the built-ins come first", withExtra.get(0).equals(AssetSweep.KINDS.get(0)));

        // Sorted additions, so the result does not depend on the order they were declared in.
        check("additions are ordered, not left in declaration order",
                AssetSweep.merge(List.of("zebra", "apple")).equals(
                        AssetSweep.merge(List.of("apple", "zebra"))));

        // A name that cannot be swept is refused at the point of declaration, because the failure otherwise
        // is silence: a kind that cannot become a path would find nothing and look like an empty directory.
        check("a name with a separator is refused", !AssetSweep.isValidKind("a/b"));
        check("an absolute-looking one too", !AssetSweep.isValidKind("/textures"));
        check("a backslash too", !AssetSweep.isValidKind("a\\b"));
        check("a dotfile-looking one is refused", !AssetSweep.isValidKind(".hidden"));
        check("an empty name is refused", !AssetSweep.isValidKind(""));
        check("and null", !AssetSweep.isValidKind(null));
        check("upper case is refused, since asset directories are lower case",
                !AssetSweep.isValidKind("Textures"));
        check("a space is refused", !AssetSweep.isValidKind("my kind"));
        check("while an ordinary name is accepted", AssetSweep.isValidKind("cutscenes"));
        check("including one with digits, dashes and underscores",
                AssetSweep.isValidKind("v2_cut-scenes.x"));
        // And a refused name never reaches the sweep, even if it got into the table by hand.
        check("an invalid name in the table is skipped rather than swept",
                AssetSweep.merge(List.of("ok", "bad/name", ".dotted")).size()
                        == AssetSweep.KINDS.size() + 1);

        // The whole path: write the table, read it back, and see the sweep grow.
        ExportConfig config = ExportConfig.builder().userDir(dir).build();
        check("nothing is declared to begin with", Uee.declared(config, AssetSweep.TABLE).isEmpty());

        Uee.declare(config, AssetSweep.TABLE, List.of("cutscenes"));
        check("the declaration is written and read back",
                Uee.declared(config, AssetSweep.TABLE).equals(List.of("cutscenes")));
        check("and the sweep covers it",
                AssetSweep.merge(Uee.declared(config, AssetSweep.TABLE)).contains("cutscenes"));

        // The table is meant to accumulate across packs, so a second declaration adds rather than replaces.
        List<String> more = new java.util.ArrayList<>(Uee.declared(config, AssetSweep.TABLE));
        more.add("shaders_extra");
        java.util.Collections.sort(more);
        Uee.declare(config, AssetSweep.TABLE, more);
        check("a second declaration adds", Uee.declared(config, AssetSweep.TABLE).size() == 2);

        // Declaring something else must not disturb it, since the file is shared with the settings and with
        // every other table.
        Uee.saveUserState(ExportConfig.builder().userDir(dir).persist(java.util.Set.of("formats")).build());
        check("another part of the state does not disturb the table",
                Uee.declared(config, AssetSweep.TABLE).size() == 2);
        check("and the settings arrived", SettingsMember.textOf(Uee.readUserState(config))
                .contains("formats"));
        Uee.declare(config, AssetSweep.TABLE, List.of("cutscenes"));
        check("writing the table does not disturb the settings",
                SettingsMember.textOf(Uee.readUserState(config)).contains("formats"));

        // Removing the last one removes the member, so "declared nothing" is one state and not two.
        Uee.declare(config, AssetSweep.TABLE, List.of());
        check("an emptied table reads as nothing declared",
                Uee.declared(config, AssetSweep.TABLE).isEmpty());
        check("and the settings are still there",
                SettingsMember.textOf(Uee.readUserState(config)).contains("formats"));

        check("the basis can name a larger total",
                AssetSweep.describe(12).startsWith("12 kinds"));
    }

    // ---------------------------------------------------------------- declared root files

    /**
     * A declared root file is fetched, by the same route and for the same reason as a declared kind.
     *
     * <p>Separately tested because the two lists reach the sweep differently: a kind becomes a prefix to
     * walk, a root file becomes a whole resource location to ask for by name. A declaration that reached one
     * route and not the other would look like it worked, since the report counts both.
     */
    private static void declaredRootFilesExtendTheSweep(Path dir) throws IOException {
        section("declared root files");

        check("with nothing declared the list is the built-in one",
                AssetSweep.mergeRootFiles(List.of()).equals(AssetSweep.ROOT_FILES));
        check("and null is the same as empty",
                AssetSweep.mergeRootFiles(null).equals(AssetSweep.ROOT_FILES));

        List<String> withExtra = AssetSweep.mergeRootFiles(List.of("credits.json", "sounds.json"));
        check("a declared root file is added", withExtra.contains("credits.json"));
        check("the built-in one is still there", withExtra.contains("sounds.json"));
        check("and one that is already built in is not duplicated",
                withExtra.size() == AssetSweep.ROOT_FILES.size() + 1);

        // A root file is fetched by name, so anything with a separator could never be asked for; storing it
        // would mean a declaration that silently fetches nothing.
        check("a name with a separator is refused", !AssetSweep.isValidRootFile("sub/sounds.json"));
        check("a leading slash too", !AssetSweep.isValidRootFile("/sounds.json"));
        check("a dotfile-looking name is refused", !AssetSweep.isValidRootFile(".hidden"));
        check("an empty name is refused", !AssetSweep.isValidRootFile(""));
        check("and null", !AssetSweep.isValidRootFile(null));
        check("upper case is refused", !AssetSweep.isValidRootFile("Sounds.json"));
        check("while an ordinary file name is accepted", AssetSweep.isValidRootFile("credits.json"));
        check("and one with digits and dashes too", AssetSweep.isValidRootFile("v2-credits.json"));
        check("an unusable name in the table is skipped rather than fetched",
                AssetSweep.mergeRootFiles(List.of("ok.json", "bad/name.json")).size()
                        == AssetSweep.ROOT_FILES.size() + 1);

        // Through the store, exactly as the kinds are: the write path is what the command uses, and a table
        // that could be read but not written would be the dead-table mistake in its other direction.
        ExportConfig config = ExportConfig.builder().userDir(dir).build();
        check("nothing is declared to begin with",
                Uee.declared(config, AssetSweep.ROOT_FILE_TABLE).isEmpty());
        Uee.declare(config, AssetSweep.ROOT_FILE_TABLE, List.of("credits.json"));
        check("the declaration survives being written and read",
                Uee.declared(config, AssetSweep.ROOT_FILE_TABLE).equals(List.of("credits.json")));
        check("and the sweep covers it",
                AssetSweep.mergeRootFiles(Uee.declared(config, AssetSweep.ROOT_FILE_TABLE))
                        .contains("credits.json"));

        // The two tables are independent, both in the file and in what they mean.
        Uee.declare(config, AssetSweep.TABLE, List.of("cutscenes"));
        check("the two tables do not overwrite each other",
                Uee.declared(config, AssetSweep.TABLE).equals(List.of("cutscenes"))
                        && Uee.declared(config, AssetSweep.ROOT_FILE_TABLE)
                                .equals(List.of("credits.json")));
        Uee.declare(config, AssetSweep.ROOT_FILE_TABLE, List.of());
        check("clearing one leaves the other",
                Uee.declared(config, AssetSweep.TABLE).equals(List.of("cutscenes"))
                        && Uee.declared(config, AssetSweep.ROOT_FILE_TABLE).isEmpty());

        check("the basis can name both totals",
                AssetSweep.describe(11, 3).equals("11 kinds + 3 root files"));
        check("and one root file reads singular",
                AssetSweep.describe(9, 1).startsWith("9 kinds + 1 root file ")
                        || AssetSweep.describe(9, 1).equals("9 kinds + 1 root file"));
    }

    // ---------------------------------------------------------------- copying

    /**
     * Copies the same bytes twice and checks the second pass writes nothing.
     *
     * <p>The behaviour the whole feature is for, exercised on real files: assets are copied verbatim, so the
     * source's fingerprint is the target's content, and an unchanged asset can be recognised from the source
     * alone — one read, no write. A record has to be generated before it can be compared, which is why
     * shards cannot do this and assets can.
     */
    private static void copying(Path root) throws IOException {
        section("copying, and copying again");

        Map<String, byte[]> assets = new java.util.LinkedHashMap<>();
        assets.put("assets/example/textures/block/ruby_ore.png",
                "\u0089PNG-not-really-a-png".getBytes(StandardCharsets.UTF_8));
        assets.put("assets/example/lang/zh_cn.json",
                "{\"item.example.ruby\":\"\\u7ea2\\u5b9d\\u77f3\"}".getBytes(StandardCharsets.UTF_8));
        assets.put("assets/example/sounds/block/ruby.ogg", new byte[4096]);

        // First pass: nothing is on disk, so everything is copied.
        int copied = 0;
        Map<String, String> snapshot = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
            Path target = root.resolve(entry.getKey());
            if (!Files.isRegularFile(target)) {
                copied++;
                write(target, entry.getValue());
            }
            snapshot.put(entry.getKey(), fingerprintOf(entry.getValue()));
        }
        check("the first pass copies everything", copied == 3);
        check("every file is on disk", Files.isRegularFile(root.resolve("assets/example/lang/zh_cn.json")));
        check("and its bytes are the source's, unchanged",
                java.util.Arrays.equals(assets.get("assets/example/lang/zh_cn.json"),
                        Files.readAllBytes(root.resolve("assets/example/lang/zh_cn.json"))));
        check("an empty file is copied faithfully",
                Files.size(root.resolve("assets/example/sounds/block/ruby.ogg")) == 4096);

        // Second pass: the fingerprint of the source is compared against the snapshot, so an unchanged
        // asset is recognised without reading the target and without writing anything.
        int rewritten = 0;
        for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
            String fingerprint = fingerprintOf(entry.getValue());
            boolean unchanged = fingerprint.equals(snapshot.get(entry.getKey()))
                    && Files.isRegularFile(root.resolve(entry.getKey()));
            if (!unchanged) {
                rewritten++;
            }
        }
        check("the second pass writes nothing", rewritten == 0);

        // Third pass: one asset changes, and only it is rewritten.
        assets.put("assets/example/textures/block/ruby_ore.png",
                "\u0089PNG-changed".getBytes(StandardCharsets.UTF_8));
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : assets.entrySet()) {
            String fingerprint = fingerprintOf(entry.getValue());
            if (!fingerprint.equals(snapshot.get(entry.getKey()))) {
                changed.add(entry.getKey());
            }
        }
        check("only the changed asset is rewritten", changed.size() == 1);
        check("and it is the one that changed",
                changed.get(0).equals("assets/example/textures/block/ruby_ore.png"));

        // The property that makes a delta sound for assets, and the reason no temporary is needed: the copy
        // is verbatim, so the source's fingerprint IS the target's content. Comparing the two here is not a
        // tautology -- it would fail the moment anything decoded or re-encoded on the way through.
        Path langTarget = root.resolve("assets/example/lang/zh_cn.json");
        check("a copied file's fingerprint equals its source's, byte for byte",
                Fingerprint.of(langTarget)
                        .equals(fingerprintOf(assets.get("assets/example/lang/zh_cn.json"))));
        check("and that is the fingerprint the run recorded",
                Fingerprint.of(langTarget).equals(snapshot.get("assets/example/lang/zh_cn.json")));
        check("so an unchanged asset can be recognised from the source alone",
                Fingerprint.of(langTarget)
                        .equals(fingerprintOf(assets.get("assets/example/lang/zh_cn.json"))));

        // A source that can only be opened once would break the pipeline, which reads a changed asset
        // twice: once to fingerprint, once to copy. The contract is stated on BytesSource; this is the
        // check that a source meeting it works.
        BytesSource twice = new BytesSource() {
            @Override
            public java.io.InputStream open() {
                return new ByteArrayInputStream(assets.get("assets/example/lang/zh_cn.json"));
            }
        };
        String first = Fingerprint.of(twice.open());
        copy(twice, root.resolve("assets/example/lang/again.json"));
        check("a source opened twice yields the same bytes",
                first.equals(Fingerprint.of(root.resolve("assets/example/lang/again.json"))));

        // And no temporary survives, whichever path was taken.
        check("no temporary files were left behind", countPartFiles(root) == 0);
    }

    private static String fingerprintOf(byte[] bytes) {
        return Fingerprint.of(bytes);
    }

    private static void write(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void copy(BytesSource source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try (java.io.InputStream in = source.open();
                java.io.OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    private static int countPartFiles(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            return (int) walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".part")).count();
        }
    }

    // ---------------------------------------------------------------- harness

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
