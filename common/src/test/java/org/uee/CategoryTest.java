package org.uee;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.uee.config.Tokens;
import org.uee.datapack.LangFile;
import org.uee.model.ElementKind;
import org.uee.model.RegistrySource;

/**
 * Checks that every category a user can ask for is one the export can produce.
 *
 * <h2>The defect this exists to catch</h2>
 *
 * <p>The project shipped nine categories that were selectable in the configuration, listed by the command
 * surface's vocabulary, and collected by nothing at all — including two, enchantments and creative tabs,
 * that were in the default set. A category with no collector produces an empty result, and an empty
 * result is indistinguishable from a pack that had no content. Nothing anywhere failed; the user simply
 * got less than they asked for and had no way to know.
 *
 * <p>The cause was that "which categories exist" and "which categories are collected" were written in two
 * places. {@link RegistrySource} makes them one, and this asserts the consequence: a category that is
 * accounted for by neither the registry table nor a named dedicated collector is a failure, and the
 * message names it.
 *
 * <p>This is a cheap test that would have caught nine real defects, which is the only justification a
 * structural test needs. It is also the reason the table is in the core rather than beside the collector:
 * a check is only worth writing if it can run without a game, and this one can because the question is
 * about declarations rather than about Minecraft.
 */
public final class CategoryTest {

    private static int failures;

