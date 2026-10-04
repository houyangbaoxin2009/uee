package org.uee.config;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.uee.model.ElementKind;

/**
 * The vocabulary a user types, shared by the command surface and the config file.
 *
 * <p>Both interfaces resolve names through this class so they cannot drift. A token accepted on the
 * command line is accepted in the config file and vice versa, and adding a category is one entry here
 * rather than an edit in two parsers.
 *
 * <p>Tokens are case-insensitive and accept the singular, the plural, and the {@code namespace:path}
 * style spelling of a category, because all three are natural things to type and rejecting one of
 * them would be a pointless papercut.
 */
public final class Tokens {

    /** Token meaning "every category", including the diagnostic ones. */
    public static final String ALL = "all";
    /** Token meaning "every collected category", i.e. the data half. */
    public static final String DATA = "data";
    /** Token meaning "the diagnostic categories only". */
    public static final String ANALYSIS = "analysis";
    /** Token meaning "the most commonly wanted categories". */
    public static final String COMMON = "common";

    /**
     * The categories the one-key default exports.
     *
     * <p>Chosen to be the set that makes a wiki-shaped export useful without being everything: the
     * registries a wiki entry is built from, plus the datapack content that describes how they are
     * obtained. Property-style categories (biomes, dimensions, structures) are left out because they
     * are large, rarely edited, and easy to ask for explicitly.
     */
    private static final Set<ElementKind> COMMON_KINDS = EnumSet.of(
            ElementKind.MOD,
            ElementKind.ITEM,
            ElementKind.BLOCK,
            ElementKind.ENTITY,
            ElementKind.RECIPE,
            ElementKind.EFFECT,
            ElementKind.NAMESPACE,
            ElementKind.MIXIN);

    /** Categories produced by the analysis rather than by a registry walk. */
    private static final Set<ElementKind> ANALYSIS_KINDS = EnumSet.of(
            ElementKind.NAMESPACE,
            ElementKind.DEPENDENCY,
            ElementKind.CONFLICT,
            ElementKind.MIXIN);

    private static final Map<String, ElementKind> BY_TOKEN = new LinkedHashMap<>();

    static {
        for (ElementKind kind : ElementKind.values()) {
            BY_TOKEN.putIfAbsent(kind.singular().toLowerCase(Locale.ROOT), kind);
            BY_TOKEN.putIfAbsent(kind.plural().toLowerCase(Locale.ROOT), kind);
        }
        // Spellings people actually reach for that are not the enum's own names.
        BY_TOKEN.put("deps", ElementKind.DEPENDENCY);
        BY_TOKEN.put("dependency", ElementKind.DEPENDENCY);
        BY_TOKEN.put("findings", ElementKind.CONFLICT);
        BY_TOKEN.put("problems", ElementKind.CONFLICT);
        BY_TOKEN.put("issues", ElementKind.CONFLICT);
        BY_TOKEN.put("ns", ElementKind.NAMESPACE);
        BY_TOKEN.put("mods", ElementKind.MOD);
        BY_TOKEN.put("mixinconfig", ElementKind.MIXIN);
        BY_TOKEN.put("damage_types", ElementKind.DAMAGE_TYPE);
        BY_TOKEN.put("damagetype", ElementKind.DAMAGE_TYPE);
        BY_TOKEN.put("creative_tabs", ElementKind.CREATIVE_TAB);
        BY_TOKEN.put("creativetab", ElementKind.CREATIVE_TAB);
        BY_TOKEN.put("potion_effects", ElementKind.EFFECT);
        BY_TOKEN.put("potioneffect", ElementKind.EFFECT);
    }

    private Tokens() {
    }

    // ---------------------------------------------------------------- categories

    /**
     * Resolves one category token.
     *
     * <p>The three group tokens are accepted here as well, so a caller can put {@code data} in a list
     * of categories and get what they mean without special-casing it.
     *
     * @return the categories named, or {@code null} when the token is not recognised
     */
    public static Set<ElementKind> kinds(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case ALL:
                return EnumSet.allOf(ElementKind.class);
            case DATA:
                Set<ElementKind> data = EnumSet.allOf(ElementKind.class);
                data.removeAll(ANALYSIS_KINDS);
                return data;
            case ANALYSIS:
                return EnumSet.copyOf(ANALYSIS_KINDS);
            case COMMON:
                return EnumSet.copyOf(COMMON_KINDS);
            default:
                ElementKind kind = BY_TOKEN.get(t);
                return kind == null ? null : EnumSet.of(kind);
        }
    }

    /**
     * Resolves a comma- or space-separated list of category tokens.
     *
     * @return the union of the named categories, or {@code null} if any token was unrecognised
     */
    public static Set<ElementKind> kinds(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        Set<ElementKind> out = EnumSet.noneOf(ElementKind.class);
        for (String raw : tokens) {
            for (String piece : raw.split("[,\\s]+")) {
                if (piece.isEmpty()) {
                    continue;
                }
                Set<ElementKind> group = kinds(piece);
                if (group == null) {
                    return null;
                }
                out.addAll(group);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Whether a token names a category or a category group. */
    public static boolean isKindToken(String token) {
        return kinds(token) != null;
    }

    /** The categories the one-key default exports. */
    public static Set<ElementKind> commonKinds() {
        return EnumSet.copyOf(COMMON_KINDS);
    }

    // ---------------------------------------------------------------- formats

    /** Whether a token names an output format this build can write. */
    public static boolean isFormat(String token) {
        if (token == null) {
            return false;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        for (String known : ExportConfig.ALL_FORMATS) {
            if (known.equals(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a comma- or space-separated list of format tokens.
     *
     * @return the formats named, or {@code null} if any token was unrecognised
     */
    public static Set<String> formats(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return null;
        }
        Set<String> out = new TreeSet<>();
        for (String raw : tokens) {
            for (String piece : raw.split("[,\\s]+")) {
                String t = piece.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                if (!isFormat(t)) {
                    return null;
                }
                out.add(t);
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ---------------------------------------------------------------- help text

    /** Every category token that means something on its own, for help output. */
    public static List<String> kindTokens() {
        List<String> out = new ArrayList<>();
        out.add(ALL);
        out.add(DATA);
        out.add(ANALYSIS);
        out.add(COMMON);
        for (ElementKind kind : ElementKind.values()) {
            out.add(kind.plural());
        }
        return out;
    }

    /** Formats in the order help should list them, default first. */
    public static List<String> formatTokens() {
        return new ArrayList<>(java.util.Arrays.asList(ExportConfig.ALL_FORMATS));
    }

    /** A one-line summary of the group tokens, for help output. */
    public static String groups() {
        return ALL + " / " + DATA + " / " + ANALYSIS + " / " + COMMON;
    }
}
