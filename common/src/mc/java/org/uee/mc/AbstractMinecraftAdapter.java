package org.uee.mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import org.uee.config.ExportConfig;
import org.uee.datapack.TagFile;
import org.uee.datapack.WorldgenFile;
import org.uee.util.OrderedWork;
import org.uee.analysis.ClassLoaderChain;
import org.uee.analysis.RegistrationOrder;
import org.uee.asset.AssetPath;
import org.uee.asset.AssetSweep;
import org.uee.datapack.AdvancementFile;
import org.uee.datapack.FunctionFile;
import org.uee.datapack.LangFile;
import org.uee.datapack.LootTableFile;
import org.uee.datapack.FunctionFlow;
import org.uee.debug.MixinConfig;
import org.uee.debug.ModContainerScanner;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.RegistrySource;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.spi.ElementSink;
import org.uee.spi.LoaderAdapter;
import org.uee.spi.LoaderInfo;

/**
 * Shared collection logic for every Minecraft loader.
 *
 * <p>This class is where the thin-SPI design pays off. "Enumerate the item registry and build
 * records" is identical on all four loaders once the game classes are mapped to the same names, so
 * it is written once here; a loader module supplies only what is genuinely loader-specific — the
 * mod list, the loader identity, where language data comes from, and how to reach the recipe
 * manager.
 *
 * <p>It lives in {@code common/src/mc/java} rather than {@code common/src/main/java} on purpose:
 * everything here needs the game classes, whereas the core next door compiles and is tested with no
 * game present at all.
 *
 * <p>Version adaptation is done by capability detection, never by comparing version strings —
 * {@link #supports(String)} is the mechanism, and callers branch on it.
 */
public abstract class AbstractMinecraftAdapter implements LoaderAdapter {

    // ---------------------------------------------------------------- loader-specific surface

    protected abstract String loaderName();

    protected abstract String loaderVersion();

    protected abstract String minecraftVersion();

    protected abstract String gameDirectory();

    protected abstract boolean isClient();

    protected abstract List<ModElement> loadedMods();

    /**
     * Live recipes, or an empty collection when no game is running. Returning empty rather than
     * throwing keeps a headless run useful: everything except recipes still exports.
     */
    protected abstract Collection<RecipeHolder<?>> recipes();

    // The two render hooks that used to live here are gone. They returned null and were called from the
    // collection path, which meant an export with icons on would have rendered in the middle of a
    // streaming pass -- the one thing the data phase is defined not to do. Rendering now belongs to
    // IconRenderer, which is reached only from the icon phase.

    // ---------------------------------------------------------------- shared surface

