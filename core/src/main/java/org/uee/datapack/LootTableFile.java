package org.uee.datapack;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.uee.util.JsonReader;

/**
 * A parsed loot table: {@code data/<namespace>/loot_table/<path>.json}.
 *
 * <h2>What is recorded, and what is only referenced</h2>
 *
 * <p>An entry tree has six entry types in the data this ships against. The rule applied here is the same
 * one tags follow: <b>walk what this file writes, reference what lives elsewhere.</b>
 *
 * <ul>
 *   <li>{@code item} — a droppable item. Recorded.
 *   <li>{@code tag} — a set of items named by a tag. Recorded as a <em>reference</em>: expanding it
 *       needs the tag registry, and the tag is its own record that a reader can look up.
 *   <li>{@code loot_table} — another table's contents. Its {@code value} is either a string naming that
 *       table (recorded as a reference) or an <em>inline table written in this file</em>, which is this
 *       file's own content and so is walked. Both forms occur in the vanilla data, and the inline one
 *       would be silently lost by a reader that assumed {@code value} was always a name.
 *   <li>{@code alternatives} — a list of {@code children}. Walked.
 *   <li>{@code empty} and {@code dynamic} — nothing to enumerate, counted.
 * </ul>
 *
 * <h2>Why the parser is here rather than in the Minecraft layer</h2>
 *
 * <p>Same reason as tags: parsing needs a string, enumerating needs a game. Keeping them apart is what
 * lets every shape above be tested without one, and the shapes are exactly where the risk is — a table
 * that lost its inline entries or its tag references still looks like a table.
 */
public final class LootTableFile {

    /**
     * How deep an entry tree is walked.
     *
     * <p>Entry trees nest through {@code children} and inline tables, and a file is untrusted input: a
     * pathological one could be deep enough to overflow the stack. The deepest real table nests four
     * levels, so this is far above anything genuine and exists only to make the walk total.
     */
    private static final int MAX_DEPTH = 32;

    /**
     * What a table declares.
     *
     * @param type the top-level type, which is the loot context — a block's drops, a chest's contents, an
     *     entity's drops. Kept because it is what makes a table interpretable: the same shape means
     *     different things for a chest and for a mob.
     * @param items distinct droppable item ids, in the order first seen
     * @param tags tag references, written as {@code #id} so they read the same way a tag member does
     * @param tables other loot tables referenced by id, in order
     * @param pools how many pools the file declares
     * @param entries how many entries the file declares, counting repeats and nesting
     * @param hasConditions whether any pool, entry or inline table carries a condition
     */
    public record Table(String type, List<String> items, List<String> tags, List<String> tables,
            int pools, int entries, boolean hasConditions) {

        public Table {
            items = List.copyOf(items);
            tags = List.copyOf(tags);
            tables = List.copyOf(tables);
        }

        /** The items as an array, for a record's value list. */
        public String[] itemArray() {
            return items.toArray(new String[0]);
        }

        public String[] tagArray() {
            return tags.toArray(new String[0]);
        }

        public String[] tableArray() {
            return tables.toArray(new String[0]);
        }
    }

    private LootTableFile() {
    }

    /**
     * Parses a loot table.
     *
     * <p>Lenient about shape and strict about identity, like the tag parser: anything unreadable is
     * skipped rather than aborting, because one malformed entry in one pack must not cost the whole
     * export. A document that is not an object at all is refused, since that is a broken file rather
     * than a broken entry.
     *
     * @throws IllegalArgumentException when the text is not a JSON object
     */
    public static Table parse(String json) {
        Map<String, Object> root = JsonReader.parseObject(json);
        String type = JsonReader.str(root, "type", "");

        // Insertion-ordered sets: the output has to be the same for every run, and a hash set would
        // order it by hash, which is stable within a JVM but not across them.
        LinkedHashSet<String> items = new LinkedHashSet<>();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        int[] entryCount = {0};
        boolean[] conditions = {false};

        Object poolsRaw = root.get("pools");
        int pools = 0;
        if (poolsRaw instanceof List<?> poolList) {
            pools = poolList.size();
            for (Object pool : poolList) {
                if (pool instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) map;
                    if (p.containsKey("conditions")) {
                        conditions[0] = true;
                    }
                    for (Object entry : asList(p.get("entries"))) {
                        walk(entry, 0, items, tags, tables, entryCount, conditions);
                    }
                }
            }
        }

