package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * zd 原语：语言无关二进制序列化的底层字节工具，与 tie-main {@code zdw.enc_i64} /
 * tiedb {@code src/zd.tie} 编码构建块一一对应，作为通用 zd 载体的唯一低层编码点。
 * <p>
 * zd primitives: the low-level byte helpers of the language-agnostic zd carrier,
 * mirroring the building blocks of tie-main {@code zdw.enc_i64} and tiedb
 * {@code src/zd.tie} — the single low-level encoding point of the carrier.
 * <p>
 * 值编码约定 / value-encoding conventions (the v2 spec type table):
 * <ul>
 *   <li>i64 — fixint 正 {@code 0x00..0x7f} / 负 {@code 0xe0..0xff}，定宽
 *       {@code 0xd0..0xd3}（i8/i16/i32/i64 大端）；u64 无符号承载 {@code 0xcc..0xcf}</li>
 *   <li>string — fixstr {@code 0xa0|n}（≤31）/ str8 {@code 0xd9} / str16 {@code 0xda} /
 *       str32 {@code 0xdb} + UTF-8</li>
 *   <li>bool — {@code 0xc2/0xc3}；null — {@code 0xc0}</li>
 *   <li>ext — {@code 0xd7 + tag varint + len varint + payload}</li>
 * </ul>
 */
public final class ZdPrimitives {

    private ZdPrimitives() {
    }

    /**
     * Protobuf 风格 base-128 varint。负值产生空字节（与 {@code zdw.varint} 一致）；调用方
     * 只传非负的 tag / 长度 / 子计数。Protobuf-style base-128 varint. Negative values
     * produce zero bytes (matching {@code zdw.varint}); callers pass non-negative
     * tags / lengths / child counts only.
     */
    public static byte[] varint(long n) {
        if (n < 0) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(8);
        writeVarint(out, n);
        return out.toByteArray();
    }

