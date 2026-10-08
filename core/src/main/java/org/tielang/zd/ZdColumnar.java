package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * zd 列式容器 + v3 编码族（对齐 tiedb {@code src/zd_extra.tie} 权威布局与
 * {@code 2026-09-28-zd-v3-design.md} §4）。
 * <pre>
 * 容器 = 0xD6 | varint 列数 | 每列列头 | 各列值
 * v3 列头 = [列类型 varint][编码 varint][列长 varint]        （v2 列头 = [列类型 varint]）
 * </pre>
 * 类型码（权威）：0 = i64、1 = f64、2 = string、3 = bool。值编码（权威）：i64 →
 * {@link ZdPrimitives#encI64}、f64 → 8 字节裸大端、string →
 * {@link ZdPrimitives#encodeString}、bool → 0xc2/0xc3。f64 位模式直写、列内元素不重复
 * 类型标签。
 * <p>
 * <b>v3 编码族（列级声明，纯表示层——解码后值域与 plain 完全一致）：</b>
 * <ul>
 *   <li>0 plain — 原值序列（通用）。</li>
 *   <li>1 RLE — {@code [游程 varint][值]*}，游程 ≥ 1（低基数/排序列）。</li>
 *   <li>2 delta — {@code [首值 i64 BE 8B][varint 差分]*}，仅整型列；差分必须非负
 *       （负差分写侧确定性 IAE，调用方应换编码）。</li>
 *   <li>3 字典 — {@code [varint 字典数][字典串]*][varint 列长][varint 索引]*}，仅字符串列；
 *       写侧按首见序构建字典（确定性），索引必须命中。</li>
 * </ul>
 * 编码族、列式容器与字符串池三者正交，可同文件并用。
 * <p>
 * The zd columnar container + v3 encoding family (the authoritative layout per tiedb
 * {@code src/zd_extra.tie} and {@code 2026-09-28-zd-v3-design.md} §4): container =
 * {@code 0xD6 | varint ncols | per-column header | values}; the v3 column header adds
 * the encoding declaration after the type code (v2 headers have type only). Type codes:
 * 0 = i64, 1 = f64, 2 = string, 3 = bool. Value encodings: i64 → encI64, f64 → 8 bare
 * BE bytes, string → encodeString, bool → 0xc2/0xc3. <b>The v3 encoding family is a
 * column-level declaration, a pure representation layer — decoded values are identical
 * to plain:</b> 0 plain, 1 RLE ({@code [run varint][value]*}, run ≥ 1), 2 delta
 * ({@code [first i64 BE][varint diff]*}, int columns only, non-negative diffs — a
 * negative diff is a deterministic write-side IAE), 3 dictionary ({@code [varint count]
 * [dict strings][varint len][varint index]*}, string columns only, dict built in
 * first-seen order). The encodings, the columnar container and the string pool are
 * orthogonal and may be combined in one file.
 */
public final class ZdColumnar {

    /** 容器前缀字节 {@code 0xD6}。 / The container prefix byte {@code 0xD6}. */
    public static final int PREFIX = 0xD6;

    /** 编码 0：plain（原值序列）。 / Encoding 0: plain (raw value sequence). */
    public static final int ENC_PLAIN = 0;
    /** 编码 1：RLE（游程 ≥ 1）。 / Encoding 1: RLE (runs ≥ 1). */
    public static final int ENC_RLE = 1;
    /** 编码 2：delta（仅整型列，差分非负）。 / Encoding 2: delta (int columns, non-negative diffs). */
    public static final int ENC_DELTA = 2;
    /** 编码 3：字典（仅字符串列）。 / Encoding 3: dictionary (string columns). */
    public static final int ENC_DICT = 3;

    /** 列类型码 0：i64。 / Column type code 0: i64. */
    public static final int TY_I64 = 0;
    /** 列类型码 1：f64。 / Column type code 1: f64. */
    public static final int TY_F64 = 1;
    /** 列类型码 2：string。 / Column type code 2: string. */
    public static final int TY_STRING = 2;
    /** 列类型码 3：bool。 / Column type code 3: bool. */
    public static final int TY_BOOL = 3;

    private ZdColumnar() {
    }

    /**
     * 一列：类型码 + 编码 + 恰一个非 null 值数组（与类型码对应）。用 {@code ofInts}/
     * {@code ofDoubles}/{@code ofStrings}/{@code ofBools} 构造，类型码自动推导。
     * A column: type code + encoding + exactly one non-null value array matching the
     * type. Construct via {@code ofInts}/{@code ofDoubles}/{@code ofStrings}/
     * {@code ofBools} — the type code is derived automatically.
     */
    public static final class Column {
        private final int type;
        private int encoding = ENC_PLAIN;
        private final long[] ints;
        private final double[] doubles;
        private final String[] strings;
        private final boolean[] bools;

        private Column(int type, long[] ints, double[] doubles, String[] strings, boolean[] bools) {
            this.type = type;
            this.ints = ints;
            this.doubles = doubles;
            this.strings = strings;
            this.bools = bools;
        }

        /** i64 列。 / An i64 column. */
        public static Column ofInts(long... values) {
            return new Column(TY_I64, values.clone(), null, null, null);
        }

        /** f64 列。 / An f64 column. */
        public static Column ofDoubles(double... values) {
            return new Column(TY_F64, null, values.clone(), null, null);
        }

        /** string 列。 / A string column. */
        public static Column ofStrings(String... values) {
            return new Column(TY_STRING, null, null, values.clone(), null);
        }

        /** bool 列。 / A bool column. */
        public static Column ofBools(boolean... values) {
            return new Column(TY_BOOL, null, null, null, values.clone());
        }

        /** 列类型码。 / The column type code. */
        public int type() {
            return type;
        }

        /** 列编码声明（缺省 plain）。 / The column encoding (plain by default). */
        public int encoding() {
            return encoding;
        }

        /** 设置编码声明并返回自身。 / Sets the encoding and returns this. */
        public Column encoding(int encoding) {
            this.encoding = encoding;
            return this;
        }

        /** 列长。 / The column length. */
        public int length() {
            return switch (type) {
                case TY_I64 -> ints.length;
                case TY_F64 -> doubles.length;
                case TY_STRING -> strings.length;
                default -> bools.length;
            };
        }

        /** i64 值数组（仅 i64 列）。 / The i64 values (i64 columns only). */
        public long[] ints() {
            return ints;
        }

        /** f64 值数组（仅 f64 列）。 / The f64 values (f64 columns only). */
        public double[] doubles() {
            return doubles;
        }

        /** string 值数组（仅 string 列）。 / The string values (string columns only). */
        public String[] strings() {
            return strings;
        }

        /** bool 值数组（仅 bool 列）。 / The bool values (bool columns only). */
        public boolean[] bools() {
            return bools;
        }
    }

    // ==================== 编码（写侧） / encoding (write side) ====================

    /**
     * 编码 v3 列式容器（每列带编码声明）。列必须非空列表；编码违约（delta 用在非整型 /
     * 负差分，字典用在非字符串 / 重复字典串）确定性 IAE。
     * Encodes a v3 columnar container (each column declares its encoding). Columns must
     * be non-empty; encoding violations (delta on a non-int column or with a negative
     * diff, dictionary on a non-string column or with duplicate dict strings) are
     * deterministic IAEs.
     */
    public static byte[] encode(List<Column> columns) {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("zd columnar container needs at least one column");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PREFIX);
        ZdPrimitives.writeVarint(out, columns.size());
        for (Column c : columns) {
            ZdPrimitives.writeVarint(out, c.type());
            ZdPrimitives.writeVarint(out, c.encoding());
        }
        for (Column c : columns) {
            ZdPrimitives.writeVarint(out, c.length());
            switch (c.encoding()) {
                case ENC_PLAIN -> encodePlain(out, c);
                case ENC_RLE -> encodeRle(out, c);
                case ENC_DELTA -> encodeDelta(out, c);
                case ENC_DICT -> encodeDict(out, c);
                default -> throw new IllegalArgumentException("unknown column encoding: " + c.encoding());
            }
        }
        return out.toByteArray();
    }

    private static void encodeValue(ByteArrayOutputStream out, Column c, int i) {
        switch (c.type()) {
            case TY_I64 -> writeAll(out, ZdPrimitives.encI64(c.ints()[i]));
            case TY_F64 -> writeAll(out, ZdPrimitives.be64(Double.doubleToLongBits(c.doubles()[i])));
            case TY_STRING -> writeAll(out, ZdPrimitives.encodeString(c.strings()[i]));
            default -> out.write(c.bools()[i] ? 0xC3 : 0xC2);
        }
    }

    private static void encodePlain(ByteArrayOutputStream out, Column c) {
        for (int i = 0; i < c.length(); i++) {
            encodeValue(out, c, i);
        }
    }

    private static void encodeRle(ByteArrayOutputStream out, Column c) {
        int n = c.length();
        int i = 0;
        while (i < n) {
            int run = 1;
            while (i + run < n && valueEquals(c, i, i + run)) {
                run++;
            }
            ZdPrimitives.writeVarint(out, run);
            encodeValue(out, c, i);
            i += run;
        }
    }

    private static void encodeDelta(ByteArrayOutputStream out, Column c) {
        if (c.type() != TY_I64) {
            throw new IllegalArgumentException("zd delta encoding applies to i64 columns only (got type " + c.type() + ")");
        }
        long[] v = c.ints();
        if (v.length == 0) {
            return; // 空列不写首值（读侧按 count 0 跳过）/ empty column writes no first value
        }
        ZdPrimitives.writeBe64(out, v[0]);
        for (int i = 1; i < v.length; i++) {
            long diff = v[i] - v[i - 1];
            if (diff < 0) {
                throw new IllegalArgumentException("zd delta encoding requires non-negative diffs (at index " + i + ": " + diff + ")");
            }
            ZdPrimitives.writeVarint(out, diff);
        }
    }

    private static void encodeDict(ByteArrayOutputStream out, Column c) {
        if (c.type() != TY_STRING) {
            throw new IllegalArgumentException("zd dictionary encoding applies to string columns only (got type " + c.type() + ")");
        }
        List<String> dict = new ArrayList<>();
        for (String s : c.strings()) {
            if (!dict.contains(s)) {
                dict.add(s);
            }
        }
        ZdPrimitives.writeVarint(out, dict.size());
        for (String s : dict) {
            writeAll(out, ZdPrimitives.encodeString(s));
        }
        ZdPrimitives.writeVarint(out, c.length());
        for (String s : c.strings()) {
            ZdPrimitives.writeVarint(out, dict.indexOf(s));
        }
    }

    private static boolean valueEquals(Column c, int a, int b) {
        return switch (c.type()) {
            case TY_I64 -> c.ints()[a] == c.ints()[b];
            case TY_F64 -> Double.doubleToLongBits(c.doubles()[a]) == Double.doubleToLongBits(c.doubles()[b]);
            case TY_STRING -> c.strings()[a].equals(c.strings()[b]);
            default -> c.bools()[a] == c.bools()[b];
        };
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    // ==================== 解码（读侧） / decoding (read side) ====================

    /**
     * 解码 v3 列式容器（每列带编码声明）。畸形 / 越界 / 编码与类型不匹配确定性 IAE。
     * Decodes a v3 columnar container (each column declares its encoding). Malformed /
     * out-of-bounds / encoding-type mismatches are deterministic IAEs.
     */
    public static List<Column> decodeV3(byte[] b, int off, int len) {
        return decode(b, off, len, true);
    }

    /**
     * 解码 v2 列式容器（列头无编码字节，全部按 plain 处理）。
     * Decodes a v2 columnar container (no encoding byte in the column header; all plain).
     */
    public static List<Column> decodeV2(byte[] b, int off, int len) {
        return decode(b, off, len, false);
    }

    private static List<Column> decode(byte[] b, int off, int len, boolean v3) {
        if (off < 0 || len < 0 || off > b.length || len > b.length - off) {
            throw new IllegalArgumentException("zd columnar range out of bounds");
        }
        int end = off + len;
        int[] pos = {off};
        if (pos[0] >= end || (b[pos[0]] & 0xFF) != PREFIX) {
            throw new IllegalArgumentException("not a zd columnar container at offset " + off);
        }
        pos[0]++;
        long ncols = ZdPrimitives.readVarint(b, pos);
        if (ncols < 1 || ncols > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd columnar column count out of range: " + ncols);
        }
        int[] types = new int[(int) ncols];
        int[] encs = new int[(int) ncols];
        for (int i = 0; i < ncols; i++) {
            types[i] = (int) ZdPrimitives.readVarint(b, pos);
            encs[i] = v3 ? (int) ZdPrimitives.readVarint(b, pos) : ENC_PLAIN;
            if (types[i] < TY_I64 || types[i] > TY_BOOL) {
                throw new IllegalArgumentException("zd columnar unknown column type: " + types[i]);
            }
            if (encs[i] < ENC_PLAIN || encs[i] > ENC_DICT) {
                throw new IllegalArgumentException("zd columnar unknown column encoding: " + encs[i]);
            }
        }
        List<Column> out = new ArrayList<>((int) ncols);
        for (int i = 0; i < ncols; i++) {
            long count = ZdPrimitives.readVarint(b, pos);
            if (count < 0 || count > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("zd columnar column length out of range: " + count);
            }
            out.add(decodeColumn(b, pos, (int) count, types[i], encs[i], end));
        }
        if (pos[0] != end) {
            throw new IllegalArgumentException("zd columnar container has trailing bytes ("
                    + (end - pos[0]) + " unparsed)");
        }
        return out;
    }

    private static Column decodeColumn(byte[] b, int[] pos, int count, int type, int enc, int end) {
        switch (enc) {
            case ENC_PLAIN -> {
                return switch (type) {
                    case TY_I64 -> {
                        long[] v = new long[count];
                        for (int i = 0; i < count; i++) {
                            v[i] = ZdPrimitives.decI64Checked(b, pos, end);
                        }
                        yield Column.ofInts(v);
                    }
                    case TY_F64 -> {
                        double[] v = new double[count];
                        for (int i = 0; i < count; i++) {
                            v[i] = readF64(b, pos, end);
                        }
                        yield Column.ofDoubles(v);
                    }
                    case TY_STRING -> {
                        String[] v = new String[count];
                        for (int i = 0; i < count; i++) {
                            v[i] = ZdPrimitives.decodeString(b, pos);
                        }
                        yield Column.ofStrings(v);
                    }
                    default -> {
                        boolean[] v = new boolean[count];
                        for (int i = 0; i < count; i++) {
                            v[i] = readBool(b, pos, end);
                        }
                        yield Column.ofBools(v);
                    }
                };
            }
            case ENC_RLE -> {
                requireTypeFor(enc, type);
                return switch (type) {
                    case TY_I64 -> {
                        long[] v = new long[count];
                        int filled = 0;
                        while (filled < count) {
                            long run = readRun(b, pos, count, filled);
                            long val = ZdPrimitives.decI64Checked(b, pos, end);
                            for (long k = 0; k < run; k++) {
                                v[filled + (int) k] = val;
                            }
                            filled += (int) run;
                        }
                        yield Column.ofInts(v);
                    }
                    case TY_F64 -> {
                        double[] v = new double[count];
                        int filled = 0;
                        while (filled < count) {
                            long run = readRun(b, pos, count, filled);
                            double val = readF64(b, pos, end);
                            for (long k = 0; k < run; k++) {
                                v[filled + (int) k] = val;
                            }
                            filled += (int) run;
                        }
                        yield Column.ofDoubles(v);
                    }
                    case TY_STRING -> {
                        String[] v = new String[count];
                        int filled = 0;
                        while (filled < count) {
                            long run = readRun(b, pos, count, filled);
                            String val = ZdPrimitives.decodeString(b, pos);
                            for (long k = 0; k < run; k++) {
                                v[filled + (int) k] = val;
                            }
                            filled += (int) run;
                        }
                        yield Column.ofStrings(v);
                    }
                    default -> {
                        boolean[] v = new boolean[count];
                        int filled = 0;
                        while (filled < count) {
                            long run = readRun(b, pos, count, filled);
                            boolean val = readBool(b, pos, end);
                            for (long k = 0; k < run; k++) {
                                v[filled + (int) k] = val;
                            }
                            filled += (int) run;
                        }
                        yield Column.ofBools(v);
                    }
                };
            }
            case ENC_DELTA -> {
                if (type != TY_I64) {
                    throw new IllegalArgumentException("zd delta encoding applies to i64 columns only (got type " + type + ")");
                }
                if (pos[0] + 8 > end && count > 0) {
                    throw new IllegalArgumentException("zd delta first value overruns the column");
                }
                long[] v = new long[count];
                if (count == 0) {
                    return Column.ofInts(v);
                }
                v[0] = ZdPrimitives.readBe64(b, pos[0]);
                pos[0] += 8;
                for (int i = 1; i < count; i++) {
                    long diff = ZdPrimitives.readVarint(b, pos);
                    if (diff < 0) {
                        throw new IllegalArgumentException("zd delta diff must be non-negative: " + diff);
                    }
                    v[i] = v[i - 1] + diff;
                }
                return Column.ofInts(v);
            }
            default -> { // ENC_DICT
                if (type != TY_STRING) {
                    throw new IllegalArgumentException("zd dictionary encoding applies to string columns only (got type " + type + ")");
                }
                long dictCount = ZdPrimitives.readVarint(b, pos);
                if (dictCount < 0 || dictCount > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("zd dict count out of range: " + dictCount);
                }
                String[] dict = new String[(int) dictCount];
                for (int i = 0; i < dictCount; i++) {
                    dict[i] = ZdPrimitives.decodeString(b, pos);
                }
                long n = ZdPrimitives.readVarint(b, pos);
                if (n != count) {
                    throw new IllegalArgumentException("zd dict column length mismatch: header " + count + " vs body " + n);
                }
                String[] v = new String[count];
                for (int i = 0; i < count; i++) {
                    long idx = ZdPrimitives.readVarint(b, pos);
                    if (idx < 0 || idx >= dictCount) {
                        throw new IllegalArgumentException("zd dict index out of range: " + idx);
                    }
                    v[i] = dict[(int) idx];
                }
                return Column.ofStrings(v);
            }
        }
    }

    /** 读一个 RLE 游程并做边界校验（游程 ≥ 1 且不越过列长）。 /
     *  Reads one RLE run with bounds checks (run ≥ 1 and within the column length). */
    private static long readRun(byte[] b, int[] pos, int count, int filled) {
        long run = ZdPrimitives.readVarint(b, pos);
        if (run < 1) {
            throw new IllegalArgumentException("zd RLE run must be >= 1: " + run);
        }
        if (filled + run > count) {
            throw new IllegalArgumentException("zd RLE runs overflow the column length");
        }
        return run;
    }

    private static void requireTypeFor(int enc, int type) {
        if (type < TY_I64 || type > TY_BOOL) {
            throw new IllegalArgumentException("zd columnar unknown column type: " + type);
        }
    }

    private static double readF64(byte[] b, int[] pos, int end) {
        if (pos[0] + 8 > end) {
            throw new IllegalArgumentException("zd f64 value overruns the column");
        }
        double v = Double.longBitsToDouble(ZdPrimitives.readBe64(b, pos[0]));
        pos[0] += 8;
        return v;
    }

    private static boolean readBool(byte[] b, int[] pos, int end) {
        if (pos[0] >= end) {
            throw new IllegalArgumentException("zd bool value overruns the column");
        }
        int t = b[pos[0]++] & 0xFF;
        if (t == 0xC3) {
            return true;
        }
        if (t == 0xC2) {
            return false;
        }
        throw new IllegalArgumentException("not a bool tag: 0x" + Integer.toHexString(t));
    }
}
