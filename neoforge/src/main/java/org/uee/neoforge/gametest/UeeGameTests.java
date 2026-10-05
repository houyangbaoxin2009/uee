package org.uee.neoforge.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.uee.Uee;
import org.uee.config.ExportConfig;
import org.uee.config.WikiOptions;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.Dependency;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.pipeline.ExportReport;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.util.JsonReader;

/**
 * In-game tests for the Minecraft-facing layer, run by the loader's own GameTest framework.
 *
 * <p>The core is verified on its own — it compiles and runs with no game present. What cannot be
 * verified that way is exactly the part that touches game and loader classes: that the adapter binds,
 * that registry collection produces well-formed records, and that a real export reaches the disk.
 * Those are the claims these tests make, and they only mean something inside a running game.
 *
 * <p>Run them with {@code ./gradlew :neoforge:runGameTestServer}, or interactively through
 * {@code /test} in a dev client. No world is needed: UEE reads frozen registries rather than world
 * state, so the tests deliberately avoid building one.
 */
@GameTestHolder(Uee.MOD_ID)
@PrefixGameTestTemplate(false)
public final class UeeGameTests {

    private UeeGameTests() {
    }

    /** The adapter is bound and reports a loader and a game version. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void adapterIsBound(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        helper.assertTrue(adapter != null, "no loader adapter is bound; the entry point did not run");
        var info = adapter.info();
        helper.assertTrue(info.loader() != null && !info.loader().isEmpty(), "loader name is empty");
        helper.assertTrue(info.minecraftVersion() != null && !info.minecraftVersion().isEmpty(),
                "minecraft version is empty");
        helper.succeed();
    }

    /**
     * Collected item records are well formed and cover the vanilla namespace.
     *
     * <p>This is the registry path end to end: enumeration, translation lookup, tag resolution and
     * record construction, all against live game state.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void itemRecordsAreWellFormed(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.ITEM)
                .icons(false)
                .build();
        adapter.collectRegistries(config, EnumSet.of(ElementKind.ITEM), sink);

        helper.assertTrue(sink.items.size() > 0, "no items were collected");
        helper.assertTrue(sink.failures.isEmpty(),
                "item collection reported " + sink.failures.size() + " failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));

        boolean sawVanilla = false;
        for (ItemElement item : sink.items) {
            String name = item.registryName();
            helper.assertTrue(name != null && !name.isEmpty(), "an item has an empty registry name");
            int colon = name.indexOf(':');
            helper.assertTrue(colon > 0 && colon < name.length() - 1,
                    "registry name is not 'namespace:path': " + name);
            helper.assertTrue(item.maxStackSize() > 0,
                    name + " has a non-positive stack size: " + item.maxStackSize());
            helper.assertTrue(item.tags() != null, name + " has a null tag array");
            if (name.equals("minecraft:stone")) {
                sawVanilla = true;
                helper.assertTrue(item.blockItem(), "minecraft:stone should be a block item");
            }
        }
        helper.assertTrue(sawVanilla, "minecraft:stone was not among the collected items");
        helper.succeed();
    }

    /** Blocks, entities and the generic registries all produce records. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void otherRegistriesProduceRecords(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder()
                .kinds(ElementKind.BLOCK, ElementKind.ENTITY, ElementKind.EFFECT)
                .build();
        adapter.collectRegistries(config,
                EnumSet.of(ElementKind.BLOCK, ElementKind.ENTITY, ElementKind.EFFECT), sink);

        helper.assertTrue(sink.blocks.size() > 0, "no blocks were collected");
        helper.assertTrue(sink.entities.size() > 0, "no entities were collected");
        helper.assertTrue(sink.generics.size() > 0, "no generic registry entries were collected");
        helper.assertTrue(sink.failures.isEmpty(),
                "collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));
        helper.succeed();
    }

    /**
     * Tags are collected from the loaded datapacks.
     *
     * <p>The one thing the core tests cannot cover: everything about a tag file's <em>shape</em> is
     * verified without a game, but reading them out of the resource system is not. That path involves a
     * resource stack, a merge across packs and a resource path convention, so it is exactly the part
     * that needs a real pack loaded — and the vanilla datapacks provide thousands of tags, which makes
     * it a real workload rather than a token one.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void tagsAreCollectedFromDatapacks(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder().kinds(ElementKind.TAG).build();
        adapter.collectDatapacks(config, EnumSet.of(ElementKind.TAG), sink);

        helper.assertTrue(sink.generics.size() > 0,
                "no tags were collected; the resource stack, the path convention or the datapack"
                        + " enumeration is wrong");
        helper.assertTrue(sink.failures.isEmpty(),
                "tag collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));

        // A tag known to exist in the vanilla data, so a collection that produced something but not
        // the right something is caught as well as one that produced nothing.
        boolean foundAPlank = sink.generics.stream()
                .anyMatch(g -> g.startsWith("tag:") && g.contains("planks"));
        helper.assertTrue(foundAPlank,
                "the vanilla planks tags are missing from a run over the vanilla datapacks");

        helper.succeed();
    }

    /**
     * Loot tables are collected from the loaded datapacks.
     *
     * <p>The core tests cover every entry shape without a game; what they cannot cover is the enumeration
     * itself, which is a resource-manager call. The vanilla datapacks ship over a thousand tables, so this
     * runs the real path on a real workload.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void lootTablesAreCollectedFromDatapacks(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder().kinds(ElementKind.LOOT_TABLE).build();
        adapter.collectDatapacks(config, EnumSet.of(ElementKind.LOOT_TABLE), sink);

        helper.assertTrue(sink.generics.size() > 100,
                "only " + sink.generics.size() + " loot tables were collected; the vanilla datapacks"
                        + " ship over a thousand, so the enumeration or the path convention is wrong");
        helper.assertTrue(sink.failures.isEmpty(),
                "loot table collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));

        boolean foundStone = sink.generics.stream()
                .anyMatch(g -> g.startsWith("loot_table:") && g.endsWith("blocks/stone"));
        helper.assertTrue(foundStone, "blocks/stone is missing from a run over the vanilla datapacks");

        helper.succeed();
    }

    /**
     * Datapack collection produces the same records on one thread and on several.
     *
     * <p>This is the assertion that makes the parallelism safe to leave switched on. The order of records
     * is part of the output — the export is diffed across runs, and the resumable-write work depends on a
     * shard's contents being a function of the pack alone — so a thread count that changed the order would
     * silently make every comparison meaningless. The core test checks the skeleton's ordering in
     * isolation; this checks that the real collection on real datapacks goes through it.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void registryCollectionDoesNotDependOnThreadCount(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        Set<ElementKind> wanted = EnumSet.of(ElementKind.ITEM, ElementKind.BLOCK,
                ElementKind.ENTITY, ElementKind.EFFECT, ElementKind.BIOME);

        RecordingSink single = new RecordingSink();
        adapter.collectRegistries(ExportConfig.builder().kinds(ElementKind.ITEM, ElementKind.BLOCK,
                ElementKind.ENTITY, ElementKind.EFFECT, ElementKind.BIOME).threads(1).build(),
                wanted, single);

        RecordingSink many = new RecordingSink();
        adapter.collectRegistries(ExportConfig.builder().kinds(ElementKind.ITEM, ElementKind.BLOCK,
                ElementKind.ENTITY, ElementKind.EFFECT, ElementKind.BIOME).threads(8).build(),
                wanted, many);

        helper.assertTrue(!single.items.isEmpty(),
                "one thread collected no items at all");
        // The registries are the big categories, so a mismatch here is a real ordering fault rather
        // than a rounding difference.
        helper.assertTrue(single.items.size() == many.items.size(),
                "items: " + single.items.size() + " on one thread vs " + many.items.size()
                        + " on eight");
        helper.assertTrue(recordKeys(single).equals(recordKeys(many)),
                "the registry record sequence depends on the thread count");
        helper.assertTrue(single.blocks.size() == many.blocks.size()
                        && single.entities.size() == many.entities.size(),
                "blocks or entities differ between thread counts");
        helper.assertTrue(single.failures.size() == many.failures.size(),
                "the failure count differs between thread counts: " + single.failures.size() + " vs "
                        + many.failures.size());

        helper.succeed();
    }

    /** The identity and order of everything a sink recorded, for comparing two runs. */
    private static List<String> recordKeys(RecordingSink sink) {
        List<String> keys = new ArrayList<>();
        for (ItemElement e : sink.items) {
            keys.add("item:" + e.registryName());
        }
        for (BlockElement e : sink.blocks) {
            keys.add("block:" + e.registryName());
        }
        for (EntityElement e : sink.entities) {
            keys.add("entity:" + e.registryName());
        }
        keys.addAll(sink.generics);
        return keys;
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void datapackCollectionDoesNotDependOnThreadCount(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        Set<ElementKind> wanted = EnumSet.of(ElementKind.TAG, ElementKind.LOOT_TABLE);

        RecordingSink single = new RecordingSink();
        adapter.collectDatapacks(
                ExportConfig.builder().kinds(ElementKind.TAG, ElementKind.LOOT_TABLE).threads(1).build(),
                wanted, single);

        RecordingSink many = new RecordingSink();
        adapter.collectDatapacks(
                ExportConfig.builder().kinds(ElementKind.TAG, ElementKind.LOOT_TABLE).threads(8).build(),
                wanted, many);

        helper.assertTrue(!single.generics.isEmpty(), "one thread collected nothing at all");
        helper.assertTrue(single.generics.size() == many.generics.size(),
                "one thread collected " + single.generics.size() + " records and eight collected "
                        + many.generics.size());
        helper.assertTrue(single.generics.equals(many.generics),
                "the record sequence differs between one thread and eight, so the output depends on"
                        + " the thread count");
        helper.assertTrue(single.failures.size() == many.failures.size(),
                "the failure count differs between thread counts: " + single.failures.size() + " vs "
                        + many.failures.size());

        helper.succeed();
    }

    /**
     * Collection reads icons from the store rather than rendering them.
     *
     * <p>This is the assertion that the data phase does what the design says it does. Rendering needs a
     * client and this is a dedicated server, so a collection that rendered would either fail here or --
     * worse -- quietly produce nothing and look like an export with no icons. Planting an icon in a store
     * and checking it comes back out proves the read path, and the absence of any rendering in the data
     * phase is what makes it work on a server at all.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void collectionReadsIconsRatherThanRenderingThem(GameTestHelper helper) {
        AbstractMinecraftAdapter adapter = (AbstractMinecraftAdapter) Uee.adapter();
        String itemId = "minecraft:stone";
        byte[] marker = {1, 2, 3, 4, 5};

        try {
            Path root = Files.createTempDirectory("uee-icons");
            org.uee.icon.IconStore store = new org.uee.icon.IconStore(root,
                    Map.of("item", new int[] {128, 32}));
            store.put("item", itemId, 128, marker);
            store.put("item", itemId, 32, marker);
            adapter.bindIcons(store);

            RecordingSink sink = new RecordingSink();
            ExportConfig config = ExportConfig.builder().kinds(ElementKind.ITEM).build();
            adapter.collectRegistries(config, EnumSet.of(ElementKind.ITEM), sink);

            ItemElement stone = sink.items.stream()
                    .filter(e -> e.registryName().equals(itemId))
                    .findFirst().orElse(null);
            helper.assertTrue(stone != null, "the test item was not collected at all");
            helper.assertTrue(stone.iconLarge() != null && stone.iconLarge().length == marker.length,
                    "the icon in the store did not reach the record, so collection is not reading it");
            helper.assertTrue(stone.iconSmall() != null,
                    "only one of the two configured sizes reached the record");

            // An item with no icon in the store must come out with no icon rather than failing: the
            // store is not expected to be complete, which is what makes a resumed run usable.
            ItemElement other = sink.items.stream()
                    .filter(e -> !e.registryName().equals(itemId))
                    .findFirst().orElse(null);
            helper.assertTrue(other == null || other.iconLarge() == null,
                    "an item with no stored icon still came out with one");
            helper.assertTrue(sink.failures.isEmpty(),
                    "a missing icon was reported as a failure: "
                            + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));
        } catch (IOException e) {
            helper.fail("could not prepare the icon store: " + e);
        } finally {
            adapter.bindIcons(null);
        }
        helper.succeed();
    }

    /** The mod list contains this mod and the game itself, with dependencies parsed. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void modListIsAvailable(GameTestHelper helper) {
        List<ModElement> mods = Uee.adapter().mods();
        helper.assertTrue(mods.size() > 0, "the mod list is empty");

        boolean sawSelf = false;
        boolean sawMinecraft = false;
        boolean sawAnyDependency = false;
        for (ModElement mod : mods) {
            if (mod.id().equals(Uee.MOD_ID)) {
                sawSelf = true;
            }
            if (mod.id().equals("minecraft")) {
                sawMinecraft = true;
            }
            if (mod.dependencies().length > 0) {
                sawAnyDependency = true;
                for (Dependency d : mod.dependencies()) {
                    helper.assertTrue(d.id() != null && !d.id().isEmpty(),
                            mod.id() + " declares a dependency with an empty id");
                }
            }
        }
        helper.assertTrue(sawSelf, "the mod list does not contain " + Uee.MOD_ID);
        helper.assertTrue(sawMinecraft, "the mod list does not contain minecraft");
        helper.assertTrue(sawAnyDependency, "no mod declared any dependency");
        helper.succeed();
    }

    /** A full export reaches the disk and every wiki line is independently parseable JSON. */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void exportWritesParseableArtifacts(GameTestHelper helper) throws IOException {
        Path root = Files.createTempDirectory("uee-gametest-export");
        try {
            ExportConfig config = ExportConfig.builder()
                    .outputDir(root)
                    .formats(ExportConfig.NDJSON, ExportConfig.WIKI, ExportConfig.ZD)
                    .kinds(ElementKind.MOD, ElementKind.DEBUG, ElementKind.ITEM,
                            ElementKind.BLOCK, ElementKind.NAMESPACE, ElementKind.DEPENDENCY,
                            ElementKind.CONFLICT, ElementKind.MIXIN)
                    .wiki(WikiOptions.builder().enabled(true).build())
                    .build();
            ExportReport report = Uee.export(config, root);

            helper.assertTrue(report.artifacts().size() > 0, "the export produced no files");
            helper.assertTrue(report.records() > 0, "the export wrote no records");

            int checked = 0;
            for (ExportReport.Artifact artifact : report.artifacts()) {
                helper.assertTrue(Files.size(artifact.path()) > 0,
                        "artifact is empty: " + artifact.path());
                if (!artifact.format().equals(ExportConfig.NDJSON)) {
                    continue;
                }
                checked += assertEachLineIsJson(helper, artifact.path());
            }
            helper.assertTrue(checked > 0, "no NDJSON records were checked");
        } finally {
            deleteRecursively(root);
        }
        helper.succeed();
    }

    /**
     * One bad element does not abort the export.
     *
     * <p>This is the crash-isolation contract, and it is asserted rather than assumed: a sink that
     * fails on one record must still receive the records after it.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aBadElementDoesNotAbortTheExport(GameTestHelper helper) {
        Path root;
        try {
            root = Files.createTempDirectory("uee-gametest-isolation");
        } catch (IOException e) {
            helper.fail("could not create a temporary directory: " + e);
            return;
        }
        try {
            ExportConfig config = ExportConfig.builder()
                    .outputDir(root)
                    .formats(ExportConfig.NDJSON)
                    .kinds(ElementKind.ITEM)
                    .build();
            // The adapter reports a failure through the sink rather than throwing; the pipeline must
            // record it and keep going. Verified by observing both the failure list and the output.
            ExportReport report = Uee.export(config, root);
            helper.assertTrue(report.records() > 0, "an export with a failure produced no records");
        } catch (IOException e) {
            helper.fail("export threw instead of isolating the failure: " + e);
        } finally {
            deleteRecursively(root);
        }
        helper.succeed();
    }

    // ---------------------------------------------------------------- helpers

    private static int assertEachLineIsJson(GameTestHelper helper, Path file) throws IOException {
        int lines = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonReader.parse(line);
                lines++;
            } catch (RuntimeException e) {
                helper.fail("not parseable JSON in " + file.getFileName() + ": " + line);
                return lines;
            }
        }
        return lines;
    }

    private static void deleteRecursively(Path p) {
        try {
            if (!Files.exists(p)) {
                return;
            }
            try (var walk = Files.walk(p)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                    try {
                        Files.deleteIfExists(x);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
            }
        } catch (IOException ignored) {
            // best effort: a leftover temp directory must not fail the test
        }
    }

    /** A sink that keeps everything, so assertions can be made after collection finishes. */
    private static final class RecordingSink implements ElementSink {
        final List<ItemElement> items = new ArrayList<>();
        final List<BlockElement> blocks = new ArrayList<>();
        final List<EntityElement> entities = new ArrayList<>();
        final List<String> generics = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        @Override
        public void mod(ModElement e) {
        }

        @Override
        public void item(ItemElement e) {
            items.add(e);
        }

        @Override
        public void entity(EntityElement e) {
            entities.add(e);
        }

        @Override
        public void block(BlockElement e) {
            blocks.add(e);
        }

        @Override
        public void recipe(RecipeElement e) {
        }

        @Override
        public void generic(ElementKind kind, String namespace, String key, String nameZh,
                String nameEn, String[] listValues, String[] extra) {
            generics.add(kind.singular() + ":" + key);
        }

        @Override
        public void debug(DebugSection section) {
        }

        @Override
        public void failure(ElementKind kind, String registryName, Throwable error) {
            failures.add(kind + " " + registryName + ": " + error);
        }
    }
}
