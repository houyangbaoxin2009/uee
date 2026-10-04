package org.tielang.zd;

import java.nio.charset.StandardCharsets;

/**
 * zd 头载体。10 字节 = {@code "TIEDBZD"}(7) + 版本数字字节（base-48，bit 高位在前：
 * v2 = {@code 0x00 0x02}、v3 = {@code 0x00 0x03}）+ 1 字节 flags。语言无关规范与 tie-main
 * {@code compiler/tdzd.tie} 一致；v3 增量演进对齐 {@code 2026-09-28-zd-v3-design.md}
 * 与语言规范 §17.2。
 * <p>
 * zd carrier header. 10 bytes = {@code "TIEDBZD"}(7) + version digit bytes (base-48,
 * high digit first: v2 = {@code 0x00 0x02}, v3 = {@code 0x00 0x03}) + a 1-byte flags
 * field. The language-agnostic spec matches tie-main {@code compiler/tdzd.tie}; the v3
 * incremental evolution follows {@code 2026-09-28-zd-v3-design.md} and language-spec
 * §17.2.
 * <p>
 * <b>v3 纪律：</b>写方默认写 v3（{@code "03"}）；v2 字节布局全兼容——v3 读取义务 = <b>v2 + v3</b>
 * （{@link #isZd} 两者都接受；写方自 v3 起<b>禁写 v1</b>，v1 头拒绝不变）。flags 在 v2 五位
 * （bit0 字典、bit1 列式、bit2 ext、bit3 流式、bit4 压缩变体）之上新增 bit5 索引 footer
 * （{@link ZdFooter}）、bit6 图容器（纯提示位）；v3 写侧 flags 掩码为低 7 位，v2 写侧低 5 位。
 * <p>
 * <b>v3 discipline:</b> writers default to v3 ({@code "03"}); the v2 byte layout is fully
 * compatible — the v3 reading obligation is <b>v2 + v3</b> ({@link #isZd} accepts both;
 * writers must not write v1 from v3 on, so the v1-header rejection stands). The flags
 * field gains bit5 (index footer) and bit6 (graph container, a pure hint bit) on top of
 * the v2 five bits; the v3 write-side mask is the low 7 bits, the v2 path the low 5 bits.
 */
public final class ZdHeader {

    /** The leading 7-byte ASCII magic {@code TIEDBZD}. */
    public static final String MAGIC = "TIEDBZD";
    private static final byte[] MAGIC_BYTES = MAGIC.getBytes(StandardCharsets.US_ASCII);

    // ---- 版本 / versions ----
    /** zd v2 = base-48 数值 2（字节 {@code 0x00 0x02}）。 / zd v2 = base-48 value 2. */
    public static final int VERSION_V2 = 2;
    /** zd v3 = base-48 数值 3（字节 {@code 0x00 0x03}）。 / zd v3 = base-48 value 3. */
    public static final int VERSION_V3 = 3;

    // ---- flags bit 定义 / flag bits ----
    /** bit0 = dictionary (字典变体字典表). */
    public static final int FLAG_DICTIONARY = 1 << 0;
    /** bit1 = columnar (列式变体). */
    public static final int FLAG_COLUMNAR = 1 << 1;
    /** bit2 = ext (扩展标记). */
    public static final int FLAG_EXT = 1 << 2;
    /** bit3 = streaming (流式). */
    public static final int FLAG_STREAMING = 1 << 3;
    /** bit4 = compressed (压缩变体). */
    public static final int FLAG_COMPRESSED = 1 << 4;
    /** bit5 = index footer (v3：文件尾索引 footer，见 {@link ZdFooter}). */
    public static final int FLAG_INDEX = 1 << 5;
    /** bit6 = graph container (v3：图容器段提示位). */
    public static final int FLAG_GRAPH = 1 << 6;

    /** v2 写侧 flags 掩码（低 5 位）。 / v2 write-side flags mask (low 5 bits). */
    public static final int FLAGS_MASK_V2 = 0x1F;
    /** v3 写侧 flags 掩码（低 7 位）。 / v3 write-side flags mask (low 7 bits). */
    public static final int FLAGS_MASK_V3 = 0x7F;

    private static final int OFFSET_FLAGS = 9;

    private ZdHeader() {
    }

    /**
     * 以给定的 flags 组合写出 10 字节 zd v2 头（字节恒为 {@code 0x00 0x02}，掩码低 5 位）。
     * v2 兼容写路径：供需要 v2 字节的消费方与探针的 v2 可读性验证使用。
     * Writes the 10-byte zd v2 header for the given flags combination (bytes always
     * {@code 0x00 0x02}, low-5-bit mask). The v2 compatibility write path: for consumers
     * that need v2 bytes and for the probe's v2-readability verification.
     */
    public static byte[] writeV2(boolean dict, boolean columnar, boolean ext,
                                 boolean streaming, boolean compressed) {
        int flags = (dict ? FLAG_DICTIONARY : 0)
                | (columnar ? FLAG_COLUMNAR : 0)
                | (ext ? FLAG_EXT : 0)
                | (streaming ? FLAG_STREAMING : 0)
                | (compressed ? FLAG_COMPRESSED : 0);
        return writeV2(flags);
    }

    /**
     * 以紧凑 flags 整数（低 5 位）写出 zd v2 头。Writes the zd v2 header from a compact
     * flags int (low 5 bits).
     */
    public static byte[] writeV2(int flags) {
        return write(VERSION_V2, flags & FLAGS_MASK_V2);
    }

