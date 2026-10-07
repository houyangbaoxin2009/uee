package org.uee.text;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.uee.datapack.LangFile;

/**
 * The sentences this program says, in whatever language the reader is using.
 *
 * <h2>Two readers, one catalogue</h2>
 *
 * <p>Most messages reach a player through the game, and the game already resolves translated text on the
 * client, per that client's own language setting. Sending a translation key and letting the game do it is
 * therefore the whole of the default behaviour: every player sees their own language, chosen by the setting
 * they already had, with nothing to configure. The catalogues are the same files the game reads — they sit
 * at {@code assets/uee/lang/} in the jar, which is exactly where the game looks — so there is one copy and
 * no second translation system.
 *
 * <p>The second reader is this class, and it exists for the case the first cannot serve: a message that has
 * no client behind it. A log line, a console command, and a run forced to one language all need the text
 * resolved on this side, and doing it here from the same files is what keeps the two in step.
 *
 * <h2>What is not translated, and why that is a rule rather than an omission</h2>
 *
 * <p>Command verbs, category names, format names and configuration keys stay as they are. They are a
 * vocabulary rather than prose: the same token has to work in a file, in a command and in an API call, and
 * a translation of it would be a second name for one thing — the same defect as two names for one decision,
 * which this project spends its time removing. A user who reads the documentation in Chinese still types
 * {@code /uee export items}, and the sentence around those tokens is what changes.
 *
 * <h2>Falling back, and failing visibly</h2>
 *
 * <p>A missing translation falls back to English rather than to a key, because a key tells a reader nothing
 * and looks like a bug. The chain is the exact locale, then its base language, then English, then — only if
 * the English catalogue itself is silent — the key, which at that point is the honest thing to show since
 * nothing knows what the message was meant to say.
 */
public final class UiText {

    /** The language every catalogue must cover, and the one the fallbacks end at. */
    public static final String FALLBACK_LOCALE = "en_us";

    /** Where the catalogues live in the jar; the game reads the same place. */
    public static final String DIRECTORY = "assets/uee/lang";

    /** Loaded catalogues, by locale. Empty rather than absent when a file is missing or unreadable. */
    private static final Map<String, Map<String, String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private UiText() {
    }

    /**
     * One message, in one language.
     *
     * @param locale the language to use; null or blank means English
     * @param key the key, which must exist in the English catalogue
     * @return the text, never null and never a bare key while English knows it
     */
    public static String get(String locale, String key) {
        if (key == null) {
            return "";
        }
        for (String candidate : chain(locale)) {
            String text = catalog(candidate).get(key);
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        // Nothing knows this key. Showing it is more use than an empty line, and the completeness check is
        // what stops it happening: a key used in code but absent from the English catalogue fails a test.
        return key;
    }

    /** One message with its arguments substituted, in the same order as {@code String.format}. */
    public static String format(String locale, String key, Object... args) {
        String text = get(locale, key);
        if (args == null || args.length == 0) {
            return text;
        }
        try {
            return String.format(text, args);
        } catch (RuntimeException e) {
            // A catalogue entry whose placeholders do not match what the caller passes is a translation
            // defect, and showing the unformatted text is better than throwing from a message about an
            // unrelated failure.
            return text;
        }
    }

    /**
     * The locales to try, most specific first: the exact one, then its base language, then English.
     *
     * <p>Exact first, then the base language, because a catalogue for {@code zh} serves a reader of
     * {@code zh_tw} who has no exact match — which is the point of trying the base form at all. English is
     * always last, so a catalogue may be as incomplete as it likes without anything going unreadable.
     *
     * <p>Public because the order is part of what a caller reasons about rather than an implementation
     * detail: someone asking which language a reader will get wants this list.
     */
    public static List<String> chain(String locale) {
        List<String> out = new ArrayList<>(3);
        String wanted = locale == null ? "" : locale.trim().toLowerCase(java.util.Locale.ROOT);
        if (!wanted.isEmpty()) {
            out.add(wanted);
            int cut = wanted.indexOf('_');
            if (cut > 0) {
                out.add(wanted.substring(0, cut));
            }
        }
        if (!out.contains(FALLBACK_LOCALE)) {
            out.add(FALLBACK_LOCALE);
        }
        return out;
    }

    /**
     * A catalogue, loaded once.
     *
     * <p>Read from the classpath rather than from a resource manager: these files are shipped inside the
     * jar, so they are always reachable, including in a run with no game at all. A resource manager is for
     * what a pack adds or replaces, and the game's own client-side resolution already covers that route for
     * messages that reach a player.
     */
    public static Map<String, String> catalog(String locale) {
        if (locale == null || locale.isBlank()) {
            locale = FALLBACK_LOCALE;
        }
        String wanted = locale.trim().toLowerCase(java.util.Locale.ROOT);
        return CACHE.computeIfAbsent(wanted, UiText::load);
    }

    private static Map<String, String> load(String locale) {
        String path = "/" + DIRECTORY + "/" + locale + ".json";
        try (InputStream in = UiText.class.getResourceAsStream(path)) {
            if (in == null) {
                return Map.of();
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // The same parser the language-file category uses, so a catalogue is read the way every other
            // language file in this program is read rather than by a second reader written for these.
            LangFile parsed = LangFile.parse(locale, json);
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed.entries()));
        } catch (IOException | RuntimeException e) {
            // A catalogue that cannot be read leaves the fallback chain to do its job. Reported nowhere
            // because there is nowhere to report it that would not itself need translating.
            return Map.of();
        }
    }

