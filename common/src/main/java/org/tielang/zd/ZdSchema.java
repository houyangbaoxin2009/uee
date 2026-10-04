package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * zd v3 schema 段 + 演进语义（{@code 2026-09-28-zd-v3-design.md} §5）。
 * <ul>
 *   <li><b>schema_id</b> = 内容指纹（tsha1f，n=48，base-48）——同一 schema 的所有文件 id
 *       相同，读端据此协商与缓存。指纹内容 = 规范序列化（字段升序）：varint 字段数 +
 *       各字段 {@code varint(num) + utf8(name) + varint(ty) + state 字节}。</li>
 *   <li><b>字段三态</b>：active（正常）→ deprecated（读取方必须仍能读、写方禁止新写）→
 *       removed（物理删除；仅允许在字段进入 deprecated 一个版本周期之后——本 API 的
 *       {@link #remove} 只接受 deprecated 态字段）。</li>
 *   <li><b>字段号永不复用</b>：removed 之后其编号作废，{@link #add} 拒绝任何曾用号。</li>
 *   <li><b>跨版本读取</b>：按字段号对齐，未知名/未知类型字段跳过（延续 v2 规则）。</li>
 * </ul>
 * <b>段字节形态（zd-java 钉定，随 KAT 向量集同步 tie-spec 仓）：</b>
 * fixmap(2)：{@code "id"} → fixstr(schema_id)、{@code "fields"} → fixarray，每个字段为
 * fixmap(4)：{@code "num"/"name"/"ty"/"state"}。自定界，段尾即数据段起点。
 * <p>
 * The zd v3 schema segment + evolution semantics (§5 of the v3 design):
 * <ul>
 *   <li><b>schema_id</b> = the content fingerprint (tsha1f, n=48, base-48) — all files
 *       of the same schema share the id, readers negotiate and cache on it. The
 *       fingerprint content = the canonical serialisation (fields ascending):
 *       varint count + per field {@code varint(num) + utf8(name) + varint(ty) + state byte}.</li>
 *   <li><b>Three field states</b>: active → deprecated (readers must still read it,
 *       writers must not write it) → removed (physically deleted; only allowed after one
 *       version cycle in deprecated — {@link #remove} accepts deprecated fields only).</li>
 *   <li><b>Field numbers are never reused</b>: after removal the number is retired;
 *       {@link #add} rejects any historically used number.</li>
 *   <li><b>Cross-version reading</b>: aligned by field number; unknown names/types are
 *       skipped (the v2 rule carries over).</li>
 * </ul>
 * <b>Segment byte form (pinned by zd-java, to be synced to the tie-spec KAT set):</b>
 * fixmap(2): {@code "id"} → fixstr(schema_id), {@code "fields"} → fixarray of fixmap(4)
 * {@code "num"/"name"/"ty"/"state"}. Self-delimiting; the segment end is the data start.
 */
public final class ZdSchema {

    /** 字段态：active。 / Field state: active. */
    public static final int STATE_ACTIVE = 0;
    /** 字段态：deprecated（读必须支持，写禁止）。 / Field state: deprecated (read required, write forbidden). */
    public static final int STATE_DEPRECATED = 1;
    /** 字段态：removed（物理删除，编号作废）。 / Field state: removed (physically deleted, number retired). */
    public static final int STATE_REMOVED = 2;

    /** schema 字段类型约定（对齐列式类型码 + v3 标准类型）。 /
     *  The schema field type convention (columnar type codes + the v3 standard types). */
    public static final int TY_I64 = 0;
    /** f64。 / f64. */
    public static final int TY_F64 = 1;
    /** string。 / string. */
    public static final int TY_STRING = 2;
    /** bool。 / bool. */
    public static final int TY_BOOL = 3;
    /** bytes。 / bytes. */
    public static final int TY_BYTES = 4;
    /** timestamp（标准类型标记 1）。 / timestamp (standard-type marker 1). */
    public static final int TY_TIMESTAMP = 5;
    /** decimal（标准类型标记 2）。 / decimal (standard-type marker 2). */
    public static final int TY_DECIMAL = 6;
    /** uuid（标准类型标记 3）。 / uuid (standard-type marker 3). */
    public static final int TY_UUID = 7;

    /** 一个 schema 字段：编号（唯一且不复用）+ 名称 + 类型码 + 生命周期态。 /
     *  One schema field: number (unique, never reused) + name + type code + lifecycle state. */
    public record Field(int num, String name, int type, int state) {
    }

    private final TreeMap<Integer, Field> fields;      // 按字段号升序 / ascending by number
    private final java.util.Set<Integer> retiredNums;  // 曾用号（含 removed） / historically used numbers

    private ZdSchema(TreeMap<Integer, Field> fields, java.util.Set<Integer> retiredNums) {
        this.fields = fields;
        this.retiredNums = retiredNums;
    }

    /** 以字段列表构建 schema（编号必须唯一且严格升序可乱序入参——内部按号升序保存）。 /
     *  Builds a schema from fields (numbers unique; input order free — stored ascending). */
    public static SchemaBuilder builder() {
        return new SchemaBuilder();
    }

    /** schema 构建器（初始字段全部 active）。 / The schema builder (initial fields all active). */
    public static final class SchemaBuilder {
        private final TreeMap<Integer, Field> fields = new TreeMap<>();
        private final java.util.Set<Integer> retired = new java.util.HashSet<>();

        /** 追加 active 字段；编号重复确定性 IAE。 / Adds an active field; duplicate numbers are a deterministic IAE. */
        public SchemaBuilder field(int num, String name, int type) {
            return field(num, name, type, STATE_ACTIVE);
        }

        /** 追加指定态字段（decode 路径用）；removed 编号同时记入作废集。 /
         *  Adds a field with an explicit state (the decode path); removed numbers also
         *  enter the retired set. */
        public SchemaBuilder field(int num, String name, int type, int state) {
            if (num < 0) {
                throw new IllegalArgumentException("zd schema field number must be non-negative: " + num);
            }
            if (fields.containsKey(num) || retired.contains(num)) {
                throw new IllegalArgumentException("zd schema field number already used: " + num);
            }
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("zd schema field name must be non-empty");
            }
            if (state < STATE_ACTIVE || state > STATE_REMOVED) {
                throw new IllegalArgumentException("zd schema field state out of range: " + state);
            }
            fields.put(num, new Field(num, name, type, state));
            if (state == STATE_REMOVED) {
                retired.add(num);
            }
            return this;
        }

        /** 构建。 / Builds the immutable schema. */
        public ZdSchema build() {
            return new ZdSchema(new TreeMap<>(fields), new java.util.HashSet<>(retired));
        }
    }

    /** 字段表（按号升序，只读）。 / The field table (ascending by number, read-only). */
    public List<Field> fields() {
        return Collections.unmodifiableList(new ArrayList<>(fields.values()));
    }

    /** 按号取字段；无则 null。 / The field for the number, or null. */
    public Field field(int num) {
        return fields.get(num);
    }

    /** schema_id（tsha1f，n=48，base-48）。 / The schema_id (tsha1f, n=48, base-48). */
    public String schemaId() {
        return Tsha1f.of(canonicalBytes(), 48);
    }

    /** 指纹内容的规范序列化（字段升序）。 / The canonical fingerprint content (fields ascending). */
    private byte[] canonicalBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZdPrimitives.writeVarint(out, fields.size());
        for (Field f : fields.values()) {
            ZdPrimitives.writeVarint(out, f.num());
            byte[] name = ZdPrimitives.utf8(f.name());
            ZdPrimitives.writeVarint(out, name.length);
            out.write(name, 0, name.length);
            ZdPrimitives.writeVarint(out, f.type());
            out.write(f.state());
        }
        return out.toByteArray();
    }

    // ==================== 演进（返回新 schema，不可变纪律） /
    //  evolution (returns a new schema — immutable discipline) ====================

    /** active → deprecated；其余态确定性 IAE。 / active → deprecated; other states are deterministic IAEs. */
    public ZdSchema deprecate(int num) {
        Field f = require(num);
        if (f.state() != STATE_ACTIVE) {
            throw new IllegalArgumentException("only active fields can be deprecated (field " + num + " is state " + f.state() + ")");
        }
        TreeMap<Integer, Field> next = new TreeMap<>(fields);
        next.put(num, new Field(f.num(), f.name(), f.type(), STATE_DEPRECATED));
        return new ZdSchema(next, new java.util.HashSet<>(retiredNums));
    }

    /** deprecated → removed（编号作废）；非 deprecated 态确定性 IAE——三态跃迁纪律。 /
     *  deprecated → removed (the number is retired); non-deprecated states are
     *  deterministic IAEs — the three-state transition discipline. */
    public ZdSchema remove(int num) {
        Field f = require(num);
        if (f.state() != STATE_DEPRECATED) {
            throw new IllegalArgumentException("only deprecated fields can be removed (field " + num + " is state " + f.state() + ")");
        }
        TreeMap<Integer, Field> next = new TreeMap<>(fields);
        next.put(num, new Field(f.num(), f.name(), f.type(), STATE_REMOVED));
        java.util.Set<Integer> retired = new java.util.HashSet<>(retiredNums);
        retired.add(num);
        return new ZdSchema(next, retired);
    }

    /** 新字段取新号；任何曾用号（含 removed）确定性 IAE——字段号永不复用。 /
     *  A new field takes a fresh number; any historically used number (incl. removed)
     *  is a deterministic IAE — field numbers are never reused. */
    public ZdSchema add(int num, String name, int type) {
        if (num < 0) {
            throw new IllegalArgumentException("zd schema field number must be non-negative: " + num);
        }
        if (fields.containsKey(num) || retiredNums.contains(num)) {
            throw new IllegalArgumentException("zd schema field number already used (numbers are never reused): " + num);
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("zd schema field name must be non-empty");
        }
        TreeMap<Integer, Field> next = new TreeMap<>(fields);
        next.put(num, new Field(num, name, type, STATE_ACTIVE));
        return new ZdSchema(next, new java.util.HashSet<>(retiredNums));
    }

    /**
     * 写侧纪律：deprecated / removed 字段禁止新写。
     * The write-side discipline: deprecated / removed fields must not be written.
     */
    public void requireWritable(int num) {
        Field f = require(num);
        if (f.state() != STATE_ACTIVE) {
            throw new IllegalArgumentException("zd schema field " + num + " is not writable (state " + f.state() + ")");
        }
    }

    private Field require(int num) {
        Field f = fields.get(num);
        if (f == null) {
            throw new IllegalArgumentException("zd schema has no field " + num);
        }
        return f;
    }

    // ==================== 段编码 / segment encoding ====================

    /** 编码 schema 段字节（自定界）。 / Encodes the schema segment bytes (self-delimiting). */
    public byte[] encodeSegment() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // fixmap 2
        out.write(0x82);
        writeMapString(out, "id");
        byte[] id = ZdPrimitives.encodeString(schemaId());
        writeAll(out, id);
        writeMapString(out, "fields");
        // fixarray / array16
        int n = fields.size();
        if (n <= 15) {
            out.write(0x90 | n);
        } else {
            out.write(0xDC);
            out.write((n >> 8) & 0xFF);
            out.write(n & 0xFF);
        }
        for (Field f : fields.values()) {
            out.write(0x84); // fixmap 4
            writeMapString(out, "num");
            writeAll(out, ZdPrimitives.encI64(f.num()));
            writeMapString(out, "name");
            writeAll(out, ZdPrimitives.encodeString(f.name()));
            writeMapString(out, "ty");
            writeAll(out, ZdPrimitives.encI64(f.type()));
            writeMapString(out, "state");
            writeAll(out, ZdPrimitives.encI64(f.state()));
        }
        return out.toByteArray();
    }

    /**
     * 解码 schema 段字节（自定界；段尾返回下一位置）。畸形 / 违纪（编号重复、state 非法）
     * 确定性 IAE。
     * Decodes the schema segment bytes (self-delimiting; returns the next position).
     * Malformed / discipline-violating inputs (duplicate numbers, illegal states) are
     * deterministic IAEs.
     */
    public static Decoded decodeSegment(byte[] b, int off) {
        int[] pos = {off};
        expect(b, pos, 0x82, "schema fixmap(2)");
        readMapString(b, pos, "id");
        String id = ZdPrimitives.decodeString(b, pos);
        readMapString(b, pos, "fields");
        int n = readArrayLen(b, pos);
        SchemaBuilder sb = builder();
        int prevNum = -1;
        for (int i = 0; i < n; i++) {
            expect(b, pos, 0x84, "field fixmap(4)");
            readMapString(b, pos, "num");
            long num = ZdPrimitives.decI64(b, pos[0]);
            pos[0] += ZdPrimitives.encI64(num).length;
            if (num <= prevNum) {
                throw new IllegalArgumentException("zd schema field numbers must be strictly ascending: " + num);
            }
            prevNum = (int) num;
            readMapString(b, pos, "name");
            String name = ZdPrimitives.decodeString(b, pos);
            readMapString(b, pos, "ty");
            long ty = ZdPrimitives.decI64(b, pos[0]);
            pos[0] += ZdPrimitives.encI64(ty).length;
            readMapString(b, pos, "state");
            long st = ZdPrimitives.decI64(b, pos[0]);
            pos[0] += ZdPrimitives.encI64(st).length;
            if (st < STATE_ACTIVE || st > STATE_REMOVED) {
                throw new IllegalArgumentException("zd schema field state out of range: " + st);
            }
            sb.field((int) num, name, (int) ty, (int) st);
        }
        ZdSchema schema = sb.build();
        String expected = schema.schemaId();
        if (!expected.equals(id)) {
            throw new IllegalArgumentException("zd schema_id mismatch (declared " + id + ", computed " + expected + ")");
        }
        return new Decoded(schema, pos[0]);
    }

    /** 解码结果：schema + 段尾位置（即数据段起点）。 / The decoded result: schema + the segment end (the data start). */
    public record Decoded(ZdSchema schema, int next) {
    }

    // ==================== 字节辅助 / byte helpers ====================

    private static void writeMapString(ByteArrayOutputStream out, String key) {
        writeAll(out, ZdPrimitives.encodeString(key));
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    private static void expect(byte[] b, int[] pos, int v, String what) {
        if (pos[0] >= b.length || (b[pos[0]] & 0xFF) != v) {
            throw new IllegalArgumentException("malformed zd schema segment (expected " + what + ")");
        }
        pos[0]++;
    }

    private static String readMapString(byte[] b, int[] pos, String expectedKey) {
        String k = ZdPrimitives.decodeString(b, pos);
        if (!k.equals(expectedKey)) {
            throw new IllegalArgumentException("malformed zd schema segment (expected key " + expectedKey + ", got " + k + ")");
        }
        return k;
    }

    private static int readArrayLen(byte[] b, int[] pos) {
        if (pos[0] >= b.length) {
            throw new IllegalArgumentException("truncated zd schema array header");
        }
        int tag = b[pos[0]++] & 0xFF;
        if (tag >= 0x90 && tag <= 0x9F) {
            return tag & 0x0F;
        }
        if (tag == 0xDC) {
            int n = ((b[pos[0]] & 0xFF) << 8) | (b[pos[0] + 1] & 0xFF);
            pos[0] += 2;
            return n;
        }
        if (tag == 0xDD) {
            long n = ZdPrimitives.readBe32(b, pos[0]);
            pos[0] += 4;
            return (int) n;
        }
        throw new IllegalArgumentException("malformed zd schema array header: 0x" + Integer.toHexString(tag));
    }

    /** utf8 便捷（供外部构建器复用）。 / utf8 convenience for external builders. */
    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
