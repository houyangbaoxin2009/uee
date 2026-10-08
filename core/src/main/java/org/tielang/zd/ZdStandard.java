package org.tielang.zd;

import java.util.Arrays;

/**
 * zd v3 标准类型三件（语言规范 §17.2，ext 固定标记升格；v3 实现强制支持，v2 读取方按
 * 未知 ext 整体跳过）：
 * <ul>
 *   <li><b>timestamp</b>（标记 1）：8 字节 Unix 纪元秒 i64 大端 + 4 字节纳秒 u32 大端；
 *       纳秒 ∈ [0, 999999999]。</li>
 *   <li><b>decimal</b>（标记 2）：符号 1 字节（0 = 正 / 1 = 负）+ 十进制数字串 UTF-8 +
 *       指数 i8（-128..127），值为 ±数字 × 10^指数；数字串非空且全为 '0'..'9'。</li>
 *   <li><b>uuid</b>（标记 3）：16 字节 RFC 4122 位模式。</li>
 * </ul>
 * zd v3 standard types (language-spec §17.2, fixed ext markers; mandatory for v3
 * implementations, skipped as unknown ext by v2 readers):
 * <ul>
 *   <li><b>timestamp</b> (marker 1): 8-byte Unix epoch-seconds i64 BE + 4-byte
 *       nanoseconds u32 BE; nano ∈ [0, 999999999].</li>
 *   <li><b>decimal</b> (marker 2): 1-byte sign (0 = positive / 1 = negative) + the
 *       decimal digit string UTF-8 + an i8 exponent (-128..127); value =
 *       ±digits × 10^exponent; digits non-empty, all '0'..'9'.</li>
 *   <li><b>uuid</b> (marker 3): 16-byte RFC 4122 bit pattern.</li>
 * </ul>
 */
public final class ZdStandard {

    /** timestamp ext 标记 1。 / The timestamp ext marker 1. */
    public static final int TAG_TIMESTAMP = 1;
    /** decimal ext 标记 2。 / The decimal ext marker 2. */
    public static final int TAG_DECIMAL = 2;
    /** uuid ext 标记 3。 / The uuid ext marker 3. */
    public static final int TAG_UUID = 3;

    private ZdStandard() {
    }

    /** timestamp 值。 / The timestamp value. */
    public record Timestamp(long epochSecond, int nano) {
    }

    /** decimal 值：±数字 × 10^指数。 / The decimal value: ±digits × 10^exponent. */
    public record Decimal(boolean negative, String digits, int exponent) {
    }

    /** uuid 值（RFC 4122 位模式的两个 64 位半）。 / The uuid value (two 64-bit halves of the RFC 4122 bit pattern). */
    public record Uuid(long msb, long lsb) {
    }

    // ==================== timestamp（标记 1） ====================

    /** 编码 timestamp 为 ext 标记 1 字节。Encodes a timestamp as the ext marker 1 bytes. */
    public static byte[] encodeTimestamp(Timestamp t) {
        if (t.nano() < 0 || t.nano() > 999_999_999) {
            throw new IllegalArgumentException("timestamp nanoseconds out of range: " + t.nano());
        }
        byte[] p = new byte[12];
        for (int i = 0; i < 8; i++) {
            p[i] = (byte) (t.epochSecond() >> ((7 - i) * 8));
        }
        int n = t.nano();
        p[8] = (byte) (n >>> 24);
        p[9] = (byte) (n >>> 16);
        p[10] = (byte) (n >>> 8);
        p[11] = (byte) n;
        return ZdExt.encode(TAG_TIMESTAMP, p);
    }

    /** 解码 ext 标记 1 为 timestamp。Decodes the ext marker 1 as a timestamp. */
    public static Timestamp decodeTimestamp(byte[] ext) {
        ZdExt.Decoded d = require(ext, TAG_TIMESTAMP, 12);
        long sec = 0;
        for (int i = 0; i < 8; i++) {
            sec = (sec << 8) | (d.payload()[i] & 0xFFL);
        }
        int nano = ((d.payload()[8] & 0xFF) << 24) | ((d.payload()[9] & 0xFF) << 16)
                | ((d.payload()[10] & 0xFF) << 8) | (d.payload()[11] & 0xFF);
        return new Timestamp(sec, nano);
    }