    /**
     * 以给定的 flags 组合写出 10 字节 zd v3 头（字节 {@code 0x00 0x03}，掩码低 7 位）。
     * 写方默认版本。Writes the 10-byte zd v3 header for the given flags combination
     * (bytes {@code 0x00 0x03}, low-7-bit mask). The default writer version.
     */
    public static byte[] writeZ(boolean dict, boolean columnar, boolean ext,
                                boolean streaming, boolean compressed,
                                boolean indexFooter, boolean graphContainer) {
        int flags = (dict ? FLAG_DICTIONARY : 0)
                | (columnar ? FLAG_COLUMNAR : 0)
                | (ext ? FLAG_EXT : 0)
                | (streaming ? FLAG_STREAMING : 0)
                | (compressed ? FLAG_COMPRESSED : 0)
                | (indexFooter ? FLAG_INDEX : 0)
                | (graphContainer ? FLAG_GRAPH : 0);
        return writeZ(flags);
    }

    /**
     * 以紧凑 flags 整数（低 7 位）写出 zd v3 头——本类的默认写路径。
     * Writes the zd v3 header from a compact flags int (low 7 bits) — the default
     * write path of this class.
     */
    public static byte[] writeZ(int flags) {
        return write(VERSION_V3, flags & FLAGS_MASK_V3);
    }

    /**
     * 按版本号写 10 字节头（{@link ZdDocWriter} 的 v2/v3 写路径共用；非法版本确定性拒绝——
     * 写方自 v3 起禁写 v1）。
     * Writes the 10-byte header for a version (shared by the {@link ZdDocWriter} v2/v3
     * paths; illegal versions are deterministically rejected — writers must not write v1
     * from v3 on).
     */
    public static byte[] write(int version, int flags) {
        if (version != VERSION_V2 && version != VERSION_V3) {
            throw new IllegalArgumentException("zd writers must not write version " + version + " (reading obligation = v2 + v3)");
        }
        byte[] head = new byte[10];
        System.arraycopy(MAGIC_BYTES, 0, head, 0, MAGIC_BYTES.length);
        head[7] = 0x00; // base-48 digit d1
        head[8] = (byte) version; // base-48 digit d2
        head[OFFSET_FLAGS] = (byte) flags;
        return head;
    }

    /**
     * 校验 {@code head} 是合法的 zd v2 <b>或</b> v3 头（魔数 + 版本 ∈ {2, 3}；v3 读取义务）。
     * 非 zd / 长度不足 / null / 其他版本返回 false，不抛异常。
     * Returns whether {@code head} is a valid zd v2 <b>or</b> v3 header prefix
     * (magic + version ∈ {2, 3}; the v3 reading obligation). Mismatches / short
     * buffers / null / other versions return false, never throw.
     */
    public static boolean isZd(byte[] head) {
        return isZd(head, 0);
    }

    /**
     * 从偏移 {@code off} 起校验 zd v2/v3 头。Same as {@link #isZd(byte[])} but starting
     * at the given offset.
     */
    public static boolean isZd(byte[] head, int off) {
        if (head == null || head.length < off + 10) {
            return false;
        }
        for (int i = 0; i < MAGIC_BYTES.length; i++) {
            if (head[off + i] != MAGIC_BYTES[i]) {
                return false;
            }
        }
        int d2 = head[off + 8] & 0xFF;
        return (head[off + 7] & 0xFF) == 0x00 && (d2 == VERSION_V2 || d2 == VERSION_V3);
    }

    /**
     * 解析头部版本号（base-48：d1*48 + d2；v2 → 2、v3 → 3）。非法魔数 / 版本 ∉ {2,3} /
     * 长度不足抛 {@link IllegalArgumentException}。Parses the version from the header
     * (base-48: d1*48 + d2; v2 → 2, v3 → 3). Invalid magic / version ∉ {2,3} / short
     * buffer raise {@link IllegalArgumentException}.
     */
    public static int parseVersion(byte[] head, int off) {
        requireValid(head, off);
        return ((head[off + 7] & 0xFF) * 48) + (head[off + 8] & 0xFF);
    }

    /**
     * 读取头部 flags 字节（原样 8 位暴露；bit5/bit6 仅 v3 语义）。非法魔数 / 长度不足抛
     * {@link IllegalArgumentException}。Reads the flags byte (all 8 bits as stored;
     * bit5/bit6 are v3-only semantics). Invalid magic / short buffer raise
     * {@link IllegalArgumentException}.
     */
    public static int flags(byte[] head, int off) {
        requireValid(head, off);
        return head[off + OFFSET_FLAGS] & 0xFF;
    }

    private static void requireValid(byte[] head, int off) {
        if (head == null || head.length < off + 10) {
            throw new IllegalArgumentException("zd header too short: " + (head == null ? "null" : head.length));
        }
        for (int i = 0; i < MAGIC_BYTES.length; i++) {
            if (head[off + i] != MAGIC_BYTES[i]) {
                throw new IllegalArgumentException("not a zd payload (bad magic)");
            }
        }
        if ((head[off + 7] & 0xFF) != 0x00) {
            throw new IllegalArgumentException("unsupported zd version bytes "
                    + (head[off + 7] & 0xFF) + " " + (head[off + 8] & 0xFF));
        }
        int d2 = head[off + 8] & 0xFF;
        if (d2 != VERSION_V2 && d2 != VERSION_V3) {
            throw new IllegalArgumentException("unsupported zd version bytes 0 " + d2);
        }
    }
}
