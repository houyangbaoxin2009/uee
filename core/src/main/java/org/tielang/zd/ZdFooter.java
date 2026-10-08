package org.tielang.zd;



import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * zd v3 索引 footer（p.2.3.7，对齐 {@code 2026-09-28-zd-v3-design.md} §3）：把 v2 时代
 * 「容器内可选拼装、无标准位置」的随机访问标准化为<b>文件尾 footer</b>。
 * <pre>
 * footer = [magic "ZD3FT" 5B][index_off u64 BE][index_len u64 BE][crc32 u32 BE]   共 25 字节
 * </pre>
 * 读端从文件尾读 25 字节，魔数匹配即存在索引；{@code index_off/index_len} 指向文件体内的
 * <b>索引段</b>（段表）；crc32（IEEE，与流式帧同多项式）覆盖索引段字节。
 * 索引段 = 段表：{@code 段数 varint} + 各段
 * {@code [段类型 varint][偏移 u64 BE][长度 u64 BE][名称 string]}。
 * 段类型初始集合：0 数据体、1 字符串池、2 列式容器、3 schema、4 图容器、5 压缩载荷、
 * 6 自定义（名称必须非空）。
 * <p>
 * <b>名称编码钉点（规范澄清，spec clarification）：</b>设计文档的「名称 string（可选，空串
 * 省略）」钉为 <b>varint 长度前缀 UTF-8</b>——长度 0 即省略（零字节），长度 &gt;0 后接 UTF-8
 * 字节。条目边界因此自描述；段类型 6 的名称长度必须 &gt; 0。此钉点需随 KAT 向量集同步
 * tie-spec 仓。
 * <p>
 * <b>语义：</b>footer 存在时，消费方<b>必须</b>经索引定位段，不得假设段序；无 footer 的
 * v3 文件 = 纯流式/单段形态，与 v2 读取规则一致。头 flags bit5（{@link ZdHeader#FLAG_INDEX}）
 * 与 footer 存在性必须一致（写方保证，读方确定性拒绝不一致）。
 * <p>
 * zd v3 index footer (p.2.3.7, per {@code 2026-09-28-zd-v3-design.md} §3): random access
 * is standardised as a <b>tail footer</b> — 25 bytes
 * {@code [magic "ZD3FT"][index_off u64 BE][index_len u64 BE][crc32 u32 BE]}, where the
 * offsets point at the <b>index segment</b> (the segment table) inside the file body and
 * the IEEE crc32 covers the index-segment bytes. The segment table =
 * {@code count varint} + per segment
 * {@code [type varint][offset u64 BE][length u64 BE][name string]}; segment types
 * 0 data / 1 string pool / 2 columnar / 3 schema / 4 graph / 5 compressed /
 * 6 custom (name required non-empty). <b>Name encoding pin (spec clarification):</b>
 * names are varint-length-prefixed UTF-8, length 0 = omitted; type-6 names must be
 * non-empty — to be synced to the tie-spec KAT set. When a footer is present, consumers
 * <b>must</b> locate segments via the index; header bit5 and footer presence must agree
 * (writer guarantees, reader deterministically rejects disagreement).
 */
public final class ZdFooter {

    /** The 5-byte ASCII footer magic {@code ZD3FT}. / footer 魔数 {@code ZD3FT}。 */
    public static final String MAGIC = "ZD3FT";
    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);

    /** Footer 固定长度（25 字节）。 / Fixed footer length (25 bytes). */
    public static final int FOOTER_LEN = 25;

    // ---- 段类型 / segment types ----
    /** 段类型 0：数据体。 / Segment type 0: the data body. */
    public static final int SEG_DATA = 0;
    /** 段类型 1：字符串池。 / Segment type 1: string pool. */
    public static final int SEG_STRING_POOL = 1;
    /** 段类型 2：列式容器。 / Segment type 2: columnar container. */
    public static final int SEG_COLUMNAR = 2;
    /** 段类型 3：schema。 / Segment type 3: schema. */
    public static final int SEG_SCHEMA = 3;
    /** 段类型 4：图容器。 / Segment type 4: graph container. */
    public static final int SEG_GRAPH = 4;
    /** 段类型 5：压缩载荷。 / Segment type 5: compressed payload. */
    public static final int SEG_COMPRESSED = 5;
    /** 段类型 6：自定义（名称必须非空）。 / Segment type 6: custom (name required). */
    public static final int SEG_CUSTOM = 6;

    /** 一条段表目：类型 + 文件内偏移 + 长度 + 可选名称（空串 = 省略）。 /
     *  One segment-table entry: type + in-file offset + length + optional name (empty = omitted). */
    public record Segment(int type, long offset, long length, String name) {
        /** 紧凑构造：null 名称归一为空串。 / Compact ctor: null name normalised to empty. */
        public Segment {
            name = name == null ? "" : name;
        }
    }

    private final long indexOff;
    private final long indexLen;
    private final int crc;
    private final List<Segment> segments;

    private ZdFooter(long indexOff, long indexLen, int crc, List<Segment> segments) {
        this.indexOff = indexOff;
        this.indexLen = indexLen;
        this.crc = crc;
        this.segments = segments;
    }

    /** 索引段在文件内的偏移。 / The index segment's in-file offset. */
    public long indexOff() {
        return indexOff;
    }

    /** 索引段长度。 / The index segment length. */
    public long indexLen() {
        return indexLen;
    }

    /** 索引段 crc32（IEEE）。 / The index segment crc32 (IEEE). */
    public int crc() {
        return crc;
    }

    /** 段表（固定序，只读视图）。 / The segment table (fixed order, read-only view). */
    public List<Segment> segments() {
        return segments;
    }

    /** 按类型查第一个段；无则返回 null（读永不抛）。 /
     *  First segment of the given type, or null (never throws). */
    public Segment segment(int type) {
        for (Segment s : segments) {
            if (s.type() == type) {
                return s;
            }
        }
        return null;
    }

    /**
     * 由索引段字节 + 其位置写出 25 字节 footer。crc 由本方法对 {@code indexBytes} 现算
     * （不信任调用方）。Writes the 25-byte footer for the given index-segment bytes at
     * {@code indexOff}; the crc is computed here over {@code indexBytes} (never trusted
     * from the caller).
     */
    public static byte[] write(long indexOff, byte[] indexBytes) {
        byte[] out = new byte[FOOTER_LEN];
        System.arraycopy(MAGIC_BYTES, 0, out, 0, MAGIC_BYTES.length);
        for (int i = 0; i < 8; i++) {
            out[5 + i] = (byte) (indexOff >> ((7 - i) * 8));
        }
        long len = indexBytes.length;
        for (int i = 0; i < 8; i++) {
            out[13 + i] = (byte) (len >> ((7 - i) * 8));
        }
        int crc = Crc32Ieee.of(indexBytes);
        out[21] = (byte) (crc >>> 24);
        out[22] = (byte) (crc >>> 16);
        out[23] = (byte) (crc >>> 8);
        out[24] = (byte) crc;
        return out;
    }

    /**
     * 编码段表为索引段字节。名称 = varint 长度前缀 UTF-8（0 = 省略）；段类型 6 名称必须
     * 非空；偏移 / 长度必须非负。Encodes the segment table into index-segment bytes.
     * Names are varint-length-prefixed UTF-8 (0 = omitted); type-6 names must be
     * non-empty; offsets / lengths must be non-negative.
     */
    public static byte[] encodeIndex(List<Segment> segments) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ZdPrimitives.writeVarint(out, segments.size());
        for (Segment s : segments) {
            if (s.offset() < 0 || s.length() < 0) {
                throw new IllegalArgumentException("zd segment offset/length must be non-negative: " + s);
            }
            if (s.type() == SEG_CUSTOM && s.name().isEmpty()) {
                throw new IllegalArgumentException("zd custom segment requires a non-empty name");
            }
            ZdPrimitives.writeVarint(out, s.type());
            ZdPrimitives.writeBe64(out, s.offset());
            ZdPrimitives.writeBe64(out, s.length());
            byte[] name = ZdPrimitives.utf8(s.name());
            ZdPrimitives.writeVarint(out, name.length);
            out.write(name, 0, name.length);
        }
        return out.toByteArray();
    }

    /**
     * 文件尾探测：最后 25 字节魔数匹配即返回解析好的 footer（含段表与 crc 校验），否则返回
     * null（无 footer）。魔数匹配但任何校验失败（长度越界 / crc 不符 / 段表畸形 / 名称违约）
     * 抛 {@link IllegalArgumentException}——坏 footer 永不静默当作无 footer。
     * Tail probe: if the last 25 bytes carry the magic, returns the parsed footer
     * (segment table + crc verified); otherwise returns null (no footer). Magic match
     * with any failed check (bounds / crc / malformed table / name violation) raises
     * {@link IllegalArgumentException} — a bad footer is never silently treated as absent.
     */
    public static ZdFooter probe(byte[] data) {
        if (data == null || data.length < FOOTER_LEN) {
            return null;
        }
        int tail = data.length - FOOTER_LEN;
        for (int i = 0; i < MAGIC_BYTES.length; i++) {
            if (data[tail + i] != MAGIC_BYTES[i]) {
                return null;
            }
        }
        long off = ZdPrimitives.readBe64(data, tail + 5);
        long len = ZdPrimitives.readBe64(data, tail + 13);
        int crc = ((data[tail + 21] & 0xFF) << 24) | ((data[tail + 22] & 0xFF) << 16)
                | ((data[tail + 23] & 0xFF) << 8) | (data[tail + 24] & 0xFF);
        if (off < 0 || len < 0 || off > data.length || len > data.length - off) {
            throw new IllegalArgumentException("zd footer index segment out of bounds (off " + off + ", len " + len + ")");
        }
        if (Crc32Ieee.of(data, (int) off, (int) len) != crc) {
            throw new IllegalArgumentException("zd footer crc mismatch over the index segment");
        }
        List<Segment> segments = decodeIndex(data, (int) off, (int) len);
        return new ZdFooter(off, len, crc, segments);
    }

    /**
     * 从 {@code data[off, off+len)} 解码段表。计数 / 边界 / 名称 / 段类型 6 违约均确定性
     * IAE。Decodes the segment table from {@code data[off, off+len)}; count / bounds /
     * name / type-6 violations are deterministic IAEs.
     */
    public static List<Segment> decodeIndex(byte[] data, int off, int len) {
        if (off < 0 || len < 0 || off > data.length || len > data.length - off) {
            throw new IllegalArgumentException("zd index segment out of bounds");
        }
        int end = off + len;
        int[] pos = {off};
        long count = ZdPrimitives.readVarint(data, pos);
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd segment count out of range: " + count);
        }
        List<Segment> out = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            int type = (int) ZdPrimitives.readVarint(data, pos);
            long segOff = ZdPrimitives.readBe64(data, pos[0]);
            pos[0] += 8;
            long segLen = ZdPrimitives.readBe64(data, pos[0]);
            pos[0] += 8;
            long nameLen = ZdPrimitives.readVarint(data, pos);
            if (nameLen < 0 || nameLen > end - pos[0]) {
                throw new IllegalArgumentException("zd segment name overruns the index segment");
            }
            String name = new String(data, pos[0], (int) nameLen, StandardCharsets.UTF_8);
            pos[0] += (int) nameLen;
            if (type == SEG_CUSTOM && name.isEmpty()) {
                throw new IllegalArgumentException("zd custom segment requires a non-empty name");
            }
            if (segOff < 0 || segLen < 0 || segOff > data.length || segLen > data.length - segOff) {
                throw new IllegalArgumentException("zd segment offset/length out of bounds (type " + type + ")");
            }
            out.add(new Segment(type, segOff, segLen, name));
        }
        if (pos[0] != end) {
            throw new IllegalArgumentException("zd index segment has trailing bytes ("
                    + (end - pos[0]) + " unparsed)");
        }
        return out;
    }
}
