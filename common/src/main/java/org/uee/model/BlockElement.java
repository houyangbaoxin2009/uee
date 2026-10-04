package org.uee.model;

/**
 * A normalized block record, collected from the block registry rather than inferred from items.
 *
 * <p>Collecting blocks in their own right is what closes a gap in the aligned exporters: the 2.x
 * lineage reports every entry as {@code "Item"} and has no block notion at all, so a consumer that
 * wants the {@code B} category has to guess. UEE records the block, its block-item link and its
 * physical properties directly.
 */
public record BlockElement(
        String registryName,
        String namespace,
        String nameZh,
        String nameEn,
        float hardness,
        float blastResistance,
        int lightEmission,
        boolean hasBlockItem,
        String material,
        String[] tags) {

    public BlockElement {
        if (registryName == null || registryName.isEmpty()) {
            throw new IllegalArgumentException("registryName is required");
        }
        namespace = namespace == null ? ItemElement.namespaceOf(registryName) : namespace;
        tags = tags == null ? new String[0] : tags;
    }
}
