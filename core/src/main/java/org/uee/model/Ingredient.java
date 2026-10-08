package org.uee.model;

/**
 * A recipe ingredient: either a concrete item, a tag, or a list of alternatives.
 *
 * <p>{@code kind} is one of {@code "item"} (single id in {@code values[0]}), {@code "tag"} (single
 * tag id), or {@code "items"} (several ids). This mirrors the vanilla {@code Ingredient#toJson}
 * shape so that the wiki writer can reproduce it byte-for-byte without a second pass.
 */
public record Ingredient(String kind, String[] values, int count) {

    public static final String ITEM = "item";
    public static final String TAG = "tag";
    public static final String ITEMS = "items";

    public Ingredient {
        if (values == null) {
            values = new String[0];
        }
    }

    public static Ingredient ofItem(String id) {
        return new Ingredient(ITEM, new String[] {id}, 1);
    }

    public static Ingredient ofTag(String tag) {
        return new Ingredient(TAG, new String[] {tag}, 1);
    }

    public static Ingredient ofItems(String[] ids) {
        return new Ingredient(ITEMS, ids, 1);
    }

    /** True when this ingredient places nothing — used to skip empty shaped-recipe slots. */
    public boolean isEmpty() {
        return values.length == 0 || (values.length == 1 && (values[0] == null || values[0].isEmpty()));
    }
}
