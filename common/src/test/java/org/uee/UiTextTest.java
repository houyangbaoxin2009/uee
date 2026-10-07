package org.uee;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.uee.text.UiText;

/**
 * Checks the message catalogues against the code that uses them.
 *
 * <h2>The check that matters, and why it is a scan</h2>
 *
 * <p>A translation system fails quietly in two directions. A key that the code asks for and the catalogue
 * does not define shows a reader the key; an entry nothing asks for is dead weight that looks maintained.
 * Neither shows up as a wrong answer anywhere, so neither is found by testing behaviour — which is why this
 * reads the source and compares it against the catalogues.
 *
 * <p>Reading the source rather than keeping a list of keys in the test is deliberate. A list would be a
 * third place for the truth to live, and it would be the one nobody updates: the whole point is that the
 * set of keys is whatever the code says, checked against what the catalogues offer.
 */
public final class UiTextTest {

    private static int failures;

    /**
     * The keys the code asks for, as written in the source.
     *
     * <p>Restricted to the areas keys are allowed to live in, because a bare {@code uee.} prefix is not
     * enough to tell a key from an ordinary string: the configuration file is called {@code uee.data.tie},
     * which is a file name and would otherwise be reported as a message nobody defined. Adding an area is
     * therefore a deliberate act, in the same list the catalogue is organised by.
     */
    private static final Pattern KEY = Pattern.compile(
            "\"(uee\\.(?:set|err|cmd|status|export|job|jobs|kinds|formats|config|declare|asset"
                    + "|help|flow|datapack|globalpack|target|strategy|finding|hover)\\.[A-Za-z0-9_.]+)\"");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/ui-text-test");
        Path source = Path.of("common/src");

        theCatalogues();
        everyKeyTheCodeUsesIsDefined(source);
        nothingIsDefinedAndUnused(source);
        theFallbackChain();
        formatting();

        System.out.println();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- the catalogues

    private static void theCatalogues() {
        section("the catalogues");

        check("English is always available", UiText.isAvailable(UiText.FALLBACK_LOCALE));
        check("and Chinese is shipped too", UiText.isAvailable("zh_cn"));
        check("English defines something", UiText.englishKeys().size() > 20);
        check("the locales are listed", UiText.availableLocales().contains("zh_cn"));
        check("with English first", UiText.availableLocales().get(0).equals(UiText.FALLBACK_LOCALE));
        check("a locale nobody ships is not available", !UiText.isAvailable("eo"));

        // Every shipped catalogue covers English exactly. A translation missing an entry would fall back to
        // English silently, which is acceptable at runtime and unacceptable as a release: it means the
        // translation is incomplete without anyone being told.
        Set<String> english = UiText.englishKeys();
        for (String locale : UiText.availableLocales()) {
            Set<String> keys = UiText.keysOf(locale);
            Set<String> missing = new TreeSet<>(english);
            missing.removeAll(keys);
            Set<String> extra = new TreeSet<>(keys);
            extra.removeAll(english);
            check(locale + " covers every English entry" + (missing.isEmpty() ? "" : ": " + missing),
                    missing.isEmpty());
            check(locale + " defines nothing English does not" + (extra.isEmpty() ? "" : ": " + extra),
                    extra.isEmpty());
        }

        // A translation is actually in the other language, rather than English copied into a second file --
        // which would pass every check above and mean nothing.
        long differing = english.stream()
                .filter(k -> !UiText.get("zh_cn", k).equals(UiText.get(UiText.FALLBACK_LOCALE, k)))
                .count();
        check("the Chinese catalogue is a translation, not a copy", differing > english.size() / 2);
    }

    // ---------------------------------------------------------------- keys versus code

    private static void everyKeyTheCodeUsesIsDefined(Path source) throws IOException {
        section("every key the code asks for is defined");

        Set<String> used = keysUsedInSource(source);
        check("the scan found keys at all", !used.isEmpty());

        Set<String> defined = UiText.englishKeys();
        List<String> undefined = used.stream().filter(k -> !defined.contains(k)).toList();
        // The failure this catches: a message the code intends to translate but that no catalogue knows, so a
        // reader is shown the key itself.
        check("every key the code uses is defined" + (undefined.isEmpty() ? "" : ": " + undefined),
                undefined.isEmpty());
    }

