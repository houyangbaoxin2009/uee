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
    DEBUG("debug", "debug");

    private final String singular;
    private final String plural;

    ElementKind(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
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
