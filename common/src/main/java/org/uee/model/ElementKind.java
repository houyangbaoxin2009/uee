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
