package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.uee.datapack.WorldgenFile;

/**
 * Checks the world-generation parser, against the shipped data where it is available.
 *
 * <h2>Why this one prefers real files</h2>
 *
 * <p>The other datapack parsers are checked against hand-written samples of every shape measured in the
 * data. This one goes further and parses the actual files when a Minecraft jar happens to be on the
 * machine, because the shapes are unusually heterogeneous: fourteen kinds of file with almost nothing in
 * common, and the two facts that matter most are both counter-intuitive. {@code structures} is a list of
 * objects rather than a list of ids, and {@code noise} looks like a reference and is an inline
 * specification. A sample can encode a belief about those; a thousand real files cannot.
 *
 * <h2>And why it still passes without one</h2>
 *
 * <p>The jar is found by looking rather than required. When it is absent — a fresh clone, a build machine
 * — the hand-written checks still run and still cover every shape, and the survey is reported as skipped
 * rather than silently passing. A test that quietly does less than it claims is worse than one that says
 * what it did.
 */
public final class WorldgenTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        theKindAndTheName();
        references();
        theType();
        counts();
        unknownKinds();
        surveyTheShippedData();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- paths

    private static void theKindAndTheName() {
        section("the kind and the name");

        check("the kind comes from the directory",
                "placed_feature".equals(WorldgenFile.kindOf("worldgen/placed_feature/ores/x.json")));
        check("and so does a longer one",
                "multi_noise_biome_source_parameter_list".equals(WorldgenFile.kindOf(
                        "worldgen/multi_noise_biome_source_parameter_list/overworld.json")));

        // A name may contain slashes: one name in a subdirectory, not a name and a category.
        check("the name keeps its subdirectories, and loses the extension",
                "ores/x".equals(WorldgenFile.nameOf("worldgen/placed_feature/ores/x.json")));
        check("a one-segment name works",
                "overworld".equals(WorldgenFile.nameOf("worldgen/noise/overworld.json")));

        // Two kinds can hold the same name, which is why the key is both.
        check("the key is the kind and the name together",
                "placed_feature/ores/x".equals(WorldgenFile.keyOf("worldgen/placed_feature/ores/x.json")));
        check("so two kinds with one name get different keys",
                !WorldgenFile.keyOf("worldgen/placed_feature/x.json")
                        .equals(WorldgenFile.keyOf("worldgen/noise/x.json")));

        check("a file directly under worldgen has no kind",
                WorldgenFile.kindOf("worldgen/x.json") == null);
        check("a non-worldgen path yields nothing",
                WorldgenFile.kindOf("loot_table/stone.json") == null);
        check("and neither does null", WorldgenFile.keyOf(null) == null);
        check("the directory is named once", WorldgenFile.directory().equals("worldgen"));
    }

    // ---------------------------------------------------------------- content

    private static void references() {
        section("references, which are what a reader follows");

        // Measured: 233 of 233 placed features name their feature in a bare id.
        WorldgenFile.Worldgen placed = WorldgenFile.parse("placed_feature", """
                { "feature": "minecraft:acacia", "placement": [ { "type": "minecraft:count" } ] }""");
        check("a placed feature names its feature",
                placed.references().equals(List.of("minecraft:acacia")));
        check("and it reaches the record",
                placed.referenceArray().length == 1
                        && "minecraft:acacia".equals(placed.referenceArray()[0]));
        check("the placement list is not mistaken for references", placed.references().size() == 1);

        // The one that would be got wrong by treating a list as strings: a structure set's structures are
        // objects each carrying an id and a weight.
        WorldgenFile.Worldgen set = WorldgenFile.parse("structure_set", """
                { "placement": { "type": "minecraft:random_spread", "spacing": 6 },
                  "structures": [ { "structure": "minecraft:ancient_city", "weight": 1 },
                                  { "structure": "minecraft:buried_treasure", "weight": 2 } ] }""");
        check("a structure set's structures are read out of their objects",
                set.references().equals(List.of("minecraft:ancient_city", "minecraft:buried_treasure")));
        check("and their order is kept",
                "minecraft:ancient_city".equals(set.references().get(0)));

        // A pack may write the short form even though the game does not.
        WorldgenFile.Worldgen shortForm = WorldgenFile.parse("structure_set", """
                { "structures": [ "minecraft:ancient_city" ] }""");
        check("a bare id in that list is accepted too",
                shortForm.references().equals(List.of("minecraft:ancient_city")));

        // Three more fields measured to be bare ids.
        check("a template pool's fallback is a reference",
                WorldgenFile.parse("template_pool", "{ \"fallback\": \"minecraft:empty\" }")
                        .references().equals(List.of("minecraft:empty")));
        check("a structure's start pool is a reference",
                WorldgenFile.parse("structure", "{ \"start_pool\": \"minecraft:bastion/starts\" }")
                        .references().equals(List.of("minecraft:bastion/starts")));
        check("a parameter list's preset is a reference",
                WorldgenFile.parse("multi_noise_biome_source_parameter_list",
                        "{ \"preset\": \"minecraft:overworld\" }")
                        .references().equals(List.of("minecraft:overworld")));

        // The one that looks like a reference and is not. Measured across every file that has it: an
        // inline specification of a height and a minimum Y, not the id of a noise.
        WorldgenFile.Worldgen settings = WorldgenFile.parse("noise_settings", """
                { "noise": { "height": 384, "min_y": -64, "size_horizontal": 1, "size_vertical": 2 },
                  "sea_level": 63 }""");
        check("an inline noise specification is not mistaken for a reference",
                settings.references().isEmpty());

        WorldgenFile.Worldgen both = WorldgenFile.parse("structure", """
                { "type": "minecraft:jigsaw", "start_pool": "minecraft:a",
                  "structures": [ { "structure": "minecraft:b" } ] }""");
        check("references from several fields are gathered",
                both.references().equals(List.of("minecraft:a", "minecraft:b")));

        WorldgenFile.Worldgen repeated = WorldgenFile.parse("structure", """
                { "start_pool": "minecraft:a", "feature": "minecraft:a" }""");
        check("the same reference twice is recorded once",
                repeated.references().equals(List.of("minecraft:a")));
    }

    private static void theType() {
        section("the type, which four kinds state");

        // Measured: configured_feature states it in 196 of 196 files, and it is what the file is.
        WorldgenFile.Worldgen feature = WorldgenFile.parse("configured_feature", """
                { "type": "minecraft:ore", "config": { "size": 9 } }""");
        check("a configured feature's type is read", "minecraft:ore".equals(feature.type()));
        check("and reaches the record",
                "minecraft:ore".equals(joined(WorldgenFile.extraPairs(feature), "type")));

        // Ten of the fourteen kinds state none, so its absence is normal rather than damaged.
        WorldgenFile.Worldgen noise = WorldgenFile.parse("noise", """
                { "amplitudes": [1,2,3], "firstOctave": -7 }""");
        check("a kind that states no type yields an empty one", noise.type().isEmpty());
        check("and emits no type key",
                joined(WorldgenFile.extraPairs(noise), "type") == null);

        check("the kind always reaches the record",
                "noise".equals(joined(WorldgenFile.extraPairs(noise), "worldgenKind")));
    }

    private static void counts() {
        section("counts for the list-valued kinds");

        // Measured: 186 template pools, every one of them with an elements list.
        WorldgenFile.Worldgen pool = WorldgenFile.parse("template_pool", """
                { "fallback": "minecraft:empty",
                  "elements": [ { "element": {}, "weight": 1 }, { "element": {}, "weight": 2 } ] }""");
        check("a template pool's element count is recorded",
                pool.counts().get("elements") == 2);
        check("and reaches the record",
                "elements=2".equals(joined(WorldgenFile.extraPairs(pool), "counts")));
        check("its fallback is still a reference",
                pool.references().equals(List.of("minecraft:empty")));

        WorldgenFile.Worldgen processors = WorldgenFile.parse("processor_list", """
                { "processors": [ { "processor_type": "minecraft:block_rot" },
                                  { "processor_type": "minecraft:rule" } ] }""");
        check("a processor list's count is recorded", processors.counts().get("processors") == 2);
        check("both counts come out together", WorldgenFile.parse("template_pool", """
                { "elements": [1], "processors": [1,2,3] }""").countsAsText().equals("elements=1,processors=3"));

        check("a file with no counts emits no key",
                joined(WorldgenFile.extraPairs(WorldgenFile.parse("noise", "{}")), "counts") == null);
    }

    private static void unknownKinds() {
        section("kinds the game does not know");

        // A pack may add its own directory. Collected rather than refused, but marked, so a reader can
        // tell a convention from a typo.
        WorldgenFile.Worldgen invented = WorldgenFile.parse("my_own_thing", "{ \"x\": 1 }");
        check("an unknown kind is still collected", "my_own_thing".equals(invented.kind()));
        check("but it is not reported as known", !invented.knownKind());
        check("and the record says so",
                "false".equals(joined(WorldgenFile.extraPairs(invented), "recognised")));
        check("a known kind carries no such key",
                joined(WorldgenFile.extraPairs(WorldgenFile.parse("noise", "{}")), "recognised") == null);

        // An array means nothing as a worldgen document, so it is refused. A bare value does not, and is
        // not: one file in the shipped data is exactly that.
        boolean refused = false;
        try {
            WorldgenFile.parse("noise", "[1,2,3]");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check("an array is refused", refused);

        // The measured case: density_function/zero.json is the bare text 0.0.
        WorldgenFile.Worldgen constant = WorldgenFile.parse("density_function", "0.0");
        check("a bare value is a constant rather than a failure", "0.0".equals(constant.constant()));
        check("and still says what kind of file it is",
                "density_function".equals(joined(WorldgenFile.extraPairs(constant), "worldgenKind")));
        check("with no references and no type",
                constant.references().isEmpty() && constant.type().isEmpty());
        check("a string constant works too",
                "minecraft:overworld".equals(
                        WorldgenFile.parse("density_function", "\"minecraft:overworld\"").constant()));
        check("an object is not a constant",
                WorldgenFile.parse("noise", "{}").constant().isEmpty());
        boolean refusedNull = false;
        try {
            WorldgenFile.parse("noise", "null");
        } catch (IllegalArgumentException expected) {
            refusedNull = true;
        }
        check("a null document is refused", refusedNull);

        // A file whose references are the wrong shape must not cost the kind and the type.
        WorldgenFile.Worldgen badRefs = WorldgenFile.parse("structure", """
                { "type": "minecraft:jigsaw", "start_pool": 42, "structures": "nonsense" }""");
        check("a malformed reference does not lose the rest",
                "minecraft:jigsaw".equals(badRefs.type()) && badRefs.references().isEmpty());
    }

    // ---------------------------------------------------------------- the shipped data

    /**
     * Parses every worldgen file in a Minecraft jar, when one can be found.
     *
     * <p>This is the check that cannot be fooled by a sample: a thousand files across fourteen kinds, each
     * one parsed and its result sanity-checked against the shape it should have. Two of the assertions
     * below are the ones that would have caught the mistakes this parser was written to avoid.
     */
    private static void surveyTheShippedData() throws IOException {
        section("a survey of the shipped data");

        Path jar = findMinecraftJar();
        if (jar == null) {
            System.out.println("  --   skipped: no Minecraft jar found on this machine");
            return;
        }
        System.out.println("      surveying " + jar.getFileName());

        int files = 0;
        int placed = 0;
        int placedWithReference = 0;
        int sets = 0;
        int setsWithReferences = 0;
        int settings = 0;
        int settingsWithReferences = 0;
        int unknownKinds = 0;
        List<String> failed = new ArrayList<>();

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.contains("/worldgen/") || !name.endsWith(".json")) {
                    continue;
                }
                int at = name.indexOf("/worldgen/");
                String path = name.substring(at + 1);
                String kind = WorldgenFile.kindOf(path);
                if (kind == null || WorldgenFile.keyOf(path) == null) {
                    failed.add("path not understood: " + path);
                    continue;
                }
                files++;
                WorldgenFile.Worldgen parsed;
                try {
                    parsed = WorldgenFile.parse(kind,
                            new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                } catch (Throwable t) {
                    failed.add(kind + ": " + t);
                    continue;
                }
                if (!parsed.knownKind()) {
                    unknownKinds++;
                }
                // A placed feature names a feature in every file that exists, so one that yields no
                // reference means the field is being looked for in the wrong place.
                if (kind.equals("placed_feature")) {
                    placed++;
                    if (!parsed.references().isEmpty()) {
                        placedWithReference++;
                    }
                }
                // A structure set does the same, through objects rather than strings.
                if (kind.equals("structure_set")) {
                    sets++;
                    if (!parsed.references().isEmpty()) {
                        setsWithReferences++;
                    }
                }
                // And a noise setting must yield none, because its noise field is not a reference.
                if (kind.equals("noise_settings")) {
                    settings++;
                    if (parsed.references().isEmpty()) {
                        settingsWithReferences++;
                    }
                }
            }
        }

        System.out.println("      " + files + " files parsed across the shipped worldgen data"
                + (unknownKinds > 0 ? ", " + unknownKinds + " of an unrecognised kind" : ""));
        check("every worldgen file parses", failed.isEmpty()
                || !failed.isEmpty() && report(failed));
        check("there is a meaningful number of them", files > 500);
        check("every placed feature yields a reference (" + placedWithReference + "/" + placed + ")",
                placed > 0 && placedWithReference == placed);
        check("every structure set yields references (" + setsWithReferences + "/" + sets + ")",
                sets > 0 && setsWithReferences == sets);
        // The counter-intuitive one, asserted against the data rather than against a sample.
        check("no noise setting yields a reference, because its noise field is not one ("
                        + settingsWithReferences + "/" + settings + ")",
                settings > 0 && settingsWithReferences == settings);
    }

    private static boolean report(List<String> failed) {
        for (int i = 0; i < Math.min(5, failed.size()); i++) {
            System.out.println("        " + failed.get(i));
        }
        return false;
    }

    /** Looks for a Minecraft jar in the places this project's tooling puts one. */
    private static Path findMinecraftJar() {
        List<Path> roots = List.of(
                Path.of(System.getProperty("user.home"), ".gradle", "caches", "neoformruntime",
                        "artifacts"),
                Path.of(System.getProperty("user.home"), ".gradle", "caches", "forge_gradle",
                        "minecraft_repo", "versions"));
        for (Path root : roots) {
            Path found = searchForClientJar(root, 0);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Path searchForClientJar(Path root, int depth) {
        if (!Files.isDirectory(root) || depth > 3) {
            return null;
        }
        try (var listing = Files.list(root)) {
            for (Path child : listing.toList()) {
                String name = child.getFileName().toString();
                if (Files.isRegularFile(child) && name.startsWith("minecraft")
                        && name.endsWith("_client.jar")) {
                    return child;
                }
                Path nested = searchForClientJar(child, depth + 1);
                if (nested != null) {
                    return nested;
                }
            }
        } catch (IOException ignored) {
            // A directory that cannot be listed is simply not where the jar is.
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static String joined(String[] pairs, String key) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].equals(key)) {
                return pairs[i + 1];
            }
        }
        return null;
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
}
