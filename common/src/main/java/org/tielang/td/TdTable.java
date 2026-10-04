package org.tielang.td;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * td (tie data) configuration model — the Java mirror of the tie table-literal
 * subset used by tie-main {@code config.parse_data} / {@code tdzd}:
 * {@code type tie<data>} header, optional table name {@code name = [...]},
 * bare tables, named entries {@code key = value}, arrays, nested tables,
 * strings (with escape sequences), integers, floats and booleans.
 * <p>
 * A td document is a single {@link TdTable}. Table entries are ordered;
 * an entry without a key is an array element. The model is immutable once built.
 */
public final class TdTable implements TdValue {

    private final Map<String, TdValue> named;
    private final List<String> keyOrder;
    private final List<TdValue> arrayElements;

    TdTable(List<TdEntry> entries) {
        this.named = new LinkedHashMap<>();
        this.keyOrder = new ArrayList<>();
        this.arrayElements = new ArrayList<>();
        for (TdEntry e : entries) {
            if (e.key() == null) {
                arrayElements.add(e.value());
            } else if (!named.containsKey(e.key())) {
                named.put(e.key(), e.value());
                keyOrder.add(e.key());
            }
        }
    }

    /** Ordered named keys (insertion order, first occurrence wins). */
    public List<String> keys() {
        return List.copyOf(keyOrder);
    }

    /** Array elements (entries without a key), in order. */
    public List<TdValue> elements() {
        return List.copyOf(arrayElements);
    }

    /** Lookup by key; null when absent. */
    public TdValue get(String key) {
        return named.get(key);
    }

    /** True when the table has no entries at all. */
    public boolean isEmpty() {
        return keyOrder.isEmpty() && arrayElements.isEmpty();
    }

    /**
     * Builds a table; entries preserve call order.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<TdEntry> entries = new ArrayList<>();

        public Builder put(String key, TdValue value) {
            entries.add(new TdEntry(key, value));
            return this;
        }

        public Builder put(String key, String value) {
            return put(key, TdValue.str(value));
        }

        public Builder element(TdValue value) {
            entries.add(new TdEntry(null, value));
            return this;
        }

        public TdTable build() {
            return new TdTable(entries);
        }
    }
}