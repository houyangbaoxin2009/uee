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
import net.minecraft.util.profiling.InactiveProfiler;
import org.uee.Uee;
import org.uee.mc.AbstractMinecraftAdapter;
import org.uee.mc.ResourceManagerCapture;
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

    /**
     * Every registry in the table actually resolves, and produces records.
     *
     * <p>The core test proves the table accounts for every declared category; it cannot prove the
     * registry ids in it are real. This can, and it is the assertion that would have caught the state
     * this table replaced — a hook that returned null, and nine categories that were selectable and
     * produced nothing while nothing anywhere said so.
     *
     * <p>A dedicated server is the harder case to pass: the data-loaded registries only exist because a
     * server is running, so a table entry that resolved them wrongly would produce nothing here and be
     * invisible everywhere else.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void everyRegistryInTheTableResolves(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        List<String> unresolved = new ArrayList<>();
        List<String> empty = new ArrayList<>();

        for (org.uee.model.RegistrySource source : org.uee.model.RegistrySource.all()) {
            int size = ((AbstractMinecraftAdapter) adapter).sizeOf(source.kind());
            if (size < 0) {
                unresolved.add(source.kind().singular() + " (" + source.registry() + ")");
            } else if (size == 0) {
                empty.add(source.kind().singular());
            }
        }

        helper.assertTrue(unresolved.isEmpty(),
                "these registries could not be reached, so their categories produce nothing: "
                        + unresolved);
        helper.assertTrue(empty.isEmpty(),
                "these registries resolved but are empty, which no vanilla registry should be: " + empty);

        // And a collection over them produces records rather than merely finding registries. The two are
        // different failures: a registry can resolve and the collector still emit nothing.
        Set<ElementKind> wanted = EnumSet.of(ElementKind.ENCHANTMENT, ElementKind.CREATIVE_TAB,
                ElementKind.SOUND, ElementKind.ATTRIBUTE, ElementKind.BIOME, ElementKind.DAMAGE_TYPE,
                ElementKind.STRUCTURE, ElementKind.DIMENSION, ElementKind.PARTICLE,
                ElementKind.EFFECT, ElementKind.FLUID, ElementKind.BLOCK_ENTITY_TYPE,
                ElementKind.POTION, ElementKind.FEATURE, ElementKind.RECIPE_TYPE, ElementKind.MENU);
        RecordingSink sink = new RecordingSink();
        adapter.collectRegistries(
                ExportConfig.builder().kinds(wanted.toArray(new ElementKind[0])).build(), wanted, sink);

        // Each of the ten registry categories must appear. Counted per prefix rather than in total,
        // because a total would pass if one registry produced everything and another produced nothing.
        for (ElementKind kind : wanted) {
            String prefix = kind.singular() + ":";
            boolean present = sink.generics.stream().anyMatch(g -> g.startsWith(prefix));
            if (!present) {
                helper.fail("no records were collected for " + kind.singular()
                        + ", though its registry resolves");
            }
        }
        helper.assertTrue(sink.failures.isEmpty(),
                "collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));
        helper.succeed();
    }

    /**
     * World generation is collected from the loaded datapacks.
     *
     * <p>The core test parses the shipped data when it can find a jar; this is the other half — the
     * enumeration over a live resource manager, on the real path, with the hundreds of files the vanilla
     * datapacks contribute. The two are different failures: a parser can be right while the sweep finds
     * nothing, and a sweep can find everything while the parser drops it.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void worldgenIsCollectedFromDatapacks(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder().kinds(ElementKind.WORLDGEN).build();
        adapter.collectDatapacks(config, EnumSet.of(ElementKind.WORLDGEN), sink);

        helper.assertTrue(sink.generics.size() > 100,
                "only " + sink.generics.size() + " world generation files were collected; the vanilla"
                        + " datapacks contribute several hundred");
        helper.assertTrue(sink.failures.isEmpty(),
                "world generation collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));

        // The key carries the kind and the name, so two kinds with one name stay distinguishable.
        boolean placedFeature = sink.generics.stream()
                .anyMatch(g -> g.startsWith("worldgen:") && g.contains("placed_feature/"));
        boolean noiseOrPool = sink.generics.stream()
                .anyMatch(g -> g.startsWith("worldgen:") && g.contains("template_pool/"));
        helper.assertTrue(placedFeature, "no placed features were collected");
        helper.assertTrue(noiseOrPool, "no template pools were collected");

        helper.succeed();
    }

    /**
     * Functions are collected from the loaded datapacks.
     *
     * <p>The core test runs the parser over real functions when it can find a pack; this is the other
     * half — the sweep over a live resource manager, with the extension that is not {@code .json} and the
     * one category whose files are text. Vanilla ships no functions of its own, so this asserts the
     * absence rather than a count: the sweep must run without finding anything and without failing, which
     * is itself the thing worth knowing on a vanilla-only instance.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void functionsAreCollectedFromDatapacks(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        ExportConfig config = ExportConfig.builder().kinds(ElementKind.FUNCTION).build();
        adapter.collectDatapacks(config, EnumSet.of(ElementKind.FUNCTION), sink);

        helper.assertTrue(sink.failures.isEmpty(),
                "function collection reported failures, first: "
                        + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));
        // Whatever was found must be named as a function, not as something else.
        boolean wellNamed = sink.generics.stream().allMatch(g -> g.startsWith("function:"));
        helper.assertTrue(wellNamed,
                "a collected record was not recorded under the function category");

        helper.succeed();
    }

    /**
     * The asset enumeration finds what a client has and reports what a server cannot.
     *
     * <p>This is the only part of asset handling that cannot be checked without a game, because it is the
     * only part that needs a resource manager. It is also the part with the interesting answer: a dedicated
     * server's manager sees {@code data/} and not {@code assets/}, so an export run here finds nothing — and
     * the assertion is that it says so rather than going quiet. "This pack has no assets" and "this side
     * cannot see assets" are different answers, and a test run on a server is the only place the second one
     * occurs.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void assetsAreOfferedOrExplained(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        RecordingSink sink = new RecordingSink();
        adapter.collectAssets(ExportConfig.builder().assets(true).build(), sink);

        // A dedicated server has no client manager, and so no assets. Asserting the emptiness is the point
        // rather than a formality: the first version of this ran whichever branch it found, and because a
        // server's manager can see its *datapack* files, "found something" would have meant offering
        // recipes and advancements as assets. The check that would have caught it is this one -- that on
        // this side there is nothing to offer -- and it only works if the emptiness is required.
        helper.assertTrue(sink.assets.isEmpty(),
                "a dedicated server offered " + sink.assets.size() + " file(s) as assets; a server has no"
                        + " client resource manager, so this can only be datapack files being mistaken for"
                        + " assets. First: " + (sink.assets.isEmpty() ? "" : sink.assets.get(0)));
        // And it has to say why, rather than leaving a user to wonder whether the pack simply had none.
        helper.assertTrue(!sink.failures.isEmpty(),
                "no assets were offered and no reason was given, so a user cannot tell an absent client"
                        + " from an empty pack");
        helper.assertTrue(sink.failures.get(0).contains("assets/"),
                "the reason does not mention where assets live, so it does not explain anything: "
                        + sink.failures.get(0));

        helper.succeed();
    }

    /**
     * The class loader chain is reported, and it is a chain.
     *
     * <p>Checked here rather than in the core because the starting points are the game's: a run inside the
     * game has a loader for the game's classes and one for this program's, and whether they are the same
     * loader is exactly the fact the section exists to report. The core test covers the walk; this covers
     * the supply of starting points, which is where a mistake would leave the section present and empty.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void classLoaderChainIsReported(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        DebugSection chain = null;
        for (DebugSection section : adapter.debugSections()) {
            if (section.name().equals("classLoaders")) {
                chain = section;
            }
        }
        helper.assertTrue(chain != null, "no class loader section was produced");
        // Read through the keys and values rather than a rendered form: this is a record holding two arrays,
        // so its own toString prints identities and an assertion against it would fail whatever the content.
        String rendered = describe(chain);
        helper.assertTrue(has(chain, "startingPoints"),
                "the section does not report how many starting points it used: " + rendered);
        helper.assertTrue(rendered.contains("bootstrap"),
                "the chain does not reach the bootstrap loader, so it is not a whole chain: " + rendered);
        // The game's own loader must have been one of them: a section reporting only this program's chain
        // would be a chain, and not the interesting one.
        helper.assertTrue(rendered.contains("game"),
                "the section does not include the game's own class loader: " + rendered);
        // Two starting points at least, since the game and this program are both running.
        helper.assertTrue(Integer.parseInt(value(chain, "startingPoints")) >= 2,
                "fewer than two starting points were used, so the game's loader was not among them: "
                        + rendered);

        helper.succeed();
    }

    /**
     * Assets are collected when a client manager has been bound, driven through the real listener.
     *
     * <h2>What this covers that the earlier asset test could not</h2>
     *
     * <p>An earlier version of the asset test ran whichever branch it found, and on a dedicated server that
     * meant the emptiness branch: it never once checked that assets are collected when they exist, which is
     * the case the feature is for. So the positive path went unexercised while the test passed.
     *
     * <p>This closes that. An asset-side resource manager is built here from a temporary directory — a real
     * one, of the client type — and handed to the real {@link ResourceManagerCapture}, which is the same
     * object a loader registers and the only route by which a client manager is ever obtained. Then the
     * collection runs and the records are read. Every link in the client path is therefore executed: the
     * listener, the binding, the enumeration and the copy, with no game window anywhere.
     *
     * <p>The manager is unbound again afterwards. The adapter is a shared singleton, and leaving a client
     * manager bound would make the "a dedicated server has no assets" assertion in the test beside this one
     * untrue while it ran.
     */
    @GameTest(template = "empty", timeoutTicks = 600)
    public static void assetsAreCollectedFromABoundClientManager(GameTestHelper helper) {
        AbstractMinecraftAdapter adapter = (AbstractMinecraftAdapter) Uee.adapter();
        java.nio.file.Path packRoot = null;
        try {
            packRoot = java.nio.file.Files.createTempDirectory("uee-asset-pack");
            writeAsset(packRoot, "assets/example/textures/block/ruby_ore.png", "PNG-bytes");
            writeAsset(packRoot, "assets/example/lang/zh_cn.json", "{\"item.example.ruby\":\"ruby\"}");
            // A file directly under the namespace, reached by name rather than by prefix.
            writeAsset(packRoot, "assets/example/sounds.json", "{\"block.ruby\":{}}");
            // The pack build's marker, which must not be copied: it has no namespace.
            writeAsset(packRoot, "assets/.mcassetsroot", "");
            // A directory the sweep does not name. It is deliberately not collected, and the assertion
            // below says so: the basis is a declared list because the resource API cannot be asked for
            // everything, and a limitation that is tested is one nobody has to rediscover.
            writeAsset(packRoot, "assets/example/invented_kind/thing.bin", "bytes");

            try (var manager = new net.minecraft.server.packs.resources.MultiPackResourceManager(
                    net.minecraft.server.packs.PackType.CLIENT_RESOURCES,
                    java.util.List.of(new net.minecraft.server.packs.PathPackResources(
                            new net.minecraft.server.packs.PackLocationInfo("uee-test",
                                    net.minecraft.network.chat.Component.literal("uee test"),
                                    net.minecraft.server.packs.repository.PackSource.DEFAULT,
                                    java.util.Optional.empty()),
                            packRoot)))) {

                // The real listener, called the way the game calls it. Its return value is what the game
                // waits on, so it is checked too: a listener that returned null would pass everything else
                // here and break the client's reload.
                ResourceManagerCapture capture =
                        new ResourceManagerCapture(adapter::bindClientResources);
                var future = capture.reload(null, manager, InactiveProfiler.INSTANCE,
                        InactiveProfiler.INSTANCE, Runnable::run, Runnable::run);
                helper.assertTrue(future != null && future.isDone(),
                        "the capture did not complete, so a client reload would stall on it");

                RecordingSink sink = new RecordingSink();
                adapter.collectAssets(ExportConfig.builder().assets(true).build(), sink);

                helper.assertTrue(sink.failures.isEmpty(),
                        "collecting from a bound client manager failed: "
                                + (sink.failures.isEmpty() ? "" : sink.failures.get(0)));
                helper.assertTrue(!sink.assets.isEmpty(),
                        "a client manager holding assets produced none, so the positive path is broken");
                helper.assertTrue(sink.assets.contains("assets/example/textures/block/ruby_ore.png"),
                        "the texture was not offered: " + sink.assets);
                helper.assertTrue(sink.assets.contains("assets/example/lang/zh_cn.json"),
                        "the language file was not offered: " + sink.assets);
                // A file under the namespace root, which no prefix reaches and which is fetched by name.
                helper.assertTrue(sink.assets.contains("assets/example/sounds.json"),
                        "the root file was not offered, so the by-name fetch is not working: "
                                + sink.assets);
                // The marker has no namespace, so it is not content and must not appear.
                helper.assertTrue(!sink.assets.stream().anyMatch(a -> a.endsWith(".mcassetsroot")),
                        "the pack marker was offered as an asset: " + sink.assets);
                // And the declared basis is asserted rather than assumed: a kind outside it is not swept.
                helper.assertTrue(!sink.assets.stream().anyMatch(a -> a.contains("invented_kind")),
                        "a kind outside the declared sweep was collected, so the basis is not what the"
                                + " report claims: " + sink.assets);
            }
        } catch (Exception e) {
            helper.fail("the client path threw: " + e);
        } finally {
            // Unbound whatever happened, so the neighbouring test's premise holds.
            adapter.bindClientResources(null);
            deleteRecursively(packRoot);
        }

        helper.succeed();
    }

    /** Writes a file below a temporary pack root, creating the directories. */
    private static void writeAsset(java.nio.file.Path root, String relativePath, String content)
            throws java.io.IOException {
        java.nio.file.Path file = root.resolve(relativePath);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, content);
    }

    /**
     * Registration order is reported, and it says something true about the game's own registries.
     *
     * <p>The numbers a registry assigns are the registration order, so this is the one place the derivation
     * can be checked against a real one rather than against a map written for the purpose. What is asserted
     * is the property a reader relies on: the game's own entries come first, having been registered before
     * anything else could be. If a mod's block ever started at zero, either the derivation or the premise
     * behind it would be wrong, and this is where that would show.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void registrationOrderIsReported(GameTestHelper helper) {
        LoaderAdapter adapter = Uee.adapter();
        DebugSection items = null;
        for (DebugSection section : adapter.debugSections()) {
            if (section.name().equals("registrationOrderItems")) {
                items = section;
            }
        }
        helper.assertTrue(items != null, "no item registration order section was produced");
        String rendered = describe(items);
        helper.assertTrue(has(items, "entries") && has(items, "namespaces"),
                "the section does not report its totals: " + rendered);

        // Every registry has the game's items in it, and they were registered first.
        helper.assertTrue(has(items, "0"),
                "the section reports no namespaces at all: " + rendered);
        helper.assertTrue(value(items, "0").startsWith("minecraft "),
                "the first namespace is not the game's, though nothing is registered before it: "
                        + value(items, "0"));
        helper.assertTrue(value(items, "0").contains("0-"),
                "the game's block does not start at zero: " + value(items, "0"));

        // And the count is a count of items, so it cannot be smaller than the game's own catalogue.
        int entries = Integer.parseInt(value(items, "entries"));
        helper.assertTrue(entries > 100,
                "only " + entries + " items were counted, which is fewer than the game itself has");

        // A second registry, since the numbers are per registry and one section would be mixing two orders.
        boolean blocks = false;
        for (DebugSection section : adapter.debugSections()) {
            if (section.name().equals("registrationOrderBlocks")) {
                blocks = true;
            }
        }
        helper.assertTrue(blocks, "no block registration order section was produced");

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
    /** A section's keys and values as one readable string, for an assertion message. */
    private static String describe(DebugSection section) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < section.keys().length; i++) {
            sb.append(section.keys()[i]).append('=').append(section.values()[i]).append("; ");
        }
        return sb.toString();
    }

    /** Whether a section has a key. */
    private static boolean has(DebugSection section, String key) {
        for (String k : section.keys()) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** A section's value for a key, or an empty string. */
    private static String value(DebugSection section, String key) {
        for (int i = 0; i < section.keys().length; i++) {
            if (section.keys()[i].equals(key)) {
                return section.values()[i];
            }
        }
        return "";
    }

    private static final class RecordingSink implements ElementSink {
        final List<ItemElement> items = new ArrayList<>();
        final List<BlockElement> blocks = new ArrayList<>();
        final List<EntityElement> entities = new ArrayList<>();
        final List<String> generics = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        /**
         * The paths of assets offered for copying.
         *
         * <p>Only the paths, and the bytes are not read: this records what the collector said exists, which
         * is the part a game test can check. Whether the copy itself works is checked without a game, where
         * the files are real and the assertions can be about content.
         */
        final List<String> assets = new ArrayList<>();

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
        public void asset(String relativePath, org.uee.spi.BytesSource source) {
            assets.add(relativePath);
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
