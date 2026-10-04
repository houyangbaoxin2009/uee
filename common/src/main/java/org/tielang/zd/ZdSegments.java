package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * zd v3 多段文档（段表 + 索引 footer 的组装与读取）。footer 存在时消费方<b>必须</b>经索引
 * 定位段、不得假设段序（§3 语义）。组装布局确定性：头（bit5 恒置；bit0/bit1/bit4/bit6
 * 随段类型自动置位；其余位由调用方 flags 传入，如 bit2 ext、bit3 流式）+ 各段载荷按给定
 * 序拼接 + 索引段 + 25 字节尾 footer（crc32 覆盖索引段）。
 * <p>
 * 压缩载荷段（段类型 5）的载荷形态（zd-java 钉定，随 KAT 同步 tie-spec）：
 * {@code varint 变体 + 压缩字节}；变体 0 = store（原样直写），1 = zstd、2 = lz4（本库不内嵌
 * 压缩算法——写方预压缩、读方经 {@link Decompressor} 解压，未知变体确定性拒绝）。
 * <p>
 * The zd v3 multi-segment document (assembling and reading via the segment table + the
 * index footer). When a footer is present, consumers <b>must</b> locate segments via the
 * index and must not assume the segment order (§3 semantics). The assembled layout is
 * deterministic: the header (bit5 always set; bit0/bit1/bit4/bit6 auto-set by segment
 * kinds; the remaining bits — e.g. bit2 ext, bit3 streaming — come from the caller's
 * flags) + the segment payloads concatenated in the given order + the index segment +
 * the 25-byte tail footer (crc32 over the index segment).
 * <p>
 * The compressed-payload segment (type 5) content form (pinned by zd-java, to be synced
 * to the tie-spec KAT set): {@code varint variant + compressed bytes}; variant
 * 0 = store (written as-is), 1 = zstd, 2 = lz4 (no compression algorithms embedded —
 * writers pre-compress, readers decompress via {@link Decompressor}, unknown variants
 * are deterministically rejected).
 */
public final class ZdSegments {

    private ZdSegments() {
    }

    /** 一段内存态载荷：段类型 + 可选名称（段类型 6 必须非空）+ 载荷字节。 /
     *  One in-memory segment: type + optional name (required for type 6) + payload bytes. */
    public record Slice(int type, String name, byte[] payload) {
        /** 紧凑构造：null 名称归一为空串。 / Compact ctor: null name normalised to empty. */
        public Slice {
            name = name == null ? "" : name;
        }
    }

