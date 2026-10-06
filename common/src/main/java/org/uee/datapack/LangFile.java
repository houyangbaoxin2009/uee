package org.uee.datapack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.uee.util.JsonReader;

/**
 * A parsed translation table: {@code assets/<namespace>/lang/<locale>.json}.
 *
 * <h2>Where these live, and the consequence</h2>
 *
 * <p>Unlike every other thing this project reads, a language file is under {@code assets/} rather than
 * {@code data/}. That is not a detail — it decides where the category works. A resource manager is
 * constructed for one side or the other and can only see its own: a client's sees {@code assets/}, a
 * dedicated server's sees {@code data/}. So translations are collectable on a client and legitimately
 * absent on a dedicated server, and the collector reports that rather than returning an empty result that
 * looks like a pack with no translations.
 *
 * <p>It also explains a wart worth knowing about: the names on every other record come from the same
 * files, so on a dedicated server they are null too.
 *
 * <h2>Shape</h2>
 *
 * <p>A flat map of key to text, which is all a language file is. Parsed here rather than in the Minecraft
 * layer for the usual reason — it takes a string, so the awkward parts are testable without a game. The
 * awkward part is that values are not always strings: a language file may hold numbers and booleans where
 * a pack author was careless, and refusing the file over one of them would lose thousands of usable keys.
 */
public final class LangFile {

    private final String locale;
    private final Map<String, String> entries;

    private LangFile(String locale, Map<String, String> entries) {
        this.locale = locale;
        this.entries = Collections.unmodifiableMap(entries);
    }

    /**
     * Parses a language file.
     *
     * <p>Every value is read as text: a number or a boolean becomes its string form, because that is what
     * a language table is for and a pack that wrote {@code 3} meant the text "3". Anything nested is
     * skipped — the format has no nesting, so a nested value is a mistake and there is no sensible text
     * for it.
     *
     * @throws IllegalArgumentException when the document is not a JSON object at all
     */
    public static LangFile parse(String locale, String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        Map<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isEmpty() || value == null) {
                continue;
            }
            if (value instanceof Map || value instanceof List) {
                continue;
            }
            entries.put(key, String.valueOf(value));
        }
        return new LangFile(locale, entries);
    }

    /** An empty table, for a file that declares nothing usable. */
    public static LangFile empty(String locale) {
        return new LangFile(locale, new LinkedHashMap<>());
    }

    /** Which locale this table is for. */
    public String locale() {
        return locale;
    }

    /** The table, sorted by key so the output does not depend on a hash map's iteration order. */
    public Map<String, String> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    /**
     * The table as {@code key=value} lines, for a record's value list.
     *
     * <p>Joined into one list rather than one record per key, because a table of seven thousand keys
     * would otherwise become seven thousand records per namespace per locale — which is a shape the
     * export would pay for in file count and shard overhead on every run, to say nothing of the diff.
     *
     * <p>The separator is a newline rather than a comma, because a translation is prose: commas occur
     * inside values and newlines do not occur at all in the format. A consumer splits on it and gets back
     * exactly what was written.
     */
    public List<String> asLines() {
        List<String> out = new ArrayList<>(entries.size());
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            out.add(entry.getKey() + "=" + entry.getValue());
        }
        return out;
    }

    /**
     * The locale from a resource path, or {@code null} when the path is not a language file.
     *
     * <p>{@code lang/<locale>.json} — one file per locale, so the name is the locale and there is nothing
     * else in it.
     */
    public static String localeOf(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith("lang/")) {
            return null;
        }
        String rest = path.substring("lang/".length());
        if (!rest.endsWith(".json")) {
            return null;
        }
        String locale = rest.substring(0, rest.length() - ".json".length());
        // A nested path would mean a locale directory, which the format does not have.
        return locale.isEmpty() || locale.indexOf('/') >= 0 ? null : locale;
    }

    /** The resource directory language files live in, as the resource manager names it. */
    public static String directory() {
        return "lang";
    }
}