    @Override
    public LoaderInfo info() {
        return new LoaderInfo(loaderName(), loaderVersion(), minecraftVersion(), gameDirectory(),
                isClient(), !isClient(), System.getProperty("java.version"),
                System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    @Override
    public List<org.uee.model.ModElement> mods() {
        return loadedMods();
    }

    @Override
    public boolean supports(String capability) {
        return switch (capability) {
            case CAP_REGISTRY_FROZEN -> true;
            case CAP_DATA_COMPONENTS -> true;
            case CAP_MOD_DEPENDENCIES -> true;
            case CAP_HEADLESS_RESOURCES -> !isClient();
            default -> false;
        };
    }

    @Override
    public void collectRegistries(ExportConfig config, Collection<ElementKind> wanted,
            ElementSink sink) {
        if (wanted.contains(ElementKind.ITEM)) {
            collectItems(config, sink);
        }
        if (wanted.contains(ElementKind.BLOCK)) {
            collectBlocks(config, sink);
        }
        if (wanted.contains(ElementKind.ENTITY)) {
            collectEntities(config, sink);
        }
        collectGenericRegistries(config, wanted, sink);
    }

    /**
     * Scans the mod containers once and keeps the results.
     *
     * <p>Scanned lazily and only once: several analyses read the same facts, and re-walking hundreds
     * of jars per analysis would make the diagnostics cost more than the export they accompany. Pure
     * file work — no game state — so it is safe at any point, including before registries are frozen.
     *
     * <p>Only facts here, no conclusions. Deciding that two mods patching one class is a problem
     * belongs to the analysis module, which keeps a new check from requiring four loader changes.
     */
    @Override
    public Map<String, ModContainerScanner.ContainerInfo> containers() {
        gatherFacts();
        return containers;
    }

    @Override
    public List<MixinConfig> mixinConfigs() {
        gatherFacts();
        return mixins;
    }

    private void gatherFacts() {
        if (factsGathered) {
            return;
        }
        factsGathered = true;
        for (ModElement mod : loadedMods()) {
            Path container = mod.container();
            if (container == null) {
                continue;
            }
            ModContainerScanner.ContainerInfo info = ModContainerScanner.inspect(container);
            if (info == null) {
                continue;
            }
            containers.put(mod.id(), info);
            mixins.addAll(ModContainerScanner.readMixinConfigs(info, mod.id()));
        }
    }

    /**
     * Flow functions found in the active datapacks.
     *
     * <p>Only the loader can enumerate a datapack's functions, so the subclass supplies the list of
     * function resource paths and the convention is applied here. Keeping the naming rule shared
     * means all four loaders agree on where a flow function lives, rather than each deciding.
     */
    @Override
    public List<FunctionFlow> functionFlows() {
        List<FunctionFlow> out = new ArrayList<>(4);
        for (String resourcePath : datapackFunctionPaths()) {
            String name = FunctionFlow.flowNameOf(resourcePath);
            if (name == null) {
                continue;
            }
            String namespace = FunctionFlow.namespaceOf(resourcePath);
            if (namespace == null) {
                continue;
            }
            out.add(new FunctionFlow(name, namespace, resourcePath, null));
        }
        return out;
    }

    /**
     * Every function resource path in the active datapacks.
     *
     * <p>Left to the loader because enumerating functions is loader API. Returning an empty list is a
     * valid answer — it simply means no function flows are offered, while configuration flows and
     * every command keep working.
     */
    protected List<String> datapackFunctionPaths() {
        return List.of();
    }

    /**
     * The resource manager for the current server or client, bound once loading finishes.
     *
     * <p>Held here rather than in each loader because all four need exactly this and nothing
     * loader-specific about it: the concrete adapters had four copies of the field, and three of them
     * also had four copies of the setter. Any datapack-backed collection — tags today, loot tables and
     * functions later — needs to read resources, so the shared layer is where the handle belongs.
     */
    private volatile net.minecraft.server.packs.resources.ResourceManager resources;

    /**
     * Language tables, built on first use and dropped when resources are rebound.
     *
     * <p>Held here for the same reason as the resource manager: all four adapters had an identical
     * copy of the field, of the lazy initialiser and of the invalidation. Four copies of a three-part
     * invariant is four chances for one of them to be missing a part, which is exactly what had
     * happened — one adapter did not invalidate.
     */
    private volatile Translator translator;

    /** Guards the one-time construction of {@link #translator}. See {@link #translator()}. */
    private final Object translatorLock = new Object();

    /**
     * Sizes the exported icon sizes are named by, shared so the phase and the reader cannot disagree.
     *
     * <p>An item gets a large and a small icon; an entity only a large one. These are the sizes the icon
     * store is keyed by, so a change here has to reach both the phase that renders and the collection
     * that reads, which is why they are constants rather than literals at each use.
     */
    protected static final int ICON_LARGE = 128;
    protected static final int ICON_SMALL = 32;

    /**
     * Where rendered icons are read from, bound by whoever ran the icon phase.
     *
     * <p>Null when icons were not requested or the phase has not run, which is the default and by far the
     * common case: icons are opt-in and cost a separate client-side pass.
     */
    private volatile org.uee.icon.IconStore icons;

    /** Container inspections, filled on first use. */
    private final Map<String, ModContainerScanner.ContainerInfo> containers = new HashMap<>();
    /** Mixin configs read from those containers, filled on first use. */
    private final List<MixinConfig> mixins = new ArrayList<>();
    private boolean factsGathered;

    @Override
    public void collectDatapacks(ExportConfig config, Collection<ElementKind> wanted,
            ElementSink sink) {
        if (wanted.contains(ElementKind.RECIPE)) {
            collectRecipes(config, sink);
        }
        if (wanted.contains(ElementKind.TAG)) {
            collectTags(config, sink);
        }
        if (wanted.contains(ElementKind.LOOT_TABLE)) {
            collectLootTables(config, sink);
        }
        if (wanted.contains(ElementKind.LANG)) {
            collectLangs(config, sink);
        }
        if (wanted.contains(ElementKind.ADVANCEMENT)) {
            collectAdvancements(config, sink);
        }
        if (wanted.contains(ElementKind.WORLDGEN)) {
            collectWorldgen(config, sink);
        }
        if (wanted.contains(ElementKind.FUNCTION)) {
            collectFunctions(config, sink);
        }
    }

    @Override
    public List<DebugSection> debugSections() {
        List<DebugSection> sections = new ArrayList<>(4);
        Runtime rt = Runtime.getRuntime();
        sections.add(DebugSection.of("environment",
                "loader", loaderName(),
                "loaderVersion", String.valueOf(loaderVersion()),
                "minecraftVersion", String.valueOf(minecraftVersion()),
                "client", String.valueOf(isClient()),
                "gameDirectory", String.valueOf(gameDirectory())));
        sections.add(DebugSection.of("runtime",
                "javaVersion", System.getProperty("java.version"),
                "javaVendor", System.getProperty("java.vendor"),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.version"),
                "arch", System.getProperty("os.arch"),
                "availableProcessors", String.valueOf(rt.availableProcessors()),
                "maxMemoryBytes", String.valueOf(rt.maxMemory())));
        sections.add(DebugSection.of("registrySizes",
                "item", String.valueOf(BuiltInRegistries.ITEM.size()),
                "block", String.valueOf(BuiltInRegistries.BLOCK.size()),
                "entity_type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.size()),
                "mob_effect", String.valueOf(BuiltInRegistries.MOB_EFFECT.size()),
                "fluid", String.valueOf(BuiltInRegistries.FLUID.size()),
                // Read through the same table the collection uses, so a size reported here cannot
                // disagree with what the run actually walks. The two hooks that used to stand here
                // returned -1 and were never overridden, which is how a category can look measured and
                // be empty.
                "enchantment", String.valueOf(sizeOf(ElementKind.ENCHANTMENT)),
                "biome", String.valueOf(sizeOf(ElementKind.BIOME)),
                "creative_tab", String.valueOf(sizeOf(ElementKind.CREATIVE_TAB)),
                "attribute", String.valueOf(sizeOf(ElementKind.ATTRIBUTE))));
        sections.add(DebugSection.of("mods",
                "count", String.valueOf(loadedMods().size())));
        sections.add(classLoaderSection());
        sections.addAll(registrationOrderSection());
        return sections;
    }

    /**
     * The class loader chain, as a debug section.
     *
     * <h2>Which starting points, and why these</h2>
     *
     * <p>Three, all of them obtainable without touching a loader's API and none of them Minecraft's:
     * the loader that defined this program's classes, the loader the game's own classes came from, and the
     * thread's context loader — which is the one a loader usually installs so that code it calls can find
     * the mods.
     *
     * <p>The interesting result is how many <em>distinct</em> loaders those three landed on. Three on one
     * loader means everything in play shares a namespace and can see each other; three on three means the
     * boundaries are real and are why a class can exist and not be found.
     *
     * <h2>What is not reported</h2>
     *
     * <p>Which loader serves which mod. The loaders' public APIs do not expose it — NeoForge's and Fabric's
     * {@code ModContainer} both stop short of the loader that defined the mod's classes — so it is left out
     * rather than inferred. A per-mod mapping that was guessed would look like the answer to the question
     * this section exists for, which is worse than the section answering a smaller question honestly.
     */
    private DebugSection classLoaderSection() {
        Map<String, ClassLoader> starting = new java.util.LinkedHashMap<>(3);
        starting.put("uee", getClass().getClassLoader());
        ClassLoader game = gameClassLoader();
        if (game != null) {
            starting.put("game", game);
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null) {
            starting.put("context", context);
        }

        Map<String, String> pairs = ClassLoaderChain.from(starting).asPairs();
        String[] flat = new String[pairs.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            flat[i++] = entry.getKey();
            flat[i++] = entry.getValue();
        }
        return DebugSection.of("classLoaders", flat);
    }

    /**
     * The order entries were registered in, for the registries where that is visible.
     *
     * <h2>Where the order comes from</h2>
     *
     * <p>A registry hands out a sequential number as each entry is registered, so the numbers are the order,
     * and grouping them by namespace shows who went first — the game, then each mod's block. That is the
     * question a reader has about order: not the number of the nine-thousandth item, but which mods came in
     * what sequence.
     *
     * <h2>Which registries</h2>
     *
     * <p>Items and blocks, which are the two whose numbers anything else tends to be derived from — a block's
     * number is its item's in most cases, and both are large enough that the grouping is meaningful. It is
     * not every registry: the ones built from a data pack have their order decided by the packs rather than
     * by registration, and reporting them here would answer a different question under this one's name.
     *
     * <p>Two sections rather than one, because the numbers are per registry and a single section mixing item
     * numbers with block numbers would be a list of two interleaved orders.
     */
    private List<DebugSection> registrationOrderSection() {
        List<DebugSection> out = new ArrayList<>(2);
        addRegistrationOrder(out, "registrationOrderItems", BuiltInRegistries.ITEM);
        addRegistrationOrder(out, "registrationOrderBlocks", BuiltInRegistries.BLOCK);
        return out;
    }

    /**
     * Adds one registry's order, if its numbers can be read.
     *
     * <p>Wrapped because a registry this is asked of might not be the kind whose numbers are meaningful, and
     * a debug section is the last place that should fail a run. A registry that cannot answer is reported as
     * such rather than left out, so its absence is a fact in the output instead of a silence.
     */
    private static <T> void addRegistrationOrder(List<DebugSection> out, String name,
            net.minecraft.core.Registry<T> registry) {
        try {
            Map<Integer, String> ids = new java.util.LinkedHashMap<>();
            for (ResourceLocation key : registry.keySet()) {
                T value = registry.get(key);
                if (value == null) {
                    continue;
                }
                ids.put(registry.getId(value), key.getNamespace());
            }
            Map<String, String> pairs = RegistrationOrder.of(ids).asPairs();
            String[] flat = new String[pairs.size() * 2];
            int i = 0;
            for (Map.Entry<String, String> entry : pairs.entrySet()) {
                flat[i++] = entry.getKey();
                flat[i++] = entry.getValue();
            }
            out.add(DebugSection.of(name, flat));
        } catch (Throwable t) {
            out.add(DebugSection.of(name, "error", String.valueOf(t)));
        }
    }

    /**
     * The loader the game's own classes came from, or {@code null} when there is no game on the class path.
     *
     * <p>Asked of a class the game always has, and its loader is the fact rather than any of the class's
     * own behaviour. A headless run that has not loaded the game gets null and reports the chain it can
     * see, which is the honest answer rather than an empty section.
     */
    protected ClassLoader gameClassLoader() {
        try {
            return net.minecraft.server.MinecraftServer.class.getClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- item collection

    private void collectItems(ExportConfig config, ElementSink sink) {
        Registry<Item> registry = BuiltInRegistries.ITEM;
        collectRegistry(config, ElementKind.ITEM, registry.keySet(), sink, id -> {
            Item item = registry.get(id);
            // A null means the id is no longer held, which is a decision not to record rather than a
            // failure. Isolation of a malformed element is handled by the caller of this lambda.
            return item == null ? null : new Prepared.Item(buildItem(id, item, config));
        });
    }

    private ItemElement buildItem(ResourceLocation id, Item item, ExportConfig config) {
        ItemStack stack = new ItemStack(item);
        String key = item.getDescriptionId();
        boolean blockItem = item instanceof BlockItem;
        // Read, not rendered. Rendering needs the client's render thread and the game loop, so it happens
        // in the icon phase, which runs before this one; collecting here would put rendering back inside
        // the data phase, which is the one thing that phase is defined not to do.
        byte[] large = iconOf(ElementKind.ITEM, id.toString(), ICON_LARGE);
        byte[] small = iconOf(ElementKind.ITEM, id.toString(), ICON_SMALL);
        return new ItemElement(
                id.toString(),
                id.getNamespace(),
                key,
                translator().translate(key, Translator.ZH_CN),
                translator().translate(key, Translator.EN_US),
                stack.getMaxStackSize(),
                stack.getMaxDamage(),
                tagIds(stack.getTags()),
                new String[0],
                large,
                small,
                blockItem);
    }

    // ---------------------------------------------------------------- block collection

    /**
     * Collects blocks.
     *
     * <p>Three accessors used here are marked deprecated in this Minecraft version and have no
     * replacement in it: the context-aware variants that supersede them
     * ({@code getSoundType(LevelReader, BlockPos, Entity)} and friends) arrive in a later release. UEE
     * also has no position to supply even if they existed, because an export runs over the whole
     * registry rather than at a place in a world.
     *
     * <p>Suppressed deliberately rather than left to warn on every build: the deprecation is
     * forward-looking, nothing here can act on it, and a warning nobody can clear is one everybody
     * learns to ignore.
     */
    private void collectBlocks(ExportConfig config, ElementSink sink) {
        collectRegistry(config, ElementKind.BLOCK, BuiltInRegistries.BLOCK.keySet(), sink, id -> {
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(id);
            return block == null ? null : new Prepared.Block(buildBlock(id, block));
        });
    }

    /**
     * Builds one block record.
     *
     * <p>Three accessors used here are marked deprecated in this Minecraft version and have no
     * replacement in it: the context-aware variants that supersede them
     * ({@code getSoundType(LevelReader, BlockPos, Entity)} and friends) arrive in a later release. UEE
     * also has no position to supply even if they existed, because an export runs over the whole
     * registry rather than at a place in a world.
     *
     * <p>Suppressed deliberately rather than left to warn on every build: the deprecation is
     * forward-looking, nothing here can act on it, and a warning nobody can clear is one everybody
     * learns to ignore.
     */
    @SuppressWarnings("deprecation")
    private BlockElement buildBlock(ResourceLocation id, Block block) {
        String key = block.getDescriptionId();
        return new BlockElement(
                id.toString(),
                id.getNamespace(),
                translator().translate(key, Translator.ZH_CN),
                translator().translate(key, Translator.EN_US),
                block.defaultDestroyTime(),
                block.getExplosionResistance(),
                block.defaultBlockState().getLightEmission(),
                block.asItem() != net.minecraft.world.item.Items.AIR,
                String.valueOf(block.defaultBlockState().getSoundType()),
                new String[0]);
    }

    // ---------------------------------------------------------------- entity collection

    private void collectEntities(ExportConfig config, ElementSink sink) {
        collectRegistry(config, ElementKind.ENTITY, BuiltInRegistries.ENTITY_TYPE.keySet(), sink, id -> {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null) {
                return null;
            }
            String key = type.getDescriptionId();
            MobCategory category = type.getCategory();
            return new Prepared.Entity(new EntityElement(
                    id.toString(),
                    id.getNamespace(),
                    key,
                    translator().translate(key, Translator.ZH_CN),
                    translator().translate(key, Translator.EN_US),
                    category == null ? null : category.getName(),
                    // Read from the icon store, not rendered: see the note in `buildItem`. This is why
                    // `parallelism` no longer has to be conservative about icons -- collection no longer
                    // goes anywhere near the render pipeline.
                    iconOf(ElementKind.ENTITY, id.toString(), ICON_LARGE)));
        });
    }

    // ---------------------------------------------------------------- other registries

    /**
     * Walks every registry-backed category.
     *
     * <h2>Why this reads a table instead of naming registries itself</h2>
     *
     * <p>What used to be here named two registries and called one hook that returned null, so nine
     * categories that the configuration, the command surface and the vocabulary all advertised produced
     * nothing — including two in the default set. Nothing could tell the difference between a category
     * with no collector and a category whose pack had no content.
     *
     * <p>Now the list lives in {@link RegistrySource}, one entry per category, and this walks it. The
     * table is checked by a test that fails if any declared category is accounted for by nothing, which is
     * the part that would have caught the nine: the question is only askable if the answer is written
     * down in one place.
     *
     * <p>Both families are read the same way. A built-in registry is looked up in
     * {@code BuiltInRegistries.REGISTRY}, which is itself a registry of registries, and a data-loaded one
     * through the server's registry access. So a category's location is data rather than a branch, and
     * adding one is a line in the table.
     */
    private void collectGenericRegistries(ExportConfig config, Collection<ElementKind> wanted,
            ElementSink sink) {
        for (RegistrySource source : RegistrySource.forKinds(
                wanted instanceof java.util.Set<ElementKind> set ? set
                        : EnumSet.copyOf(wanted))) {
            Registry<?> registry = registryOf(source);
            if (registry == null) {
                // Legitimately unreachable rather than broken: a data-loaded registry needs a running
                // server to exist, and a collection attempted without one has nothing to read. The
                // category is simply absent from the output, which is the same thing that would happen
                // if the pack had no entries.
                continue;
            }
            ElementKind kind = source.kind();
            collectRegistry(config, kind, registry.keySet(), sink, id -> {
                Object entry = registry.get(id);
                String[] names = namesOf(source, id, entry);
                return new Prepared.Generic(kind, id.getNamespace(), id.toString(), names[0], names[1],
                        new String[0], new String[0]);
            });
        }
    }

    /**
     * The registry a source names, or {@code null} when it cannot be reached.
     *
     * <p>A built-in registry is always reachable. A data-loaded one is only reachable while a server is
     * running, because the server is what holds it — which is why the registry access is bound by the
     * loaders rather than looked up here, and why an unbound one yields nothing instead of an error.
     */
    private Registry<?> registryOf(RegistrySource source) {
        ResourceLocation location = ResourceLocation.tryParse(source.registry());
        if (location == null) {
            return null;
        }
        if (source.dynamic()) {
            RegistryAccess access = registryAccess;
            if (access == null) {
                return null;
            }
            return access.registry(ResourceKey.createRegistryKey(location)).orElse(null);
        }
        return BuiltInRegistries.REGISTRY.get(location);
    }

    /**
     * An entry's name pair, according to how its registry says names are found.
     *
     * <p>The three conventions are not interchangeable, and the differences were read off the shipped
     * language files rather than assumed. Two assumptions that would have been made here are wrong:
     * fluids have no language key of their own (their block does), and an attribute's key is not
     * derivable from its id — the entry reports {@code attribute.name.generic.max_health} while the
     * obvious transform produces {@code attribute.minecraft.max_health}, which does not exist. So a
     * registry either states its convention or the entry is asked, and neither is guessed.
     *
     * <p>Returns nulls when there is no name, which the record shape already handles: the fields are
     * omitted rather than written empty, so a consumer sees "no name" rather than an empty one.
     */
    private String[] namesOf(RegistrySource source, ResourceLocation id, Object entry) {
        switch (source.entryNames()) {
            case LANG_KEY -> {
                String key = id.toLanguageKey(source.namePrefix());
                return new String[] {
                    translator().translate(key, Translator.ZH_CN),
                    translator().translate(key, Translator.EN_US)};
            }
            case FROM_ENTRY -> {
                String key = descriptionIdOf(entry);
                if (key == null) {
                    return new String[] {null, null};
                }
                return new String[] {
                    translator().translate(key, Translator.ZH_CN),
                    translator().translate(key, Translator.EN_US)};
            }
            default -> {
                return new String[] {null, null};
            }
        }
    }

    /**
     * The language key an entry reports for itself, or {@code null} when it does not report one.
     *
     * <p>Type-tested rather than declared, because the alternative is a per-kind branch at the call site —
     * which is how the categories got out of step in the first place. Only the kinds whose entries
     * genuinely know their own key appear here; a kind that does not will not be added to the table with
     * this convention, and the table says which convention it uses.
     */
    private static String descriptionIdOf(Object entry) {
        if (entry instanceof net.minecraft.world.entity.ai.attributes.Attribute attribute) {
            return attribute.getDescriptionId();
        }
        return null;
    }

    /**
     * Binds the resource manager, discarding anything derived from the previous one.
     *
     * <p>The translator is dropped rather than kept because a resource reload can change language
     * files, and a cached table would then serve names from the pack that was loaded before — silently,
     * since nothing about a stale translation looks wrong. One of the four adapters did not do this,
     * which is the kind of divergence that copies produce; having one implementation removes the
     * possibility.
     */
    /**
     * The client's resource manager, when one has been bound.
     *
     * <h2>Why this is a second binding and not the same one</h2>
     *
     * <p>A resource manager is built for one side of the game and can only see that side: a server's sees
     * {@code data/}, a client's sees {@code assets/}. So the two are not interchangeable and binding one
     * over the other does not extend what can be read — it <em>replaces</em> it. That is not a subtlety: an
     * earlier version bound the client's manager to the same field the data categories use, which would have
     * left a client able to read assets and unable to read its own datapacks.
     *
     * <p>Kept separate from {@link #bindResources} so each side keeps its own, and so a loader that has no
     * client hook simply leaves this null and produces no assets rather than producing the wrong ones. The
     * second point is the one that matters: with one manager, a server run would have offered its
     * <em>datapack</em> files as assets, because a manager asked for everything returns everything it can
     * see and nothing in a resource location says which side it came from.
     */
    private volatile net.minecraft.server.packs.resources.ResourceManager clientResources;

    /** Binds the client's resource manager, which is the only one that can see assets. */
    public void bindClientResources(
            net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        this.clientResources = resourceManager;
    }

    public void bindResources(net.minecraft.server.packs.resources.ResourceManager resourceManager) {
        this.resources = resourceManager;
        this.translator = null;
    }

    /**
     * The bound resource manager, or {@code null} if loading has not reached that point yet.
     *
     * <p>Null is a real state — a collection attempted before resources exist should report that it
     * found nothing rather than throw — so callers check rather than assume.
     */
    protected net.minecraft.server.packs.resources.ResourceManager resources() {
        return resources;
    }

    /**
     * The translator for the bound resources, built on first use.
     *
     * <p>With no resources bound it answers with the empty translator rather than null: every caller
     * wants a name for a key, and "no translation available" is a usable answer while a null check at
     * every call site is not. Building the tables reads every namespace's language files, so it is
     * deferred until something actually asks for a name — a run that collects no translated fields
     * never pays for it.
     */
    protected Translator translator() {
        Translator current = translator;
        if (current == null) {
            // Double-checked, and the check is not an optimisation: building the tables reads every
            // namespace's language files into two hash maps, so two workers racing here would each build
            // a full copy and throw one away. Correct either way, but wasteful enough to matter now that
            // preparation runs on several threads -- and the waste would be invisible, showing up only as
            // memory that the fenced live-set measurement does not attribute to anything.
            synchronized (translatorLock) {
                current = translator;
                if (current == null) {
                    net.minecraft.server.packs.resources.ResourceManager manager = resources;
                    current = manager == null ? Translator.none() : new ResourceManagerTranslator(manager);
                    translator = current;
                }
            }
        }
        return current;
    }

    /**
     * Binds the icons to read while collecting, or {@code null} to collect without any.
     *
     * <p>Bound rather than looked up so collection has no opinion about where icons come from. The icon
     * phase decides that, and a run without icons never touches the store at all.
     */
    public void bindIcons(org.uee.icon.IconStore store) {
        this.icons = store;
    }

    /** The icons bound for this run, or {@code null}. */
    protected org.uee.icon.IconStore icons() {
        return icons;
    }

    /**
     * One rendered icon, or {@code null} when there is none.
     *
     * <p>Returns null rather than throwing for every reason an icon can be missing — no store bound, the
     * element was not rendered, the file was removed — because a missing icon is a normal state and not
     * an error: the record is still perfectly usable, and a reader treats "no icon" as "look it up
     * yourself". An exception here would turn a cosmetic gap into a failed export.
     */
    protected byte[] iconOf(ElementKind kind, String id, int size) {
        org.uee.icon.IconStore store = icons;
        if (store == null || !store.renders(kind.singular())) {
            return null;
        }
        try {
            return store.get(kind.singular(), id, size);
        } catch (Throwable t) {
            // Reported once per affected element rather than swallowed silently, since a store that
            // cannot be read would otherwise produce an export with every icon quietly missing.
            org.uee.Uee.reportFailure("icon for " + kind.singular() + " " + id + " could not be read", t);
            return null;
        }
    }

    // ---------------------------------------------------------------- datapack content

    /**
     * Something a worker prepared, ready to hand to the sink.
     *
     * <p>Preparation is the half that can run on several threads; emitting is not, because the sink feeds
     * per-shard buffers that are appended in sequence. So a worker never touches the sink — it returns one
     * of these and the calling thread makes the call. That also keeps every failure report on one thread,
     * which matters more than it looks: the report collector is not synchronised, and two threads adding
     * to it would lose entries under load, which is exactly when failures are most likely.
     */
    private sealed interface Prepared {

        /** One item record. */
        record Item(org.uee.model.ItemElement element) implements Prepared {
        }

        /** One block record. */
        record Block(org.uee.model.BlockElement element) implements Prepared {
        }

        /** One entity record. */
        record Entity(org.uee.model.EntityElement element) implements Prepared {
        }

        /** One recipe record. */
        record Recipe(org.uee.model.RecipeElement element) implements Prepared {
        }

        /**
         * A record with no dedicated sink method, written through the generic channel.
         *
         * <p>Carries the display names because some generic categories have them — an effect's
         * translation key is real data — and the ones that do not pass null rather than the record
         * pretending every category is nameless.
         */
        record Generic(ElementKind kind, String namespace, String key, String nameZh, String nameEn,
                String[] values, String[] extra) implements Prepared {
        }

        /** A problem with one item, reported as a failure against the category. */
        record Bad(ElementKind kind, String name, Throwable error) implements Prepared {
        }

        /** A problem worth a message but not a failure against an item. */
        record Note(String message, Throwable error) implements Prepared {
        }
    }

    /** Makes the sink call for one prepared item. Always called on the collecting thread. */
    private static void emit(ElementSink sink, Prepared prepared) {
        switch (prepared) {
            case Prepared.Item p -> sink.item(p.element());
            case Prepared.Block p -> sink.block(p.element());
            case Prepared.Entity p -> sink.entity(p.element());
            case Prepared.Recipe p -> sink.recipe(p.element());
            case Prepared.Generic e -> sink.generic(e.kind(), e.namespace(), e.key(), e.nameZh(),
                    e.nameEn(), e.values(), e.extra());
            case Prepared.Bad b -> sink.failure(b.kind(), b.name(), b.error());
            case Prepared.Note n -> org.uee.Uee.reportFailure(n.message(), n.error());
        }
    }

    /**
     * How many workers collection may use, which is not always what the configuration asked for.
     *
     * <p>The adapter's own declaration overrides the setting. {@code registry_frozen} says the registries
     * are frozen and safe to read off the thread that owns them, and an adapter that has not declared it
     * is saying the opposite — so running its collection across workers would contradict the promise it
     * made, and a fault that only appears under load is the hardest kind to trace back to a decision made
     * here. The same declaration already gates running the whole export off the server thread, so this
     * reuses a promise the adapter has already made rather than inventing a second one.
     *
     * <p>Configuration can therefore make collection more conservative, but never less: there is no
     * setting that reads registries from workers on an adapter that said not to.
     *
     * <p>There used to be a second condition here, disabling workers when icons were enabled, because
     * rendering went through the client's render thread and collection called it inline. Collection no
     * longer renders — icons come from the icon phase, which is where rendering belongs — so the
     * condition became unnecessary rather than wrong. Removing it is the point of separating the phases:
     * the constraint disappeared instead of having to be remembered.
     */
    private int parallelism(ExportConfig config) {
        if (config.threads() <= 1) {
            return 1;
        }
        if (!supports(CAP_REGISTRY_FROZEN)) {
            return 1;
        }
        return config.threads();
    }

    /**
     * Prepares every key on worker threads and emits the results in order.
     *
     * <p>Workers prepare, this thread emits, and the results arrive in the order the keys were given —
     * so the output does not depend on the thread count. Threads come from {@link #parallelism}.
     */
    private void collectKeys(ExportConfig config, ElementKind kind, List<String> keys, ElementSink sink,
            java.util.function.Function<String, Prepared> prepare) {
        OrderedWork.run(keys, parallelism(config), "collect", key -> {
            try {
                return prepare.apply(key);
            } catch (Throwable t) {
                // A failure that happens while preparing is reported against the item it belongs to, on
                // the emitting thread, so one malformed element cannot end the export.
                return new Prepared.Bad(kind, key, t);
            }
        }, prepared -> {
            if (prepared != null) {
                emit(sink, prepared);
            }
        });
    }

    /**
     * Collects a registry-backed category.
     *
     * <p>The keys are sorted before any work starts, for two reasons that happen to agree: the output
     * must be a function of the data and not of a hash map's iteration order, and the ordering guarantee
     * the parallel skeleton provides is only meaningful if the input order is itself defined.
     *
     * <p>A prepare step returning {@code null} means the entry should not be recorded — an id the
     * registry no longer holds, or a shape this build does not describe. That is a decision rather than a
     * failure, so it produces no record and no failure report.
     */
    private void collectRegistry(ExportConfig config, ElementKind kind,
            java.util.Collection<ResourceLocation> ids, ElementSink sink,
            java.util.function.Function<ResourceLocation, Prepared> prepare) {
        List<String> keys = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            if (config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);
        collectKeys(config, kind, keys, sink, key -> prepare.apply(ResourceLocation.parse(key)));
    }

    /**
     * Runs a datapack collection pass across worker threads, in configuration order.
     *
     * <p>Kept separate from {@link #collectKeys} because a datapack pass may produce several results for
     * one key — a record plus a note about a file that could not be read.
     */
    private void collectPrepared(ExportConfig config, List<String> orderedKeys,
            java.util.function.Function<String, List<Prepared>> prepare, ElementSink sink) {
        OrderedWork.run(orderedKeys, parallelism(config), "collect", prepare,
                prepared -> prepared.forEach(p -> emit(sink, p)));
    }

    /** Reads a resource to its end. */
    private static String readAll(net.minecraft.server.packs.resources.Resource resource)
            throws java.io.IOException {
        try (java.io.BufferedReader reader = resource.openAsReader()) {
            StringBuilder sb = new StringBuilder(4096);
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- tags

    /**
     * Collects the tags packs declare under {@code data/<ns>/tags/<type>/<path>.json}.
     *
     * <h2>Why the stacks, and not just the winning file</h2>
     *
     * <p>A tag is the result of merging every pack's version of the same file, so reading only the
     * highest-priority one would produce a tag that looks complete and is missing entries — the worst kind
     * of wrong, since nothing about it appears broken.
     *
     * <p>The merge rule is read from vanilla's own {@code TagLoader} rather than inferred: walk the stack
     * in order, parse each file, and if a file sets {@code replace} clear what has accumulated before
     * appending its entries. A pack that declares {@code replace} therefore discards everything below it,
     * which is what a pack overriding a tag intends.
     *
     * <h2>What is recorded</h2>
     *
     * <p>The declared members, including nested tags written with a leading {@code #}. Nested tags are
     * <em>not</em> expanded: expansion needs the registry to resolve ids and cycle detection to terminate,
     * and it would replace what a pack wrote with a derived set.
     */
    private void collectTags(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            // No resources bound yet. Nothing found rather than a failure: a run before the resource load
            // simply has no datapack content to offer.
            return;
        }
        Map<ResourceLocation, List<net.minecraft.server.packs.resources.Resource>> stacks;
        try {
            stacks = manager.listResourceStacks("tags", path -> path.getPath().endsWith(".json"));
        } catch (Throwable t) {
            sink.failure(ElementKind.TAG, "tags", t);
            return;
        }

        // Sorted so the output is a function of the packs and not of a hash map's iteration order. The
        // ordering guarantee only means something if the input order is itself defined.
        List<String> keys = new ArrayList<>(stacks.size());
        for (ResourceLocation id : stacks.keySet()) {
            if (TagFile.pathOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            List<Prepared> out = new ArrayList<>(2);
            try {
                TagFile merged = mergeTag(stacks.get(id), id, out);
                if (merged == null) {
                    return out;
                }
                out.add(new Prepared.Generic(ElementKind.TAG, id.getNamespace(),
                        TagFile.pathOf(id.getPath()), null, null, merged.membersAsWritten(),
                        new String[] {
                                // `type` is recorded because it is not implied by the id: c:gems exists as
                                // an item tag and as a block tag in different packs, and merging those two
                                // would merge two different sets.
                                "type", TagFile.typeOf(id.getPath()),
                                "replace", Boolean.toString(merged.replace()),
                                "count", Integer.toString(merged.entries().size()),
                                "nested", Integer.toString(merged.nestedCount()),
                                "optional", Integer.toString(merged.optionalCount())}));
            } catch (Throwable t) {
                out.add(new Prepared.Bad(ElementKind.TAG, id.toString(), t));
            }
            return out;
        }, sink);
    }

    /** Merges one tag file's stack, in the order given, applying each file's {@code replace}. */
    private TagFile mergeTag(List<net.minecraft.server.packs.resources.Resource> stack,
            ResourceLocation id, List<Prepared> problems) {
        List<TagFile.Entry> merged = new ArrayList<>(16);
        boolean sawReplace = false;
        for (net.minecraft.server.packs.resources.Resource resource : stack) {
            TagFile file;
            try {
                file = TagFile.parse(readAll(resource));
            } catch (Throwable t) {
                // One unreadable file in the stack must not lose the others: the packs below still
                // contribute. Returned rather than reported here, because this runs on a worker.
                problems.add(new Prepared.Note("tag file " + id + " from pack '"
                        + resource.sourcePackId() + "' could not be read", t));
                continue;
            }
            if (file.replace()) {
                merged.clear();
                sawReplace = true;
            }
            merged.addAll(file.entries());
        }
        if (merged.isEmpty() && !sawReplace) {
            return null;
        }
        return TagFile.of(sawReplace, merged);
    }

    // ---------------------------------------------------------------- assets

    /**
     * Offers the pack's assets for copying.
     *
     * <h2>Nothing is decoded, and that is the point</h2>
     *
     * <p>Textures, models and sounds are handed over as streams and copied byte for byte. Nothing here
     * opens a PNG to learn its size or re-encodes an OGG: the design forbids it, and the reason is not only
     * speed. A fingerprint is taken over the bytes that land on disk, so a re-encoded texture would be a
     * different artifact every time the encoder changed, and the delta that exists to avoid rewriting would
     * report every asset as changed on every run, forever.
     *
     * <h2>Why this can come up empty, and why it says so</h2>
     *
     * <p>Assets live under {@code assets/}, and a resource manager only ever sees its own side: a client's
     * sees assets, a dedicated server's sees data. So on a dedicated server there is nothing to copy — not
     * because anything is wrong, but because the files are not there to read. That is reported rather than
     * passed over, because "no assets in this pack" and "this program cannot see assets from here" are
     * different answers and only one of them is worth acting on.
     *
     * <h2>What it asks for, since it cannot ask for everything</h2>
     *
     * <p>The sweep names the kinds it visits and the root files it fetches by name. That is not a
     * preference: asking the manager for everything — an empty path — is rejected by its path validation and
     * the rejection is logged and swallowed, so the caller gets an empty result and no error, which looks
     * exactly like a pack with no assets. Nothing in the resource API enumerates a namespace either, so
     * there is no other way to find out what is there. {@code AssetSweep} says all of this at more length,
     * including the part that matters to a user: a directory outside its list is not swept.
     *
     * <p>{@code AssetPath} still rejects what is not content — the pack build's marker, anything under a dot
     * directory — because a declared kind can still contain them.
     *
     * <p>A method of its own rather than part of {@code collectDatapacks}, despite reading from the same
     * resource manager: assets are not records, they are files, and a caller reading the names should be
     * able to tell which one produces artifacts and which one copies bytes.
     */
    public void collectAssets(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = clientResources;
        if (manager == null) {
            sink.failure(null, AssetPath.DIRECTORY, new IllegalStateException(
                    "no client resource manager is bound. Assets live under assets/, which only a client's"
                            + " resource manager can see, so assets are copied on a client that has"
                            + " announced itself; a dedicated server has none to give"));
            return;
        }
        int offered = 0;

        // One listing per declared kind, gathered before any copy so the records come out sorted rather
        // than in whatever order the packs happened to be asked.
        // The built-in kinds plus whatever the user has declared, which is the point of the declaration
        // table: a pack that puts content somewhere vanilla does not is otherwise never swept, and the API
        // offers no way to find out that it did.
        java.util.List<String> kinds = AssetSweep.merge(declaredAssetKinds(config));

        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files =
                new java.util.LinkedHashMap<>();
        for (String kind : kinds) {
            try {
                files.putAll(manager.listResources(AssetSweep.prefixOf(kind), path -> true));
            } catch (Throwable t) {
                // One kind failing does not abandon the sweep: the others are independent, and a partly
                // copied asset tree with a reported reason is more use than none.
                sink.failure(null, AssetPath.DIRECTORY + "/" + kind, t);
            }
        }
        // And the files that have no directory to be found under, fetched by name.
        for (String rootFile : AssetSweep.ROOT_FILES) {
            for (String namespace : manager.getNamespaces()) {
                ResourceLocation id = ResourceLocation.fromNamespaceAndPath(namespace, rootFile);
                manager.getResource(id).ifPresent(resource -> files.put(id, resource));
            }
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (!AssetPath.isCopyableNamespace(id.getNamespace()) || !AssetPath.isCopyable(id.getPath())) {
                continue;
            }
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            keys.add(id.toString());
        }
        keys.sort(null);

        for (String key : keys) {
            ResourceLocation id = ResourceLocation.parse(key);
            String target = AssetPath.outputPath(id.getNamespace(), id.getPath());
            if (target == null) {
                continue;
            }
            net.minecraft.server.packs.resources.Resource resource = files.get(id);
            offered++;
            sink.asset(target, () -> resource.open());
        }

        if (offered == 0) {
            sink.failure(null, AssetPath.DIRECTORY, new IllegalStateException(
                    "the client resource manager exposed no asset files. A client that has not finished"
                            + " loading its resources has none to give, which is not the same as a pack"
                            + " with no assets"));
        }
    }

    /**
     * The asset kinds the user has declared, or an empty list.
     *
     * <p>Read through the configuration the run was given rather than through a fresh one, because the
     * state file's location is itself configurable. A bare configuration would look in the default place
     * while the command that wrote the declaration looked wherever the setting said — so a user who moved
     * the directory would write a declaration that was never read, and the symptom would be a kind that
     * silently found nothing.
     *
     * <p>Failure reads as "nothing declared" rather than propagating: the declared kinds are an addition to
     * the built-in list and not a replacement for it, so a state file that cannot be read means fewer kinds
     * swept and never a sweep that does not happen.
     */
    private static java.util.List<String> declaredAssetKinds(ExportConfig config) {
        try {
            return org.uee.Uee.declared(config, AssetSweep.TABLE);
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    // ---------------------------------------------------------------- translation tables

    /**
     * Collects the translation tables under {@code assets/<ns>/lang/<locale>.json}.
     *
     * <h2>Why this category can be empty on a server, and why that is said out loud</h2>
     *
     * <p>Language files are the only thing this project reads from {@code assets/} rather than
     * {@code data/}, and a resource manager is built for one side or the other and can only see its own:
     * a client's sees assets, a dedicated server's sees data. So this reads whatever the bound manager
     * exposes, and on a dedicated server that is nothing — not because anything is wrong, but because the
     * files are not there to read.
     *
     * <p>Which is exactly the situation that must not be silent. A category that produces nothing looks
     * identical to a pack that has no translations, and the user has no way to tell which they are looking
     * at. So when a run asks for translations and the manager can see no language files at all, that is
     * reported as a failure with the reason. It is not an error in the export; it is the answer to
     * "where are my translations", and the export is the only place it can be given.
     *
     * <h2>One record per namespace and locale</h2>
     *
     * <p>A table, not a row per key: the vanilla language file alone has nearly seven thousand keys, and
     * one record each would multiply that by namespace and locale into a file count and a diff no one
     * would want, to say nothing of the shard overhead. The keys are in the record's value list.
     */
    private void collectLangs(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            // "lang" is matched within whichever side the manager was built for, so this finds
            // assets/<ns>/lang on a client and nothing at all on a dedicated server -- which is the
            // behaviour described above rather than an oversight.
            files = manager.listResources(LangFile.directory(),
                    path -> path.getPath().endsWith(".json"));
        } catch (Throwable t) {
            sink.failure(ElementKind.LANG, LangFile.directory(), t);
            return;
        }

        if (files.isEmpty()) {
            sink.failure(ElementKind.LANG, LangFile.directory(), new IllegalStateException(
                    "no language files are visible to this resource manager. Language files live under"
                            + " assets/, which a dedicated server's resource manager cannot see, so"
                            + " translations are collected on a client only"));
            return;
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (LangFile.localeOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            try {
                LangFile file = LangFile.parse(LangFile.localeOf(id.getPath()), readAll(files.get(id)));
                if (file.size() == 0) {
                    // A file with nothing usable in it is not a record: it would say a namespace has a
                    // locale and then offer no keys, which is worse than saying nothing.
                    return List.of();
                }
                return List.of(new Prepared.Generic(ElementKind.LANG, id.getNamespace(),
                        LangFile.localeOf(id.getPath()), null, null, file.asLines().toArray(new String[0]),
                        new String[] {"locale", file.locale(), "keys", Integer.toString(file.size())}));
            } catch (Throwable t) {
                return List.of(new Prepared.Bad(ElementKind.LANG, id.toString(), t));
            }
        }, sink);
    }

    // ---------------------------------------------------------------- functions

    /**
     * Collects functions from {@code data/<ns>/function/<path>.mcfunction}.
     *
     * <h2>The one category that is not JSON</h2>
     *
     * <p>Both the extension and the filter differ, which is why this does not go through the JSON helper:
     * the files are plain text and the suffix is {@code .mcfunction}. The rest of the shape is the familiar
     * one — a directory listing, a sort, and the workers preparing records — so only the filter is new.
     *
     * <h2>Single winner</h2>
     *
     * <p>Read off the game rather than assumed, as with the other single-winner categories: functions load
     * through {@code FileToIdConverter} with {@code listMatchingResources}, which collects into a map keyed
     * by id. So a pack shipping the same function replaces the one below it rather than having its lines
     * appended, which is what a reader might expect of a text file.
     *
     * <h2>Function tags are not a second category</h2>
     *
     * <p>A function tag lives at {@code tags/function/<path>.json} and is already collected by the tag
     * category, whose type field says {@code function}. Recording it here as well would put one file in two
     * categories and invite a consumer to count it twice.
     */
    private void collectFunctions(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            files = manager.listResources(FunctionFile.directory(),
                    path -> path.getPath().endsWith(FunctionFile.extension()));
        } catch (Throwable t) {
            sink.failure(ElementKind.FUNCTION, FunctionFile.directory(), t);
            return;
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (FunctionFile.pathOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            try {
                FunctionFile.Function function = FunctionFile.parse(readAll(files.get(id)));
                return List.of(new Prepared.Generic(ElementKind.FUNCTION, id.getNamespace(),
                        FunctionFile.pathOf(id.getPath()), null, null, function.functionArray(),
                        FunctionFile.extraPairs(function)));
            } catch (Throwable t) {
                return List.of(new Prepared.Bad(ElementKind.FUNCTION, id.toString(), t));
            }
        }, sink);
    }

    // ---------------------------------------------------------------- world generation

    /**
     * Collects world generation from {@code data/<ns>/worldgen/<kind>/<path>.json}.
     *
     * <p>A directory sweep rather than a registry walk, and the difference is deliberate. Fourteen
     * registries load from these files, and walking them would need a running server for thirteen of the
     * fourteen — while reading the files works anywhere, including on a dedicated server, and answers the
     * question this half of the export exists to answer: what did the pack write.
     *
     * <p>The trade is that it cannot say what the game ended up with after other packs overrode it. For
     * the two kinds where that matters, biomes and structures, there are registry categories that do say,
     * so both answers are available and neither is guessed at.
     */
    private void collectWorldgen(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            files = manager.listResources(WorldgenFile.directory(),
                    path -> path.getPath().endsWith(".json"));
        } catch (Throwable t) {
            sink.failure(ElementKind.WORLDGEN, WorldgenFile.directory(), t);
            return;
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (WorldgenFile.keyOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            try {
                String kind = WorldgenFile.kindOf(id.getPath());
                WorldgenFile.Worldgen worldgen = WorldgenFile.parse(kind, readAll(files.get(id)));
                // The key is the kind and the name together: two kinds can hold the same name, and the
                // records would otherwise be indistinguishable.
                return List.of(new Prepared.Generic(ElementKind.WORLDGEN, id.getNamespace(),
                        WorldgenFile.keyOf(id.getPath()), null, null, worldgen.referenceArray(),
                        WorldgenFile.extraPairs(worldgen)));
            } catch (Throwable t) {
                return List.of(new Prepared.Bad(ElementKind.WORLDGEN, id.toString(), t));
            }
        }, sink);
    }

    // ---------------------------------------------------------------- advancements

    /**
     * Collects advancements from {@code data/<ns>/advancement/<path>.json}.
     *
     * <h2>Single winner, like a loot table</h2>
     *
     * <p>Read off the game rather than assumed: they load through the same
     * {@code SimpleJsonResourceReloadListener.scanDirectory} call the loot tables use, which collects into a
     * map keyed by id, so a pack shipping the same id replaces the one below it. Merging stacks as tags do
     * would invent a tree no pack declared.
     *
     * <h2>Most of them have nothing to show</h2>
     *
     * <p>About a tenth of the vanilla advancements carry a display block; the rest are the invisible
     * recipe-unlock nodes, one per recipe. So the record does not treat a missing display as a defect and
     * does not omit the record either — its parent and its triggers are still the tree.
     */
    private void collectAdvancements(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            files = manager.listResources(AdvancementFile.directory(),
                    path -> path.getPath().endsWith(".json"));
        } catch (Throwable t) {
            sink.failure(ElementKind.ADVANCEMENT, AdvancementFile.directory(), t);
            return;
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (AdvancementFile.pathOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            try {
                AdvancementFile.Advancement advancement =
                        AdvancementFile.parse(readAll(files.get(id)));
                return List.of(new Prepared.Generic(ElementKind.ADVANCEMENT, id.getNamespace(),
                        AdvancementFile.pathOf(id.getPath()), advancement.titleOrKey(),
                        advancement.descriptionOrKey(), advancement.triggerArray(),
                        AdvancementFile.extraPairs(advancement)));
            } catch (Throwable t) {
                return List.of(new Prepared.Bad(ElementKind.ADVANCEMENT, id.toString(), t));
            }
        }, sink);
    }

    // ---------------------------------------------------------------- loot tables

    /**
     * Collects loot tables from {@code data/<ns>/loot_table/<path>.json}.
     *
     * <h2>Single winner, unlike a tag</h2>
     *
     * <p>A loot table is one document identified by one id, so a pack that ships a table at the same id
     * <em>replaces</em> the one below it rather than adding to it. That is read off vanilla's own loading
     * path rather than assumed — it goes through {@code SimpleJsonResourceReloadListener.scanDirectory},
     * which collects into a map keyed by id, the same call shape as {@code listResources} here. Merging
     * stacks the way tags do would therefore invent entries no pack ever declared.
     *
     * <h2>What is recorded</h2>
     *
     * <p>The item ids the table can produce, plus references to tags and to other tables, which a reader
     * follows rather than having them expanded. See {@link LootTableFile} for why the walk stops there.
     */
    private void collectLootTables(ExportConfig config, ElementSink sink) {
        net.minecraft.server.packs.resources.ResourceManager manager = resources;
        if (manager == null) {
            return;
        }
        Map<ResourceLocation, net.minecraft.server.packs.resources.Resource> files;
        try {
            files = manager.listResources(LootTableFile.directory(),
                    path -> path.getPath().endsWith(".json"));
        } catch (Throwable t) {
            sink.failure(ElementKind.LOOT_TABLE, LootTableFile.directory(), t);
            return;
        }

        List<String> keys = new ArrayList<>(files.size());
        for (ResourceLocation id : files.keySet()) {
            if (LootTableFile.pathOf(id.getPath()) != null && config.acceptsNamespace(id.getNamespace())) {
                keys.add(id.toString());
            }
        }
        keys.sort(null);

        collectPrepared(config, keys, key -> {
            ResourceLocation id = ResourceLocation.parse(key);
            try {
                LootTableFile.Table table = LootTableFile.parse(readAll(files.get(id)));
                String folder = LootTableFile.folderOf(id.getPath());
                String[] extra = LootTableFile.extraPairs(table);
                if (folder != null) {
                    // A filing hint, not part of the id: the directories under loot_table/ are included
                    // in the table's name, so `blocks/stone` is one id and `blocks` says only where the
                    // author chose to keep it.
                    String[] withFolder = java.util.Arrays.copyOf(extra, extra.length + 2);
                    withFolder[extra.length] = "folder";
                    withFolder[extra.length + 1] = folder;
                    extra = withFolder;
                }
                return List.of(new Prepared.Generic(ElementKind.LOOT_TABLE, id.getNamespace(),
                        LootTableFile.pathOf(id.getPath()), null, null, table.itemArray(), extra));
            } catch (Throwable t) {
                return List.of(new Prepared.Bad(ElementKind.LOOT_TABLE, id.toString(), t));
            }
        }, sink);
    }

    // ---------------------------------------------------------------- recipes

    private void collectRecipes(ExportConfig config, ElementSink sink) {
        Collection<RecipeHolder<?>> holders = recipes();
        if (holders == null || holders.isEmpty()) {
            return;
        }
        // Recipes arrive as a collection rather than a registry, so the key list is built here. Sorted
        // for the same two reasons the registry pass sorts: a defined input order is what makes the
        // ordering guarantee mean anything, and the output must not depend on a map's iteration order.
        Map<String, RecipeHolder<?>> byId = new HashMap<>(holders.size() * 2);
        List<String> keys = new ArrayList<>(holders.size());
        for (RecipeHolder<?> holder : holders) {
            ResourceLocation id = holder.id();
            if (id == null || !config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            String key = id.toString();
            if (byId.putIfAbsent(key, holder) == null) {
                keys.add(key);
            }
        }
        keys.sort(null);
        collectKeys(config, ElementKind.RECIPE, keys, sink, key -> {
            RecipeElement element =
                    buildRecipe(ResourceLocation.parse(key), byId.get(key).value());
            // A null means this build does not describe that recipe shape; not recording it is the
            // decision, and the export continues.
            return element == null ? null : new Prepared.Recipe(element);
        });
    }

    /**
     * Converts a recipe into a record, dispatching on shape rather than on version.
     *
     * <p>Slot numbering follows the convention both aligned dumpers use, so an imported recipe lands
     * in the same grid position: shaped recipes key by {@code column + row * 3 + 1} with the result
     * at slot {@code 1}; shapeless and single-input recipes use a sequential slot; furnace-like
     * recipes additionally carry experience and cooking time.
     *
     * @return the record, or {@code null} for a recipe shape this build does not describe
     */
    protected RecipeElement buildRecipe(ResourceLocation id, Recipe<?> recipe) {
        String namespace = id.getNamespace();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            List<String> slots = new ArrayList<>();
            List<org.uee.model.Ingredient> inputs = new ArrayList<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int index = x + y * w;
                    if (index >= shaped.getIngredients().size()) {
                        continue;
                    }
                    org.uee.model.Ingredient ing = toIngredient(shaped.getIngredients().get(index));
                    if (ing == null || ing.isEmpty()) {
                        continue;
                    }
                    slots.add(String.valueOf(x + y * 3 + 1));
                    inputs.add(ing);
                }
            }
            return recipe(id, "minecraft:crafting_shaped", namespace,
                    slots.toArray(new String[0]), inputs.toArray(new org.uee.model.Ingredient[0]),
                    shaped.getResultItem(RegistryAccess.EMPTY), null, null);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<String> slots = new ArrayList<>();
            List<org.uee.model.Ingredient> inputs = new ArrayList<>();
            int slot = 1;
            for (net.minecraft.world.item.crafting.Ingredient mcIng : shapeless.getIngredients()) {
                org.uee.model.Ingredient ing = toIngredient(mcIng);
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                slots.add(String.valueOf(slot++));
                inputs.add(ing);
            }
            return recipe(id, "minecraft:crafting_shapeless", namespace,
                    slots.toArray(new String[0]), inputs.toArray(new org.uee.model.Ingredient[0]),
                    shapeless.getResultItem(RegistryAccess.EMPTY), null, null);
        }
        if (recipe instanceof AbstractCookingRecipe cooking) {
            List<String> slots = new ArrayList<>();
            List<org.uee.model.Ingredient> inputs = new ArrayList<>();
            for (net.minecraft.world.item.crafting.Ingredient mcIng : cooking.getIngredients()) {
                org.uee.model.Ingredient ing = toIngredient(mcIng);
                if (ing == null || ing.isEmpty()) {
                    continue;
                }
                slots.add(String.valueOf(slots.size() + 1));
                inputs.add(ing);
            }
            return recipe(id, serializerId(recipe), namespace,
                    slots.toArray(new String[0]), inputs.toArray(new org.uee.model.Ingredient[0]),
                    cooking.getResultItem(RegistryAccess.EMPTY),
                    (double) cooking.getExperience(), cooking.getCookingTime());
        }
        // Stonecutting, smithing and modded shapes: described generically rather than dropped, so a
        // consumer still sees the recipe and its result even when the input grid is not modelled.
        ItemStack result = recipe.getResultItem(RegistryAccess.EMPTY);
        if (result == null || result.isEmpty()) {
            return null;
        }
        return recipe(id, serializerId(recipe), namespace,
                new String[0], new org.uee.model.Ingredient[0], result, null, null);
    }

