package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * zd 字符串池 / 字典引用（v2 权威形态，对齐 tiedb {@code src/zd_extra.tie}）：
 * 池段 = 各字符串的 {@link ZdPrimitives#encodeString} 串接；重复字符串只存一次——写方经
 * {@link #index} 查池命中复用序号；引用编码 = <b>{@code 0xC8 + varint 序号}</b>（只写序号
 * 不写串），需头部 flags 字典位（bit0）。
 * <p>
 * The zd string pool / dictionary reference (the authoritative v2 form, per tiedb
 * {@code src/zd_extra.tie}): the pool segment = each string's
 * {@link ZdPrimitives#encodeString} concatenated; repeated strings are stored once —
 * writers look up {@link #index} and reuse the slot; the reference encoding =
 * <b>{@code 0xC8 + varint(index)}</b> (index only, no string), requiring the header
 * flags dictionary bit (bit0).
 */
public final class ZdPool {

    /** 字典引用前缀字节 {@code 0xC8}。 / The dictionary-reference prefix byte {@code 0xC8}. */
    public static final int REF_PREFIX = 0xC8;

    private ZdPool() {
    }

    /**
     * 构建字符串池段字节（各元素 encodeString 串接，构造序即池序）。重复字符串是否入池
     * 由调用方决定（推荐先 {@link #index} 去重）。
     * Builds the string-pool segment bytes (each element's encodeString concatenated,
     * construction order = pool order). Whether duplicates enter the pool is the
     * caller's call (dedupe via {@link #index} first is recommended).
     */
    public static byte[] build(List<String> strings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String s : strings) {
            byte[] e = ZdPrimitives.encodeString(s);
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    /**
     * 查池内字符串序号；未找到返回 -1（线性扫池段）。
     * The pool index of the string, or -1 when absent (linear scan of the pool segment).
     */
    public static int index(byte[] pool, String s) {
        int[] pos = {0};
        int idx = 0;
        while (pos[0] < pool.length) {
            String v = ZdPrimitives.decodeString(pool, pos);
            if (v.equals(s)) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    /**
     * 池内序号 → 字符串；越界确定性 IAE。
     * The pool string for the index; out of bounds is a deterministic IAE.
     */
    public static String get(byte[] pool, int idx) {
        if (idx < 0) {
            throw new IllegalArgumentException("zd pool index must be non-negative: " + idx);
        }
        int[] pos = {0};
        for (int i = 0; i < idx; i++) {
            if (pos[0] >= pool.length) {
                throw new IllegalArgumentException("zd pool index out of bounds: " + idx);
            }
            ZdPrimitives.decodeString(pool, pos);
        }
        if (pos[0] >= pool.length) {
            throw new IllegalArgumentException("zd pool index out of bounds: " + idx);
        }
        return ZdPrimitives.decodeString(pool, pos);
    }

    /**
     * 字典引用编码 = {@code 0xC8 + varint 序号}（只写序号不写串）。
     * The dictionary-reference encoding = {@code 0xC8 + varint(index)} (index only).
     */
    public static byte[] encodeRef(int idx) {
        if (idx < 0) {
            throw new IllegalArgumentException("zd pool reference index must be non-negative: " + idx);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(6);
        out.write(REF_PREFIX);
        ZdPrimitives.writeVarint(out, idx);
        return out.toByteArray();
    }

    /**
     * 解码 {@code b[pos[0]]} 起的字典引用并推进 {@code pos[0]}，从池内取回字符串。
     * 非 {@code 0xC8} / 序号越界确定性 IAE。
     * Decodes the dictionary reference at {@code b[pos[0]]}, advancing {@code pos[0]},
     * and resolves it against the pool. Non-{@code 0xC8} / out-of-range index are
     * deterministic IAEs.
     */
    public static String decodeRef(byte[] b, int[] pos, byte[] pool) {
        if (pos[0] >= b.length || (b[pos[0]] & 0xFF) != REF_PREFIX) {
            throw new IllegalArgumentException("not a zd dictionary reference at offset " + pos[0]);
        }
        int[] q = {pos[0] + 1};
        long idx = ZdPrimitives.readVarint(b, q);
        if (idx < 0 || idx > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd pool reference index out of range: " + idx);
        }
        String s = get(pool, (int) idx);
        pos[0] = q[0];
        return s;
    }

    /**
     * 便捷：把字符串列表去重构建池并返回「池字节 + 每串引用序号」。输入重复串只入池一次。
     * Convenience: builds a deduped pool from the strings and returns
     * {@code [pool bytes, reference index per input]} — duplicate inputs share one slot.
     */
    public static Built buildDeduped(List<String> strings) {
        List<String> pool = new ArrayList<>();
        int[] refs = new int[strings.size()];
        for (int i = 0; i < strings.size(); i++) {
            int found = -1;
            for (int j = 0; j < pool.size(); j++) {
                if (pool.get(j).equals(strings.get(i))) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                pool.add(strings.get(i));
                refs[i] = pool.size() - 1;
            } else {
                refs[i] = found;
            }
        }
        return new Built(build(pool), refs);
    }

    /** 去重构建结果：池字节 + 每输入串的引用序号。 / The deduped build: pool bytes + per-input reference index. */
    public record Built(byte[] pool, int[] refs) {
    }
}
