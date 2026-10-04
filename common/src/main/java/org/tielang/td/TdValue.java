package org.tielang.td;

import java.util.List;
import java.util.Map;

/**
 * A td value: a scalar (string / long / double / boolean) or a nested table.
 * Immutable; scalars carry exactly one active field per {@link Kind}.
 */
public sealed interface TdValue permits TdValue.Scalar, TdTable {

    enum Kind {
        STRING, INT, FLOAT, BOOL
    }

    /** Scalar wrapper; exactly one of str/i/f/b matches {@link Kind}. */
    record Scalar(Kind kind, String str, long i, double f, boolean b) implements TdValue {

        public static Scalar str(String value) {
            return new Scalar(Kind.STRING, value, 0, 0, false);
        }

        public static Scalar of(long value) {
            return new Scalar(Kind.INT, null, value, 0, false);
        }

        public static Scalar of(double value) {
            return new Scalar(Kind.FLOAT, null, 0, value, false);
        }

        public static Scalar of(boolean value) {
            return new Scalar(Kind.BOOL, null, 0, 0, value);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case STRING -> str;
                case INT -> Long.toString(i);
                case FLOAT -> Double.toString(f);
                case BOOL -> Boolean.toString(b);
            };
        }
    }

    /** Convenience factories (delegate to {@link Scalar}). */
    static Scalar str(String value) {
        return Scalar.str(value);
    }

    static Scalar of(long value) {
        return Scalar.of(value);
    }

    static Scalar of(double value) {
        return Scalar.of(value);
    }

    static Scalar of(boolean value) {
        return Scalar.of(value);
    }

    /**
     * Convenience accessors, mirroring tie's {@code type_of / int_val / str_val}
     * style. Invalid for the wrong kind -> default (0 / "" / false).
     */
    default String asString() {
        if (this instanceof Scalar s && s.kind() == Kind.STRING) {
            return s.str();
        }
        if (this instanceof Scalar s && s.kind() == Kind.INT) {
            return Long.toString(s.i());
        }
        return "";
    }

    default long asInt() {
        if (this instanceof Scalar s && (s.kind() == Kind.INT || s.kind() == Kind.BOOL)) {
            return s.kind() == Kind.INT ? s.i() : (s.b() ? 1 : 0);
        }
        return 0;
    }

    default double asFloat() {
        if (this instanceof Scalar s && s.kind() == Kind.FLOAT) {
            return s.f();
        }
        if (this instanceof Scalar s && s.kind() == Kind.INT) {
            return s.i();
        }
        return 0.0;
    }

    default boolean asBool() {
        if (this instanceof Scalar s && s.kind() == Kind.BOOL) {
            return s.b();
        }
        if (this instanceof Scalar s && s.kind() == Kind.INT) {
            return s.i() != 0;
        }
        return false;
    }

    /** Asserts this value is {@link Scalar} of the given kind. */
    default Scalar scalar() {
        return (Scalar) this;
    }

    /** Convenience: a plain map view of named entries (nested values by reference). */
    default Map<String, TdValue> asMap() {
        if (this instanceof TdTable t) {
            Map<String, TdValue> map = new java.util.LinkedHashMap<>();
            for (String k : t.keys()) {
                map.put(k, t.get(k));
            }
            return map;
        }
        return Map.of();
    }

    /** Convenience: element list of a table value. */
    default List<TdValue> asList() {
        return (this instanceof TdTable t) ? t.elements() : List.of();
    }
}