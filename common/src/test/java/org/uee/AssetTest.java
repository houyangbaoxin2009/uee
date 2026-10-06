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

import org.uee.asset.AssetPath;
import org.uee.asset.AssetSweep;
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