    public static void main(String[] args) {
        coverage();
        theVocabularyAgrees();
        langParsing();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the invariant

    private static void coverage() {
        section("every declared category is one the export can produce");

        List<ElementKind> unaccounted = RegistrySource.unaccountedFor();
        if (!unaccounted.isEmpty()) {
            System.out.println("      not accounted for by a registry source or a dedicated collector:");
            for (ElementKind kind : unaccounted) {
                System.out.println("        " + kind.singular() + " (" + kind + ")");
            }
        }
        check("no category is unaccounted for", unaccounted.isEmpty());

        // The default set is the one that hurts most when it is wrong: it is what a user gets without
        // asking for anything, so a gap here is invisible by construction.
        List<ElementKind> covered = new ArrayList<>();
        for (RegistrySource source : RegistrySource.all()) {
            covered.add(source.kind());
        }
        List<ElementKind> holes = new ArrayList<>();
        for (ElementKind kind : Tokens.kinds(Tokens.COMMON)) {
            if (!covered.contains(kind) && !isDedicated(kind)) {
                holes.add(kind);
            }
        }
        if (!holes.isEmpty()) {
            System.out.println("      default-set categories with no collector:");
            for (ElementKind kind : holes) {
                System.out.println("        " + kind.singular());
            }
        }
        check("every category in the default set has a collector", holes.isEmpty());

        // The table has to be usable as written: a registry id that will not parse, or a name convention
        // that is missing its prefix, would fail at collection time on a live game rather than here.
        boolean allWellFormed = true;
        for (RegistrySource source : RegistrySource.all()) {
            if (wellFormedLocation(source.registry()) == null) {
                System.out.println("      unusable registry id: " + source.registry()
                        + " for " + source.kind().singular());
                allWellFormed = false;
            }
        }
        check("every registry id in the table is a valid resource location", allWellFormed);

        // Each registry is named once. Two entries for one registry would collect it twice under two
        // categories, which would look like duplicated content rather than a mistake.
        Set<String> registries = new java.util.HashSet<>();
        boolean unique = true;
        for (RegistrySource source : RegistrySource.all()) {
            if (!registries.add(source.registry())) {
                System.out.println("      registry named twice: " + source.registry());
                unique = false;
            }
        }
        check("no registry is listed twice", unique);

        Set<ElementKind> kinds = EnumSet.noneOf(ElementKind.class);
        boolean kindsUnique = true;
        for (RegistrySource source : RegistrySource.all()) {
            if (!kinds.add(source.kind())) {
                System.out.println("      kind listed twice: " + source.kind());
                kindsUnique = false;
            }
        }
        check("no category is listed twice", kindsUnique);

        // Every name convention is stated, and a language key names its prefix -- the constructor checks
        // these, so reaching this point at all is the evidence.
        boolean namesStated = true;
        for (RegistrySource source : RegistrySource.all()) {
            if (source.entryNames() == RegistrySource.Names.NONE && source.namePrefix() != null) {
                namesStated = false;
            }
            if (source.entryNames() != RegistrySource.Names.NONE && source.entryNames() == null) {
                namesStated = false;
            }
        }
        check("every entry states how its name is found", namesStated);

        // The lookup used by the debug report must agree with the table it reports on.
        Map<ElementKind, RegistrySource> byKind = RegistrySource.byKind();
        check("the by-kind lookup covers the whole table",
                byKind.size() == RegistrySource.all().size());
        check("and resolves a known kind",
                byKind.containsKey(ElementKind.ENCHANTMENT)
                        && byKind.get(ElementKind.ENCHANTMENT).dynamic());
    }

    private static boolean isDedicated(ElementKind kind) {
        return switch (kind) {
            case MOD, DEBUG, NAMESPACE, DEPENDENCY, CONFLICT, MIXIN, ITEM, BLOCK, ENTITY, RECIPE, TAG,
                    LOOT_TABLE, LANG -> true;
            default -> false;
        };
    }

    /** A resource-location shape check that does not need Minecraft on the classpath. */
    private static String wellFormedLocation(String registry) {
        if (registry == null || registry.isEmpty()) {
            return null;
        }
        int colon = registry.indexOf(':');
        if (colon <= 0 || colon == registry.length() - 1) {
            return null;
        }
        String path = registry.substring(colon + 1);
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean fine = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.'
                    || c == '-' || c == '/';
            if (!fine) {
                return null;
            }
        }
        String namespace = registry.substring(0, colon);
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-')) {
                return null;
            }
        }
        return registry;
    }

    private static void theVocabularyAgrees() {
        section("the vocabulary and the table agree");

        // A category the table reads must be selectable, or the work is unreachable.
        boolean selectable = true;
        for (RegistrySource source : RegistrySource.all()) {
            Set<ElementKind> resolved = Tokens.kinds(source.kind().plural());
            if (resolved == null || !resolved.contains(source.kind())) {
                System.out.println("      not selectable: " + source.kind().plural());
                selectable = false;
            }
        }
        check("every registry-backed category is selectable by its own token", selectable);

        // And the group token includes them, since "all" is how a user asks for everything.
        Set<ElementKind> all = Tokens.kinds(Tokens.ALL);
        boolean inAll = true;
        for (RegistrySource source : RegistrySource.all()) {
            if (!all.contains(source.kind())) {
                System.out.println("      missing from 'all': " + source.kind().singular());
                inAll = false;
            }
        }
        check("every registry-backed category is in the 'all' group", inAll);

        // The data group is "everything that is not analysis", which is what the table is.
        Set<ElementKind> data = Tokens.kinds(Tokens.DATA);
        check("and in the data group", data.containsAll(
                Set.of(ElementKind.ENCHANTMENT, ElementKind.CREATIVE_TAB, ElementKind.SOUND,
                        ElementKind.BIOME, ElementKind.STRUCTURE, ElementKind.DIMENSION,
                        ElementKind.DAMAGE_TYPE, ElementKind.PARTICLE, ElementKind.ATTRIBUTE,
                        ElementKind.LANG)));

        check("collected content is not mistaken for analysis",
                !ElementKind.ENCHANTMENT.isAnalysis() && !ElementKind.LANG.isAnalysis());
        check("LANG has a token", ElementKind.LANG.singular().equals("lang")
                && ElementKind.LANG.plural().equals("langs"));
    }

    // ---------------------------------------------------------------- language tables

    private static void langParsing() {
        section("language tables");

        LangFile file = LangFile.parse("en_us", """
                { "item.example.ruby": "Ruby",
                  "item.example.ruby.desc": "A red gem, finely cut",
                  "block.example.ruby_ore": "Ruby Ore",
                  "num": 3,
                  "flag": true }""");
        check("every key is read", file.size() == 5);
        check("a value with a comma survives",
                file.entries().get("item.example.ruby.desc").equals("A red gem, finely cut"));
        // A pack that wrote a number meant the text "3"; refusing the file over it would lose every
        // other key in it.
        check("a number becomes text", file.entries().get("num").equals("3"));
        check("a boolean becomes text", file.entries().get("flag").equals("true"));
        check("the locale is carried", file.locale().equals("en_us"));

        // Sorted, so the output does not depend on a hash map's iteration order and a re-run of the same
        // pack produces the same bytes.
        List<String> keys = new ArrayList<>(file.entries().keySet());
        List<String> sorted = new ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        check("the keys come out sorted", keys.equals(sorted));

        // One string per line, joined by a newline: translations are prose and contain the commas a
        // comma-joined format would break on.
        List<String> lines = file.asLines();
        check("there is a line per key", lines.size() == 5);
        check("a line is key=value", lines.contains("item.example.ruby=Ruby"));
        check("a value containing a comma is still one line",
                lines.contains("item.example.ruby.desc=A red gem, finely cut"));

        check("an empty table is allowed", LangFile.parse("en_us", "{}").size() == 0);
        check("and reports no lines", LangFile.empty("zh_cn").asLines().isEmpty());

        // Nested values are skipped rather than stringified: the format has no nesting, so there is no
        // sensible text for one.
        LangFile nested = LangFile.parse("en_us", """
                { "a": "text", "b": { "c": "d" }, "e": [1, 2] }""");
        check("a nested value is skipped, the rest survive",
                nested.size() == 1 && nested.entries().containsKey("a"));

        boolean refused = false;
        try {
            LangFile.parse("en_us", "[1,2,3]");
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check("a document that is not an object is refused", refused);

        // Path shape: lang/<locale>.json, with the locale as the whole name.
        check("the locale comes from the path",
                "en_us".equals(LangFile.localeOf("lang/en_us.json")));
        check("zh_cn too", "zh_cn".equals(LangFile.localeOf("lang/zh_cn.json")));
        check("a nested path is not a locale", LangFile.localeOf("lang/sub/en_us.json") == null);
        check("a non-lang path yields nothing", LangFile.localeOf("loot_table/stone.json") == null);
        check("an empty name yields nothing", LangFile.localeOf("lang/.json") == null);
        check("and neither does null", LangFile.localeOf(null) == null);
        check("the directory is named once", LangFile.directory().equals("lang"));
        check("two locales are collected",
                LangFile.LOCALES.equals(List.of("zh_cn", "en_us")));
    }

    // ---------------------------------------------------------------- harness

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }
}
