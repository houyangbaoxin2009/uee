package org.uee.util;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Growable byte sink with a UTF-8 fast path.
 *
 * <p>This is the single write primitive the whole export pipeline funnels through: every writer
 * appends here and the buffer is handed to the filesystem once. The class deliberately avoids
 * {@code String} concatenation and {@code StringBuilder} for the byte-level work so that the cost
 * of producing a large export stays proportional to the payload size rather than to the number of
 * intermediate objects.
 *
 * <p>设计意图：整条导出链路只经过这一个写原语，避免逐元素拼接字符串产生的中间对象。
 *
 * <p>Not thread-safe: one buffer per encoding shard, which is how the pipeline gets its
 * parallelism without shared mutable state.
 */
public final class ByteBuf {

    private static final int DEFAULT_CAPACITY = 1 << 12;

    private byte[] buf;
    private int len;

    public ByteBuf() {
        this(DEFAULT_CAPACITY);
    }

    public ByteBuf(int capacity) {
        this.buf = new byte[Math.max(16, capacity)];
    }

    public int length() {
        return len;
    }

    public boolean isEmpty() {
        return len == 0;
    }

    /** Ensures at least {@code extra} more bytes fit without reallocating. */
    public void ensure(int extra) {
        int need = len + extra;
        if (need > buf.length) {
            int cap = buf.length;
            while (cap < need) {
                cap <<= 1;
                if (cap < 0) {
                    cap = need;
                    break;
                }
            }
            buf = Arrays.copyOf(buf, cap);
        }
    }

    public ByteBuf u8(int b) {
        ensure(1);
        buf[len++] = (byte) b;
        return this;
    }

    public ByteBuf raw(byte[] src, int off, int n) {
        ensure(n);
        System.arraycopy(src, off, buf, len, n);
        len += n;
        return this;
    }

    public ByteBuf raw(byte[] src) {
        return raw(src, 0, src.length);
    }

    /** Appends a literal ASCII string. Callers must guarantee the content is ASCII-only. */
    public ByteBuf ascii(String s) {
        int n = s.length();
        ensure(n);
        byte[] b = buf;
        int p = len;
        for (int i = 0; i < n; i++) {
            b[p++] = (byte) s.charAt(i);
        }
        len = p;
        return this;
    }

    public ByteBuf utf8(String s) {
        if (s == null) {
            return this;
        }
        if (isAscii(s)) {
            return ascii(s);
        }
        return raw(s.getBytes(StandardCharsets.UTF_8));
    }

    public ByteBuf utf8(String s, int off, int n) {
        return raw(s.substring(off, off + n).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isAscii(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            if (s.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** Appends a decimal long. Hand-rolled to avoid allocating a String per number. */
    public ByteBuf dec(long v) {
        ensure(20);
        if (v < 0) {
            buf[len++] = '-';
            if (v == Long.MIN_VALUE) {
                return raw("-9223372036854775808".getBytes(StandardCharsets.US_ASCII));
            }
            v = -v;
        }
        int start = len;
        do {
            buf[len++] = (byte) ('0' + (int) (v % 10));
            v /= 10;
        } while (v != 0);
        for (int i = start, j = len - 1; i < j; i++, j--) {
            byte t = buf[i];
            buf[i] = buf[j];
            buf[j] = t;
        }
        return this;
    }

    public ByteBuf dec(int v) {
        return dec((long) v);
    }

    /** Appends a JSON number for a double, keeping a trailing {@code .0} off integral values. */
    public ByteBuf jsonNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return dec((long) v);
        }
        return ascii(Double.toString(v));
    }

    public ByteBuf nl() {
        return u8('\n');
    }

    /** Appends {@code count} copies of an ASCII byte. */
    public ByteBuf fill(int b, int count) {
        ensure(count);
        Arrays.fill(buf, len, len + count, (byte) b);
        len += count;
        return this;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, len);
    }

    /** Returns the backing array without copying. Only valid up to {@link #length()}. */
    public byte[] backing() {
        return buf;
    }

    public void reset() {
        len = 0;
    }
}
