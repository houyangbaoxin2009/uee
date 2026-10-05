package org.uee.write;

import org.uee.model.ElementKind;

/**
 * Receives one element's fields in a format-independent way.
 *
 * <p>All text backends (td, YAML, TOML, XML) drive {@link ElementExpander} through this interface,
 * so the set of fields and their names is defined in exactly one place. Without that, four
 * hand-written serializers would drift the moment a field is added — and a drifted field name is
 * invisible until a consumer silently reads {@code null}.
 *
 * <p>Call order is: {@link #element} once, then a run of field calls, then {@link #end}.
 */
public interface FieldVisitor {

    /** Opens an element. {@code registryName} may be {@code null} for records that lack one. */
    void element(ElementKind kind, String registryName);

    /** A string field. Implementations must skip {@code null} values rather than emit empty ones. */
    void string(String key, String value);

    void number(String key, long value);

    void decimal(String key, double value);

    void bool(String key, boolean value);

    /**
     * Whether a field should be written at all.
     *
     * <p>Asked through the visitor rather than read from the configuration by the caller, so the
     * expansion code stays free of configuration and a writer that ignores projection — the wiki
     * projection, whose field names are a contract with a third party — can say so once.
     *
     * <p>Identity fields always answer true; see {@link org.uee.config.FieldMask}.
     */
    default boolean has(String field) {
        return true;
    }

    /** A list-of-strings field. An empty array is still emitted, so consumers see the key. */
    void array(String key, String[] values);

    /**
     * A list of records that all share the same columns.
     *
     * <p>Needed because some content is genuinely tabular and flattening it to text would put the
     * burden of parsing back onto every consumer. Dependencies are the motivating case: the
     * difference between a required, optional and explicitly incompatible entry is the whole point
     * of the analysis, and a {@code "id@range"} string cannot carry it.
     *
     * @param key the field name
     * @param columns column names, in order
     * @param rows one array per record, each the same length as {@code columns}
     */
    void records(String key, String[] columns, java.util.List<String[]> rows);

    /** Closes the element opened by {@link #element}. */
    void end();
}
