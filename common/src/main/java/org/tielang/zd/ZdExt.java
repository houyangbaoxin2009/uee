package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * zd ext 扩展类型（权威形态，对齐 tiedb {@code src/zd_ext.tie}）：
 * {@code 0xd7 + 类型标记 varint + 长度 varint + 载荷}。tie 特有载荷与 v3 标准类型
 * （{@link ZdStandard}）、图容器 ext 形态（标记 0x47，见 {@link ZdGraph}）都走此子类型；
 * 未知 ext 类型可整体跳过。
 * <p>
 * The zd ext extended type (the authoritative form, per tiedb {@code src/zd_ext.tie}):
 * {@code 0xd7 + tag varint + length varint + payload}. Tie-specific payloads, the v3
 * standard types ({@link ZdStandard}) and the graph container's ext form (marker 0x47,
 * see {@link ZdGraph}) all use this subtype; unknown ext types can be skipped whole.
 */
public final class ZdExt {

    /** ext 前缀字节 {@code 0xd7}。 / The ext prefix byte {@code 0xd7}. */
    public static final int PREFIX = 0xD7;
    /** 图容器 ext 类型标记 {@code 0x47}（'G'）。 / The graph-container ext marker {@code 0x47} ('G'). */
    public static final int TAG_GRAPH = 0x47;

    private ZdExt() {
    }

    /**
     * 编码 ext 值：{@code 0xd7 + varint(tag) + varint(len) + payload}；tag 必须非负。
     * Encodes an ext value: {@code 0xd7 + varint(tag) + varint(len) + payload}; the tag
     * must be non-negative.
     */
    public static byte[] encode(int tag, byte[] payload) {
        if (tag < 0) {
            throw new IllegalArgumentException("zd ext tag must be non-negative: " + tag);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 12);
        out.write(PREFIX);
        ZdPrimitives.writeVarint(out, tag);
        ZdPrimitives.writeVarint(out, payload.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /**
     * 解码 {@code b[pos[0]]} 起的 ext 值并推进 {@code pos[0]}，返回 {@code [tag, 载荷副本,
     * 下一位置]} 的记录。非 0xd7 / 边界越界 / 畸形 varint 确定性 IAE——未知 tag 的载荷无需
     * 理解语义，调用方可按 {@code next} 整体跳过。
     * Decodes the ext value at {@code b[pos[0]]}, advancing {@code pos[0]}; returns a
     * record of {@code [tag, payload copy, next]}. Non-0xd7 / bounds overrun / malformed
     * varints are deterministic IAEs — an unknown tag's payload needs no semantic
     * understanding, callers can skip whole via {@code next}.
     */
    public static Decoded decode(byte[] b, int[] pos) {
        int p = pos[0];
        if (p >= b.length || (b[p] & 0xFF) != PREFIX) {
            throw new IllegalArgumentException("not a zd ext value at offset " + p);
        }
        int[] q = {p + 1};
        long tag = ZdPrimitives.readVarint(b, q);
        long len = ZdPrimitives.readVarint(b, q);
        if (tag < 0 || tag > Integer.MAX_VALUE || len < 0 || len > b.length - q[0]) {
            throw new IllegalArgumentException("zd ext tag/length out of bounds (tag " + tag + ", len " + len + ")");
        }
        byte[] payload = Arrays.copyOfRange(b, q[0], q[0] + (int) len);
        pos[0] = q[0] + (int) len;
        return new Decoded((int) tag, payload);
    }

    /** ext 解码结果：类型标记 + 载荷副本。 / The decoded ext: tag + payload copy. */
    public record Decoded(int tag, byte[] payload) {
    }
}