    /** Whether a catalogue for this locale exists in the jar. */
    public static boolean isAvailable(String locale) {
        return locale != null && !catalog(locale).isEmpty();
    }

    /**
     * The locales that have a catalogue, English first.
     *
     * <p>Listed from the classpath rather than declared, so adding a translation is adding a file. A
     * declared list would be one more thing to keep in step, and the list being wrong would show up as a
     * language that cannot be selected.
     */
    public static List<String> availableLocales() {
        Set<String> found = new LinkedHashSet<>();
        found.add(FALLBACK_LOCALE);
        // The directory itself, rather than a guess at each name: a jar's entries are listable when the
        // code is running from a directory, and a class loader that cannot list leaves just the fallback.
        try {
            java.net.URL url = UiText.class.getResource("/" + DIRECTORY);
            if (url != null && "file".equals(url.getProtocol())) {
                java.nio.file.Path dir = java.nio.file.Path.of(url.toURI());
                try (var stream = java.nio.file.Files.list(dir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                        String name = p.getFileName().toString();
                        found.add(name.substring(0, name.length() - ".json".length()));
                    });
                }
            }
        } catch (IOException | java.net.URISyntaxException | RuntimeException e) {
            // Left with what is certain.
        }
        // Whatever the classpath listing could not see, from the catalogues that were actually loaded --
        // and only those with entries. A locale that was asked about and found absent is cached as empty,
        // and listing it would offer a language that selects nothing and silently stays in English.
        CACHE.forEach((locale, entries) -> {
            if (!entries.isEmpty()) {
                found.add(locale);
            }
        });
        List<String> out = new ArrayList<>(found);
        out.remove(FALLBACK_LOCALE);
        java.util.Collections.sort(out);
        out.add(0, FALLBACK_LOCALE);
        return out;
    }

    /** Every key the English catalogue defines, for checking against the keys the code uses. */
    public static Set<String> englishKeys() {
        return new LinkedHashSet<>(catalog(FALLBACK_LOCALE).keySet());
    }

    /** A catalogue's keys, for checking that a translation covers the English one. */
    public static Set<String> keysOf(String locale) {
        return new LinkedHashSet<>(catalog(locale).keySet());
    }

    /** Prefix every key carries, so a scan for keys in source has something specific to look for. */
    public static final String PREFIX = "uee.";
}
