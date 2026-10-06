package org.uee.model;

/**
 * The element categories an export can carry.
 *
 * <p>The first four mirror the four categories the MC百科 import pipeline counts as
 * {@code I} / {@code E} / {@code B} / {@code R}; the rest are UEE additions that existing exporter
 * mods split across separate tools.
 */
public enum ElementKind {

    /** Items — the {@code I} category. */
    ITEM("item", "items"),
    /** Entities — the {@code E} category. */
    ENTITY("entity", "entities"),
    /** Blocks — the {@code B} category. */
    BLOCK("block", "blocks"),
    /** Recipes — the {@code R} category. */
    RECIPE("recipe", "recipes"),
    /**
     * Tags — the named member sets a pack declares under {@code data/<ns>/tags/}.
     *
     * <p>Collected content rather than a registry: a tag is not registered, it is declared in data, and
     * the same id can exist as an item tag and a block tag at once. It belongs in this half because it
     * is read from the same places as recipes and is filtered by the same rules.
     */
    TAG("tag", "tags"),
    /**
     * Loot tables — what a block, mob, chest or fishing rod produces.
     *
     * <p>Collected content, like tags: declared in data rather than registered, read from the same
     * resource system, and filtered by the same rules. Its own category rather than part of a block's or
     * entity's record because a table is referenced by many things at once and describes itself.
     */
    LOOT_TABLE("loot_table", "loot_tables"),
    /**
     * Translation tables — {@code assets/<ns>/lang/<locale>.json}.
     *
     * <p>Collected content, and the one category that is about text rather than about things: a wiki
     * needs it for everything an item or a block record cannot carry, which is a large minority of what
     * a player reads — tooltips, subtitles, death messages, GUI labels. Every other record refers to a
     * translation key; this is where those are resolved.
     */
    LANG("lang", "langs"),
    /** Status effects (potion effects). */
    EFFECT("effect", "effects"),
    /** Fluids. */
    FLUID("fluid", "fluids"),
    /** Enchantments. */
    ENCHANTMENT("enchantment", "enchantments"),
    /** Damage types. */
    DAMAGE_TYPE("damage_type", "damage_types"),
    /** Biomes. */
    BIOME("biome", "biomes"),
    /** Dimensions. */
    DIMENSION("dimension", "dimensions"),
    /** Structures. */
    STRUCTURE("structure", "structures"),
    /** Sounds. */
    SOUND("sound", "sounds"),
    /** Particle types. */
    PARTICLE("particle", "particles"),
    /** Attributes. */
    ATTRIBUTE("attribute", "attributes"),
    /** Creative tabs. */
    CREATIVE_TAB("creative_tab", "creative_tabs"),
    /**
     * The type of a block's entity, not the block entity itself — an instance lives in a world and there
     * are as many as there are placed blocks, while the type is a registered kind.
     */
    BLOCK_ENTITY_TYPE("block_entity_type", "block_entity_types"),
    /** A potion: the effect set a bottle carries. */
    POTION("potion", "potions"),
    /** A world-gen feature kind, as opposed to a configured or placed feature. */
    FEATURE("feature", "features"),
    /** A recipe kind — what the recipe's type field selects. */
    RECIPE_TYPE("recipe_type", "recipe_types"),
    /** A menu kind — the container screen a block or item opens. */
    MENU("menu", "menus"),
    /**
     * Advancements — the achievement tree.
     *
     * <p>Declared in data rather than registered, like tags and loot tables. The parent link is the point
     * of it: an advancement is a statement about what must be done first, and a wiki page that lists
     * prerequisites is reading exactly this.
     */
    ADVANCEMENT("advancement", "advancements"),
    /** Loaded mods (layer A). */
    MOD("mod", "mods"),
    /** Debug / environment information (layer A). */
    DEBUG("debug", "debug"),
    /** Namespace ownership map, including conflicts. */
    NAMESPACE("namespace", "namespaces"),
    /** Per-mod dependency graph, including reverse edges. */
    DEPENDENCY("dependency", "dependencies"),
    /** A diagnostic finding: something wrong or suspicious about the instance. */
    CONFLICT("conflict", "conflicts"),
    /** Parsed mixin configs: which classes a mod patches. */
    MIXIN("mixin", "mixins");

    private final String singular;
    private final String plural;

    ElementKind(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    /**
     * Categories produced by the analysis rather than by a registry walk.
     *
     * <p>This is a property of the category, so it lives here rather than being re-listed by every
     * component that needs to tell the two halves apart. It was previously duplicated in the pipeline
     * and in the token vocabulary, which is one copy away from the two disagreeing.
     */
    public static final java.util.Set<ElementKind> ANALYSIS_KINDS = java.util.EnumSet.of(
            NAMESPACE, DEPENDENCY, CONFLICT, MIXIN);

    /** Whether this category comes from the analysis rather than from collection. */
    public boolean isAnalysis() {
        return ANALYSIS_KINDS.contains(this);
    }

    /** Whether this category comes from collection. */
    public boolean isCollected() {
        return !isAnalysis();
    }

    /** Singular token, used in per-element file names and diagnostic messages. */
    public String singular() {
        return singular;
    }

    /** Plural token, used as the NDJSON file base name and JSON field for the collection. */
    public String plural() {
        return plural;
    }
}
