package org.uee.mc;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
import org.uee.util.OrderedWork;
import org.uee.datapack.LootTableFile;
import org.uee.datapack.FunctionFlow;
import org.uee.debug.MixinConfig;
import org.uee.debug.ModContainerScanner;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
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

    /**
     * Renders an item to a PNG at the requested edge length, or {@code null} when rendering is
     * unavailable. Only a client can render, and rendering is a separate opt-in phase precisely so
     * the data path never depends on it.
     */
    protected byte[] renderItemIcon(ItemStack stack, int size) {
        return null;
    }

    /** Renders a mob to a PNG, or {@code null} when rendering is unavailable. */
    protected byte[] renderEntityIcon(EntityType<?> type, int size) {
        return null;
    }

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
                "enchantment", String.valueOf(enchantmentRegistrySize()),
                "biome", String.valueOf(biomeRegistrySize())));
        sections.add(DebugSection.of("mods",
                "count", String.valueOf(loadedMods().size())));
        return sections;
    }

    // ---------------------------------------------------------------- item collection

    private void collectItems(ExportConfig config, ElementSink sink) {
        Registry<Item> registry = BuiltInRegistries.ITEM;
        for (ResourceLocation id : registry.keySet()) {
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            Item item = registry.get(id);
            if (item == null) {
                continue;
            }
            try {
                sink.item(buildItem(id, item, config));
            } catch (Throwable t) {
                // Isolation: one malformed item must not end the export.
                sink.failure(ElementKind.ITEM, id.toString(), t);
            }
        }
    }

    private ItemElement buildItem(ResourceLocation id, Item item, ExportConfig config) {
        ItemStack stack = new ItemStack(item);
        String key = item.getDescriptionId();
        boolean blockItem = item instanceof BlockItem;
        byte[] large = null;
        byte[] small = null;
        if (config.icons()) {
            large = renderItemIcon(stack, 128);
            small = renderItemIcon(stack, 32);
        }
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
    @SuppressWarnings("deprecation")
    private void collectBlocks(ExportConfig config, ElementSink sink) {
        Registry<Block> registry = BuiltInRegistries.BLOCK;
        for (ResourceLocation id : registry.keySet()) {
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            Block block = registry.get(id);
            if (block == null) {
                continue;
            }
            try {
                String key = block.getDescriptionId();
                sink.block(new BlockElement(
                        id.toString(),
                        id.getNamespace(),
                        translator().translate(key, Translator.ZH_CN),
                        translator().translate(key, Translator.EN_US),
                        block.defaultDestroyTime(),
                        block.getExplosionResistance(),
                        block.defaultBlockState().getLightEmission(),
                        block.asItem() != net.minecraft.world.item.Items.AIR,
                        String.valueOf(block.defaultBlockState().getSoundType()),
                        new String[0]));
            } catch (Throwable t) {
                sink.failure(ElementKind.BLOCK, id.toString(), t);
            }
        }
    }

    // ---------------------------------------------------------------- entity collection

    private void collectEntities(ExportConfig config, ElementSink sink) {
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null) {
                continue;
            }
            try {
                String key = type.getDescriptionId();
                MobCategory category = type.getCategory();
                sink.entity(new EntityElement(
                        id.toString(),
                        id.getNamespace(),
                        key,
                        translator().translate(key, Translator.ZH_CN),
                        translator().translate(key, Translator.EN_US),
                        category == null ? null : category.getName(),
                        config.icons() ? renderEntityIcon(type, 128) : null));
            } catch (Throwable t) {
                sink.failure(ElementKind.ENTITY, id.toString(), t);
            }
        }
    }

    // ---------------------------------------------------------------- other registries

    private void collectGenericRegistries(ExportConfig config, Collection<ElementKind> wanted,
            ElementSink sink) {
        forEachSimple(ElementKind.EFFECT, wanted, config, sink, BuiltInRegistries.MOB_EFFECT, true);
        forEachSimple(ElementKind.FLUID, wanted, config, sink, BuiltInRegistries.FLUID, false);
        forEachBiome(wanted, config, sink);
    }

    /** Walks a registry of named, translatable entries and reports each through {@code generic}. */
    private <T> void forEachSimple(ElementKind kind, Collection<ElementKind> wanted,
            ExportConfig config, ElementSink sink, Registry<T> registry, boolean translatable) {
        if (!wanted.contains(kind)) {
            return;
        }
        for (ResourceLocation id : registry.keySet()) {
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            try {
                String key = id.toLanguageKey(kind.singular());
                sink.generic(kind, id.getNamespace(), id.toString(),
                        translator().translate(key, Translator.ZH_CN),
                        translator().translate(key, Translator.EN_US),
                        new String[0], new String[0]);
            } catch (Throwable t) {
                sink.failure(kind, id.toString(), t);
            }
        }
    }

    private void forEachBiome(Collection<ElementKind> wanted, ExportConfig config,
            ElementSink sink) {
        if (!wanted.contains(ElementKind.BIOME)) {
            return;
        }
        Registry<net.minecraft.world.level.biome.Biome> registry = biomeRegistry();
        if (registry == null) {
            return;
        }
        for (ResourceLocation id : registry.keySet()) {
            if (!config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            try {
                String key = id.toLanguageKey("biome");
                sink.generic(ElementKind.BIOME, id.getNamespace(), id.toString(),
                        translator().translate(key, Translator.ZH_CN),
                        translator().translate(key, Translator.EN_US),
                        new String[0], new String[0]);
            } catch (Throwable t) {
                sink.failure(ElementKind.BIOME, id.toString(), t);
            }
        }
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
            net.minecraft.server.packs.resources.ResourceManager manager = resources;
            current = manager == null ? Translator.none() : new ResourceManagerTranslator(manager);
            translator = current;
        }
        return current;
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

        /** A record to write. */
        record Emit(ElementKind kind, String namespace, String key, String[] values, String[] extra)
                implements Prepared {
        }

        /** A problem with one item, reported as a failure against the category. */
        record Bad(ElementKind kind, String name, Throwable error) implements Prepared {
        }

        /** A problem worth a message but not a failure against an item. */
        record Note(String message, Throwable error) implements Prepared {
        }
    }

    /** Makes the sink calls for one prepared item. Always called on the collecting thread. */
    private static void emit(ElementSink sink, Prepared prepared) {
        switch (prepared) {
            case Prepared.Emit e ->
                    sink.generic(e.kind(), e.namespace(), e.key(), null, null, e.values(), e.extra());
            case Prepared.Bad b -> sink.failure(b.kind(), b.name(), b.error());
            case Prepared.Note n -> org.uee.Uee.reportFailure(n.message(), n.error());
        }
    }

    /**
     * Runs a datapack collection pass across worker threads, in configuration order.
     *
     * <p>Workers prepare, this thread emits, and the results arrive in the order the keys were given —
     * so the output does not depend on the thread count. Threads come from the configuration and are
     * only used when there is enough work to be worth it; see {@link OrderedWork}.
     */
    private void collectPrepared(ExportConfig config, List<String> orderedKeys,
            java.util.function.Function<String, List<Prepared>> prepare, ElementSink sink) {
        OrderedWork.run(orderedKeys, OrderedWork.usefulThreads(config.threads(), orderedKeys.size()),
                "collect", prepare, prepared -> prepared.forEach(p -> emit(sink, p)));
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
                out.add(new Prepared.Emit(ElementKind.TAG, id.getNamespace(),
                        TagFile.pathOf(id.getPath()), merged.membersAsWritten(),
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
                return List.of(new Prepared.Emit(ElementKind.LOOT_TABLE, id.getNamespace(),
                        LootTableFile.pathOf(id.getPath()), table.itemArray(), extra));
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
        for (RecipeHolder<?> holder : holders) {
            Recipe<?> recipe = holder.value();
            ResourceLocation id = holder.id();
            if (id == null || !config.acceptsNamespace(id.getNamespace())) {
                continue;
            }
            try {
                RecipeElement element = buildRecipe(id, recipe);
                if (element != null) {
                    sink.recipe(element);
                }
            } catch (Throwable t) {
                sink.failure(ElementKind.RECIPE, id.toString(), t);
            }
        }
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
    protected int enchantmentRegistrySize() {
        return -1;
    }

    protected int biomeRegistrySize() {
        return -1;
    }

    /** The biome registry, or {@code null} when it cannot be reached from here. */
    protected Registry<net.minecraft.world.level.biome.Biome> biomeRegistry() {
        return null;
    }

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

    /** Registry access used when a recipe's result needs resolving without a live server. */
    protected static RegistryAccess registryAccess() {
        return RegistryAccess.EMPTY;
    }
}
