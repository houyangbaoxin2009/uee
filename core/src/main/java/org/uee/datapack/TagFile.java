package org.uee.datapack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.uee.util.JsonReader;

/**
 * A parsed tag file: {@code data/<namespace>/tags/<type>/<path>.json}.
 *
 * <h2>What a tag file is</h2>
 *
 * <p>A tag names a set of things, and its members may be other tags:
 *
 * <pre>
 * { "replace": false,
 *   "values": [ "minecraft:stone", "#minecraft:base_stone_overworld",
 *               { "id": "minecraft:dirt", "required": false } ] }
 * </pre>
 *
 * <p>Three entry shapes, all of which occur in real packs and all of which are read here: a plain id, a
 * nested tag written with a leading {@code #}, and an object form that can mark the entry optional. The
 * object form matters because it is how a pack says "include this if present, and do not fail if it is
 * not" — dropping it would silently lose entries or, worse, invent failures.
 *
 * <h2>Why the parsing lives here and not in the Minecraft layer</h2>
 *
 * <p>Splitting reading from enumerating is what makes this testable. Enumerating tag files needs a
 * resource manager and therefore a game; parsing one needs a string, so the shape handling — including
 * its awkward cases — can be exercised with no game present. That matters more than usual here: the
 * resource path conventions and the three entry forms are exactly the kind of detail that is easy to
 * get subtly wrong and hard to notice, since a tag that lost its optional entries still looks like a
 * tag.
 */
public final class TagFile {

    /**
     * One member of a tag.
     *
     * @param id the member's id, with the leading {@code #} removed when it names another tag
     * @param nested whether the member is another tag rather than an element
     * @param required false when the file marked the entry optional — the pack is saying the member may
     *     legitimately be absent, so a missing one is not a defect
     */
    public record Entry(String id, boolean nested, boolean required) {

        public Entry {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("a tag entry needs an id");
            }
        }

        /** The member as it would be written in a tag file, with {@code #} on a nested tag. */
        public String asWritten() {
            return nested ? "#" + id : id;
        }
    }

    private final boolean replace;
    private final List<Entry> entries;

    private TagFile(boolean replace, List<Entry> entries) {
        this.replace = replace;
        this.entries = List.copyOf(entries);
    }

    /** An empty tag, for a file that declares none. */
    public static TagFile empty() {
        return new TagFile(false, List.of());
    }

    /**
     * Builds a tag from already-parsed entries, for merging a stack of files.
     *
     * <p>Exposed because merging cannot be expressed as parsing: the result depends on what the packs
     * below declared, so it has to be assembled outside this class while still being one of its values.
     */
    public static TagFile of(boolean replace, List<Entry> entries) {
        return new TagFile(replace, entries);
    }

    /**
     * Parses a tag file.
     *
     * <p>Lenient about shape and strict about identity: anything unreadable is skipped rather than
     * aborting, because one malformed entry in one pack must not cost the whole export — the same rule
     * the rest of collection follows. An entry with no usable id is the only thing dropped, since
     * without an id there is nothing to record.
     *
     * @throws IllegalArgumentException when the text is not a JSON object at all, which is a broken
     *     file rather than a broken entry and worth reporting
     */
    public static TagFile parse(String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        boolean replace = JsonReader.bool(root, "replace");
        Object raw = root.get("values");
        List<Entry> entries = new ArrayList<>(16);
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                Entry entry = entryOf(item);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } else if (raw != null) {
            // A single value instead of a list. Not valid by the format, but harmless to accept and
            // cheaper than refusing a file that a pack author clearly meant as one member.
            Entry entry = entryOf(raw);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return new TagFile(replace, entries);
    }

    /** Reads one entry, in any of the three forms. Returns {@code null} when there is no usable id. */
    private static Entry entryOf(Object item) {
        if (item instanceof String text) {
            return stringEntry(text);
        }
        if (item instanceof Map<?, ?> map) {
            Object id = map.get("id");
            if (!(id instanceof String text)) {
                return null;
            }
            Entry entry = stringEntry(text);
            if (entry == null) {
                return null;
            }
            // `required` defaults to true, so a missing key means the pack expects the member to exist.
            Object required = map.get("required");
            boolean isRequired = !(required instanceof Boolean b) || b;
            return new Entry(entry.id(), entry.nested(), isRequired);
        }
        return null;
    }

    /** Reads a plain id, splitting off a leading {@code #}. */
    private static Entry stringEntry(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        boolean nested = text.startsWith("#");
        String id = nested ? text.substring(1).trim() : text;
        return id.isEmpty() ? null : new Entry(id, nested, true);
    }

    /**
     * The relative path of a tag file's resource location.
     *
     * <p>The resource lives at {@code data/<namespace>/tags/<type>/<path>.json}, and the tag's own name
     * is {@code <path>} — the type is a separate axis. Passing the whole path through would produce an
     * id like {@code tags/item/foo}, which names no tag the game would recognise.
     *
     * @return the path part, or {@code null} when the location is not shaped like a tag file
     */
    public static String pathOf(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith("tags/")) {
            return null;
        }
        int slash = path.indexOf('/', "tags/".length());
        if (slash < 0) {
            return null;
        }
        String rest = path.substring(slash + 1);
        if (rest.endsWith(".json")) {
            rest = rest.substring(0, rest.length() - ".json".length());
        }
        return rest.isEmpty() ? null : rest;
    }

    /**
     * The tag's type from a resource location — {@code item}, {@code block}, {@code fluid} and so on.
     *
     * <p>Kept because the type is not implied by the id: {@code c:gems} exists as both an item tag and a
     * block tag in different packs, and a consumer that merged them would be merging two different sets.
     *
     * @return the type segment, or {@code null} when the location is not shaped like a tag file
     */
    public static String typeOf(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith("tags/")) {
            return null;
        }
        int start = "tags/".length();
        int slash = path.indexOf('/', start);
        if (slash < 0) {
            return null;
        }
        String type = path.substring(start, slash);
        return type.isEmpty() ? null : type;
    }

    /** Whether the file asked for its values to replace rather than add to inherited ones. */
    public boolean replace() {
        return replace;
    }

    /** The members, in the order the file listed them. */
    public List<Entry> entries() {
        return entries;
    }

    /** The members as written, for a record's value list. */
    public String[] membersAsWritten() {
        String[] out = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            out[i] = entries.get(i).asWritten();
        }
        return out;
    }

    /** How many members are nested tags rather than elements. */
    public int nestedCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.nested()) {
                n++;
            }
        }
        return n;
    }

    /** How many members were marked optional. */
    public int optionalCount() {
        int n = 0;
        for (Entry e : entries) {
            if (!e.required()) {
                n++;
            }
        }
        return n;
    }
}
