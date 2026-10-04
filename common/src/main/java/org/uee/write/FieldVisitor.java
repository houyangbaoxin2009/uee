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

    /** A list-of-strings field. An empty array is still emitted, so consumers see the key. */
    void array(String key, String[] values);

    /** Closes the element opened by {@link #element}. */
    void end();
}
