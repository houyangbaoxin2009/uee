package org.uee.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The categories that are read from a registry, and where each one lives.
 *
 * <h2>Why this is a table and not eleven blocks of code</h2>
 *
 * <p>Three of the categories in the default set were advertised and produced nothing: there was no code
 * that collected enchantments or creative tabs at all, and a sixth — biomes — was collected through a
 * hook that returned null. Nine categories in total were declared and never delivered, and nothing said
 * so, because a category with no collector is indistinguishable from a category with no content.
 *
 * <p>The cause was that "which categories exist" and "which categories are collected" were written down
 * in two places with nothing keeping them in step. So they are one place now: a category is
 * registry-backed if and only if it appears here, and a test walks every declared category and fails if
 * it is accounted for by nothing. The test is the part that matters — the table makes the question
 * askable, and the test asks it.
 *
 * <h2>What the fields mean</h2>
 *
 * @param kind the category this registry populates
 * @param registry the registry's id, as a resource location
 * @param dynamic whether the registry is loaded from data rather than built in. A dynamic registry is
 *     only reachable while a server is running, because that is what holds it; asking for one without a
 *     server legitimately yields nothing rather than an error.
 * @param entryNames how an entry's name is found, or {@link Names#NONE} when the game gives it none
 * @param namePrefix the language key prefix for {@link Names#LANG_KEY}, unused otherwise
 */
public record RegistrySource(ElementKind kind, String registry, boolean dynamic, Names entryNames,
        String namePrefix) {

    /**
     * Where an entry's display name comes from.
     *
     * <p>Recorded per registry because it genuinely differs, and the differences were established by
     * checking the shipped language files rather than by assuming a convention. Two of the guesses that
     * would have been made here are wrong: fluids have no language key at all (their block does), and an
     * attribute's key is not derivable from its id — {@code Attribute.getDescriptionId()} returns
     * {@code attribute.name.generic.max_health}, while the obvious transform would produce
     * {@code attribute.minecraft.max_health}, which does not exist.
     */
    public enum Names {
        /** No name is available. The id is all there is, and inventing a key would produce blanks. */
        NONE,
        /** The name is under {@code <prefix>.<namespace>.<path>}, resolved through the language tables. */
        LANG_KEY,
        /** The game resolves the name itself; the entry is asked for it. */
        FROM_ENTRY
    }

    public RegistrySource {
        if (kind == null || registry == null || entryNames == null) {
            throw new IllegalArgumentException("a registry source needs a kind, a registry and a name"
                    + " convention");
        }
        if (entryNames == Names.LANG_KEY && (namePrefix == null || namePrefix.isEmpty())) {
            throw new IllegalArgumentException("a language-key name needs a prefix");
        }
    }

    /**
     * Every registry-backed category.
     *
     * <p>Ordered so the output is stable, and grouped so a reader can see the two families at a glance:
     * the built-in registries first, which are always readable, then the ones loaded from data, which
     * need a running server.
     */
    private static final List<RegistrySource> ALL = List.of(
            // --- built in, always readable ---
            new RegistrySource(ElementKind.EFFECT, "minecraft:mob_effect", false, Names.LANG_KEY,
                    "effect"),
            new RegistrySource(ElementKind.FLUID, "minecraft:fluid", false, Names.NONE, null),
            new RegistrySource(ElementKind.ENCHANTMENT, "minecraft:enchantment", true, Names.LANG_KEY,
                    "enchantment"),
            new RegistrySource(ElementKind.ATTRIBUTE, "minecraft:attribute", false, Names.FROM_ENTRY,
                    null),
            new RegistrySource(ElementKind.CREATIVE_TAB, "minecraft:creative_mode_tab", false,
                    Names.FROM_ENTRY, null),
            new RegistrySource(ElementKind.SOUND, "minecraft:sound_event", false, Names.NONE, null),
            new RegistrySource(ElementKind.PARTICLE, "minecraft:particle_type", false, Names.NONE, null),
            // These four were in the design's collection surface and had no entry here, so like the nine
            // before them they produced nothing.
            new RegistrySource(ElementKind.BLOCK_ENTITY_TYPE, "minecraft:block_entity_type", false,
                    Names.NONE, null),
            new RegistrySource(ElementKind.POTION, "minecraft:potion", false, Names.NONE, null),
            new RegistrySource(ElementKind.RECIPE_TYPE, "minecraft:recipe_type", false, Names.NONE, null),
            new RegistrySource(ElementKind.MENU, "minecraft:menu", false, Names.NONE, null),

            // --- loaded from data, so only while a server is running ---
            //
            // Two of these ids carry a "worldgen/" prefix that nothing about the category name
            // suggests. Both were written without it at first, and a game test is what said so: the
            // registries resolved to nothing, so the categories produced nothing, which is precisely the
            // failure this table exists to make impossible. The ids are read off the registry keys in
            // the game rather than derived from the category names.
            new RegistrySource(ElementKind.BIOME, "minecraft:worldgen/biome", true, Names.LANG_KEY, "biome"),
            new RegistrySource(ElementKind.DAMAGE_TYPE, "minecraft:damage_type", true, Names.NONE, null),
            new RegistrySource(ElementKind.STRUCTURE, "minecraft:worldgen/structure", true, Names.NONE, null),
            new RegistrySource(ElementKind.DIMENSION, "minecraft:dimension_type", true, Names.NONE, null),
            new RegistrySource(ElementKind.FEATURE, "minecraft:worldgen/feature", true, Names.NONE, null));

    /**
     * Categories that are collected by dedicated code rather than by walking a registry.
     *
     * <p>Listed so the completeness check can tell "handled elsewhere" from "not handled", which is the
     * whole distinction it exists to make. An item needs forty fields and a recipe needs a decoded grid;
     * neither is a registry walk, and pretending otherwise to fit a table would be worse than naming
     * them.
     */
    private static final Set<ElementKind> COLLECTED_ELSEWHERE = EnumSet.of(
            ElementKind.ITEM,
            ElementKind.BLOCK,
            ElementKind.ENTITY,
            ElementKind.RECIPE,
            ElementKind.TAG,
            ElementKind.LOOT_TABLE,
            ElementKind.LANG,
            ElementKind.ADVANCEMENT,
            ElementKind.WORLDGEN,
            // Assembled from the facts rather than from a registry: the mod list comes from the loader,
            // the environment from the process, the rest from the analyses.
            ElementKind.MOD,
            ElementKind.DEBUG,
            ElementKind.NAMESPACE,
            ElementKind.DEPENDENCY,
            ElementKind.CONFLICT,
            ElementKind.MIXIN);

    /** Every registry-backed category, in output order. */
    public static List<RegistrySource> all() {
        return ALL;
    }

    /** The registry-backed categories among {@code wanted}, in output order. */
    public static List<RegistrySource> forKinds(Set<ElementKind> wanted) {
        List<RegistrySource> out = new ArrayList<>(ALL.size());
        for (RegistrySource source : ALL) {
            if (wanted.contains(source.kind())) {
                out.add(source);
            }
        }
        return out;
    }

    /**
     * The categories nothing accounts for.
     *
     * <p>Empty in a correct build. A category here is one that can be configured, appears in the command
     * surface and the vocabulary, and produces nothing at all — which is what this project shipped nine
     * of, silently.
     */
    public static List<ElementKind> unaccountedFor() {
        Set<ElementKind> accounted = EnumSet.noneOf(ElementKind.class);
        for (RegistrySource source : ALL) {
            accounted.add(source.kind());
        }
        accounted.addAll(COLLECTED_ELSEWHERE);
        List<ElementKind> missing = new ArrayList<>();
        for (ElementKind kind : ElementKind.values()) {
            if (!accounted.contains(kind)) {
                missing.add(kind);
            }
        }
        return missing;
    }

    /**
     * Whether anything collects this category.
     *
     * <p>Exposed because the alternative is what this class exists to remove: a caller keeping its own
     * list of the categories that are handled elsewhere, which is a second copy of the same decision and
     * drifts from this one. It already had: a check that asked its own copy reported a freshly added
     * category as having no collector, which is the right answer to the wrong question.
     */
    public static boolean isAccountedFor(ElementKind kind) {
        if (kind == null) {
            return false;
        }
        for (RegistrySource source : ALL) {
            if (source.kind() == kind) {
                return true;
            }
        }
        return COLLECTED_ELSEWHERE.contains(kind);
    }

    /**
     * Whether a registry backs this category, as opposed to something collecting it by hand.
     *
     * <p>The distinction matters to the completeness check: a registry-backed category that resolves to
     * nothing is a defect, while a hand-written collector needs no registry at all.
     */
    public static boolean isRegistryBacked(ElementKind kind) {
        for (RegistrySource source : ALL) {
            if (source.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    /** The sources by kind, for a caller that wants to look one up. */
    public static Map<ElementKind, RegistrySource> byKind() {
        Map<ElementKind, RegistrySource> out = new LinkedHashMap<>(ALL.size() * 2);
        for (RegistrySource source : ALL) {
            out.put(source.kind(), source);
        }
        return Collections.unmodifiableMap(out);
    }
}
