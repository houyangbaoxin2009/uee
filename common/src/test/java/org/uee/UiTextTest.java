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
    /**
     * Characters that belong in a translation and not in the English catalogue.
     *
     * <p>Written as code points rather than as the characters themselves, so the check does not depend
     * on this file being read in the right encoding -- a check about text is the last place to rely on
     * how text was decoded.
     */
    private static final Pattern CJK = Pattern.compile(
            "[\u3000-\u303f\u3400-\u4dbf\u4e00-\u9fff\uff00-\uffef]");

    private static final Pattern KEY = Pattern.compile(
            "\"(uee\\.(?:set|err|cmd|status|export|job|jobs|kinds|formats|config|declare|asset"
                    + "|help|flow|datapack|globalpack|target|strategy|finding|hover|app)\\.[A-Za-z0-9_.]+)\"");

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "build/ui-text-test");
        Path source = Path.of("common/src");

        theCatalogues();
        everyKeyTheCodeUsesIsDefined(source);
        nothingIsDefinedAndUnused(source);
        theFallbackChain();
        theNameIsTranslated();
        theLabelledLinesLineUp();
        theSectionHeadingsAreOneWidth();
        argumentsAreAlwaysSendable();
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

        // And the English catalogue is actually English. Nothing above would notice a value written in
        // the wrong language: the key is defined, it is used, and the two files agree. So a Chinese label
        // copied into the English file shows up only when someone reads the output, which is how five
        // entries kept their labels in Chinese on both sides until this was looked at.
        List<String> notEnglish = english.stream()
                .filter(k -> CJK.matcher(UiText.get(UiText.FALLBACK_LOCALE, k)).find())
                .sorted().toList();
        check("the English catalogue holds no non-Latin text"
                + (notEnglish.isEmpty() ? "" : ": " + notEnglish), notEnglish.isEmpty());
    }

    /**
     * The program's own name is translated, and it is resolved in the reader's language.
     *
     * <h2>The defect this covers</h2>
     *
     * <p>The name is substituted <em>into</em> messages, so it has to be resolved on this side rather than
     * sent as a key. It was resolved against the configured language, which is empty by default, and an
     * empty request falls back to English — so a Chinese reader was shown "Universal Element Exporter" in
     * the middle of Chinese sentences. The choice of language is the game layer's job; what is checkable
     * here is that the name is a translation at all, so that whatever language is chosen for it, the answer
     * is in that language.
     */
    private static void theNameIsTranslated() {
        section("the program's own name");

        String key = "uee.app.name";
        check("the name is defined", UiText.englishKeys().contains(key));

        String english = UiText.get("en_us", key);
        String chinese = UiText.get("zh_cn", key);
        check("in English it is the English name", english.equals("Universal Element Exporter"));
        check("in Chinese it is not", !chinese.equals(english));
        check("but the Chinese one is Chinese", CJK.matcher(chinese).find());

        // And an unresolvable language still yields a name rather than a key, since the fallback chain ends
        // at English rather than at nothing.
        check("an unknown language still gives the English name",
                UiText.get("eo", key).equals(english));

        // The keys that are substituted into sentences rather than sent as messages depend on this too: if
        // the name were not translated, these would be the visible symptom.
        check("and so is every value substituted into a sentence",
                CJK.matcher(UiText.get("zh_cn", "uee.status.analysisOff")).find()
                        && CJK.matcher(UiText.get("zh_cn", "uee.declare.kindWhy")).find());
    }

    /**
     * The labelled lines line up: every label occupies the same width, in every language.
     *
     * <h2>What this is for</h2>
     *
     * <p>These lines are a column of labels and a column of values, and the column only exists if the labels
     * are the same width. Adding a label of a different length silently breaks the alignment for every line
     * at once, and nothing else would notice — the entries would all still be defined, used and translated.
     *
     * <p>Width is counted in text columns rather than in characters, because that is what a reader sees: a
     * CJK glyph takes two columns and a Latin character one. That is also why the Chinese labels are padded
     * with ideographic spaces — padding by character count with ordinary spaces leaves the colons staggered.
     */
    private static void theLabelledLinesLineUp() {
        section("the labelled lines line up");

        // Grouped by the listing they appear in, since two listings need not share a width.
        java.util.List<java.util.List<String>> groups = java.util.List.of(
                java.util.List.of("uee.status.loader", "uee.status.game", "uee.status.mods",
                        "uee.status.export", "uee.status.analyzer", "uee.status.threads",
                        "uee.status.categories", "uee.status.output", "uee.status.configLine",
                        "uee.status.persist", "uee.status.language"),
                java.util.List.of("uee.export.dataLoc", "uee.export.analysisLoc"));

        for (String locale : UiText.availableLocales()) {
            for (java.util.List<String> group : groups) {
                java.util.Set<Integer> widths = new java.util.LinkedHashSet<>();
                StringBuilder seen = new StringBuilder();
                for (String key : group) {
                    String entry = UiText.get(locale, key);
                    // Everything up to the first placeholder, or the whole entry when there is none: that is
                    // the label and its padding.
                    int cut = entry.indexOf("%s");
                    String label = cut < 0 ? entry : entry.substring(0, cut);
                    widths.add(columns(label));
                    seen.append('[').append(label).append('=').append(columns(label)).append(']');
                }
                check(locale + " labels share a width: " + seen, widths.size() == 1);
            }
        }

        // And the padding is real: an entry with a shorter label has to carry the filler, or the check above
        // would pass on a group that had been made uniform by making every label short.
        // The padded one is a three-character label, since the four-character ones need no padding at all.
        check("a short label is padded to match the long ones",
                columns(labelOf("zh_cn", "uee.status.loader"))
                        == columns(labelOf("zh_cn", "uee.status.categories")));
        check("with ideographic spaces rather than ordinary ones, so CJK aligns",
                UiText.get("zh_cn", "uee.status.loader").indexOf('\u3000') > 0);
        check("and the padding is measured, not counted",
                columns(UiText.get("zh_cn", "uee.status.loader"))
                        == columns(UiText.get("zh_cn", "uee.status.categories")));
    }

    /**
     * The section headings are all one width, so the eye can find where a section starts.
     *
     * <p>The frame is punctuation sized by the renderer from the name's measured width, so the property worth
     * checking is the one a reader sees: whatever each language calls a section, every heading is as wide as
     * every other heading. The width is the one the renderer uses, so the two cannot disagree.
     */
    private static void theSectionHeadingsAreOneWidth() {
        section("the section headings");

        int target = 38;
        for (String locale : UiText.availableLocales()) {
            java.util.Set<Integer> widths = new java.util.LinkedHashSet<>();
            StringBuilder seen = new StringBuilder();
            for (String key : java.util.List.of("uee.status.section.game", "uee.status.section.export",
                    "uee.status.section.messages")) {
                String name = UiText.get(locale, key);
                int remaining = Math.max(0, target - UiText.columns(name));
                String heading = "=".repeat(remaining / 2) + name + "=".repeat(remaining - remaining / 2);
                widths.add(UiText.columns(heading));
                seen.append('[').append(name).append('=').append(UiText.columns(heading)).append(']');
            }
            check(locale + " headings are all " + target + " wide: " + seen,
                    widths.size() == 1 && widths.contains(target));
        }

        // A section is named by the catalogue, so a name of a different length is how this would break, and a
        // name wider than the heading would leave no frame at all -- a heading that stopped being one.
        for (String locale : UiText.availableLocales()) {
            for (String key : java.util.List.of("uee.status.section.game", "uee.status.section.export",
                    "uee.status.section.messages")) {
                check(locale + " names " + key + " within the heading width",
                        UiText.columns(UiText.get(locale, key)) < target);
            }
        }
    }

    /** The label part of an entry: up to the first placeholder, or the whole entry. */
    private static String labelOf(String locale, String key) {
        String entry = UiText.get(locale, key);
        int cut = entry.indexOf("%s");
        return cut < 0 ? entry : entry.substring(0, cut);
    }

    /**
     * The width of a string in text columns, asked of the core rather than measured again here.
     *
     * <p>A second copy would be a second rule, and the renderer pads with the core one: if the two ever
     * differed, this check would pass on text the game lays out differently.
     */
    private static int columns(String text) {
        return UiText.columns(text);
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
        // Only this mod's own keys are required to be used. The catalogue also holds keys other software
        // reads by its own convention -- the mod list reads a name translation under its own prefix -- and
        // those are addressed to that software, not to a call site here.
        List<String> orphans = UiText.englishKeys().stream()
                .filter(k -> k.startsWith(UiText.PREFIX))
                .filter(k -> !used.contains(k))
                .sorted().toList();
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

    /**
     * A translation argument may be anything, and the message still has to be sendable.
     *
     * <h2>Why this is here</h2>
     *
     * <p>The client refuses to encode a component whose translation arguments are not a number, a boolean
     * or a string, and the refusal takes the whole packet with it: the reader gets nothing and the log gets
     * an encode failure. A {@code Path} is not one of the three, so the status readout — which reports
     * paths, and where the path is the most useful thing on the line — could not be sent at all.
     *
     * <p>So the arguments are checked directly rather than through a message, because the rule is about
     * what the component will accept and not about any one sentence. The types here are the ones a caller
     * is most likely to pass without thinking: the path it just computed, the collection it just built, the
     * value it looked up.
     */
    private static void argumentsAreAlwaysSendable() {
        section("arguments a message can carry");

        Object[] mixed = {java.nio.file.Path.of("D:/x/config/uee.data.tie"), List.of("a", "b"),
                Map.of("k", "v"), null, 7, true, "text", new StringBuilder("built")};
        Object[] safe = UiText.translatable(mixed);
        check("the conversion returns as many arguments as it was given",
                safe.length == mixed.length);

        boolean allAllowed = true;
        for (Object arg : safe) {
            // The exact predicate the client applies, as read from its own code rather than restated.
            if (!(arg instanceof Number || arg instanceof Boolean || arg instanceof String)) {
                allAllowed = false;
            }
        }
        check("every argument is one the client will encode" + (allAllowed ? "" : ": " + java.util.Arrays.toString(safe)),
                allAllowed);

        // The conversions a reader would expect, not merely ones that pass the check.
        check("a path becomes its text", safe[0] instanceof String && safe[0].toString().contains("uee.data.tie"));
        check("a collection becomes its own text", safe[1] instanceof String);
        check("a map too", safe[2] instanceof String);
        check("null becomes the word", "null".equals(safe[3]));
        check("a number is left as a number", safe[4] instanceof Number);
        check("a boolean is left as a boolean", safe[5] instanceof Boolean);
        check("and a string is left alone", "text".equals(safe[6]));
        check("while anything else becomes its text", "built".equals(safe[7]));

        // And the entry that broke is formatted with a path without complaint.
        String withPath = UiText.format("en_us", "uee.status.output", java.nio.file.Path.of("D:/x/exports/uee"));
        check("a message whose argument is a path formats", withPath.contains("uee"));
        check("with the path's own text in it", withPath.contains("exports"));

        check("no arguments is left alone", UiText.translatable(null) == null);
        check("and an empty array too", UiText.translatable(new Object[0]).length == 0);
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
