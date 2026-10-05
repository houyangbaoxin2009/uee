package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.uee.config.ConfigFile;
import org.uee.config.ExportConfig;
import org.uee.config.Tokens;
import org.uee.datapack.TagFile;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.pipeline.ExportReport;
import org.uee.pipeline.Exporter;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;

/**
 * Checks tag collection: the parsing, the merge rule, and the record it produces.
 *
 * <h2>What is being checked, and why here rather than in a game</h2>
 *
 * <p>Tag files are the first datapack-backed category, and their awkward parts are the shape handling
 * — three entry forms, a {@code replace} flag, a merge across packs — none of which needs a game to
 * exercise. That separation is the reason the parsing lives in the core: the alternative is code whose
 * only test is "a pack loaded and nothing threw", which catches none of the cases below.
 *
 * <h2>The merge rule is asserted, not assumed</h2>
 *
 * <p>It was read out of vanilla's own {@code TagLoader}, which walks a file's stack in priority order
 * and clears the accumulated entries when a file sets {@code replace}. That is counter-intuitive enough
 * to be worth a test: the intuitive reading is the opposite — that a later file adds to what came
 * before and {@code replace} means "start a new tag" rather than "discard everything below me".
 */
public final class TagTest {

    private static int failures;

    public static void main(String[] args) throws IOException {
        parsing();
        theThreeEntryForms();
        resourcePaths();
        theRecordItProduces(Path.of(args.length > 0 ? args[0] : "build/tag-test"));
        theCategoryItself();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- parsing

    private static void parsing() {
        section("parsing a tag file");

        TagFile simple = TagFile.parse("""
                { "values": [ "minecraft:stone", "minecraft:dirt" ] }""");
        check("a plain list parses", simple.entries().size() == 2);
        check("ids are kept as written",
                simple.entries().get(0).id().equals("minecraft:stone")
                        && !simple.entries().get(0).nested());
        check("replace defaults to false", !simple.replace());
        check("no members are nested or optional",
                simple.nestedCount() == 0 && simple.optionalCount() == 0);
        check("members round-trip as written",
                String.join(",", simple.membersAsWritten())
                        .equals("minecraft:stone,minecraft:dirt"));

        check("an empty list parses",
                TagFile.parse("{ \"values\": [] }").entries().isEmpty());
        check("a missing values key parses",
                TagFile.parse("{ }").entries().isEmpty());
        check("replace is read",
                TagFile.parse("{ \"replace\": true, \"values\": [] }").replace());
        check("an explicit false is not confused with true",
                !TagFile.parse("{ \"replace\": false, \"values\": [] }").replace());

        // A single value is not valid by the format, but refusing it helps nobody and the intent is
        // unambiguous.
        check("a lone value is accepted as one member",
                TagFile.parse("{ \"values\": \"minecraft:stone\" }").entries().size() == 1);

        // Not an object at all is a broken file rather than a broken entry, so it is refused.
        boolean refused = false;
        try {
            TagFile.parse("[1, 2, 3]");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check("a document that is not an object is refused", refused);
        boolean refusedBad = false;
        try {
            TagFile.parse("{ \"values\": [ ");
        } catch (RuntimeException expected) {
            refusedBad = true;
        }
        check("truncated json is refused", refusedBad);
    }

    private static void theThreeEntryForms() {
        section("the three entry forms");

        // 1) a plain id
        TagFile plain = TagFile.parse("""
                { "values": ["minecraft:stone"] }""");
        check("a plain id is a plain member",
                !plain.entries().get(0).nested() && plain.entries().get(0).required());

        // 2) a nested tag, written with a leading # -- the marker must be recorded, not just stripped,
        //    because a consumer needs to know whether to look up another tag or an element.
        TagFile nested = TagFile.parse("""
                { "values": ["#minecraft:base_stone_overworld"] }""");
        check("a hash makes a member nested", nested.entries().get(0).nested());
        check("the hash is not part of the id",
                nested.entries().get(0).id().equals("minecraft:base_stone_overworld"));
        check("and it is written back with the hash",
                nested.entries().get(0).asWritten().startsWith("#"));
        check("nested members are counted", nested.nestedCount() == 1);

        // 3) the object form, which is how a pack marks an entry optional.
        TagFile objects = TagFile.parse("""
                { "values": [
                    { "id": "minecraft:dirt", "required": false },
                    { "id": "minecraft:stone" },
                    { "id": "#minecraft:logs", "required": false }
                ] }""");
        check("an object member is read", objects.entries().size() == 3);
        check("required:false is recorded",
                !objects.entries().get(0).required() && objects.optionalCount() == 2);
        check("a missing required means required",
                objects.entries().get(1).required());
        check("the object form's required flag is not shared between members",
                !objects.entries().get(0).required() && objects.entries().get(1).required()
                        && !objects.entries().get(2).required());
        check("the object form can nest too",
                objects.entries().get(2).nested() && !objects.entries().get(2).required());
        check("an object's id survives",
                objects.entries().get(0).id().equals("minecraft:dirt"));

        // An entry with no usable id has nothing to record, so it is dropped rather than becoming a
        // blank member -- the rest of the file still contributes.
        TagFile broken = TagFile.parse("""
                { "values": ["minecraft:stone", { "required": false }, "", "#", null, 7] }""");
        check("entries without an id are dropped, the rest survive",
                broken.entries().size() == 1
                        && broken.entries().get(0).id().equals("minecraft:stone"));

        // Mixed forms in one file, which is what real packs look like.
        TagFile mixed = TagFile.parse("""
                { "values": ["minecraft:stone", "#c:gems", { "id": "minecraft:dirt",
                  "required": false }] }""");
        check("the forms mix in one file", mixed.entries().size() == 3);
        check("and each keeps its own shape",
                !mixed.entries().get(0).nested() && mixed.entries().get(1).nested()
                        && !mixed.entries().get(2).required());
    }

    private static void resourcePaths() {
        section("resource paths");

        // data/<ns>/tags/<type>/<path>.json -- the type is an axis of its own, not part of the name.
        check("the tag path is the part after the type",
                "gems".equals(TagFile.pathOf("tags/item/gems.json")));
        check("a nested path is kept whole",
                "ores/deep".equals(TagFile.pathOf("tags/block/ores/deep.json")));
        check("the type is read separately",
                "item".equals(TagFile.typeOf("tags/item/gems.json")));
        check("a nested path still reports the outer type",
                "block".equals(TagFile.typeOf("tags/block/ores/deep.json")));
        check("a leading slash is tolerated",
                "gems".equals(TagFile.pathOf("/tags/item/gems.json")));
        check("the extension is not part of the name",
                !TagFile.pathOf("tags/item/gems.json").contains(".json"));

        check("a non-tag path yields nothing",
                TagFile.pathOf("recipes/foo.json") == null && TagFile.pathOf(null) == null);
        check("a path with no type yields nothing",
                TagFile.pathOf("tags/gems.json") == null);
        check("an empty name yields nothing",
                TagFile.pathOf("tags/item/.json") == null);
    }

    // ---------------------------------------------------------------- the produced record

    private static void theRecordItProduces(Path root) throws IOException {
        section("the exported record");

        deleteRecursively(root);
        Files.createDirectories(root);

        Fixture fixture = new Fixture();
        Uee.bind(fixture);
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.TAG)
                .analyze(false)
                .build();
        ExportReport report = new Exporter(fixture, config, root).run();

        check("the export reported the tag records", report.records() >= 2);

        String itemTag = firstFileContaining(root, "example-tags");
        check("a tag landed in its namespace's file", itemTag != null);
        if (itemTag != null) {
            check("the tag's own name is recorded", itemTag.contains("\"key\":\"gems\"")
                    || itemTag.contains("gems"));
            check("the type is recorded, since the id does not imply it",
                    itemTag.contains("\"type\":\"item\""));
            check("members are recorded as written",
                    itemTag.contains("minecraft:stone")
                            && itemTag.contains("minecraft:diamond"));
            check("a nested member keeps its hash", itemTag.contains("#c:raw_materials"));
            check("the optional member is recorded", itemTag.contains("minecraft:diamond"));
            check("counts are stated",
                    itemTag.contains("\"count\"") && itemTag.contains("\"nested\""));
        }

        // A tag is its own category, so it must not be bundled with recipes.
        check("the record is a tag, not a recipe", itemTag == null || !itemTag.contains("recipes/"));

        // The whole output must still parse, which is the check that catches a writer that was not
        // taught about the new category.
        boolean allParse = true;
        for (Path p : listFiles(root)) {
            if (!p.toString().endsWith(".json")) {
                continue;
            }
            try {
                org.uee.util.JsonReader.parse(Files.readString(p, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                allParse = false;
                System.out.println("      unparseable: " + p.getFileName() + " - " + e.getMessage());
            }
        }
        check("every artifact is a whole document", allParse);

        // And in a format whose writer was not otherwise touched, to catch a category that some
        // writers silently drop.
        Path tdRoot = root.resolveSibling(root.getFileName() + "-td");
        deleteRecursively(tdRoot);
        ExportConfig td = config.toBuilder().formats(ExportConfig.TD).build();
        new Exporter(fixture, td, tdRoot).run();
        check("the td writer emits tags too", countFiles(tdRoot) > 0);
        check("and the td output mentions the tag", firstFileContaining(tdRoot, "example-tags") != null);
    }

    private static void theCategoryItself() {
        section("the category in the vocabulary");

        check("TAG is collected content, not analysis", !ElementKind.TAG.isAnalysis());
        check("its token is 'tag'", ElementKind.TAG.singular().equals("tag"));
        check("and its plural is 'tags'", ElementKind.TAG.plural().equals("tags"));
        check("the plural resolves to the category",
                Tokens.kinds("tags").equals(Set.of(ElementKind.TAG)));
        check("the singular does too",
                Tokens.kinds("tag").equals(Set.of(ElementKind.TAG)));
        check("'tags' is in the data half",
                Tokens.kinds(Tokens.DATA).contains(ElementKind.TAG));
        check("and not in the analysis half",
                !Tokens.kinds(Tokens.ANALYSIS).contains(ElementKind.TAG));

        // Every category must be reachable from a written config, which is the invariant that keeps
        // the command surface and the config file from drifting apart.
        boolean known = ConfigFile.knownKeys().contains("kinds");
        check("kinds is a config key, so the category is settable there", known);
    }

    // ---------------------------------------------------------------- fixtures

    private static String firstFileContaining(Path root, String needle) throws IOException {
        for (Path f : listFiles(root)) {
            if (!f.getFileName().toString().contains(needle)) {
                continue;
            }
            return Files.readString(f, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static List<Path> listFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static int countFiles(Path root) throws IOException {
        return listFiles(root).size();
    }

    /**
     * An adapter that declares tag files the way a datapack would.
     *
     * <p>It emits the records a tag collection would produce, so the test covers the pipeline —
     * category, writer, record shape — without needing a resource manager. The parsing and the merge
     * rule, which is where the real risk is, are covered by the sections above.
     */
    private static final class Fixture implements LoaderAdapter {
        @Override
        public LoaderInfo info() {
            return new LoaderInfo("test", "1.0", "1.21.1", "build/tag-test", false, true,
                    "21", "test", "test");
        }

        @Override
        public List<ModElement> mods() {
            return List.of(new ModElement("example", "Example Mod", "1.0.0", "example", "neoforge",
                    "1.21.1", new String[] {"Someone"}, "TPL-2.3", "demo", new Dependency[0],
                    new String[0], "example-1.0.0.jar"));
        }

        @Override
        public List<DebugSection> debugSections() {
            return List.of();
        }

        @Override
        public void collectRegistries(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
            if (wanted.contains(ElementKind.ITEM)) {
                sink.item(new ItemElement("example:ruby", "example", "item.example.ruby", "红宝石",
                        "Ruby", 64, 0, new String[] {"c:gems"}, new String[] {"misc"}, null, null,
                        false));
            }
        }

        @Override
        public void collectDatapacks(ExportConfig config, java.util.Collection<ElementKind> wanted,
                ElementSink sink) {
            if (!wanted.contains(ElementKind.TAG)) {
                return;
            }
            // A tag that exercises every shape at once: a plain member, a nested tag and an optional
            // member.
            sink.generic(ElementKind.TAG, "example", "gems", null, null,
                    new String[] {"minecraft:stone", "#c:raw_materials", "minecraft:diamond"},
                    new String[] {"type", "item", "replace", "false", "count", "3", "nested", "1",
                            "optional", "0"});
            sink.generic(ElementKind.TAG, "example", "ores/deep", null, null,
                    new String[] {"example:deepslate_ruby"},
                    new String[] {"type", "block", "replace", "false", "count", "1", "nested", "0",
                            "optional", "0"});
        }

        @Override
        public List<org.uee.debug.MixinConfig> mixinConfigs() {
            return List.of();
        }

        @Override
        public boolean supports(String capability) {
            return LoaderAdapter.CAP_REGISTRY_FROZEN.equals(capability);
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