    /**
     * 组装多段 v3 文档（头 + 段载荷 + 索引 + footer）。段列表可空（空文档 = 纯索引形态）；
     * 自定义段（类型 6）名称必须非空；同 (类型, 名称) 重复确定性 IAE。
     * Assembles the multi-segment v3 document (header + payloads + index + footer). The
     * segment list may be empty (an empty document = the pure-index shape); custom
     * segments (type 6) need non-empty names; duplicate (type, name) pairs are
     * deterministic IAEs.
     */
    public static byte[] assemble(int flags, List<Slice> segments) {
        int headerFlags = ZdHeader.FLAG_INDEX;
        for (Slice s : segments) {
            switch (s.type()) {
                case ZdFooter.SEG_STRING_POOL -> headerFlags |= ZdHeader.FLAG_DICTIONARY;
                case ZdFooter.SEG_COLUMNAR -> headerFlags |= ZdHeader.FLAG_COLUMNAR;
                case ZdFooter.SEG_GRAPH -> headerFlags |= ZdHeader.FLAG_GRAPH;
                case ZdFooter.SEG_COMPRESSED -> headerFlags |= ZdHeader.FLAG_COMPRESSED;
                case ZdFooter.SEG_DATA, ZdFooter.SEG_SCHEMA, ZdFooter.SEG_CUSTOM -> {
                }
                default -> throw new IllegalArgumentException("unknown zd segment type: " + s.type());
            }
            if (s.type() == ZdFooter.SEG_CUSTOM && s.name().isEmpty()) {
                throw new IllegalArgumentException("zd custom segment requires a non-empty name");
            }
        }
        headerFlags |= (flags & ZdHeader.FLAGS_MASK_V3 & ~ZdHeader.FLAG_INDEX);
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                Slice a = segments.get(i);
                Slice b = segments.get(j);
                if (a.type() == b.type() && a.name().equals(b.name())) {
                    throw new IllegalArgumentException("zd duplicate segment (type " + a.type() + ", name '" + a.name() + "')");
                }
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ZdHeader.writeZ(headerFlags), 0, 10);
        List<ZdFooter.Segment> table = new ArrayList<>(segments.size());
        for (Slice s : segments) {
            table.add(new ZdFooter.Segment(s.type(), out.size(), s.payload().length, s.name()));
            out.write(s.payload(), 0, s.payload().length);
        }
        byte[] index = ZdFooter.encodeIndex(table);
        long indexOff = out.size();
        out.write(index, 0, index.length);
        byte[] footer = ZdFooter.write(indexOff, index);
        out.write(footer, 0, footer.length);
        return out.toByteArray();
    }

    /**
     * 读取多段文档：经 footer 段表定位并切出各段载荷（副本）。无 footer / 非 v3 / bit5
     * 不一致确定性 IAE（复用 {@link ZdVolume#footer} 的一致性门）。
     * Reads the multi-segment document: locates and slices each segment payload (copies)
     * via the footer segment table. No footer / non-v3 / bit5 disagreement are
     * deterministic IAEs (reusing the {@link ZdVolume#footer} consistency gate).
     */
    public static List<Slice> read(byte[] data) {
        ZdFooter footer = ZdVolume.footer(data); // null → IAE（多段文档必须有 footer）
        if (footer == null) {
            throw new IllegalArgumentException("zd multi-segment document requires an index footer");
        }
        List<Slice> out = new ArrayList<>(footer.segments().size());
        for (ZdFooter.Segment s : footer.segments()) {
            byte[] payload = new byte[(int) s.length()];
            System.arraycopy(data, (int) s.offset(), payload, 0, (int) s.length());
            out.add(new Slice(s.type(), s.name(), payload));
        }
        return out;
    }

    /** 按类型取第一段载荷（副本）；无则 null。 / The first segment payload of the type (a copy), or null. */
    public static byte[] payload(List<Slice> slices, int type) {
        for (Slice s : slices) {
            if (s.type() == type) {
                return s.payload();
            }
        }
        return null;
    }

    /** 按类型 + 名称取段载荷（副本）；无则 null。 / The segment payload by type + name (a copy), or null. */
    public static byte[] payload(List<Slice> slices, int type, String name) {
        for (Slice s : slices) {
            if (s.type() == type && s.name().equals(name)) {
                return s.payload();
            }
        }
        return null;
    }

    // ==================== 压缩载荷段（段类型 5） / compressed segment (type 5) ====================

    /** 压缩变体 0：store（原样直写）。 / Compression variant 0: store (written as-is). */
    public static final int VARIANT_STORE = 0;
    /** 压缩变体 1：zstd（本库不内嵌，写方预压缩）。 / Compression variant 1: zstd (not embedded; writers pre-compress). */
    public static final int VARIANT_ZSTD = 1;
    /** 压缩变体 2：lz4（本库不内嵌，写方预压缩）。 / Compression variant 2: lz4 (not embedded; writers pre-compress). */
    public static final int VARIANT_LZ4 = 2;

    /**
     * 编码压缩载荷段内容：{@code varint 变体 + 数据}（调用方负责预压缩）。
     * Encodes the compressed-segment content: {@code varint variant + data} (the caller
     * pre-compresses).
     */
    public static byte[] encodeCompressed(int variant, byte[] data) {
        if (variant < VARIANT_STORE || variant > VARIANT_LZ4) {
            throw new IllegalArgumentException("unknown zd compression variant: " + variant);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 5);
        ZdPrimitives.writeVarint(out, variant);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /** 压缩载荷信封：变体 + 数据。 / The compressed envelope: variant + data. */
    public record CompressedEnvelope(int variant, byte[] data) {
    }

    /** 解码压缩载荷段内容。畸形变体 / 截断确定性 IAE。Decodes the compressed-segment content. */
    public static CompressedEnvelope decodeCompressed(byte[] b, int off, int len) {
        if (off < 0 || len < 1 || off > b.length || len > b.length - off) {
            throw new IllegalArgumentException("zd compressed segment out of bounds");
        }
        int[] pos = {off};
        long variant = ZdPrimitives.readVarint(b, pos);
        if (variant < VARIANT_STORE || variant > VARIANT_LZ4) {
            throw new IllegalArgumentException("unknown zd compression variant: " + variant);
        }
        byte[] data = new byte[off + len - pos[0]];
        System.arraycopy(b, pos[0], data, 0, data.length);
        return new CompressedEnvelope((int) variant, data);
    }

    /** 读端解压器接口（本库不内嵌压缩算法——变体 0 store 之外的解压由上层承担）。 /
     *  The reader-side decompressor (no compression algorithms embedded — decompression
     *  beyond the variant-0 store rides on the upper layer). */
    public interface Decompressor {
        /** 解压。 / Decompresses. */
        byte[] decompress(int variant, byte[] data);
    }

    /** 缺省解压器：仅支持 store 变体，其余确定性拒绝。 / The default decompressor: the store variant only; others are deterministically rejected. */
    public static final Decompressor STORE_ONLY = (variant, data) -> {
        if (variant != VARIANT_STORE) {
            throw new IllegalArgumentException("no decompressor for zd compression variant: " + variant);
        }
        return data.clone();
    };
}
