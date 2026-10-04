package org.uee.model;

/**
 * A normalized entity record.
 *
 * <p>The aligned exporter emits {@code registerName} / {@code name} / {@code englishName} /
 * {@code Icon}; the writer performs that projection. As with items, {@code icon} carries raw PNG
 * bytes.
 *
 * <p>Note a known defect in the 1.x exporter lineage that UEE does not reproduce: it wrote the
 * entity's <em>loot table</em> id into {@code registerName} (e.g.
 * {@code minecraft:entities/zombie}) and derived {@code mod} from that. Here {@code registryName}
 * is always the entity type's real registry name.
 */
public record EntityElement(
        String registryName,
        String namespace,
        String translationKey,
        String nameZh,
        String nameEn,
        String category,
        byte[] icon) {

    public EntityElement {
        if (registryName == null || registryName.isEmpty()) {
            throw new IllegalArgumentException("registryName is required");
        }
        namespace = namespace == null ? ItemElement.namespaceOf(registryName) : namespace;
    }

    public boolean hasIcon() {
        return icon != null && icon.length > 0;
    }
}
