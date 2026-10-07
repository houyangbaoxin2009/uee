package org.uee.state;

import java.util.ArrayList;
import java.util.List;

import org.tielang.zd.ZdDocWriter;
import org.tielang.zd.ZdRow;
import org.tielang.zd.ZdVolume;

/**
 * A declaration table holding an ordered list of names.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>This is the simple case: a table whose whole content is a list of strings. It is the shape most
 * declarations take — which directories hold assets, which namespaces are known, which ids are conventions —
 * and having one codec for that shape means the first three tables do not each invent one.
 *
 * <p>It is deliberately not a general table format. A declaration that needs columns gets its own codec
 * beside whatever understands it, because the meaning of a column belongs to the thing that reads it, and a
 * generic schema would have to encode meanings it does not have. The point of the store holding payloads is
 * that this choice stays open.
 *
 * <h2>Why zd and not a text list</h2>
 *
 * <p>Because a table is supposed to be a zd document. Storing a newline-joined list would work and would be
 * one more ad-hoc format inside a file that already has one; every table being the same kind of document
 * means a reader of the file needs one decoder and can see, from the segment types alone, what it is looking
 * at.
 */
public final class DeclarationTable {

    /** zd row kind for a string, per {@code ZdRow}. */
    private static final int K_STRING = 1;

    /** The key of every row, so a reader does not depend on row position. */
    private static final String VALUE_KEY = "v";

    private DeclarationTable() {
    }

    /**
     * Encodes a list of names.
     *
     * <p>Order is preserved: a declaration may be a sequence a person reads back, and sorting it here would
     * take that away from the table that knows whether order matters.
     */
    public static byte[] encode(List<String> values) {
        List<ZdRow> rows = new ArrayList<>(values == null ? 0 : values.size());
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isEmpty()) {
                    // A row with nothing in it would come back as a value that exists and is empty, which is
                    // a different fact from not being declared.
                    continue;
                }
                rows.add(new ZdRow(K_STRING, VALUE_KEY, 0L, 0.0, value, 0));
            }
        }
        return ZdDocWriter.writeIndexed(0, rows);
    }

    /**
     * The names in a table payload.
     *
     * <p>Empty rather than throwing when the payload cannot be read: a damaged table costs the declarations
     * it held, and the rest of the state file is still worth reading.
     */
    public static List<String> decode(byte[] payload) {
        List<String> out = new ArrayList<>();
        if (payload == null || payload.length == 0) {
            return out;
        }
        try {
            for (ZdRow row : ZdVolume.readRows(payload)) {
                if (row.kind() == K_STRING && VALUE_KEY.equals(row.key())
                        && row.valueStr() != null && !row.valueStr().isEmpty()) {
                    out.add(row.valueStr());
                }
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return out;
    }
}
