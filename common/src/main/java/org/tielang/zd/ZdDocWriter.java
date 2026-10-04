package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * zd 文档写出器：写 10 字节头 + 一串六字段 wire2 记录。树的写出复用 DFS 先序平铺语义
 * （见 {@link ZdTrees}）。
 * <p>
 * <b>v3 纪律：</b>写方默认写 v3（头 {@code "03"}）；{@link #write} 产出无 footer 的 v3
 * 文件（纯单段形态，v2 读取规则可读的正文布局）；{@link #writeIndexed} 产出带索引 footer
 * 的 v3 文件（头 bit5 + 段表 + 尾 footer，见 {@link ZdFooter}）；{@link #writeV2} 保留 v2
 * 字节兼容路径。正文行格式在 v2/v3 间逐字节一致，仅头版本与可选 footer 不同。多段文档
 * （数据 + 池 + 列式 + schema + 图 + 压缩 + 自定义）走 {@link ZdSegments#assemble}。
 * <p>
 * zd document writer: writes a 10-byte header + a series of six-field wire2 records.
 * The tree path reuses the DFS preorder flatten semantics (see {@link ZdTrees}).
 * <b>v3 discipline:</b> writers default to v3 (header {@code "03"}); {@link #write}
 * produces a footerless v3 file (single-segment shape, a body layout readable under the
 * v2 reading rules); {@link #writeIndexed} produces a v3 file with the index footer
 * (header bit5 + segment table + tail footer, see {@link ZdFooter}); {@link #writeV2}
 * keeps the v2 byte-compatibility path. The row body is byte-identical across v2/v3 —
 * only the header version and the optional footer differ. Multi-segment documents
 * (data + pool + columnar + schema + graph + compressed + custom) go through
 * {@link ZdSegments#assemble}.
 */
public final class ZdDocWriter {

    private ZdDocWriter() {
    }

    /**
     * 写完整 zd <b>v3</b> 字节流（头 "03" + 每行六字段，无 footer；bit5 不置位）。
     * 默认写路径。Writes the full zd <b>v3</b> byte stream (header "03" + six-field row
     * per row, no footer; bit5 clear). The default write path.
     */
    public static byte[] write(int flags, List<ZdRow> rows) {
        return writeVersioned(ZdHeader.VERSION_V3, flags & ZdHeader.FLAGS_MASK_V3, rows);
    }

    /**
     * 写完整 zd <b>v2</b> 字节流（头 "02"，低 5 位 flags）——v2 字节兼容写路径。
     * Writes the full zd <b>v2</b> byte stream (header "02", low-5-bit flags) — the
     * v2 byte-compatibility write path.
     */
    public static byte[] writeV2(int flags, List<ZdRow> rows) {
        return writeVersioned(ZdHeader.VERSION_V2, flags & ZdHeader.FLAGS_MASK_V2, rows);
    }

    private static byte[] writeVersioned(int version, int flags, List<ZdRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ZdHeader.write(version, flags), 0, 10);
        for (ZdRow r : rows) {
            writeRow(out, r);
        }
        return out.toByteArray();
    }

    /**
     * 写纯行体（六字段记录串，<b>无</b>10 字节头）——多段文档（{@link ZdSegments}）中段
     * 类型 0（数据体）的规范载荷形态。
     * Writes the bare row body (six-field records, <b>no</b> 10-byte header) — the
     * canonical payload form of the type-0 (data) segment in a multi-segment document
     * ({@link ZdSegments}).
     */
    public static byte[] writeBody(List<ZdRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (ZdRow r : rows) {
            writeRow(out, r);
        }
        return out.toByteArray();
    }

    /**
     * 写带索引 footer 的 zd v3 字节流：头（bit5 置位）+ 数据体行 + 索引段（单条段类型 0
     * 条目，指向数据体）+ 25 字节尾 footer；crc32 覆盖索引段。数据体从偏移 10 起、索引段
     * 紧随其后——布局确定性，同输入同字节。
     * Writes a v3 zd byte stream with the index footer: header (bit5 set) + body rows +
     * the index segment (a single type-0 entry pointing at the body) + the 25-byte tail
     * footer; crc32 covers the index segment. The body starts at offset 10 and the index
     * segment follows directly — deterministic layout, same input → same bytes.
     */
    public static byte[] writeIndexed(int flags, List<ZdRow> rows) {
        int v3Flags = (flags & ZdHeader.FLAGS_MASK_V3) | ZdHeader.FLAG_INDEX;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ZdHeader.write(ZdHeader.VERSION_V3, v3Flags), 0, 10);
        for (ZdRow r : rows) {
            writeRow(out, r);
        }
        long bodyOff = 10;
        long bodyLen = out.size() - 10;
        byte[] index = ZdFooter.encodeIndex(List.of(new ZdFooter.Segment(
                ZdFooter.SEG_DATA, bodyOff, bodyLen, "")));
        // The index segment's in-file offset is the stream size BEFORE appending it
        // (header + body); the footer then follows the index directly.
        long indexOff = out.size();
        out.write(index, 0, index.length);
        byte[] footer = ZdFooter.write(indexOff, index);
        out.write(footer, 0, footer.length);
        return out.toByteArray();
    }

    /**
     * 以 DFS 先序把 {@code root} 树平铺为行再按 v3 写出。Flattens the {@code root} tree
     * in DFS preorder into rows and writes them as v3.
     */
    public static byte[] writeTree(int flags, ZdNode root) {
        return write(flags, ZdTrees.flatten(root));
    }

    /**
     * 以 DFS 先序把 {@code root} 树平铺为行再按 v2 写出（v2 兼容路径）。
     * Flattens the {@code root} tree in DFS preorder into rows and writes them as v2
     * (compatibility path).
     */
    public static byte[] writeTreeV2(int flags, ZdNode root) {
        return writeV2(flags, ZdTrees.flatten(root));
    }

    /**
     * 以 DFS 先序把 {@code root} 树平铺为行再按带索引 v3 写出。
     * Flattens the {@code root} tree in DFS preorder into rows and writes them as an
     * index-footered v3 document.
     */
    public static byte[] writeTreeIndexed(int flags, ZdNode root) {
        return writeIndexed(flags, ZdTrees.flatten(root));
    }

    /**
     * 写一条记录的固定六字段：tag 10 (kind)、18 (key)、26 (value_i64)、34 (value_f64)、
     * 42 (value_str)、50 (child_count)。Writes one node as the fixed six wire2 fields:
     * tags 10 (kind), 18 (key), 26 (value_i64), 34 (value_f64), 42 (value_str),
     * 50 (child_count).
     */
    static void writeRow(ByteArrayOutputStream out, ZdRow r) {
        writeField(out, 10, ZdPrimitives.encI64(r.kind()));
        writeField(out, 18, ZdPrimitives.utf8(r.key()));
        writeField(out, 26, ZdPrimitives.encI64(r.valueI64()));
        writeField(out, 34, ZdPrimitives.be64(Double.doubleToLongBits(r.valueF64())));
        writeField(out, 42, ZdPrimitives.utf8(r.valueStr()));
        writeField(out, 50, ZdPrimitives.varint(r.childCount()));
    }

    private static void writeField(ByteArrayOutputStream out, int tag, byte[] payload) {
        ZdPrimitives.writeVarint(out, tag);
        ZdPrimitives.writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
    }
}
