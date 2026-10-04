package org.tielang.zd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * zd-java 确定性探针（纯 JDK，同输入同结果；exit 0 = PASS / exit 1 = FAIL，无时序、无
 * 随机）。覆盖：头与 flags、索引 footer、行/树往返、列式编码族、字符串池与字典引用、
 * schema 三态与 schema_id（tsha1f KAT 钉死，权威源 = tiec {@code tests/tsha_probe/
 * gen_tsha1_core.py}）、标准类型三件、图容器（段 + ext）、多段文档与压缩载荷段。
 * <p>
 * The zd-java deterministic probe (pure JDK, same input → same result; exit 0 = PASS /
 * exit 1 = FAIL, no timing, no randomness). Covers: header &amp; flags, the index footer,
 * row/tree round-trips, the columnar encoding family, the string pool &amp; dictionary
 * references, the schema three states &amp; schema_id (tsha1f KATs pinned; the authority
 * = tiec {@code tests/tsha_probe/gen_tsha1_core.py}), the three standard types, the
 * graph container (segment + ext), and the multi-segment document &amp; compressed
 * segments.
 */
public final class ZdProbe {

    private static int failures = 0;
    private static int checks = 0;

    private ZdProbe() {
    }

    /** 探针入口。 / The probe entry point. */
    public static void main(String[] args) {
        headerChecks();
        footerChecks();
        docChecks();
        columnarChecks();
        poolChecks();
        schemaChecks();
        standardChecks();
        graphChecks();
        graphColumnarChecks();
        segmentsChecks();
        tsha1fChecks();
        if (failures > 0) {
            System.out.println("[ZdProbe] FAIL: " + failures + " assertion(s) of " + checks);
            System.exit(1);
        }
        System.out.println("[ZdProbe] PASS (" + checks + " checks)");
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("[FAIL] " + what);
        }
    }

    // ==================== 1. 头与 flags ====================

    private static void headerChecks() {
        byte[] v2 = ZdHeader.writeV2(0);
        byte[] v3 = ZdHeader.writeZ(0);
        check("v2 头逐字节 TIEDBZD 00 02 00", v2.length == 10 && v2[7] == 0x00 && v2[8] == 0x02 && v2[9] == 0x00);
        check("v3 头逐字节 TIEDBZD 00 03 00", v3.length == 10 && v3[7] == 0x00 && v3[8] == 0x03 && v3[9] == 0x00);
        check("v3 掩码低 7 位（bit5/bit6 可置）", (ZdHeader.writeZ(0xFF)[9] & 0xFF) == 0x7F);
        check("v2 掩码低 5 位（bit5/bit6 不可置）", (ZdHeader.writeV2(0xFF)[9] & 0xFF) == 0x1F);
        check("isZd 接受 v2+v3（读义务）", ZdHeader.isZd(v2) && ZdHeader.isZd(v3));
        check("parseVersion 2/3", ZdHeader.parseVersion(v2, 0) == 2 && ZdHeader.parseVersion(v3, 0) == 3);
        boolean threwV1 = false;
        try {
            ZdHeader.write(1, 0);
        } catch (IllegalArgumentException e) {
            threwV1 = true;
        }
        check("写侧禁写 v1（确定性拒绝）", threwV1);
        byte[] v1 = {'T', 'I', 'E', 'D', 'B', 'Z', 'D', 0x01, 0x00};
        check("v1 头 isZd 拒绝", !ZdHeader.isZd(v1));
    }

    // ==================== 2. 索引 footer ====================

    private static void footerChecks() {
        List<ZdFooter.Segment> segs = List.of(
                new ZdFooter.Segment(ZdFooter.SEG_DATA, 10, 20, ""),
                new ZdFooter.Segment(ZdFooter.SEG_CUSTOM, 30, 5, "extra"));
        byte[] index = ZdFooter.encodeIndex(segs);
        byte[] doc = new byte[35 + index.length + 25];
        System.arraycopy(index, 0, doc, 35, index.length);
        byte[] footer = ZdFooter.write(35, index);
        System.arraycopy(footer, 0, doc, 35 + index.length, 25);
        ZdFooter parsed = ZdFooter.probe(doc);
        check("footer 尾探测解析段表", parsed != null && parsed.segments().size() == 2
                && parsed.segment(ZdFooter.SEG_CUSTOM).name().equals("extra"));
        check("footer 长度 25 与魔数 ZD3FT", ZdFooter.FOOTER_LEN == 25 && doc[35 + index.length] == 'Z');
        boolean threwCrc = false;
        byte[] bad = doc.clone();
        bad[35] ^= 0x01;
        try {
            ZdFooter.probe(bad);
        } catch (IllegalArgumentException e) {
            threwCrc = true;
        }
        check("索引段字节翻转 → crc 拒绝", threwCrc);
        check("无 footer → probe 返回 null", ZdFooter.probe(new byte[40]) == null);
        boolean threwName = false;
        try {
            ZdFooter.encodeIndex(List.of(new ZdFooter.Segment(ZdFooter.SEG_CUSTOM, 0, 0, "")));
        } catch (IllegalArgumentException e) {
            threwName = true;
        }
        check("段类型 6 空名拒绝", threwName);
    }

    // ==================== 3. 行/树往返 ====================

    private static void docChecks() {
        List<ZdRow> rows = List.of(
                new ZdRow(0, "", 0L, 0.0, "", 2),
                new ZdRow(1, "name", 0L, 0.0, "zd", 0),
                new ZdRow(2, "count", 42L, 0.0, "", 0),
                new ZdRow(0, "", 0L, 0.0, "", 1),
                new ZdRow(3, "", 0L, 1.5, "", 0));
        byte[] plain = ZdDocWriter.write(0, rows);
        check("行往返 v3：read(write) 恒等", ZdVolume.readRows(plain).equals(rows));
        byte[] plainV2 = ZdDocWriter.writeV2(0, rows);
        check("行往返 v2：v3 读义务可读", ZdVolume.readRows(plainV2).equals(rows));
        check("v2/v3 正文逐字节一致（仅头不同）",
                Arrays.equals(Arrays.copyOfRange(plain, 10, plain.length),
                        Arrays.copyOfRange(plainV2, 10, plainV2.length)));
        byte[] indexed = ZdDocWriter.writeIndexed(0, rows);
        check("索引读：footer 存在 + 行往返恒等",
                ZdVolume.footer(indexed) != null && ZdVolume.readRows(indexed).equals(rows));
        check("索引布局：index_off 指向索引段起点",
                ZdVolume.footer(indexed).indexOff() == plain.length);

        // 树级往返：TdTable/ZdNode 无 equals —— 字节级恒等断言
        ZdNode.Table root = ZdNode.Table.builder()
                .put("name", "subterra")
                .put("deep", ZdNode.Table.builder()
                        .element(ZdNode.Scalar.of(1))
                        .element(ZdNode.Scalar.of(2.5))
                        .element(ZdNode.Scalar.str("x"))
                        .put("flag", true)
                        .build())
                .element(ZdNode.Scalar.of(-7))
                .build();
        byte[] tree = ZdDocWriter.writeTree(0, root);
        byte[] treeAgain = ZdDocWriter.writeTree(0, ZdVolume.readTree(tree));
        check("树级往返（重建树再写出与原正文逐字节一致）", Arrays.equals(treeAgain, tree));
    }

    // ==================== 4. 列式编码族 ====================

    private static void columnarChecks() {
        List<ZdColumnar.Column> cols = List.of(
                ZdColumnar.Column.ofInts(0, 10, 20, 30, 40).encoding(ZdColumnar.ENC_DELTA),
                ZdColumnar.Column.ofStrings("warrior", "mage", "warrior", "archer", "mage").encoding(ZdColumnar.ENC_DICT),
                ZdColumnar.Column.ofInts(7, 7, 7, 7).encoding(ZdColumnar.ENC_RLE),
                ZdColumnar.Column.ofDoubles(1.5, -2.25),
                ZdColumnar.Column.ofBools(true, false, true));
        byte[] container = ZdColumnar.encode(cols);
        List<ZdColumnar.Column> back = ZdColumnar.decodeV3(container, 0, container.length);
        check("列式往返：列数与类型码恒等", back.size() == 5
                && back.get(0).type() == ZdColumnar.TY_I64
                && back.get(4).type() == ZdColumnar.TY_BOOL);
        check("delta 编码解码值恒等", Arrays.equals(back.get(0).ints(), cols.get(0).ints()));
        check("字典编码解码值恒等", Arrays.equals(back.get(1).strings(), cols.get(1).strings()));
        check("RLE 编码解码值恒等", Arrays.equals(back.get(2).ints(), cols.get(2).ints()));
        check("f64/bool 编码解码值恒等",
                Arrays.equals(back.get(3).doubles(), cols.get(3).doubles())
                        && Arrays.equals(back.get(4).bools(), cols.get(4).bools()));
        byte[] again = ZdColumnar.encode(cols);
        check("列式确定性：同输入同字节", Arrays.equals(container, again));

        boolean threwNeg = false;
        try {
            ZdColumnar.encode(List.of(ZdColumnar.Column.ofInts(5, 3).encoding(ZdColumnar.ENC_DELTA)));
        } catch (IllegalArgumentException e) {
            threwNeg = true;
        }
        check("delta 负差分写侧确定性拒绝", threwNeg);

        boolean threwType = false;
        try {
            ZdColumnar.encode(List.of(ZdColumnar.Column.ofStrings("a").encoding(ZdColumnar.ENC_DELTA)));
        } catch (IllegalArgumentException e) {
            threwType = true;
        }
        check("delta 用在非整型列拒绝", threwType);

        // v2 形态（列头无编码字节）：手工构造 i64 列容器并按 v2 规则解码
        byte[] v2form = stripEncodings(List.of(ZdColumnar.Column.ofInts(1, 2, 3)));
        List<ZdColumnar.Column> v2back = ZdColumnar.decodeV2(v2form, 0, v2form.length);
        check("v2 列头（无编码声明）解码", v2back.size() == 1
                && Arrays.equals(v2back.get(0).ints(), new long[]{1, 2, 3}));
    }

    /** v2 形态构造辅助：直接生成无编码字节容器。 / v2-form helper: builds a container without encoding bytes. */
    private static byte[] stripEncodings(List<ZdColumnar.Column> cols) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(ZdColumnar.PREFIX);
        ZdPrimitives.writeVarint(out, cols.size());
        for (ZdColumnar.Column c : cols) {
            ZdPrimitives.writeVarint(out, c.type());
        }
        for (ZdColumnar.Column c : cols) {
            ZdPrimitives.writeVarint(out, c.length());
            for (int i = 0; i < c.length(); i++) {
                writeAll(out, ZdPrimitives.encI64(c.ints()[i]));
            }
        }
        return out.toByteArray();
    }

    private static void writeAll(java.io.ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    // ==================== 5. 字符串池与字典引用 ====================

    private static void poolChecks() {
        List<String> strings = List.of("ashen_marches", "ember_valley", "ashen_marches");
        ZdPool.Built built = ZdPool.buildDeduped(strings);
        check("池去重：重复串共享槽位", built.refs()[0] == 0 && built.refs()[2] == 0 && built.refs()[1] == 1);
        check("池查序：index 命中", ZdPool.index(built.pool(), "ember_valley") == 1
                && ZdPool.index(built.pool(), "missing") == -1);
        check("池取回：get 恒等", ZdPool.get(built.pool(), 0).equals("ashen_marches")
                && ZdPool.get(built.pool(), 1).equals("ember_valley"));
        byte[] ref = ZdPool.encodeRef(1);
        check("字典引用形态 0xC8 + varint", (ref[0] & 0xFF) == ZdPool.REF_PREFIX && ref.length == 2);
        byte[] stream = new byte[ref.length + 3];
        System.arraycopy(ref, 0, stream, 0, ref.length);
        int[] pos = {0};
        check("字典引用解码取回池串", ZdPool.decodeRef(stream, pos, built.pool()).equals("ember_valley") && pos[0] == ref.length);
    }

    // ==================== 6. schema 三态与 schema_id ====================

    private static void schemaChecks() {
        ZdSchema s1 = ZdSchema.builder().field(1, "kind", ZdSchema.TY_I64)
                .field(2, "key", ZdSchema.TY_STRING).build();
        ZdSchema s2 = ZdSchema.builder().field(2, "key", ZdSchema.TY_STRING)
                .field(1, "kind", ZdSchema.TY_I64).build();
        check("schema_id 内容指纹：同内容同 id（入参序无关）",
                s1.schemaId().equals(s2.schemaId()) && s1.schemaId().length() == 48);

        ZdSchema s3 = s1.add(3, "weight", ZdSchema.TY_F64);
        check("演进后 id 随内容变化", !s3.schemaId().equals(s1.schemaId()));

        ZdSchema s4 = s3.deprecate(2);
        ZdSchema s5 = s4.remove(2);
        check("三态跃迁 active→deprecated→removed",
                s3.field(2).state() == ZdSchema.STATE_ACTIVE
                        && s4.field(2).state() == ZdSchema.STATE_DEPRECATED
                        && s5.field(2).state() == ZdSchema.STATE_REMOVED);
        boolean threwRemoved = false;
        try {
            s1.remove(1);
        } catch (IllegalArgumentException e) {
            threwRemoved = true;
        }
        check("active 直升 removed 拒绝（须经 deprecated）", threwRemoved);
        boolean threwReuse = false;
        try {
            s5.add(2, "key2", ZdSchema.TY_STRING);
        } catch (IllegalArgumentException e) {
            threwReuse = true;
        }
        check("字段号永不复用（removed 编号作废）", threwReuse);
        boolean threwWrite = false;
        try {
            s4.requireWritable(2);
        } catch (IllegalArgumentException e) {
            threwWrite = true;
        }
        check("deprecated 字段写侧禁止", threwWrite);

        // 段编码往返 + id 校验
        byte[] seg = s5.encodeSegment();
        ZdSchema.Decoded d = ZdSchema.decodeSegment(seg, 0);
        check("schema 段往返：字段与 id 恒等", d.next() == seg.length
                && d.schema().schemaId().equals(s5.schemaId())
                && d.schema().field(2).state() == ZdSchema.STATE_REMOVED);
        byte[] tampered = seg.clone();
        for (int i = tampered.length - 1; i >= 0; i--) {
            if (tampered[i] == 'a') {
                tampered[i] = 'b';
                break;
            }
        }
        boolean threwId = false;
        try {
            ZdSchema.decodeSegment(tampered, 0);
        } catch (IllegalArgumentException e) {
            threwId = true;
        }
        check("schema_id 校验：篡改 id 拒绝", threwId);
    }

    // ==================== 7. 标准类型三件 ====================

    private static void standardChecks() {
        ZdStandard.Timestamp ts = new ZdStandard.Timestamp(1789000000L, 123456789);
        check("timestamp 往返（标记 1）", ZdStandard.decodeTimestamp(ZdStandard.encodeTimestamp(ts)).equals(ts));
        boolean threwNano = false;
        try {
            ZdStandard.encodeTimestamp(new ZdStandard.Timestamp(0, 1_000_000_000));
        } catch (IllegalArgumentException e) {
            threwNano = true;
        }
        check("timestamp 纳秒越界拒绝", threwNano);

        ZdStandard.Decimal dec = new ZdStandard.Decimal(true, "314159265", -8);
        check("decimal 往返（标记 2，值 = -3.14159265）",
                ZdStandard.decodeDecimal(ZdStandard.encodeDecimal(dec)).equals(dec));
        boolean threwDigit = false;
        try {
            ZdStandard.encodeDecimal(new ZdStandard.Decimal(false, "12a3", 0));
        } catch (IllegalArgumentException e) {
            threwDigit = true;
        }
        check("decimal 非数字串拒绝", threwDigit);

        ZdStandard.Uuid u = new ZdStandard.Uuid(0x0011223344556677L, 0x8899AABBCCDDEEFFL);
        check("uuid 往返（标记 3，16B RFC 4122 位模式）",
                ZdStandard.decodeUuid(ZdStandard.encodeUuid(u)).equals(u));
        int[] p1 = {0};
        ZdExt.Decoded d1 = ZdExt.decode(ZdStandard.encodeTimestamp(ts), p1);
        int[] p3 = {0};
        ZdExt.Decoded d3 = ZdExt.decode(ZdStandard.encodeUuid(u), p3);
        check("标准类型 ext 载荷：timestamp 12B / uuid 16B",
                d1.tag() == ZdStandard.TAG_TIMESTAMP && d1.payload().length == 12
                        && d3.tag() == ZdStandard.TAG_UUID && d3.payload().length == 16);
    }

    // ==================== 8. 图容器 ====================

    private static void graphChecks() {
        ZdNode.Table attrs = ZdNode.Table.builder().put("tier", "core").build();
        List<ZdGraph.GNode> nodes = List.of(
                new ZdGraph.GNode(0L, ZdNode.Scalar.str("node-a"), attrs),
                new ZdGraph.GNode(1L, ZdNode.Scalar.of(7), null),
                new ZdGraph.GNode(2L, ZdNode.Table.builder().element(ZdNode.Scalar.str("deep")).build(), null));
        List<ZdGraph.GEdge> edges = List.of(
                new ZdGraph.GEdge(0, 1, null, "flows", 1.5, null),
                new ZdGraph.GEdge(1, 1, true, null, null, null),           // 自环 + 有向覆盖
                new ZdGraph.GEdge(0, 1, null, "flows2", null, null));      // 多重边
        ZdGraph.GraphData g = new ZdGraph.GraphData(true, false, nodes, edges);
        byte[] container = ZdGraph.encode(g);
        ZdGraph.GraphData back = ZdGraph.decode(container, 0, container.length);
        check("图往返：声明与表长恒等", back.directed() && !back.stringIds()
                && back.nodes().size() == 3 && back.edges().size() == 3);
        check("图往返：节点载荷与属性恒等", back.nodes().get(0).payload() instanceof ZdNode.Scalar s
                && s.str().equals("node-a")
                && back.nodes().get(0).attrs() instanceof ZdNode.Table t && t.get("tier") != null);
        check("图往返：边可选件恒等（标签/权重/覆盖）",
                back.edges().get(0).label().equals("flows") && back.edges().get(0).weight() == 1.5
                        && back.edges().get(1).directedOverride() == Boolean.TRUE);
        byte[] again = ZdGraph.encode(g);
        check("图确定性：同输入同字节", Arrays.equals(container, again));

        // 空图合法
        ZdGraph.GraphData empty = new ZdGraph.GraphData(false, false, List.of(), List.of());
        byte[] e0 = ZdGraph.encode(empty);
        check("空图合法（0 节点 0 边）", ZdGraph.decode(e0, 0, e0.length).nodes().isEmpty());

        // string id 形态
        ZdGraph.GraphData sg = new ZdGraph.GraphData(false, true,
                List.of(new ZdGraph.GNode("alpha", ZdNode.Scalar.of(1), null),
                        new ZdGraph.GNode("beta", ZdNode.Scalar.of(2), null)),
                List.of(new ZdGraph.GEdge(0, 1, null, null, null, null)));
        byte[] sc = ZdGraph.encode(sg);
        ZdGraph.GraphData sb = ZdGraph.decode(sc, 0, sc.length);
        check("string 显式 id 形态往返", sb.stringIds() && sb.nodes().get(1).id().equals("beta"));

        boolean threwDup = false;
        try {
            ZdGraph.encode(new ZdGraph.GraphData(false, false,
                    List.of(new ZdGraph.GNode(0L, ZdNode.Scalar.of(1), null),
                            new ZdGraph.GNode(0L, ZdNode.Scalar.of(2), null)), List.of()));
        } catch (IllegalArgumentException e) {
            threwDup = true;
        }
        check("节点 id 唯一约束", threwDup);
        boolean threwRef = false;
        try {
            ZdGraph.encode(new ZdGraph.GraphData(false, false,
                    List.of(new ZdGraph.GNode(0L, ZdNode.Scalar.of(1), null)),
                    List.of(new ZdGraph.GEdge(0, 5, null, null, null, null))));
        } catch (IllegalArgumentException e) {
            threwRef = true;
        }
        check("边引用必须指向存在节点", threwRef);

        // ext 子类型形态（0x47）
        byte[] ext = ZdGraph.encodeExt(g);
        check("图容器 ext 形态（标记 0x47）往返", ZdGraph.decodeExt(ext).edges().size() == 3);
    }

    // ==================== 8b. 图容器列式承载（设计案 §6 大图） ====================

    private static void graphColumnarChecks() {
        // 一个能体现列式收益的大图：节点 id 升序（delta 命中）、标签高重复（字典命中）
        List<ZdGraph.GNode> nodes = new ArrayList<>();
        List<ZdGraph.GEdge> edges = new ArrayList<>();
        String[] tier = {"terrain", "climate", "hydro", "vegetation"};
        for (int i = 0; i < 200; i++) {
            ZdNode payload = i % 3 == 0 ? ZdNode.Scalar.of(i) : null;
            ZdNode attrs = i % 5 == 0 ? ZdNode.Table.builder().put("w", i * 2).build() : null;
            nodes.add(new ZdGraph.GNode((long) i, payload, attrs));
        }
        for (int i = 0; i < 400; i++) {
            String label = tier[i % tier.length];
            Double weight = i % 2 == 0 ? 1.0 + i % 7 : null;
            ZdNode attrs = i % 11 == 0 ? ZdNode.Table.builder().put("k", i).build() : null;
            edges.add(new ZdGraph.GEdge(i % 200, (i * 7 + 1) % 200, null, label, weight, attrs));
        }
        ZdGraph.GraphData big = new ZdGraph.GraphData(true, false, nodes, edges);

        byte[] compact = ZdGraph.encode(big);
        byte[] columnar = ZdGraph.encodeColumnar(big);
        check("列式图：声明位 2 置位（isColumnarForm 判别）",
                ZdGraph.isColumnarForm(columnar, 0, columnar.length)
                        && !ZdGraph.isColumnarForm(compact, 0, compact.length));
        check("列式图：含载荷图不劣于紧凑形态", columnar.length < compact.length);

        // 纯结构大图：列式形态的收益面（结构列全走编码族）
        List<ZdGraph.GNode> flatNodes = new ArrayList<>();
        List<ZdGraph.GEdge> flatEdges = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            flatNodes.add(new ZdGraph.GNode((long) i, null, null));
        }
        for (int i = 0; i < 1000; i++) {
            flatEdges.add(new ZdGraph.GEdge(i / 2, (i * 7 + 1) % 500, null, tier[i % tier.length], null, null));
        }
        ZdGraph.GraphData flat = new ZdGraph.GraphData(true, false, flatNodes, flatEdges);
        int flatCompact = ZdGraph.encode(flat).length;
        int flatColumnar = ZdGraph.encodeColumnar(flat).length;
        check("列式图：纯结构大图至少省一半（" + flatCompact + " → " + flatColumnar + "）",
                flatCompact >= flatColumnar * 2);
        check("列式图：纯结构大图语义与紧凑形态等价",
                sameGraph(ZdGraph.decode(ZdGraph.encode(flat), 0, flatCompact),
                        ZdGraph.decode(ZdGraph.encodeColumnar(flat), 0, flatColumnar)));

        ZdGraph.GraphData back = ZdGraph.decode(columnar, 0, columnar.length);
        check("列式图往返：声明 / 表长恒等", back.directed() && !back.stringIds()
                && back.nodes().size() == 200 && back.edges().size() == 400);
        check("列式图往返：节点 id 与顺序恒等",
                back.nodes().get(37).id().equals(37L) && back.nodes().get(199).id().equals(199L));
        check("列式图往返：稀疏载荷恒等",
                ((ZdNode.Scalar) back.nodes().get(3).payload()).i() == 3
                        && back.nodes().get(1).payload() == null);
        check("列式图往返：节点属性恒等（稀疏）",
                back.nodes().get(5).attrs() instanceof ZdNode.Table t5 && t5.get("w") != null
                        && back.nodes().get(1).attrs() == null);
        check("列式图往返：边标签 / 权重（含 null 区分）恒等",
                back.edges().get(0).label().equals("terrain") && back.edges().get(0).weight() != null
                        && back.edges().get(1).label().equals("climate") && back.edges().get(1).weight() == null);
        check("列式图往返：边属性恒等（稀疏）",
                back.edges().get(0).attrs() instanceof ZdNode.Table e0 && e0.get("k") != null
                        && back.edges().get(1).attrs() == null);
        check("列式图确定性：同输入同字节",
                Arrays.equals(columnar, ZdGraph.encodeColumnar(big)));

        // 两形态语义等价：紧凑 → 行级往返 → 列式，值域一致
        ZdGraph.GraphData viaCompact = ZdGraph.decode(compact, 0, compact.length);
        ZdGraph.GraphData viaColumnar = ZdGraph.decode(
                ZdGraph.encodeColumnar(viaCompact), 0, ZdGraph.encodeColumnar(viaCompact).length);
        check("两形态语义等价：边标签 / 权重 / 属性 / 载荷全量对齐",
                sameGraph(viaCompact, viaColumnar));

        // id 非升序 → 自动回退 plain（不回退失败）
        List<ZdGraph.GNode> unordered = List.of(
                new ZdGraph.GNode(9L, ZdNode.Scalar.of(1), null),
                new ZdGraph.GNode(2L, ZdNode.Scalar.of(2), null));
        byte[] un = ZdGraph.encodeColumnar(new ZdGraph.GraphData(false, false, unordered, List.of()));
        ZdGraph.GraphData unBack = ZdGraph.decode(un, 0, un.length);
        check("列式图：id 非升序回退 plain 且往返正确",
                unBack.nodes().get(0).id().equals(9L) && unBack.nodes().get(1).id().equals(2L));

        // 空图 / 有向覆盖 / ext 形态
        byte[] e0 = ZdGraph.encodeColumnar(new ZdGraph.GraphData(false, false, List.of(), List.of()));
        check("列式图：空图合法", ZdGraph.decode(e0, 0, e0.length).nodes().isEmpty());
        List<ZdGraph.GEdge> ovEdges = List.of(new ZdGraph.GEdge(0, 1, true, null, null, null),
                new ZdGraph.GEdge(1, 0, false, null, null, null));
        List<ZdGraph.GNode> ovNodes = List.of(
                new ZdGraph.GNode(0L, null, null), new ZdGraph.GNode(1L, null, null));
        byte[] ov = ZdGraph.encodeColumnar(new ZdGraph.GraphData(false, false, ovNodes, ovEdges));
        ZdGraph.GraphData ovBack = ZdGraph.decode(ov, 0, ov.length);
        check("列式图：有向覆盖 true/false/null 三态恒等",
                ovBack.edges().get(0).directedOverride() == Boolean.TRUE
                        && ovBack.edges().get(1).directedOverride() == Boolean.FALSE);
        check("列式图 ext 形态（标记 0x47）往返",
                ZdGraph.decodeExt(ZdGraph.encodeColumnarExt(big)).edges().size() == 400);

        boolean threwStringId = false;
        try {
            ZdGraph.encodeColumnar(new ZdGraph.GraphData(false, true,
                    List.of(new ZdGraph.GNode("a", null, null)), List.of()));
        } catch (IllegalArgumentException e2) {
            threwStringId = true;
        }
        check("列式图：string 显式 id 确定性拒绝", threwStringId);

        boolean threwTrailing = false;
        byte[] padded = Arrays.copyOf(columnar, columnar.length + 1);
        try {
            ZdGraph.decode(padded, 0, padded.length);
        } catch (IllegalArgumentException e2) {
            threwTrailing = true;
        }
        check("列式图：尾部多余字节拒绝", threwTrailing);
    }

    /** 两图数据语义等价（节点与边的全字段逐项比对）。 /
     *  Structural equality of two graph payloads (field-by-field over nodes and edges). */
    private static boolean sameGraph(ZdGraph.GraphData a, ZdGraph.GraphData b) {
        if (a.directed() != b.directed() || a.stringIds() != b.stringIds()
                || a.nodes().size() != b.nodes().size() || a.edges().size() != b.edges().size()) {
            return false;
        }
        for (int i = 0; i < a.nodes().size(); i++) {
            ZdGraph.GNode x = a.nodes().get(i);
            ZdGraph.GNode y = b.nodes().get(i);
            if (!x.id().equals(y.id()) || !sameValue(x.payload(), y.payload()) || !sameValue(x.attrs(), y.attrs())) {
                return false;
            }
        }
        for (int i = 0; i < a.edges().size(); i++) {
            ZdGraph.GEdge x = a.edges().get(i);
            ZdGraph.GEdge y = b.edges().get(i);
            if (x.from() != y.from() || x.to() != y.to()
                    || !java.util.Objects.equals(x.directedOverride(), y.directedOverride())
                    || !java.util.Objects.equals(x.label(), y.label())
                    || !java.util.Objects.equals(x.weight(), y.weight())
                    || !sameValue(x.attrs(), y.attrs())) {
                return false;
            }
        }
        return true;
    }

    /** 两 zd 值语义等价（字节级：同值 → 同行字节）。 /
     *  Semantic equality of two zd values (byte-level: equal values produce equal rows). */
    private static boolean sameValue(ZdNode a, ZdNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        List<ZdRow> ra = ZdTrees.flatten(a);
        List<ZdRow> rb = ZdTrees.flatten(b);
        return ra.equals(rb);
    }

    // ==================== 9. 多段文档与压缩载荷段 ====================

    private static void segmentsChecks() {
        byte[] data = ZdDocWriter.writeBody(List.of(new ZdRow(2, "x", 9L, 0.0, "", 0)));
        byte[] columnar = ZdColumnar.encode(List.of(ZdColumnar.Column.ofInts(1, 2, 3)));
        ZdSchema schema = ZdSchema.builder().field(1, "kind", ZdSchema.TY_I64).build();
        byte[] graph = ZdGraph.encode(new ZdGraph.GraphData(true, false,
                List.of(new ZdGraph.GNode(0L, ZdNode.Scalar.of(1), null)), List.of()));
        byte[] compressed = ZdSegments.encodeCompressed(ZdSegments.VARIANT_STORE,
                "raw-bytes".getBytes(StandardCharsets.UTF_8));
        List<ZdSegments.Slice> slices = List.of(
                new ZdSegments.Slice(ZdFooter.SEG_DATA, "", data),
                new ZdSegments.Slice(ZdFooter.SEG_COLUMNAR, "", columnar),
                new ZdSegments.Slice(ZdFooter.SEG_SCHEMA, "", schema.encodeSegment()),
                new ZdSegments.Slice(ZdFooter.SEG_GRAPH, "", graph),
                new ZdSegments.Slice(ZdFooter.SEG_COMPRESSED, "", compressed),
                new ZdSegments.Slice(ZdFooter.SEG_STRING_POOL, "", ZdPool.build(List.of("a", "b"))),
                new ZdSegments.Slice(ZdFooter.SEG_CUSTOM, "meta", new byte[]{1, 2, 3}));
        byte[] doc = ZdSegments.assemble(0, slices);
        int flags = ZdHeader.flags(doc, 0);
        check("多段文档：自动置位 bit0/1/4/5/6", (flags & ZdHeader.FLAG_DICTIONARY) != 0
                && (flags & ZdHeader.FLAG_COLUMNAR) != 0
                && (flags & ZdHeader.FLAG_COMPRESSED) != 0
                && (flags & ZdHeader.FLAG_INDEX) != 0
                && (flags & ZdHeader.FLAG_GRAPH) != 0);
        List<ZdSegments.Slice> back = ZdSegments.read(doc);
        check("多段读取：段数与载荷恒等", back.size() == 7
                && Arrays.equals(ZdSegments.payload(back, ZdFooter.SEG_DATA), data)
                && Arrays.equals(ZdSegments.payload(back, ZdFooter.SEG_COLUMNAR), columnar)
                && Arrays.equals(ZdSegments.payload(back, ZdFooter.SEG_CUSTOM, "meta"), new byte[]{1, 2, 3}));
        check("多段读取：schema 段解码 + 图段解码",
                ZdSchema.decodeSegment(ZdSegments.payload(back, ZdFooter.SEG_SCHEMA), 0).schema().field(1).name().equals("kind")
                        && ZdGraph.decode(ZdSegments.payload(back, ZdFooter.SEG_GRAPH), 0,
                        ZdSegments.payload(back, ZdFooter.SEG_GRAPH).length).nodes().size() == 1);
        byte[] again = ZdSegments.assemble(0, slices);
        check("多段确定性：同输入同字节", Arrays.equals(doc, again));
        check("多段文档行级读义务：经索引定位数据体", ZdVolume.readRows(doc).size() == 1);

        ZdSegments.CompressedEnvelope env = ZdSegments.decodeCompressed(
                ZdSegments.payload(back, ZdFooter.SEG_COMPRESSED), 0,
                ZdSegments.payload(back, ZdFooter.SEG_COMPRESSED).length);
        check("压缩载荷段：store 变体往返",
                env.variant() == ZdSegments.VARIANT_STORE
                        && new String(ZdSegments.STORE_ONLY.decompress(env.variant(), env.data()),
                        StandardCharsets.UTF_8).equals("raw-bytes"));
        boolean threwVariant = false;
        try {
            ZdSegments.STORE_ONLY.decompress(ZdSegments.VARIANT_ZSTD, new byte[]{1});
        } catch (IllegalArgumentException e) {
            threwVariant = true;
        }
        check("未知压缩变体确定性拒绝（无内嵌算法）", threwVariant);
        boolean threwDup = false;
        try {
            ZdSegments.assemble(0, List.of(
                    new ZdSegments.Slice(ZdFooter.SEG_CUSTOM, "meta", new byte[]{1}),
                    new ZdSegments.Slice(ZdFooter.SEG_CUSTOM, "meta", new byte[]{2})));
        } catch (IllegalArgumentException e) {
            threwDup = true;
        }
        check("同 (类型, 名称) 段重复拒绝", threwDup);
    }

    // ==================== 10. tsha1f KAT（权威源 gen_tsha1_core.py） ====================

    private static void tsha1fChecks() {
        // 向量由 tiec tests/tsha_probe/gen_tsha1_core.py 生成（模型 f，权威语义源），
        // 清单存于仓内 scripts/tsha1f-kat.txt。
        // Vectors generated by tiec tests/tsha_probe/gen_tsha1_core.py (the f model,
        // the semantic authority); the table lives at scripts/tsha1f-kat.txt.
        java.util.Map<String, byte[]> msgs = new java.util.HashMap<>();
        msgs.put("empty", new byte[0]);
        msgs.put("abc", "abc".getBytes(StandardCharsets.UTF_8));
        msgs.put("schema", "zd-java schema id KAT vector 2026-09-28".getBytes(StandardCharsets.UTF_8));
        msgs.put("A63", "A".repeat(63).getBytes(StandardCharsets.UTF_8));
        msgs.put("B64", "B".repeat(64).getBytes(StandardCharsets.UTF_8));
        msgs.put("C65", "C".repeat(65).getBytes(StandardCharsets.UTF_8));
        msgs.put("fox", "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));
        java.util.Map<String, String> kat = new java.util.HashMap<>();
        kat.put("empty|2", "b3");
        kat.put("abc|2", "dE");
        kat.put("schema|2", "5q");
        kat.put("A63|2", "9n");
        kat.put("B64|2", "b3");
        kat.put("C65|2", "4y");
        kat.put("fox|2", "2c");
        kat.put("empty|8", "5juavlyl");
        kat.put("abc|8", "5FGIxu1J");
        kat.put("schema|8", "4GCrHyvG");
        kat.put("A63|8", "2lgEA952");
        kat.put("B64|8", "5juavlyl");
        kat.put("C65|8", "2jxuq5wo");
        kat.put("fox|8", "2kut5x3j");
        kat.put("empty|48", "2d3GdBwF55vIHExJ95jGyrEmBDd6p9e0DCEnwHbuFqyBB5dC");
        kat.put("abc|48", "g8e3aCsch9EfvLil9l8nq3nbrholGzy7o2HBp4w1E58cwxaa");
        kat.put("schema|48", "86Asgkuz1wyvouEb8j1Khv5lfCgGdw06nqCfaqE08Jywx0mw");
        kat.put("A63|48", "3i6rLv9k2zpd2Gl6jk9aiIAI3d7eDExoynsjysu8tKmtni0A");
        kat.put("B64|48", "7oiFbwb1hirH50k03tK8xKEizizvvAyDkLBbI281tqhKCe1o");
        kat.put("C65|48", "1mabhxJGFtEmfpAhJrJrq5Kq1KhvL5vcFkgazGh6Gbgm4jfK");
        kat.put("fox|48", "53y05m67ubA5J63cFbmzHie1HCFLILpgHEwnBy084Loe4DI4");
        kat.put("empty|69", "1oH8i7u4pxtArqrrFeyLbHf6hrGhLlmbbgIpGJ6g7bDbh2KlEs7p1C8Lk3CFzvfyCviL9");
        kat.put("abc|69", "1I9l33lkj37gaaE5ra9qgeL2E8KqqdD58Kb7umvoHH6tqa4gsKDt8efyrzFoK8l2IFbik");
        kat.put("schema|69", "4DsbBx1B5wupuJHaw3nhkyAvuwnhKuLp0IH3EIwscKa1ftLyt2q7l5h8wbA50av738aid");
        kat.put("A63|69", "5ynvsvk5po80mjq7Gmhon3Lt0DlvK9ktgDImlsJBFbz4bwJjy4irKIoImfJG79hq3ut3h");
        kat.put("B64|69", "4LzhaqgftLGihfvpGfe7m6eEw0J1rtccwiuGjo3478gIFgx2Fo9afuJpAtbkfd4HFjzm9");
        kat.put("C65|69", "4f8fItcey6FuhplyFr3lDKjmw1axhu3FuzHEvkaLmlLt3vFIj4GD9fLJv5Hafnip7j6jD");
        kat.put("fox|69", "4kAgId5m46o3eJCa1wHIFLosHdjLmLxbtwGdbz0imLIB6vteib3hzas8FaGgbmAuDar4H");
        kat.put("empty|92", "7bdffIpk6uordzy4xLrG325216K9g7vojhcLl8chds8KtrkKzr3ga5G6aycxFaI40jwHnjnnjE3BqeAnayIqoFGyGGGC");
        kat.put("abc|92", "9xLIfmocG7IBHmlfxsm5oblyAgAF9lrKKCp1Iewkl5lytkmzefv9nvjBw9i8sLGcw2HyihlHk6sd5zLcsILnIdGzmyqA");
        kat.put("schema|92", "2bBdf1rGbrddLsEJI2gksyeCzrylyyq8dCgk7mhLJfdGrgiGw9eu4uj2doBJqqD5FBL2o9EHKzeheEizmgDsp4mddFAl");
        kat.put("A63|92", "1EutcIC2uAjHlutzAKq871aF6zkvBsGegrHbgmb2EApuD5e4yyJ36Dj5DBedrEg6cKnHDGned3DuDcDD5zr3KcnzaaDJ");
        kat.put("B64|92", "4h20sKvkkqBgDD99hB0e9imnwoCKwylHyvvjtcw9hk2ougEaiw1spv15fvDJtFwxdB8znpl1ibrzFe4coI1zpJvDemiC");
        kat.put("C65|92", "BryK3od4IrgnB4t2eKItbe1hgsyi5r64uHhDhB1CGcJCxokgbmK0d4eeskk9sBrazb52vws4Kpiq1l4ne2or4ju83lw0");
        kat.put("fox|92", "14Hd3jkA9q2mzla5c5fjJCg5cjD3awxiFwK65sEqBFnxe0fDkgd2bf93C5hnLicFDxzfh4FiJ38gkulFI61i9L9A1tAy");
        for (java.util.Map.Entry<String, String> e : kat.entrySet()) {
            String[] key = e.getKey().split("\\|", -1);
            check("tsha1f KAT " + e.getKey(), Tsha1f.of(msgs.get(key[0]), Integer.parseInt(key[1])).equals(e.getValue()));
        }
        check("tsha1f n=48 输出恰 48 符号", Tsha1f.of("abc", 48).length() == 48);
        check("tsha1f 非法位长返回空串", Tsha1f.of("abc", 50).isEmpty() && Tsha1f.of("abc", 1).isEmpty());
        check("tsha1f 确定性：同输入同输出",
                Tsha1f.of("zd-java", 48).equals(Tsha1f.of("zd-java", 48)));
        check("tsha1f 雪崩：单字节差 → 摘要不同",
                !Tsha1f.of("alpha", 48).equals(Tsha1f.of("alphb", 48)));
    }

}
