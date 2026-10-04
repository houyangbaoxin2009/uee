package org.uee.model;

/**
 * A normalized recipe record.
 *
 * <p>Slots are kept as parallel arrays rather than maps so that a recipe costs two small arrays
 * instead of a hash table — recipes are the single most numerous category in a large pack.
 *
 * <p>The slot numbering convention is the one both aligned dumpers use: for shaped crafting the key
 * is {@code column + row * 3 + 1} (so the top-left is {@code "1"}); shapeless uses a sequential
 * {@code "1".."9"}; furnace-like types have a single input at {@code "1"}. Outputs are keyed the
 * same way, with the shaped result at {@code "1"}.
 */
public record RecipeElement(
        String id,
        String type,
        String namespace,
        String[] inputSlots,
        Ingredient[] inputs,
        String[] outputSlots,
        String[] outputItems,
        int[] outputCounts,
        String[] outputNbt,
        Double experience,
        Integer cookTime) {

    public RecipeElement {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("recipe type is required");
        }
        if (inputSlots == null) {
            inputSlots = new String[0];
        }
        if (inputs == null) {
            inputs = new Ingredient[0];
        }
        if (outputSlots == null) {
            outputSlots = new String[0];
        }
        if (outputItems == null) {
            outputItems = new String[0];
        }
        if (outputCounts == null) {
            outputCounts = new int[outputItems.length];
        }
        namespace = namespace == null || namespace.isEmpty() ? "minecraft" : namespace;
    }

    /** True when the output slot at {@code i} carries an NBT payload. */
    public boolean hasNbt(int i) {
        return outputNbt != null && i < outputNbt.length && outputNbt[i] != null && !outputNbt[i].isEmpty();
    }

    public boolean hasCookingData() {
        return experience != null || cookTime != null;
    }
}