    private static void nothingIsDefinedAndUnused(Path source) throws IOException {
        section("nothing is defined and unused");

        Set<String> used = keysUsedInSource(source);
        Set<String> defined = UiText.englishKeys();
        List<String> orphans = defined.stream().filter(k -> !used.contains(k)).sorted().toList();
        // Not a correctness problem, but the entry is a claim that something says this, and a stale one
        // hides the fact that the message it belonged to is gone.
        check("every English entry is used somewhere" + (orphans.isEmpty() ? "" : ": " + orphans),
                orphans.isEmpty());
    }

    /** Every {@code "uee.…"} literal in the source tree. */
    private static Set<String> keysUsedInSource(Path source) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher m = KEY.matcher(text);
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    // ---------------------------------------------------------------- resolution

    private static void theFallbackChain() {
        section("the fallback chain");

        String key = "uee.set.quiet";
        check("a shipped locale resolves in that locale",
                UiText.get("zh_cn", key).equals(UiText.catalog("zh_cn").get(key)));
        check("English resolves in English",
                UiText.get(UiText.FALLBACK_LOCALE, key).equals(UiText.catalog("en_us").get(key)));
        check("and so does nothing at all", UiText.get(null, key).equals(UiText.get("en_us", key)));

        // A region with no catalogue of its own falls back to its language, which is the whole point of
        // trying the base form: a reader of zh_tw gets the Chinese that exists rather than the English that
        // does not need to.
        // The base form is tried, but only a catalogue that exists can answer: Chinese is shipped as
        // zh_cn, so a reader of zh_tw reaches English through the missing zh rather than getting Chinese by
        // accident. The chain is what makes that a fact rather than a hope.
        check("a region tries its base language", UiText.chain("zh_tw").contains("zh"));
        check("but with no zh catalogue it reaches English",
                UiText.get("zh_tw", key).equals(UiText.get("en_us", key)));
        check("while the shipped region gets the translation",
                !UiText.get("zh_cn", key).equals(UiText.get("en_us", key)));
        check("and an unknown region still reaches English",
                UiText.get("de_de", key).equals(UiText.get("en_us", key)));
        check("case does not matter", UiText.get("ZH_CN", key).equals(UiText.get("zh_cn", key)));
        check("nor does whitespace", UiText.get("  zh_cn ", key).equals(UiText.get("zh_cn", key)));

        // A key nothing knows is shown as the key. That is the honest last resort, and the completeness
        // check above is what stops it being reached in practice.
        check("an unknown key comes back as itself", UiText.get("en_us", "uee.nope").equals("uee.nope"));
        check("an empty key gives nothing", UiText.get(null, "").isEmpty() && UiText.get(null, null).isEmpty());

        check("the chain tries the locale then its base then English",
                UiText.chain("zh_cn").equals(List.of("zh_cn", "zh", "en_us")));
        check("a bare language has no base to try",
                UiText.chain("zh").equals(List.of("zh", "en_us")));
        check("and an empty request is English alone",
                UiText.chain("").equals(List.of("en_us")) && UiText.chain(null).equals(List.of("en_us")));
    }

    private static void formatting() {
        section("arguments");

        Map<String, String> en = UiText.catalog("en_us");
        String key = "uee.set.shards";
        check("the entry takes an argument", en.get(key).contains("%s"));
        check("English substitutes it", UiText.format("en_us", key, 500).contains("500"));
        check("Chinese substitutes it too", UiText.format("zh_cn", key, 500).contains("500"));
        check("and both keep a real localisation around it",
                !UiText.format("zh_cn", key, 500).equals(UiText.format("en_us", key, 500)));
        check("no arguments leaves the text alone", UiText.format("en_us", key).contains("%s"));
        // A catalogue entry whose placeholders do not match what the caller passes is a translation defect;
        // showing the raw text is better than throwing from a message about something else.
        check("too many arguments is tolerated", UiText.format("en_us", "uee.set.quiet", 1, 2) != null);
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
