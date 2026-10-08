package org.uee.model;

/**
 * A normalized item (or block item) record.
 *
 * <p>Field naming here is deliberately UEE-internal and neutral. The wiki-facing field names an
 * importer expects ({@code registerName}, {@code maxStackSize}, {@code TagList}, ...) are produced
 * by the wiki writer as a projection — see {@code org.uee.write.WikiWriter}. Keeping the two apart
 * is what lets one collector feed every output format.
 *
 * <p><b>Icons are raw PNG bytes, never base64.</b> Encoding to base64 belongs to the writer that
 * needs it; holding the encoded text in the model would both inflate peak memory by ~33% and
 * change bytes that a binary backend should keep verbatim.
 */
public record ItemElement(
        String registryName,
        String namespace,
        String translationKey,
        String nameZh,
        String nameEn,
        int maxStackSize,
        int maxDurability,
        String[] tags,
        String[] creativeTabs,
        byte[] iconLarge,
        byte[] iconSmall,
        boolean blockItem) {

    public ItemElement {
        if (registryName == null || registryName.isEmpty()) {
            throw new IllegalArgumentException("registryName is required");
        }
        tags = tags == null ? EMPTY : tags;
        creativeTabs = creativeTabs == null ? EMPTY : creativeTabs;
        namespace = namespace == null ? namespaceOf(registryName) : namespace;
    }

    private static final String[] EMPTY = new String[0];

    /** Extracts the namespace from a {@code namespace:path} registry name, defaulting to minecraft. */
    public static String namespaceOf(String registryName) {
        int i = registryName.indexOf(':');
        return i < 0 ? "minecraft" : registryName.substring(0, i);
    }

    /** The path portion of the registry name, i.e. everything after the colon. */
    public String path() {
        int i = registryName.indexOf(':');
        return i < 0 ? registryName : registryName.substring(i + 1);
    }

    /** True when any icon payload is present. */
    public boolean hasIcon() {
        return (iconLarge != null && iconLarge.length > 0) || (iconSmall != null && iconSmall.length > 0);
    }
}
