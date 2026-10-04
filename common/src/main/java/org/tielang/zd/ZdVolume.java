package org.tielang.zd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * zd 卷读取器：把 zd 字节流解析回 {@link ZdRow} 列表或经 {@link ZdTrees#unflatten} 逆推回
 * 一棵 {@link ZdNode} 树。<b>v3 读取义务 = v2 + v3</b>：v2 文件（头 "02"，正文从偏移 10 到
 * 文件尾）与无 footer 的 v3 文件同规则读取；带索引 footer 的 v3 文件（尾 25 字节魔数
 * {@code ZD3FT}，见 {@link ZdFooter}）<b>必须</b>经段表定位段类型 0（数据体）读取，头 bit5
 * 与 footer 存在性不一致确定性拒绝。逐字段迭代，对未知 tag 跳过；字段可缺失（缺失给默认
 * 0 / ""）。往返恒等：{@code write == read(write)} 逐字节。
 * <p>
 * zd volume reader: parses a zd byte stream back into a list of {@link ZdRow} or
 * reconstructs a {@link ZdNode} tree via {@link ZdTrees#unflatten}. <b>The v3 reading
 * obligation = v2 + v3:</b> v2 files (header "02", body from offset 10 to EOF) and
 * footerless v3 files read under the same rules; a v3 file with the index footer (tail
 * 25-byte magic {@code ZD3FT}, see {@link ZdFooter}) <b>must</b> be read through the
 * segment table's type-0 (data) segment, and a header-bit5/footer-presence disagreement
 * is deterministically rejected. Iterates fields, skipping unknown tags; missing fields
 * fall back to their defaults (0 / ""). Round-trips: {@code write == read(write)}
 * byte-for-byte.
 */
public final class ZdVolume {

    private static final int TAG_KIND = 10;
    private static final int TAG_KEY = 18;
    private static final int TAG_I64 = 26;
    private static final int TAG_F64 = 34;
    private static final int TAG_STR = 42;
    private static final int TAG_CHILD = 50;

    private ZdVolume() {
    }

    /**
     * 解析 zd 载荷为 {@link ZdRow} 列表（v2 + v3 读义务）。非 zd v2/v3 头抛
     * {@link IllegalArgumentException}；带 footer 时经段表定位数据体（段类型 0），头 bit5
     * 与 footer 存在性不一致确定性拒绝；字段缺失给默认 0 / ""；未知字段跳过。
     * Parses a zd payload into a list of {@link ZdRow} (v2 + v3 reading obligation).
     * A non-zd-v2/v3 header raises {@link IllegalArgumentException}; a footered payload
     * is read through the segment table's type-0 data segment, and a header-bit5 /
     * footer-presence disagreement is deterministically rejected; missing fields
     * default to 0 / ""; unknown fields are skipped.
     */
    public static List<ZdRow> readRows(byte[] data) {
        if (!ZdHeader.isZd(data)) {
            throw new IllegalArgumentException("not a zd v2/v3 payload");
        }
        int version = ZdHeader.parseVersion(data, 0);
        int headerFlags = ZdHeader.flags(data, 0);
        ZdFooter footer = ZdFooter.probe(data);
        if (footer != null) {
            if (version != ZdHeader.VERSION_V3) {
                throw new IllegalArgumentException("zd footer on a non-v3 payload (version " + version + ")");
            }
            if ((headerFlags & ZdHeader.FLAG_INDEX) == 0) {
                throw new IllegalArgumentException("zd footer present but header bit5 (index) is clear");
            }
            ZdFooter.Segment body = footer.segment(ZdFooter.SEG_DATA);
            if (body == null) {
                throw new IllegalArgumentException("zd footer index has no data segment (type 0)");
            }
            return readRowsRange(data, (int) body.offset(), (int) body.length());
        }
        if (version == ZdHeader.VERSION_V3 && (headerFlags & ZdHeader.FLAG_INDEX) != 0) {
            throw new IllegalArgumentException("zd v3 header declares bit5 (index) but no footer is present");
        }
        return readRowsRange(data, 10, data.length - 10);
    }

    /**
     * 文件尾探测索引 footer：存在返回解析好的 {@link ZdFooter}（crc 与段表已校验），无
     * footer 返回 null。footer 与头的一致性（v3 + bit5 置位）在此确定性拒绝。
     * Tail probe for the index footer: returns the parsed {@link ZdFooter} when present
     * (crc and segment table verified), null when absent. Footer/header consistency
     * (v3 + bit5 set) is deterministically enforced here.
     */
    public static ZdFooter footer(byte[] data) {
        if (!ZdHeader.isZd(data)) {
            throw new IllegalArgumentException("not a zd v2/v3 payload");
        }
        ZdFooter footer = ZdFooter.probe(data);
        if (footer != null) {
            int version = ZdHeader.parseVersion(data, 0);
            int headerFlags = ZdHeader.flags(data, 0);
            if (version != ZdHeader.VERSION_V3) {
                throw new IllegalArgumentException("zd footer on a non-v3 payload (version " + version + ")");
            }
            if ((headerFlags & ZdHeader.FLAG_INDEX) == 0) {
                throw new IllegalArgumentException("zd footer present but header bit5 (index) is clear");
            }
        }
        return footer;
    }

    /** 在 {@code [off, off+len)} 范围内逐字段解析 wire2 行。 / Parses wire2 rows within {@code [off, off+len)}. */
    static List<ZdRow> readRowsRange(byte[] data, int off, int len) {
        if (off < 0 || len < 0 || off > data.length || len > data.length - off) {
            throw new IllegalArgumentException("zd body range out of bounds (off " + off + ", len " + len + ")");
        }
        int end = off + len;
        int[] pos = {off};
        List<ZdRow> rows = new ArrayList<>();
        int kind = 0;
        String key = "";
        long vi = 0L;
        double vf = 0.0;
        String vs = "";
        long child = 0L;
        boolean started = false;
        while (pos[0] < end) {
            long tag = ZdPrimitives.readVarint(data, pos);
            long flen = ZdPrimitives.readVarint(data, pos);
            if (flen < 0 || pos[0] + flen > end) {
                throw new IllegalArgumentException("zd field overruns buffer (tag " + tag + ", len " + flen + ")");
            }
            switch ((int) tag) {
                case TAG_KIND -> {
                    if (started) {
                        rows.add(new ZdRow(kind, key, vi, vf, vs, child));
                    }
                    started = true;
                    kind = (int) ZdPrimitives.decI64(data, pos[0]);
                    key = "";
                    vi = 0L;
                    vf = 0.0;
                    vs = "";
                    child = 0L;
                }
                case TAG_KEY -> key = new String(data, pos[0], (int) flen, StandardCharsets.UTF_8);
                case TAG_I64 -> vi = ZdPrimitives.decI64(data, pos[0]);
                case TAG_F64 -> vf = Double.longBitsToDouble(ZdPrimitives.readBe64(data, pos[0]));
                case TAG_STR -> vs = new String(data, pos[0], (int) flen, StandardCharsets.UTF_8);
                case TAG_CHILD -> child = ZdPrimitives.readVarintBounded(data, pos[0], (int) flen);
                default -> { /* unknown tag: skip payload */ }
            }
            pos[0] += (int) flen;
        }
        if (started) {
            rows.add(new ZdRow(kind, key, vi, vf, vs, child));
        }
        return rows;
    }

    /**
     * 逆推 zd 载荷为一棵 {@link ZdNode} 树。根必须是表（kind 0）；否则抛
     * {@link IllegalArgumentException}。Reconstructs a {@link ZdNode} tree from a zd
     * payload. The root must be a table (kind 0), otherwise
     * {@link IllegalArgumentException}.
     */
    public static ZdNode readTree(byte[] data) {
        return ZdTrees.unflatten(readRows(data));
    }
}