    private RecipeElement recipe(ResourceLocation id, String type, String namespace,
            String[] slots, org.uee.model.Ingredient[] inputs, ItemStack result,
            Double experience, Integer cookTime) {
        int count = result == null || result.isEmpty() ? 0 : result.getCount();
        String itemId = result == null || result.isEmpty() ? "" : String.valueOf(BuiltInRegistries.ITEM.getKey(result.getItem()));
        return new RecipeElement(id.toString(), type, namespace, slots, inputs,
                new String[] {"1"}, new String[] {itemId}, new int[] {count},
                new String[] {null}, experience, cookTime);
    }

    /**
     * Converts a game ingredient into a record.
     *
     * <p>An ingredient backed by several stacks becomes a multi-item entry; a single-stack
     * ingredient becomes a plain item reference.
     */
    protected org.uee.model.Ingredient toIngredient(
            net.minecraft.world.item.crafting.Ingredient mcIngredient) {
        if (mcIngredient == null) {
            return null;
        }
        ItemStack[] stacks = mcIngredient.getItems();
        if (stacks == null || stacks.length == 0) {
            return null;
        }
        if (stacks.length == 1) {
            return org.uee.model.Ingredient.ofItem(
                    String.valueOf(BuiltInRegistries.ITEM.getKey(stacks[0].getItem())));
        }
        String[] ids = new String[stacks.length];
        for (int i = 0; i < stacks.length; i++) {
            ids[i] = String.valueOf(BuiltInRegistries.ITEM.getKey(stacks[i].getItem()));
        }
        return org.uee.model.Ingredient.ofItems(ids);
    }

