package org.uee.datapack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.uee.util.JsonReader;

/**
 * A parsed advancement: {@code data/<namespace>/advancement/<path>.json}.
 *
 * <h2>What an advancement is, for the purpose this serves</h2>
 *
 * <p>A tree of prerequisites. Nearly every one of them names a parent, and that link is the record's most
 * useful field: a wiki page saying "you must have done this first" is reading the parent, and a reader
 * following the tree can lay out the whole progression.
 *
 * <h2>Most advancements have nothing to show</h2>
 *
 * <p>Of the fourteen hundred in the vanilla data, only about a tenth carry a {@code display} block. The
 * rest exist to be referenced — the recipe-unlock advancements are the bulk of them, one per recipe, and
 * they are invisible by design. So a reader that assumed {@code display} was present would lose nine
 * records in ten, and a reader that assumed its absence meant a broken file would report nine hundred
 * failures. Neither happens here: the display fields are read when present and omitted when not, and the
 * record carries whether there was one.
 *
 * <h2>Text is a key, not a string</h2>
 *
 * <p>A title is written as {@code {"translate": "advancements.story.root.title"}} in every vanilla file,
 * though the format also allows a literal. Both are read: the key when there is one, because that is what
 * the language tables resolve and what a consumer can look up, and the literal otherwise, because a pack
 * that wrote one meant to be read.
 *
 * <h2>Where it is parsed</h2>
 *
 * <p>In the core, for the reason every other parser is: it takes a string, and the shapes are where the
 * risk is. An advancement that lost its parent or its icon still parses as an advancement.
 */
public final class AdvancementFile {

    /**
     * What a table declares.
     *
     * @param display whether the file has a display block at all, which most do not
     * @param parent the id of the advancement this one requires, or {@code null} for a root
     * @param titleKey the translation key of the title, or {@code null} when there is no title
     * @param descriptionKey the translation key of the description, or {@code null}
     * @param titleLiteral the title's literal text, when it was written as text rather than as a key
     * @param descriptionLiteral the description's literal text, likewise
     * @param iconItem the item id shown as the icon, or {@code null}
     * @param frame {@code task}, {@code goal} or {@code challenge}; empty when not stated, which means
     *     {@code task}
     * @param hidden whether the advancement is hidden from the tree until earned
     * @param triggers the distinct trigger ids its criteria use, in order
     * @param criteria how many criteria the file declares
     * @param rewardRecipes how many recipes it unlocks as a reward
     * @param experience the experience it grants, or zero
     */
    public record Advancement(boolean display, String parent, String titleKey, String descriptionKey,
            String titleLiteral, String descriptionLiteral, String iconItem, String frame,
            boolean hidden, List<String> triggers, int criteria, int rewardRecipes, int experience) {

        public Advancement {
            triggers = List.copyOf(triggers);
        }

        /** The title to show: the literal if one was written, otherwise the key, otherwise nothing. */
        public String titleOrKey() {
            if (titleLiteral != null && !titleLiteral.isEmpty()) {
                return titleLiteral;
            }
            return titleKey;
        }

        public String descriptionOrKey() {
            if (descriptionLiteral != null && !descriptionLiteral.isEmpty()) {
                return descriptionLiteral;
            }
            return descriptionKey;
        }

        public String[] triggerArray() {
            return triggers.toArray(new String[0]);
        }
    }

    private AdvancementFile() {
    }

