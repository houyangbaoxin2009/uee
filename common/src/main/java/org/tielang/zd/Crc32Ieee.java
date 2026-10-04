package org.tielang.zd;

/**
 * CRC-32/IEEE（poly 0xEDB88320，反射，初值 0xFFFFFFFF，末次异或 0xFFFFFFFF），与
 * zlib.crc32 逐位一致（p.2.4.1）。表驱动，允许增量更新：{@code init()} 起步 →
 * 多次 {@code update} → {@code value()} 收口。已知向量：{@code crc32("123456789" ASCII)}
 * == {@code 0xCBF43926}。
 * <p>
 * CRC-32/IEEE (poly 0xEDB88320, reflected, init 0xFFFFFFFF, final XOR 0xFFFFFFFF),
 * bit-compatible with zlib.crc32 . Table-driven, incremental: start with
 * {@link #init()}, feed any number of {@link #update(int, byte[])} calls, and finish
 * with {@link #value(int)}. Known vector: crc32 of ASCII "123456789" == 0xCBF43926.
 */
public final class Crc32Ieee {

    /** 反射 poly 0xEDB88320 的 256 项查找表。256-entry lookup table for the reflected poly. */
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                if ((c & 1) != 0) {
                    c = 0xEDB88320 ^ (c >>> 1);
                } else {
                    c >>>= 1;
                }
            }
            TABLE[i] = c;
        }
    }

    private Crc32Ieee() {
    }

    /** 初始 crc 寄存器（0xFFFFFFFF）。Initial CRC register (0xFFFFFFFF). */
    public static int init() {
        return 0xFFFFFFFF;
    }

    /**
     * 增量更新：喂入整段 {@code data}，返回下一个 crc 寄存器值。
     * Incremental update over the whole {@code data} array; returns the next crc register.
     */
    public static int update(int crc, byte[] data) {
        return update(crc, data, 0, data.length);
    }

    /**
     * 增量更新：喂入 {@code b[off..off+len)}，返回下一个 crc 寄存器值。
     * Incremental update over {@code b[off..off+len)}; returns the next crc register.
     */
    public static int update(int crc, byte[] b, int off, int len) {
        int c = crc;
        for (int i = off; i < off + len; i++) {
            c = TABLE[(c ^ b[i]) & 0xFF] ^ (c >>> 8);
        }
        return c;
    }

    /** 收口：末次异或 0xFFFFFFFF，得到标准 CRC-32/IEEE 值。Finalises: final XOR 0xFFFFFFFF. */
    public static int value(int crc) {
        return crc ^ 0xFFFFFFFF;
    }

    /** 便捷单次计算整段 {@code data} 的 CRC-32/IEEE。Convenience: CRC-32/IEEE of a whole array. */
    public static int of(byte[] data) {
        return of(data, 0, data.length);
    }

    /** 便捷单次计算 {@code b[off..off+len)} 的 CRC-32/IEEE。Convenience for a bounded slice. */
    public static int of(byte[] b, int off, int len) {
        return value(update(init(), b, off, len));
    }
}