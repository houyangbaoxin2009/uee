package org.uee.write;

import java.util.ArrayList;
import java.util.List;
import org.tielang.zd.ZdDocWriter;
import org.tielang.zd.ZdRow;
import org.uee.config.ExportConfig;
import org.uee.model.BlockElement;
import org.uee.model.DebugSection;
import org.uee.model.ElementKind;
import org.uee.model.EntityElement;
import org.uee.model.ItemElement;
import org.uee.model.ModElement;
import org.uee.model.RecipeElement;
import org.uee.util.StringPool;

/**
 * zd writer — the tie ecosystem's binary serialization.
 *
 * <p>zd is the reason a UEE artifact can be more than a dump: its columnar encoding family, string
 * pool and content fingerprints make the output compressible, diffable and cross-language
 * reproducible, which is what the tie-side toolchain consumes.
 *
 * <p>Unlike the text backends this writer is <b>batch</b>: zd is a document format whose records are
 * emitted as one encoded unit, so rows accumulate for the shard and are encoded at
 * {@link #endShard()}. It is the one backend where peak memory scales with shard size, which is why
 * sharding exists.
 *
 * <p>The record shape mirrors {@link ElementExpander}'s fields exactly, so the binary and text
 * outputs carry the same information: each element is a table node with one child per field, and a
 * list field is a nested table node with one bare child per value.
 */
public final class ZdWriter extends Writer implements FieldVisitor {

    /** zd node kinds, per {@link ZdRow}: 0 table/array, 1 string, 2 integer or bool, 3 float. */
    private static final int K_TABLE = 0;
    private static final int K_STRING = 1;
    private static final int K_INT = 2;
    private static final int K_FLOAT = 3;

    private final StringPool pool;
    private final ArrayList<Row> rows = new ArrayList<>(4096);
    private int openIdx = -1;
    private long elementCount;

    public ZdWriter(ExportConfig config, StringPool pool) {
        super(config);
        this.pool = pool;
    }

    @Override
    public String token() {
        return ExportConfig.ZD;
    }

    @Override
    public String extension() {
        return "zd";
    }

    @Override
    public boolean isBatch() {
        return true;
    }

    @Override
    public void beginShard(String namespace, ElementKind kind) {
        rows.clear();
        openIdx = -1;
        elementCount = 0;
    }

    @Override
    public void endShard() {
        if (rows.isEmpty()) {
            return;
        }
        // The document root is a table whose children are the elements; its child count is only
        // known once collection is done, which is why it is prepended here rather than streamed.
        List<ZdRow> encoded = new ArrayList<>(rows.size() + 1);
        encoded.add(new ZdRow(K_TABLE, "", 0L, 0.0, null, elementCount));
        for (Row r : rows) {
            encoded.add(new ZdRow(r.kind, r.key, r.i64, r.f64, r.str, r.child));
        }
        out.raw(ZdDocWriter.writeIndexed(0, encoded));
    }

    // ---------------------------------------------------------------- element entry points

    @Override
    public void item(ItemElement e) {
        ElementExpander.item(e, this);
    }

    @Override
    public void entity(EntityElement e) {
        ElementExpander.entity(e, this);
    }

    @Override
    public void block(BlockElement e) {
        ElementExpander.block(e, this);
    }

    @Override
    public void recipe(RecipeElement e) {
        ElementExpander.recipe(e, this);
    }

    @Override
    public void mod(ModElement e) {
        ElementExpander.mod(e, this);
    }

    @Override
    public void generic(ElementKind kind, String namespace, String key, String nameZh,
            String nameEn, String[] listValues, String[] extra) {
        ElementExpander.generic(kind, namespace, key, nameZh, nameEn, listValues, extra, this);
    }

    @Override
    public void debug(DebugSection s) {
        ElementExpander.debug(s, this);
    }

    // ---------------------------------------------------------------- visitor

    @Override
    public void element(ElementKind kind, String registryName) {
        Row r = new Row();
        r.kind = K_TABLE;
        r.key = "";
        rows.add(r);
        openIdx = rows.size() - 1;
        elementCount++;
        if (registryName != null) {
            string("registryName", registryName);
        }
    }

    @Override
    public void string(String key, String value) {
        if (value == null) {
            return;
        }
        addLeaf(key, K_STRING, 0L, 0.0, pool.value(pool.intern(value)));
    }

    @Override
    public void number(String key, long value) {
        addLeaf(key, K_INT, value, 0.0, null);
    }

    @Override
    public void decimal(String key, double value) {
        addLeaf(key, K_FLOAT, 0L, value, null);
    }

    @Override
    public void bool(String key, boolean value) {
        addLeaf(key, K_INT, value ? 1L : 0L, 0.0, null);
    }

    @Override
    public void array(String key, String[] values) {
        Row a = new Row();
        a.kind = K_TABLE;
        a.key = key;
        a.child = values.length;
        rows.add(a);
        int arrIdx = rows.size() - 1;
        countParent();
        for (String v : values) {
            Row leaf = new Row();
            leaf.kind = K_STRING;
            leaf.key = "";
            leaf.str = v == null ? null : pool.value(pool.intern(v));
            rows.add(leaf);
        }
        // Keep the array node's own open state irrelevant: the element node stays the parent.
        assert arrIdx >= 0;
    }

    @Override
    public void records(String key, String[] columns, java.util.List<String[]> data) {
        Row list = new Row();
        list.kind = K_TABLE;
        list.key = key;
        list.child = data.size();
        rows.add(list);
        for (String[] row : data) {
            Row record = new Row();
            record.kind = K_TABLE;
            record.key = "";
            record.child = columns.length;
            rows.add(record);
            for (int c = 0; c < columns.length; c++) {
                Row leaf = new Row();
                leaf.kind = K_STRING;
                leaf.key = columns[c];
                leaf.str = c < row.length && row[c] != null ? pool.value(pool.intern(row[c])) : null;
                rows.add(leaf);
            }
        }
        countParent();
    }

    @Override
    public void end() {
        openIdx = -1;
    }

    // ---------------------------------------------------------------- internals

    private void addLeaf(String key, int kind, long i64, double f64, String str) {
        Row r = new Row();
        r.kind = kind;
        r.key = key;
        r.i64 = i64;
        r.f64 = f64;
        r.str = str;
        rows.add(r);
        countParent();
    }

    private void countParent() {
        if (openIdx >= 0) {
            rows.get(openIdx).child++;
        }
    }

    /** Mutable mirror of {@link ZdRow}; the immutable record is built once, at shard end. */
    private static final class Row {
        int kind;
        String key;
        long i64;
        double f64;
        String str;
        int child;
    }
}
