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

    protected abstract Translator translator();

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

    /** Container inspections, filled on first use. */
    private final Map<String, ModContainerScanner.ContainerInfo> containers = new HashMap<>();
    /** Mixin configs read from those containers, filled on first use. */
    private final List<MixinConfig> mixins = new ArrayList<>();
    private boolean factsGathered;
    }

    @Override
    public void collectDatapacks(ExportConfig config, Collection<ElementKind> wanted,
            ElementSink sink) {
        if (wanted.contains(ElementKind.RECIPE)) {
            collectRecipes(config, sink);
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

    /** Kept for adapters that need a holder list from a dynamic registry. */
    protected static <T> List<Holder<T>> holders(Registry<T> registry) {
        return registry.holders().toList();
    }

    /** Registry access used when a recipe's result needs resolving without a live server. */
    protected static RegistryAccess registryAccess() {
        return RegistryAccess.EMPTY;
    }
}