    // ==================== decimal（标记 2） ====================

    /** 编码 decimal 为 ext 标记 2 字节。Encodes a decimal as the ext marker 2 bytes. */
    public static byte[] encodeDecimal(Decimal d) {
        byte[] digits = ZdPrimitives.utf8(d.digits());
        if (digits.length == 0 || digits.length > 255) {
            throw new IllegalArgumentException("decimal digit string must be 1..255 bytes: " + digits.length);
        }
        for (byte b : digits) {
            if (b < '0' || b > '9') {
                throw new IllegalArgumentException("decimal digits must be '0'..'9': " + d.digits());
            }
        }
        if (d.exponent() < -128 || d.exponent() > 127) {
            throw new IllegalArgumentException("decimal exponent out of i8 range: " + d.exponent());
        }
        byte[] p = new byte[2 + digits.length];
        p[0] = (byte) (d.negative() ? 1 : 0);
        p[1] = (byte) d.exponent();
        System.arraycopy(digits, 0, p, 2, digits.length);
        return ZdExt.encode(TAG_DECIMAL, p);
    }

    /** 解码 ext 标记 2 为 decimal。Decodes the ext marker 2 as a decimal. */
    public static Decimal decodeDecimal(byte[] ext) {
        ZdExt.Decoded d = require(ext, TAG_DECIMAL, -1);
        byte[] p = d.payload();
        if (p.length < 2) {
            throw new IllegalArgumentException("decimal payload too short: " + p.length);
        }
        boolean negative = p[0] != 0;
        if (p[0] != 0 && p[0] != 1) {
            throw new IllegalArgumentException("decimal sign byte must be 0 or 1: " + p[0]);
        }
        String digits = new String(p, 2, p.length - 2, java.nio.charset.StandardCharsets.UTF_8);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("decimal digit string is empty");
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("decimal digits must be '0'..'9': " + digits);
            }
        }
        return new Decimal(negative, digits, p[1]);
    }

    // ==================== uuid（标记 3） ====================

    /** 编码 uuid 为 ext 标记 3 字节（16 字节 RFC 4122 位模式）。Encodes a uuid as the ext marker 3 bytes. */
    public static byte[] encodeUuid(Uuid u) {
        byte[] p = new byte[16];
        for (int i = 0; i < 8; i++) {
            p[i] = (byte) (u.msb() >> ((7 - i) * 8));
            p[8 + i] = (byte) (u.lsb() >> ((7 - i) * 8));
        }
        return ZdExt.encode(TAG_UUID, p);
    }

    /** 解码 ext 标记 3 为 uuid。Decodes the ext marker 3 as a uuid. */
    public static Uuid decodeUuid(byte[] ext) {
        ZdExt.Decoded d = require(ext, TAG_UUID, 16);
        long msb = 0;
        long lsb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (d.payload()[i] & 0xFFL);
            lsb = (lsb << 8) | (d.payload()[8 + i] & 0xFFL);
        }
        return new Uuid(msb, lsb);
    }

    // ==================== 内部 / internals ====================

    private static ZdExt.Decoded require(byte[] ext, int tag, int len) {
        int[] pos = {0};
        ZdExt.Decoded d = ZdExt.decode(ext, pos);
        if (d.tag() != tag) {
            throw new IllegalArgumentException("expected ext tag " + tag + " but got " + d.tag());
        }
        if (pos[0] != ext.length) {
            throw new IllegalArgumentException("ext value has trailing bytes (" + (ext.length - pos[0]) + ")");
        }
        if (len >= 0 && d.payload().length != len) {
            throw new IllegalArgumentException("ext tag " + tag + " payload must be " + len + " bytes: "
                    + d.payload().length + " (" + Arrays.toString(d.payload()) + ")");
        }
        return d;
    }
}
