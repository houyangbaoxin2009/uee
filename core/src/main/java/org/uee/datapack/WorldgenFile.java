package org.uee.datapack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.uee.util.JsonReader;

/**
 * A parsed world-generation file: {@code data/<namespace>/worldgen/<kind>/<path>.json}.
 *
 * <h2>One category, fourteen kinds of file</h2>
 *
 * <p>World generation is not one thing. Under {@code worldgen/} the vanilla data has fourteen
 * directories, from {@code placed_feature} and {@code configured_feature} down to
 * {@code multi_noise_biome_source_parameter_list}, and each holds a completely different shape. They are
 * collected as one category because that is how a reader thinks about them — "the world generation" —
 * and the file's own kind is carried on the record rather than being fourteen switches.
 *
 * <p>They are read as files rather than through the registries that load them, unlike biomes and
 * structures. That choice has a consequence worth stating: this reads what a pack <em>wrote</em>, and it
 * works on a dedicated server and without a game at all, because {@code worldgen/} is under {@code data/}.
 * What it cannot say is what the game ended up with after other packs overrode it — for the two kinds
 * where that matters, biomes and structures, there are registry categories that do say.
 *
 * <h2>What is recorded, and what was measured rather than assumed</h2>
 *
 * <p>Three things, all of them checked against the nine hundred and eighty-nine worldgen files in the
 * shipped data:
 *
 * <ul>
 *   <li><b>The kind</b>, from the directory. Always present, and it is the axis along which these files
 *       differ — which is why it belongs in the record's key rather than only in a field.
 *   <li><b>The type</b>, when the file states one. Four of the fourteen kinds do
 *       ({@code configured_feature}, {@code configured_carver}, {@code density_function},
 *       {@code structure}), and for those it is what the file actually is: an ore feature, a tree feature.
 *   <li><b>The references</b>, which are what a reader follows. Four fields hold a bare id —
 *       {@code feature}, {@code fallback}, {@code start_pool} and {@code preset} — and one holds a list of
 *       objects each naming another id, {@code structures}.
 * </ul>
 *
 * <p>One field that looks like a reference and is not: {@code noise_settings.noise} was measured to hold
 * an inline noise specification — a height, a minimum Y, two size figures — rather than the id of a noise.
 * It is left out for that reason, and the reason is written down here because the name invites exactly
 * that mistake.
 */
public final class WorldgenFile {

    /**
     * Fields whose value is a bare reference id.
     *
     * <p>Each was measured to be a string in every file that has it, across the whole shipped dataset.
     */
    private static final List<String> REFERENCE_FIELDS =
            List.of("feature", "fallback", "start_pool", "preset");

    /**
     * Fields holding a list of objects that each name something, and the key within each object.
     *
     * <p>{@code structure_set.structures} is a list of {@code {structure: id, weight: n}} rather than a
     * list of ids, which is the sort of thing that is only known by looking: a reader that treated the
     * list as strings would find nothing and report a structure set with no structures.
     */
    private static final Map<String, String> REFERENCE_LIST_FIELDS = Map.of("structures", "structure");

    /**
     * List-valued fields whose size is worth recording, without their contents.
     *
     * <p>A template pool's elements and a processor list's processors are both deeply structured objects
     * describing how a piece is assembled. A count is what a reader wants, and the internals are a world
     * generation concern rather than a wiki one: copying them would make the record a second copy of a
     * file that is already on disk.
     */
    private static final List<String> COUNTED_FIELDS = List.of("elements", "processors");

    /**
     * The kinds under {@code worldgen/}, so a consumer can tell a recognised kind from an unrecognised one.
     *
     * <p>Listed rather than inferred: an unknown directory is still collected — a pack has a right to add
     * one — but it is marked as unrecognised so a reader knows the game may not load it.
     */
    private static final Set<String> KNOWN_KINDS = Set.of(
            "biome", "configured_carver", "configured_feature", "density_function",
            "flat_level_generator_preset", "multi_noise_biome_source_parameter_list", "noise",
            "noise_settings", "placed_feature", "processor_list", "structure", "structure_set",
            "template_pool", "world_preset", "feature");

    /**
     * What a file declares.
     *
     * @param kind the directory it lives under
     * @param type the type it states, or empty when it states none
     * @param references the ids it names, in the order they occur, de-duplicated
     * @param counts the sizes of its list-valued fields, by field name
     * @param constant the file's whole content when it is a bare value rather than an object, or empty
     */
    public record Worldgen(String kind, String type, List<String> references,
            Map<String, Integer> counts, String constant) {

        public Worldgen {
            references = List.copyOf(references);
            // Not Map.copyOf: that returns an unordered map, so the counts would come out in a different
            // order between runs and two identical exports would differ. The whole output is compared byte
            // for byte, so an unordered collection here is a correctness problem rather than a cosmetic
            // one. Caught by a check that expected the fields in the order they are declared.
            counts = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }

        /** Whether the kind is one the game knows about, as opposed to one a pack invented. */
        public boolean knownKind() {
            return KNOWN_KINDS.contains(kind);
        }

        public String[] referenceArray() {
            return references.toArray(new String[0]);
        }

        /** The counts as a single descriptive string, for a record's extra pairs. */
        public String countsAsText() {
            if (counts.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> entry : new LinkedHashMap<>(counts).entrySet()) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return sb.toString();
        }
    }

    private WorldgenFile() {
    }

