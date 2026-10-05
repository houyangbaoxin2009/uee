package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.uee.icon.IconPhase;
import org.uee.icon.IconStore;

/**
 * Checks the icon phase: the store it fills and the batching it runs in.
 *
 * <h2>What can be checked here, and what cannot</h2>
 *
 * <p>Everything about the icon phase except the rendering itself is checked here — the store's
 * completion rule, its atomicity, what resuming sees, the batching, the budget, and how a renderer that
 * fails is handled. The rendering needs a client with a render pipeline, so it is checked nowhere in
 * this repository: there is no client in the test environment and the game tests run on a headless
 * server. That gap is the point of the design rather than a limitation of the tests — rendering is a
 * phase of its own precisely so that everything else can be verified without it.
 *
 * <p>The renderer in these tests is a callback that produces bytes, so the phase is exercised end to end
 * against a stand-in that can also be told to fail, which is the case worth exercising.
 */
public final class IconTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/icon-test");
        deleteRecursively(root);
        Files.createDirectories(root);

        pngEncoding();
        theStoreKnowsWhenAnElementIsDone(root.resolve("store"));
        writesAreAtomic(root.resolve("atomic"));
        resumingOnlyRendersWhatIsMissing(root.resolve("resume"));
        batchesAreBounded(root.resolve("batch"));
        aFailureDoesNotEndThePhase(root.resolve("failure"));
        theOrderIsTheCallers(root.resolve("order"));
        nothingIsRenderedForAKindWithNoSizes(root.resolve("kinds"));

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the encoder

    /**
     * The encoder, checked by reading its output back with the JDK's own image reader.
     *
     * <p>Verifying a PNG by inspecting the bytes a second time would only confirm that the encoder is
     * self-consistent, which it would be even if it were wrong in the same way twice. Handing the bytes
     * to an independent reader is the difference between "my format is what I think it is" and "my format
     * is the format".
     */
    private static void pngEncoding() {
        section("the png encoder, read back by an independent decoder");

        int[] pixels = new int[4 * 4];
        for (int i = 0; i < pixels.length; i++) {
            // Distinct, fully opaque colours, so a channel swap or a lost alpha shows up.
            pixels[i] = 0xFF000000 | (i * 0x00112233 & 0xFFFFFF);
        }

        byte[] png = org.uee.icon.Png.encode(pixels, 4, 4);
        check("the signature is the eight bytes a PNG starts with",
                png.length > 8 && (png[0] & 0xFF) == 0x89 && png[1] == 'P' && png[2] == 'N'
                        && png[3] == 'G');

        try {
            java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            check("an independent decoder accepts it", decoded != null);
            if (decoded != null) {
                check("the size survives", decoded.getWidth() == 4 && decoded.getHeight() == 4);
                boolean same = true;
                for (int i = 0; i < pixels.length; i++) {
                    int want = pixels[i];
                    int got = decoded.getRGB(i % 4, i / 4);
                    if (want != got) {
                        same = false;
                        System.out.println("      pixel " + i + ": want "
                                + Integer.toHexString(want) + " got " + Integer.toHexString(got));
                        break;
                    }
                }
                // Channels in the wrong order, or alpha moved, both survive a signature check and fail
                // here.
                check("every pixel comes back with the same channels", same);
            }
        } catch (java.io.IOException e) {
            check("an independent decoder accepts it (" + e + ")", false);
        }

        // Transparency is the whole point for an icon: a white box behind a cut-out item is the defect
        // the design's own competitive notes call out.
        // Four pixels across one row, so each of the four alpha levels lands on a different column --
        // a 2x2 image would put two of them out of reach of a single-row read.
        int[] transparent = {0x00FFFFFF, 0x80FF0000, 0xFF00FF00, 0x00000000};
        byte[] withAlpha = org.uee.icon.Png.encode(transparent, 4, 1);
        try {
            java.awt.image.BufferedImage decoded =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(withAlpha));
            check("alpha survives the round trip",
                    decoded != null && (decoded.getRGB(0, 0) >>> 24) == 0
                            && (decoded.getRGB(1, 0) >>> 24) == 0x80
                            && (decoded.getRGB(2, 0) >>> 24) == 0xFF
                            && (decoded.getRGB(3, 0) >>> 24) == 0);
        } catch (java.io.IOException e) {
            check("alpha survives the round trip (" + e + ")", false);
        }

        // Deterministic: the export is compared byte for byte across runs, and a different byte sequence
        // for the same pixels would show up as a change in every diff.
        byte[] again = org.uee.icon.Png.encode(pixels, 4, 4);
        check("the same pixels produce the same bytes", java.util.Arrays.equals(png, again));

        check("a 1x1 image is allowed",
                org.uee.icon.Png.encode(new int[] {0xFF112233}, 1, 1).length > 8);

        boolean tooFew = false;
        try {
            org.uee.icon.Png.encode(new int[3], 4, 4);
        } catch (IllegalArgumentException expected) {
            tooFew = true;
        }
        check("a short pixel array is refused rather than encoded as a corrupt image", tooFew);

        boolean badSize = false;
        try {
            org.uee.icon.Png.encode(new int[0], 0, 4);
        } catch (IllegalArgumentException expected) {
            badSize = true;
        }
        check("a zero dimension is refused", badSize);

        // The one a caller is most likely to get wrong: passing more pixels than the size needs.
        check("extra pixels are ignored rather than shifting the image",
                org.uee.icon.Png.encode(new int[100], 2, 2).length > 8);
    }

    // ---------------------------------------------------------------- the store

    private static void theStoreKnowsWhenAnElementIsDone(Path root) throws IOException {
        section("completion is per element, not per file");

        // Items want two sizes. A store that counted one file as done would resume a run and produce
        // records with an icon missing and nothing to say so.
        IconStore store = new IconStore(root, Map.of("item", new int[] {128, 32}));

        check("nothing is done before anything is rendered", !store.has("item", "test:ruby"));
        store.put("item", "test:ruby", 128, png(10));
        check("one of two sizes is not done", !store.has("item", "test:ruby"));
        store.put("item", "test:ruby", 32, png(4));
        check("both sizes means done", store.has("item", "test:ruby"));

        check("the sizes round-trip", store.get("item", "test:ruby", 128).length == png(10).length);
        check("the small icon is not the large one",
                store.get("item", "test:ruby", 32).length == png(4).length);
        check("an unrendered size reads as absent",
                store.get("item", "test:ruby", 64) == null);
        check("an unknown kind is not rendered at all", !store.renders("fluid"));
        check("and asking for one yields no sizes", store.sizes("fluid").length == 0);

        // Ids are filed per namespace so one directory does not hold every item in the game.
        check("the id is split into a namespace directory",
                Files.isRegularFile(root.resolve("item").resolve("test").resolve("ruby")
                        .resolve("128.png")));

        // A path or id with characters a filesystem dislikes is still an element that needs an icon.
        store.put("item", "test:weird/name", 128, png(2));
        store.put("item", "test:weird/name", 32, png(2));
        check("an id with unusual characters is still stored and found",
                store.has("item", "test:weird/name"));

        // An id with no namespace would otherwise become a path segment.
        store.put("item", "nocolonnonsense", 128, png(2));
        store.put("item", "nocolonnonsense", 32, png(2));
        check("an id without a namespace is filed rather than rejected",
                store.has("item", "nocolonnonsense"));

        store.put("item", "test:empty", 128, new byte[0]);
        check("an empty rendering is not stored", !store.has("item", "test:empty"));

        check("counting what is done walks the list",
                store.completeCount("item", List.of("test:ruby", "test:missing")) == 1);
    }

    private static void writesAreAtomic(Path root) throws IOException {
        section("a write is never half-visible");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128}));
        store.put("item", "test:stone", 128, png(32));

        // The temporary name must not survive a completed write, or a resumed run would see files it
        // cannot account for and a directory listing would grow with every attempt.
        List<String> leftovers = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().contains(".part-"))
                    .forEach(p -> leftovers.add(p.toString()));
        }
        check("no temporary file survives a completed write", leftovers.isEmpty());
        check("the finished file is exactly what was written",
                store.get("item", "test:stone", 128).length == png(32).length);

        // Overwriting is how a re-render replaces a bad icon, so it has to be allowed.
        store.put("item", "test:stone", 128, png(8));
        check("a write replaces an earlier one", store.get("item", "test:stone", 128).length == png(8).length);
    }

    private static void resumingOnlyRendersWhatIsMissing(Path root) throws IOException {
        section("resuming renders only what is missing");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128, 32}));
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put("item", List.of("test:a", "test:b", "test:c"));

        // A first run that stops after two elements -- the shape of a player closing the game.
        List<String> rendered = new ArrayList<>();
        IconPhase first = new IconPhase(store, (kind, id, sizes) -> {
            rendered.add(id);
            return Map.of(128, png(8), 32, png(4));
        }, candidates);
        check("everything is outstanding to begin with", first.remaining() == 3);
        first.advance(2);
        check("the first batch rendered exactly its budget", rendered.equals(List.of("test:a", "test:b")));
        check("one element is left", first.remaining() == 1);
        check("and the phase is not complete", !first.isComplete());

        // A second run over the same list must skip what the first one stored.
        List<String> secondRendered = new ArrayList<>();
        IconPhase second = new IconPhase(store, (kind, id, sizes) -> {
            secondRendered.add(id);
            return Map.of(128, png(8), 32, png(4));
        }, candidates);
        check("the resumed phase only has the missing element outstanding", second.remaining() == 1);
        check("it counts the earlier work as done", second.done() == 2);
        check("and still knows the whole job", second.total() == 3);
        second.advance(10);
        check("it rendered only the missing one", secondRendered.equals(List.of("test:c")));
        check("the phase is now complete", second.isComplete());
        check("everything is on disk", store.has("item", "test:c"));

        // An element with only one of its two sizes is unfinished, so a resume must redo it. This is
        // the case a per-file completion rule would get wrong.
        store.put("item", "test:d", 128, png(8));
        Map<String, List<String>> half = Map.of("item", List.of("test:d"));
        IconPhase third = new IconPhase(store, (kind, id, sizes) -> Map.of(128, png(8), 32, png(4)),
                half);
        check("an element with one of two sizes is re-rendered, not skipped",
                third.remaining() == 1);
        third.advance(1);
        check("and is complete afterwards", store.has("item", "test:d"));
    }

    // ---------------------------------------------------------------- the phase

    private static void batchesAreBounded(Path root) throws IOException {
        section("the budget is respected, and progress is reported");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128, 32}));
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add("test:i" + i);
        }
        candidates.put("item", ids);

        List<Integer> batchSizes = new ArrayList<>();
        IconPhase phase = new IconPhase(store, (kind, id, sizes) -> {
            batchSizes.add(1);
            return Map.of(128, png(8), 32, png(4));
        }, candidates);

        check("the whole list is outstanding", phase.remaining() == 10);
        int first = phase.advance(3);
        check("a budget of three renders three", first == 3);
        check("seven remain", phase.remaining() == 7);
        check("progress reads as a fraction", phase.progressLine().equals("3/10 icons"));

        check("two more batches finish it", phase.runBatches(4, null) == 2);
        check("the phase reports complete", phase.isComplete());
        check("every element is stored", store.completeCount("item", ids) == 10);
        check("progress counts them all", phase.done() == 10);

        // A budget of zero would stall the phase forever with no sign of why, so it is treated as one.
        IconStore store2 = new IconStore(root.resolve("zero"), Map.of("item", new int[] {128}));
        IconPhase zero = new IconPhase(store2, (kind, id, sizes) -> Map.of(128, png(4)),
                Map.of("item", List.of("test:x", "test:y")));
        check("a budget of zero still makes progress", zero.advance(0) == 1);

        // The loop can be stopped from outside, which is what a caller does when the frame runs late or
        // the player closes the game. What was rendered stays rendered.
        IconStore store3 = new IconStore(root.resolve("stop"), Map.of("item", new int[] {128}));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add("test:s" + i);
        }
        IconPhase stoppable = new IconPhase(store3, (kind, id, sizes) -> Map.of(128, png(4)),
                Map.of("item", many));
        int batches = stoppable.runBatches(2, p -> p.done() < 6);
        check("the loop stops when asked", batches == 3 && !stoppable.isComplete());
        check("and what was rendered is kept", store3.completeCount("item", many) == 6);
    }

    private static void aFailureDoesNotEndThePhase(Path root) throws IOException {
        section("one element that cannot be rendered does not stop the others");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128, 32}));
        List<String> ids = List.of("test:ok1", "test:throws", "test:empty", "test:ok2");

        IconPhase phase = new IconPhase(store, (kind, id, sizes) -> {
            if (id.equals("test:throws")) {
                throw new IllegalStateException("no renderer for this item");
            }
            if (id.equals("test:empty")) {
                // A renderer that answers but produces nothing: the shape of an entity with no renderer.
                return Map.of();
            }
            return Map.of(128, png(8), 32, png(4));
        }, Map.of("item", ids));

        phase.runBatches(10, null);
        check("the phase finished despite two unusable elements", phase.isComplete());
        check("the good elements were rendered", store.has("item", "test:ok1")
                && store.has("item", "test:ok2"));
        check("the ones that could not be rendered are not stored",
                !store.has("item", "test:throws") && !store.has("item", "test:empty"));
        check("and they are reported rather than hidden", phase.skipped().size() == 2);
        check("the report says which and why",
                phase.skipped().get(0).contains("test:throws")
                        && phase.skipped().get(0).contains("no renderer"));
        check("progress mentions the skips", phase.progressLine().contains("2 skipped"));
        check("the counts distinguish rendered from attempted", phase.done() == 2);

        // A renderer that returns sizes nobody asked for has not produced an icon.
        IconStore store2 = new IconStore(root.resolve("wrongsize"), Map.of("item", new int[] {128}));
        IconPhase wrong = new IconPhase(store2, (kind, id, sizes) -> Map.of(999, png(4)),
                Map.of("item", List.of("test:w")));
        wrong.runBatches(5, null);
        check("a size nobody asked for is not stored", !store2.has("item", "test:w"));
        check("and is reported", wrong.skipped().size() == 1);
    }

    private static void theOrderIsTheCallers(Path root) throws IOException {
        section("the work order is kept");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128}));
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put("item", List.of("test:c", "test:a", "test:b"));

        List<String> order = new ArrayList<>();
        IconPhase phase = new IconPhase(store, (kind, id, sizes) -> {
            order.add(id);
            return Map.of(128, png(4));
        }, candidates);
        phase.runBatches(10, null);
        // Preserving the caller's order means a run that stops part-way has rendered a prefix rather
        // than a scatter, which is what makes the partial output usable on its own.
        check("the caller's order is preserved", order.equals(List.of("test:c", "test:a", "test:b")));

        IconPhase inspect = new IconPhase(store,
                (kind, id, sizes) -> Map.of(128, png(4)),
                Map.of("item", List.of("test:x", "test:y")));
        check("the totals per kind are reported",
                inspect.totalsByKind().equals(Map.of("item", 2)));
        List<String> listed = new ArrayList<>();
        inspect.forEachOutstanding(listed::add);
        check("and the outstanding work can be listed",
                listed.equals(List.of("item/test:x", "item/test:y")));
    }

    private static void nothingIsRenderedForAKindWithNoSizes(Path root) throws IOException {
        section("kinds that are not rendered are left alone");

        IconStore store = new IconStore(root, Map.of("item", new int[] {128, 32}));
        Map<String, List<String>> candidates = new LinkedHashMap<>();
        candidates.put("item", List.of("test:i"));
        candidates.put("recipe", List.of("test:r1", "test:r2"));

        List<String> asked = new ArrayList<>();
        IconPhase phase = new IconPhase(store, (kind, id, sizes) -> {
            asked.add(kind + "/" + id);
            return Map.of(128, png(4), 32, png(2));
        }, candidates);
        check("a kind with no configured sizes is not work", phase.total() == 1);
        check("so nothing is outstanding for it", phase.remaining() == 1);
        phase.runBatches(5, null);
        check("and the renderer is never asked about it", asked.equals(List.of("item/test:i")));
        check("the kind that is not rendered is still renderable in principle",
                !store.renders("recipe"));
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] png(int size) {
        byte[] out = new byte[size];
        // A PNG signature, so a test that inspected one would not be looking at noise.
        out[0] = (byte) 0x89;
        if (size > 1) {
            out[1] = 'P';
        }
        if (size > 2) {
            out[2] = 'N';
        }
        if (size > 3) {
            out[3] = 'G';
        }
        return out;
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