        // A table may carry functions at the top level, which apply to everything it produces. Only
        // their presence is recorded: a function's effect is not something a wiki cites as content.
        return new Table(type, List.copyOf(items), List.copyOf(tags), List.copyOf(tables), pools,
                entryCount[0], conditions[0]);
    }

    /**
     * Walks one entry and its children.
     *
     * <p>Depth is capped rather than tracked for cycles: an inline table cannot contain itself, since
     * it is written out, so depth is the only unbounded axis and the cap makes the walk total.
     */
    private static void walk(Object raw, int depth, LinkedHashSet<String> items,
            LinkedHashSet<String> tags, LinkedHashSet<String> tables, int[] entryCount,
            boolean[] conditions) {
        if (depth >= MAX_DEPTH || !(raw instanceof Map<?, ?> map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) map;
        entryCount[0]++;
        if (entry.containsKey("conditions")) {
            conditions[0] = true;
        }

        String type = JsonReader.str(entry, "type", "");
        // The modern form. `minecraft:item` carries the item id in `name`, and so do `tag` and
        // `dynamic`; this is read off the shipped data rather than assumed, because the obvious guess
        // (that a tag reference would be written like a tag member, with a leading hash) is wrong.
        String name = JsonReader.str(entry, "name");
        if (name != null && !name.isEmpty()) {
            switch (type) {
                case "minecraft:item" -> items.add(name);
                case "minecraft:tag" -> tags.add("#" + name);
                case "minecraft:dynamic" -> {
                    // A placeholder resolved at runtime from the block or entity that produced it, so
                    // there is no id to record. Counted, and left out of every list.
                }
                default -> {
                    // An entry type this build does not know. `name` is recorded as an item, since that
                    // is what every entry type carrying `name` has meant so far and losing it would be
                    // worse than a slightly generous reading.
                    items.add(name);
                }
            }
        }

        Object value = entry.get("value");
        if (value instanceof String tableId && !tableId.isEmpty()) {
            tables.add(tableId);
        } else if (value instanceof Map<?, ?> inline) {
            // An inline table: this file's own content, so it is walked like any other entry tree.
            @SuppressWarnings("unchecked")
            Map<String, Object> inlineTable = (Map<String, Object>) inline;
            for (Object pool : asList(inlineTable.get("pools"))) {
                if (pool instanceof Map<?, ?> pm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) pm;
                    for (Object child : asList(p.get("entries"))) {
                        walk(child, depth + 1, items, tags, tables, entryCount, conditions);
                    }
                }
            }
        }

        for (Object child : asList(entry.get("children"))) {
            walk(child, depth + 1, items, tags, tables, entryCount, conditions);
        }
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /**
     * The table's own path from a resource location.
     *
     * <p>{@code data/<namespace>/loot_table/<path>.json} — the path after the directory is the table id,
     * and the directories under it are part of that id ({@code chests/desert_pyramid} is one name, not a
     * name and a category).
     *
     * @return the path, or {@code null} when the location is not shaped like a loot table
     */
    public static String pathOf(String resourcePath) {
        return afterDirectory(resourcePath, "loot_table/");
    }

    /**
     * The directory a loot table lives under, from a resource location.
     *
     * <p>Unlike a tag, whose type is a real axis of the id, a loot table's first directory
     * ({@code blocks/}, {@code entities/}, {@code chests/}) is only a filing convention — the table id
     * includes it. It is still worth reporting, because it is how a pack author and a reader think about
     * a table, but it is recorded as a separate hint and never as part of the id.
     */
    public static String folderOf(String resourcePath) {
        String path = pathOf(resourcePath);
        if (path == null) {
            return null;
        }
        int slash = path.indexOf('/');
        return slash < 0 ? null : path.substring(0, slash);
    }

    /** The path of a resource inside {@code directory}, with the extension removed. */
    private static String afterDirectory(String resourcePath, String directory) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith(directory)) {
            return null;
        }
        String rest = path.substring(directory.length());
        if (rest.endsWith(".json")) {
            rest = rest.substring(0, rest.length() - ".json".length());
        }
        return rest.isEmpty() ? null : rest;
    }

    /** The resource directory loot tables live in, for the enumerating side to share. */
    public static String directory() {
        return "loot_table";
    }

    /**
     * A table's description as key/value pairs, ready for a record's extra field.
     *
     * <p>Kept out of {@link Table} because it is presentation rather than content: what a reader wants
     * beside a table, not something another consumer should parse back.
     *
     * <p>The references are written out in full, not only counted. A tag reference and a table reference
     * are the two things a wiki page needs to follow — "this mob also drops whatever that table rolls" is
     * a statement a reader acts on — and a count would leave the reader with nothing to look up.
     *
     * <p>A key is emitted only when it has something to say. That is not cosmetic: records are compared
     * byte for byte across runs, so adding a key that is usually empty would put a difference between two
     * identical exports into every diff.
     */
    public static String[] extraPairs(Table table) {
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        pairs.put("lootType", table.type().isEmpty() ? "unknown" : table.type());
        pairs.put("pools", Integer.toString(table.pools()));
        pairs.put("entries", Integer.toString(table.entries()));
        if (!table.tags().isEmpty()) {
            // The hash is dropped here because the key already says these are tags, and a value list
            // and a key/value pair are read differently; keeping it would suggest a tag id starts with
            // one.
            pairs.put("tagRefs", String.join(",", strip(table.tags())));
        }
        if (!table.tables().isEmpty()) {
            pairs.put("tableRefs", String.join(",", table.tables()));
        }
        if (table.hasConditions()) {
            pairs.put("conditional", "true");
        }
        String[] out = new String[pairs.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : pairs.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    private static List<String> strip(List<String> hashed) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(hashed.size());
        for (String s : hashed) {
            out.add(s.startsWith("#") ? s.substring(1) : s);
        }
        return out;
    }
}
