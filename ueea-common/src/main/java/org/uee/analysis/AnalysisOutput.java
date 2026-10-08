package org.uee.analysis;

import org.uee.model.ElementKind;

/**
 * Where an analysis writes its two kinds of output.
 *
 * <p>An analysis produces two distinct things and they should not be conflated:
 *
 * <ul>
 *   <li><b>Findings</b> — conclusions, e.g. "this dependency is missing". These are opinions about
 *       the instance and carry a severity.
 *   <li><b>Records</b> — structured data, e.g. the dependency graph itself. These are facts, have no
 *       severity, and belong in the output alongside the collected elements.
 * </ul>
 *
 * <p>Combining them here rather than passing two parameters keeps every analysis signature stable as
 * new output kinds appear, and it mirrors {@link org.uee.spi.ElementSink} closely enough that the
 * pipeline adapts one onto the other by direct delegation.
 */
public interface AnalysisOutput extends FindingSink {

    /**
     * Emits a structured record.
     *
     * @param kind the category the record belongs to
     * @param namespace the shard it belongs to; cross-cutting records use the global namespace
     * @param key the record's identity, unique within its namespace and category
     * @param listValues list-valued content, rendered as an array field
     * @param extra additional key/value pairs, flattened as k0,v0,k1,v1...
     */
    void record(ElementKind kind, String namespace, String key, String[] listValues, String[] extra);
}