    /**
     * Parses an advancement.
     *
     * <p>Lenient about everything except identity, like the other datapack parsers: a file whose display
     * is malformed still yields its parent and its triggers rather than being dropped, because losing the
     * tree link over a bad icon would be a worse outcome than the bad icon.
     *
     * @throws IllegalArgumentException when the document is not a JSON object
     */
    public static Advancement parse(String json) {
        Map<String, Object> root = JsonReader.parseObject(json);

        // The parent is written with or without a namespace. Both forms occur; an id without one means the
        // file's own namespace, which the caller knows and this does not, so it is passed through as
        // written rather than guessed at here.
        String parent = JsonReader.str(root, "parent");
        if (parent != null && parent.isEmpty()) {
            parent = null;
        }

        Map<String, Object> display = asMap(root.get("display"));
        boolean hasDisplay = !display.isEmpty();

        String titleKey = null;
        String titleLiteral = null;
        String descriptionKey = null;
        String descriptionLiteral = null;
        String iconItem = null;
        String frame = "";
        boolean hidden = false;

        if (hasDisplay) {
            Map<String, Object> title = asMap(display.get("title"));
            titleKey = JsonReader.str(title, "translate");
            titleLiteral = JsonReader.str(title, "text");

            Map<String, Object> description = asMap(display.get("description"));
            descriptionKey = JsonReader.str(description, "translate");
            descriptionLiteral = JsonReader.str(description, "text");

            // 1.21 writes an item stack here: the id is under "id". A pack may still write the older
            // "item", and reading only one of the two would lose the icon rather than fail loudly.
            Map<String, Object> icon = asMap(display.get("icon"));
            iconItem = JsonReader.str(icon, "id");
            if (iconItem == null) {
                iconItem = JsonReader.str(icon, "item");
            }

            frame = JsonReader.str(display, "frame", "");
            hidden = JsonReader.bool(display, "hidden");
        }

        // Criteria are named blocks each carrying a trigger; the names are the file's own business and the
        // triggers are what identify the kind of action. Distinct and in order, since a record listing the
        // same trigger four times says nothing the first one did not.
        LinkedHashSet<String> triggers = new LinkedHashSet<>();
        int criteria = 0;
        for (Object value : asMap(root.get("criteria")).values()) {
            criteria++;
            String trigger = JsonReader.str(asMap(value), "trigger");
            if (trigger != null && !trigger.isEmpty()) {
                triggers.add(trigger);
            }
        }

        int rewardRecipes = 0;
        int experience = 0;
        Map<String, Object> rewards = asMap(root.get("rewards"));
        if (!rewards.isEmpty()) {
            Object recipes = rewards.get("recipes");
            if (recipes instanceof List<?> list) {
                rewardRecipes = list.size();
            }
            Object xp = rewards.get("experience");
            if (xp instanceof Number number) {
                experience = number.intValue();
            }
        }

        return new Advancement(hasDisplay, parent, titleKey, descriptionKey, titleLiteral,
                descriptionLiteral, iconItem, frame, hidden, new ArrayList<>(triggers), criteria,
                rewardRecipes, experience);
    }

    /**
     * The pairs a record carries beside its values.
     *
     * <p>Only what is true is emitted. Most advancements have no display, no parent, no rewards and no
     * experience, and a key present on every record to say "none" would put a difference between two
     * identical exports into every diff.
     */
    public static String[] extraPairs(Advancement advancement) {
        java.util.LinkedHashMap<String, String> pairs = new java.util.LinkedHashMap<>();
        if (advancement.parent() != null) {
            pairs.put("parent", advancement.parent());
        }
        if (advancement.display()) {
            pairs.put("display", "true");
            String title = advancement.titleOrKey();
            if (title != null && !title.isEmpty()) {
                pairs.put("title", title);
            }
            String description = advancement.descriptionOrKey();
            if (description != null && !description.isEmpty()) {
                pairs.put("description", description);
            }
            if (advancement.iconItem() != null && !advancement.iconItem().isEmpty()) {
                pairs.put("icon", advancement.iconItem());
            }
            // "task" is what the game assumes when nothing is written, so writing it out would be noise.
            if (!advancement.frame().isEmpty() && !"task".equals(advancement.frame())) {
                pairs.put("frame", advancement.frame());
            }
            if (advancement.hidden()) {
                pairs.put("hidden", "true");
            }
        }
        pairs.put("criteria", Integer.toString(advancement.criteria()));
        if (advancement.rewardRecipes() > 0) {
            pairs.put("rewardRecipes", Integer.toString(advancement.rewardRecipes()));
        }
        if (advancement.experience() > 0) {
            pairs.put("experience", Integer.toString(advancement.experience()));
        }
        String[] out = new String[pairs.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            out[i++] = entry.getKey();
            out[i++] = entry.getValue();
        }
        return out;
    }

    /** The advancement's own path from a resource location. */
    public static String pathOf(String resourcePath) {
        return afterDirectory(resourcePath, "advancement/");
    }

    /** The resource directory advancements live in, for the enumerating side to share. */
    public static String directory() {
        return "advancement";
    }

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

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }
}