    private String serializerId(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return String.valueOf(key);
    }

    // ---------------------------------------------------------------- helpers

    private static String[] tagIds(java.util.stream.Stream<TagKey<Item>> tags) {
        return tags.map(tag -> tag.location().toString()).distinct().toArray(String[]::new);
    }

    /** Enchantment registry; present as a dynamic registry rather than a built-in one. */
    /**
     * How many entries a registry-backed category has, or {@code -1} when its registry is unreachable.
     *
     * <p>-1 rather than zero, because the two mean different things here: a data-loaded registry with no
     * server behind it is not empty, it is unavailable, and a report that said zero would be claiming
     * knowledge it does not have.
     */
    public int sizeOf(ElementKind kind) {
        RegistrySource source = RegistrySource.byKind().get(kind);
        if (source == null) {
            return -1;
        }
        Registry<?> registry = registryOf(source);
        return registry == null ? -1 : registry.size();
    }



    /** The biome registry, or {@code null} when it cannot be reached from here. */


    /**
     * A holder list from a registry, widened to the interface type.
     *
     * <p>The element-by-element widening is required because generics are invariant: the registry
     * hands back {@code Holder.Reference<T>}, which implements {@code Holder<T>}, but a
     * {@code List<Reference<T>>} is not a {@code List<Holder<T>>}.
     */
    protected static <T> List<Holder<T>> holders(Registry<T> registry) {
        List<Holder<T>> out = new ArrayList<>();
        registry.holders().forEach(out::add);
        return out;
    }

    /**
     * The registry access of the running server, or {@code null} when there is none.
     *
     * <p>A field rather than a hook returning {@code RegistryAccess.EMPTY}, which is what stood here: a
     * method that nothing called and no subclass overrode, so the registries it was meant to reach were
     * unreachable and the categories reading them produced nothing. Bound by the loaders because only
     * they see the lifecycle moment at which a server exists.
     */
    private volatile RegistryAccess registryAccess;

    /**
     * Binds the registry access of the running server.
     *
     * <p>Null is a real state and not an error: before a world is loaded there is no server, and the
     * categories that live in data-loaded registries are simply absent from the output — the same thing
     * that happens when a pack has no entries. Saying so is better than reporting a failure for a run
     * that did exactly what it could.
     */
    public void bindRegistryAccess(RegistryAccess access) {
        this.registryAccess = access;
        // The cached language tables were built against the previous access; a reload can change them.
        this.translator = null;
    }

    /** The bound registry access, or {@code null}. */
    protected RegistryAccess registryAccess() {
        return registryAccess;
    }
}