    /** 追加一个非负 protobuf varint 到流。Appends a non-negative protobuf varint to the stream. */
    public static void writeVarint(ByteArrayOutputStream out, long n) {
        long v = n;
        while (v >= 0x80) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>= 7;
        }
        out.write((int) v);
    }

    /**
     * 读取 {@code b[off]} 起的一个已长度约束的 protobuf varint（最多消费 {@code len}
     * 字节）。Reads a length-bounded protobuf varint from {@code b[off]} consuming at
     * most {@code len} bytes.
     */
    public static long readVarintBounded(byte[] b, int off, int len) {
        long v = 0;
        int shift = 0;
        int p = off;
        int end = off + len;
        while (p < end) {
            byte x = b[p++];
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint too long");
            }
        }
        throw new IllegalArgumentException("varint overruns its field");
    }

    /**
     * 读取 {@code b[pos[0]]} 起的一个流式（未限长）protobuf varint，并推进 {@code pos[0]}。
     * Reads a streaming (unbounded) protobuf varint from {@code b[pos[0]]}, advancing
     * {@code pos[0]} past it.
     */
    public static long readVarint(byte[] b, int[] pos) {
        long v = 0;
        int shift = 0;
        while (true) {
            if (pos[0] >= b.length) {
                throw new IllegalArgumentException("truncated varint");
            }
            byte x = b[pos[0]++];
            v |= (long) (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return v;
            }
            shift += 7;
            if (shift > 63) {
                throw new IllegalArgumentException("varint too long");
            }
        }
    }

    /**
     * {@code zdw.enc_i64} 紧凑编码，逐分支照抄。The {@code zdw.enc_i64} compact encoding,
     * transcribed branch for branch.
     */
    public static byte[] encI64(long n) {
        if (n >= 0 && n <= 127) {
            return new byte[]{(byte) n};
        }
        if (n >= -32 && n < 0) {
            return new byte[]{(byte) (n & 0xFF)};
        }
        if (n >= -128 && n < -32) {
            return new byte[]{(byte) 0xD0, (byte) (n & 0xFF)};
        }
        if (n >= 128 && n <= 255) {
            return new byte[]{(byte) 0xCC, (byte) n};
        }
        if (n >= -32768 && n < -128) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(3);
            out.write(0xD1);
            writeBe16(out, n & 0xFFFF);
            return out.toByteArray();
        }
        if (n >= 256 && n <= 65535) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(3);
            out.write(0xCD);
            writeBe16(out, n);
            return out.toByteArray();
        }
        if (n >= -2147483648L && n < -32768) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(5);
            out.write(0xD2);
            writeBe32(out, n & 0xFFFFFFFFL);
            return out.toByteArray();
        }
        if (n >= 65536 && n <= 4294967295L) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(5);
            out.write(0xCE);
            writeBe32(out, n);
            return out.toByteArray();
        }
        if (n >= 0) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(9);
            out.write(0xCF);
            writeBe64(out, n);
            return out.toByteArray();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(9);
        out.write(0xD3);
        writeBe64(out, n);
        return out.toByteArray();
    }

    /**
     * 逆向 {@code encI64}：解码 {@code b[off]} 起的紧凑编码。Reverse of {@link #encI64}:
     * decodes the compact encoding starting at {@code b[off]}.
     */
    public static long decI64(byte[] b, int off) {
        int first = b[off] & 0xFF;
        switch (first) {
            case 0xD0:
                return (byte) b[off + 1]; // [-128,-33]
            case 0xCC:
                return b[off + 1] & 0xFF; // [128,255]
            case 0xD1:
                return (short) (((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF)); // [-32768,-129]
            case 0xCD:
                return ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF); // [256,65535]
            case 0xD2: {
                long u = readBe32(b, off + 1);
                return (int) u; // [-2147483648,-32769] sign-extended
            }
            case 0xCE:
                return readBe32(b, off + 1); // [65536,4294967295] unsigned
            case 0xCF:
                return readBe64(b, off + 1); // positive be64
            case 0xD3:
                return readBe64(b, off + 1); // negative be64 (two's complement)
            default:
                return (byte) first; // single byte: [0,127] or [-32,-1]
        }
    }

    /** IEEE-754 位模式按 8 字节大端（f64 直写，无数学分解）。Bit pattern of a double as 8 BE bytes. */
    public static byte[] be64(long bits) {
        return new byte[]{
            (byte) (bits >> 56), (byte) (bits >> 48), (byte) (bits >> 40), (byte) (bits >> 32),
            (byte) (bits >> 24), (byte) (bits >> 16), (byte) (bits >> 8), (byte) bits
        };
    }

    /** 大端读 8 字节 → long。Reads 8 big-endian bytes as a long. */
    public static long readBe64(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    /**
     * 有界解码 {@code b[pos[0]]} 起的紧凑 i64 并推进 {@code pos[0]}；越过 {@code end}
     * 抛 {@link IllegalArgumentException}（列式等定界场景用）。
     * Bounded decode of the compact i64 at {@code b[pos[0]]}, advancing {@code pos[0]};
     * overrunning {@code end} raises {@link IllegalArgumentException} (for bounded
     * contexts like columnar).
     */
    public static long decI64Checked(byte[] b, int[] pos, int end) {
        int p = pos[0];
        if (p >= end) {
            throw new IllegalArgumentException("zd i64 value overruns the column");
        }
        long v = decI64(b, p);
        pos[0] = p + encI64(v).length;
        if (pos[0] > end) {
            throw new IllegalArgumentException("zd i64 value overruns the column");
        }
        return v;
    }

    /** 大端读 4 字节 → long（无符号承载）。Reads 4 big-endian bytes as a long (unsigned). */
    public static long readBe32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFFL);
    }

    /** 大端写 16 位。Writes a 16-bit value big-endian. */
    public static void writeBe16(ByteArrayOutputStream out, long n) {
        out.write((int) ((n >> 8) & 0xFF));
        out.write((int) (n & 0xFF));
    }

    /** 大端写 32 位。Writes a 32-bit value big-endian. */
    public static void writeBe32(ByteArrayOutputStream out, long n) {
        out.write((int) ((n >> 24) & 0xFF));
        out.write((int) ((n >> 16) & 0xFF));
        out.write((int) ((n >> 8) & 0xFF));
        out.write((int) (n & 0xFF));
    }

    /** 大端写 64 位。Writes a 64-bit value big-endian. */
    public static void writeBe64(ByteArrayOutputStream out, long n) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((n >> (i * 8)) & 0xFF));
        }
    }

    /** UTF-8 编码字符串；null → 空字节。Encodes a string as UTF-8; null → empty bytes. */
    public static byte[] utf8(String s) {
        if (s == null) {
            return new byte[0];
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ==================== 自描述字符串（权威形态） / self-describing strings ====================

    /**
     * 权威自描述字符串编码：fixstr {@code 0xa0|n}（n ≤ 31）/ str8 {@code 0xd9}（n ≤ 255）/
     * str16 {@code 0xda}（n ≤ 65535）/ str32 {@code 0xdb}，均后接 UTF-8 字节。
     * The authoritative self-describing string encoding: fixstr {@code 0xa0|n} (n ≤ 31) /
     * str8 {@code 0xd9} (n ≤ 255) / str16 {@code 0xda} (n ≤ 65535) / str32 {@code 0xdb},
     * each followed by the UTF-8 bytes.
     */
    public static byte[] encodeString(String s) {
        byte[] p = utf8(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream(p.length + 5);
        int n = p.length;
        if (n <= 31) {
            out.write(0xA0 | n);
        } else if (n <= 0xFF) {
            out.write(0xD9);
            out.write(n);
        } else if (n <= 0xFFFF) {
            out.write(0xDA);
            writeBe16(out, n);
        } else {
            out.write(0xDB);
            writeBe32(out, n);
        }
        out.write(p, 0, n);
        return out.toByteArray();
    }

    /**
     * 解码 {@code b[pos[0]]} 起的自描述字符串并推进 {@code pos[0]}；越界 / 畸形头确定性 IAE。
     * Decodes the self-describing string at {@code b[pos[0]]}, advancing {@code pos[0]};
     * out-of-bounds / malformed headers are deterministic IAEs.
     */
    public static String decodeString(byte[] b, int[] pos) {
        int p = pos[0];
        if (p >= b.length) {
            throw new IllegalArgumentException("truncated string header");
        }
        int tag = b[p++] & 0xFF;
        long n;
        if (tag >= 0xA0 && tag <= 0xBF) {
            n = tag & 0x1F;
        } else if (tag == 0xD9) {
            if (p >= b.length) {
                throw new IllegalArgumentException("truncated str8 header");
            }
            n = b[p++] & 0xFF;
        } else if (tag == 0xDA) {
            if (p + 2 > b.length) {
                throw new IllegalArgumentException("truncated str16 header");
            }
            n = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
            p += 2;
        } else if (tag == 0xDB) {
            if (p + 4 > b.length) {
                throw new IllegalArgumentException("truncated str32 header");
            }
            n = readBe32(b, p);
            p += 4;
        } else {
            throw new IllegalArgumentException("not a string tag: 0x" + Integer.toHexString(tag));
        }
        if (n < 0 || p + n > b.length) {
            throw new IllegalArgumentException("string payload overruns the buffer");
        }
        pos[0] = p + (int) n;
        return new String(b, p, (int) n, StandardCharsets.UTF_8);
    }
}