    /**
     * Parses one worldgen file.
     *
     * <p>Lenient as the other datapack parsers are: a file whose references are malformed still yields its
     * kind and its type, because the kind is the axis the export is organised along and losing a record
     * entirely over one bad reference would be the larger loss.
     *
     * <h2>The form that is not an object</h2>
     *
     * <p>One file in the shipped data is the bare text {@code 0.0}: a density function may be written as a
     * constant instead of as an object. Refusing it would lose a record and report a failure against data
     * that is correct, so a top-level value is recorded as what it is. Only a document that is neither an
     * object nor a value — an array, which means nothing here — is refused.
     *
     * @throws IllegalArgumentException when the document is neither an object nor a value
     */
    public static Worldgen parse(String kind, String json) {
        Object document = JsonReader.parse(json);
        if (!(document instanceof Map)) {
            if (document instanceof Number || document instanceof String || document instanceof Boolean) {
                // A constant. Rare, real, and worth keeping: the record still says there is a file of this
                // kind under this name, which is most of what a reader wants from a constant.
                return new Worldgen(kind, "", List.of(), Map.of(), String.valueOf(document));
            }
            throw new IllegalArgumentException("expected a worldgen document, got "
                    + (document == null ? "null" : document.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) document;

        LinkedHashSet<String> references = new LinkedHashSet<>();

        for (String field : REFERENCE_FIELDS) {
            String value = JsonReader.str(root, field);
            if (value != null && !value.isEmpty()) {
                references.add(value);
            }
        }

        for (Map.Entry<String, String> entry : REFERENCE_LIST_FIELDS.entrySet()) {
            Object value = root.get(entry.getKey());
            if (!(value instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    String id = JsonReader.str(typed, entry.getValue());
                    if (id != null && !id.isEmpty()) {
                        references.add(id);
                    }
                } else if (item instanceof String s && !s.isEmpty()) {
                    // A pack may write the short form. Accepted rather than ignored, since it names the
                    // same thing.
                    references.add(s);
                }
            }
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String field : COUNTED_FIELDS) {
            Object value = root.get(field);
            if (value instanceof List<?> list) {
                counts.put(field, list.size());
            }
        }

        return new Worldgen(kind, JsonReader.str(root, "type", ""), new ArrayList<>(references), counts,
                "");
    }

    /**
     * The pairs a record carries beside its references.
     *
     * <p>Only what is true is emitted, so a file with no type and no counts carries neither key.
     */
    public static String[] extraPairs(Worldgen worldgen) {
        LinkedHashMap<String, String> pairs = new LinkedHashMap<>();
        pairs.put("worldgenKind", worldgen.kind());
        if (!worldgen.constant().isEmpty()) {
            pairs.put("constant", worldgen.constant());
        }
        if (!worldgen.type().isEmpty()) {
            pairs.put("type", worldgen.type());
        }
        String counts = worldgen.countsAsText();
        if (!counts.isEmpty()) {
            pairs.put("counts", counts);
        }
        if (!worldgen.knownKind()) {
            // Flagged rather than dropped: a directory the game does not know about may be a typo or a
            // pack's own convention, and either way a reader should be able to tell.
            pairs.put("recognised", "false");
        }
        String[] out = new String[pairs.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            out[i++] = entry.getKey();
            out[i++] = entry.getValue();
        }
        return out;
    }

    /**
     * The kind and the name from a resource location, or {@code null} when it is not a worldgen file.
     *
     * <p>Both are returned because both are needed: the kind alone is the axis, and the name alone
     * collides between kinds — {@code worldgen/placed_feature/x.json} and {@code worldgen/noise/x.json}
     * have the same name and are different records in the same namespace. That is why the record's key is
     * the kind and the name together.
     */
    public static String kindOf(String resourcePath) {
        String rest = afterDirectory(resourcePath);
        if (rest == null) {
            return null;
        }
        int slash = rest.indexOf('/');
        return slash < 0 ? null : rest.substring(0, slash);
    }

    /**
     * The file's name within its kind, or {@code null}.
     *
     * <p>Slashes are kept: {@code worldgen/placed_feature/ores/overworld_ore.json} is a name of
     * {@code ores/overworld_ore}, one name in a subdirectory rather than a name and a category — the same
     * convention the other datapack categories use.
     */
    public static String nameOf(String resourcePath) {
        String rest = afterDirectory(resourcePath);
        if (rest == null) {
            return null;
        }
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        String name = rest.substring(slash + 1);
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - ".json".length());
        }
        return name.isEmpty() ? null : name;
    }

    /**
     * The record key for a worldgen file: the kind and the name together.
     *
     * <p>Not the name alone, because two kinds can hold the same name and the records would then be
     * indistinguishable — the same reason a tag's type is recorded.
     */
    public static String keyOf(String resourcePath) {
        String kind = kindOf(resourcePath);
        String name = nameOf(resourcePath);
        return kind == null || name == null ? null : kind + "/" + name;
    }

    /** The resource directory world generation lives in. */
    public static String directory() {
        return "worldgen";
    }

    private static String afterDirectory(String resourcePath) {
        if (resourcePath == null) {
            return null;
        }
        String path = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        if (!path.startsWith("worldgen/")) {
            return null;
        }
        String rest = path.substring("worldgen/".length());
        if (rest.isEmpty()) {
            return null;
        }
        return rest;
    }
}
