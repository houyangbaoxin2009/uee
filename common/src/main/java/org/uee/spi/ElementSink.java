package org.uee.spi;

import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;

/**
 * Receives elements as they are collected, one at a time.
 *
 * <p>This is a streaming interface on purpose. Materializing a full export as an in-memory list
 * would make peak memory scale with pack size — the exact failure mode UEE is designed to avoid.
 * A sink writes or shards as it goes, so peak memory is bounded by the shard, not the pack.
 *
 * <p>All methods are called from the collecting thread. Implementations are not required to be
 * thread-safe; the pipeline gives each shard its own sink.
 */
public interface ElementSink {

    /** A mod metadata record (layer A). */
    void mod(ModElement e);

    /** An item record (category {@code I}). */
    void item(ItemElement e);

    /** An entity record (category {@code E}). */
    void entity(EntityElement e);

    /** A block record (category {@code B}). */
    void block(BlockElement e);

    /** A recipe record (category {@code R}). */
    void recipe(RecipeElement e);

    /**
     * Any other record, for the categories that have no dedicated record type.
     *
     * <p>The namespace is explicit rather than derived from {@code key}, because a finding can be
     * cross-cutting: a conflict detected between two mods belongs to the collection, not to either
     * mod's shard, and a mixin record belongs to the mod that declared it. Deriving it would force
     * every caller to encode routing intent inside a display key.
     *
     * @param kind the category
     * @param namespace the shard this record belongs to
     * @param key the record's identity, unique within its namespace and kind
     * @param nameZh localized name, or {@code null}
     * @param nameEn English name, or {@code null}
     * @param listValues list-valued content, rendered as an array field
     * @param extra additional key/value pairs, flattened as k0,v0,k1,v1,...
     */
    void generic(ElementKind kind, String namespace, String key, String nameZh, String nameEn,
            String[] listValues, String[] extra);

    /** A debug section (layer A). */
    void debug(DebugSection section);

    /**
     * Reports an element that failed to collect. Collection continues afterwards — this is the
     * crash-isolation contract: one bad element must never abort the whole export.
     */
    void failure(ElementKind kind, String registryName, Throwable error);
}
