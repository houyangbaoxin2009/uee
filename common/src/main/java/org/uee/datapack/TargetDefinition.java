package org.uee.datapack;

import java.util.List;
import org.uee.model.ElementKind;

/**
 * A datapack-defined collection target: a named selection over one existing category, written to its
 * own output.
 *
 * <h2>What a target can and cannot be</h2>
 *
 * <p>A datapack cannot invent a new kind of record — the element model is compiled Java, and a data
 * file cannot add a type to it. So a target is a <em>selection and projection</em> over an existing
 * category: "the items carrying tag X", "the blocks in namespace Y", "every recipe, but into its own
 * file". That boundary is real and is stated rather than worked around, because the alternative —
 * pretending a data file can define a new record — would produce a feature that quietly cannot do
 * what its name suggests.
 *
 * <h2>Where the output goes</h2>
 *
 * <p>A target produces an <b>additional</b> shard, alongside the category's own. Nothing is removed
 * from the category output, so adding a target is purely additive and cannot silently truncate an
 * export someone already depends on. If a target's selection should be the only copy of something,
 * that is a flow's business — the flow can leave the category out of {@code kinds}.
 *
 * @param id the target's identity, unique across all datapacks
 * @param namespace the declaring datapack's namespace, for reporting
 * @param category which category this selects within
 * @param selector the selection
 * @param description optional human-readable note
 */
public record TargetDefinition(String id, String namespace, ElementKind category, Selector selector,
        String description) {

    public TargetDefinition {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("target id is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("target '" + id + "' has no category");
        }
        selector = selector == null ? Selector.ALL : selector;
    }

    /** Tests one element against this target. */
    public boolean accepts(String registryName, String elementNamespace, String[] tags) {
        return selector.accepts(registryName, elementNamespace, tags);
    }

    /** The category this target selects within, as a set, for enabling it through the sink filter. */
    public List<ElementKind> requiredKinds() {
        return List.of(category);
    }

    /** A stable key for the target's output, used in the shard identity and the directory name. */
    public String shardKey() {
        return namespace == null ? id : namespace + ":" + id;
    }
}
