package org.tielang.zd;

import java.io.ByteArrayOutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * zd v3 图容器（语言无关的图序列化，{@code 2026-09-28-zd-v3-design.md} §6）：新段类型
 * （段类型 4），同时可作为 ext 子类型（类型标记 {@code 0x47 'G'}）嵌入任意 zd 值位置。
 * <pre>
 * 紧凑形态 = [声明 1B][节点数 varint][节点表][边数 varint][边表]
 * 列式形态 = [声明 1B, bit2 置位][varint 列式容器长度][列式容器][varint 载荷区长度][载荷区]
 * 声明字节：bit0 有向（0 无向 / 1 有向）；bit1 节点 id 形态（0 varint 索引 / 1 string 显式 id）；
 *          bit2 列式承载（zd-java 钉定，见 {@link #encodeColumnar}）
 * 节点 = [id][载荷标志 1B：0 无 / 1 有][载荷（有则：任意 zd 值）][属性标志 1B：0 无 / 1 有][属性（有则：map）]
 * 边   = [from: 节点引用 varint][to: 节点引用 varint][边头 1B][标签 string（可选）]
 *        [权重 f64 BE（可选）][属性 map（可选）]
 * 边头字节：bit0 有向覆盖（与容器声明异或生效）；bit1 带标签；bit2 带权重；bit3 带属性
 * </pre>
 * 约束：节点 id 唯一；边的 from/to 必须指向存在的节点（编解码双侧校验）；自环与多重边
 * 合法；空图（0 节点 0 边）合法。<b>zd 值嵌入形态（zd-java 钉定）</b>：任意 zd 值 /
 * 属性 map 在值位置嵌入为 {@code varint 行数 + 行数 × 六字段 wire2 记录}（自定界子树，
 * 见 {@link ZdTrees}）。tie 侧图的边与节点操作产生/消费此容器；节点载荷可携带 code 等
 * ext 载荷；大图的节点表与边表按设计案 §6 用<b>列式承载</b>——{@link #encodeColumnar}
 * 以 id 列 delta + 标签列字典承载，{@link #decode} 按声明位 2 自动分派两种形态。
 * <p>
 * The zd v3 graph container (language-agnostic graph serialisation, §6 of the v3
 * design): a new segment type (type 4) that can also ride as an ext subtype (marker
 * {@code 0x47 'G'}) at any zd value position. Structure and edge-head bits as pinned
 * above. Constraints: node ids unique; edge endpoints must reference existing nodes
 * (validated on both encode and decode); self-loops and multi-edges legal; the empty
 * graph legal. <b>zd value embedding (pinned by zd-java):</b> any zd value / attribute
 * map embeds at a value position as {@code varint rowCount + rowCount × six-field
 * wire2 records} (a self-delimiting subtree, see {@link ZdTrees}). Tie-side graph edge
 * and node operations produce/consume this container; node payloads may carry code and
 * other ext payloads; large graphs should carry their node/edge tables through the
 * columnar container + encoding family (callers compose {@link ZdColumnar}).
 */
public final class ZdGraph {

    /** 图容器段字节无前缀标记（段类型 4 载荷即容器字节本身）。 /
     *  The graph container has no prefix (the type-4 segment payload is the container itself). */
    public static final int DECL_DIRECTED = 1;
    /** 声明位 1：节点 id 用 string 显式 id。 / Declaration bit 1: node ids are explicit strings. */
    public static final int DECL_STRING_IDS = 2;
    /**
     * 声明位 2：<b>列式承载</b>（zd-java 钉定，随 KAT 向量集同步 tie-spec 仓）。置位时节点表与
     * 边表按设计案 §6 用列式容器 + 编码族承载（id 列 delta / 标签列字典），形态为
     * {@code [声明 1B][varint 列式容器长度][列式容器][varint 载荷区长度][载荷区]}；清零时
     * 沿用紧凑逐条形态 {@code [声明][节点数][节点表][边数][边表]}。两种形态语义等价，
     * {@link #decode} 按本位自动分派。
     * <p>
     * Declaration bit 2: <b>columnar carriage</b> (pinned by zd-java, to be synced to the
     * tie-spec KAT set). When set, the node and edge tables ride on the columnar container
     * + encoding family per design §6 (delta for the id column, dictionary for the label
     * column): {@code [decl 1B][varint columnar length][columnar container][varint payload
     * length][payload area]}; when clear, the compact per-record form is used. The two
     * forms are semantically equivalent and {@link #decode} dispatches on this bit.
     */
    public static final int DECL_COLUMNAR = 4;

    /** 边头位 0：有向覆盖（与容器声明异或生效）。 / Edge-head bit 0: directed override (XOR with the container declaration). */
    public static final int EDGE_DIRECTED_OVERRIDE = 1;
    /** 边头位 1：带标签。 / Edge-head bit 1: has a label. */
    public static final int EDGE_HAS_LABEL = 2;
    /** 边头位 2：带权重。 / Edge-head bit 2: has a weight. */
    public static final int EDGE_HAS_WEIGHT = 4;
    /** 边头位 3：带属性。 / Edge-head bit 3: has attributes. */
    public static final int EDGE_HAS_ATTRS = 8;

    private ZdGraph() {
    }

    /** 图节点：id（varint 索引形态为 Long、string 形态为 String）+ 任意 zd 载荷 + 可空属性 map。 /
     *  A graph node: id (Long in the varint form, String in the string form) + any zd
     *  payload + optional attribute map. */
    public record GNode(Object id, ZdNode payload, ZdNode attrs) {
    }

    /** 图边：两端节点引用 + 可选有向覆盖 / 标签 / 权重 / 属性（null = 缺省）。 /
     *  A graph edge: endpoint references + optional directed override / label / weight /
     *  attributes (null = absent). */
    public record GEdge(int from, int to, Boolean directedOverride, String label,
                        Double weight, ZdNode attrs) {
    }

    /** 图数据：方向 + id 形态 + 节点表 + 边表。 / The graph data: direction + id form + node table + edge table. */
    public record GraphData(boolean directed, boolean stringIds,
                            List<GNode> nodes, List<GEdge> edges) {
    }

    // ==================== 编码（写侧） / encoding (write side) ====================

    /**
     * 编码图容器字节。约束违约（id 混型 / 重复 / 边引用越界）确定性 IAE。
     * Encodes the graph container bytes. Constraint violations (mixed-type / duplicate
     * ids, out-of-range edge references) are deterministic IAEs.
     */
    public static byte[] encode(GraphData g) {
        validate(g);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int decl = (g.directed() ? DECL_DIRECTED : 0) | (g.stringIds() ? DECL_STRING_IDS : 0);
        out.write(decl);
        ZdPrimitives.writeVarint(out, g.nodes().size());
        for (GNode n : g.nodes()) {
            if (g.stringIds()) {
                writeAll(out, ZdPrimitives.encodeString((String) n.id()));
            } else {
                ZdPrimitives.writeVarint(out, (Long) n.id());
            }
            if (n.payload() == null) {
                out.write(0x00);
            } else {
                out.write(0x01);
                writeValue(out, n.payload());
            }
            if (n.attrs() == null) {
                out.write(0x00);
            } else {
                out.write(0x01);
                writeValue(out, n.attrs());
            }
        }
        ZdPrimitives.writeVarint(out, g.edges().size());
        for (GEdge e : g.edges()) {
            ZdPrimitives.writeVarint(out, e.from());
            ZdPrimitives.writeVarint(out, e.to());
            int head = headByte(e);
            out.write(head);
            if (e.directedOverride() != null) {
                out.write(e.directedOverride() ? 1 : 0);
            }
            if (e.label() != null) {
                writeAll(out, ZdPrimitives.encodeString(e.label()));
            }
            if (e.weight() != null) {
                writeAll(out, ZdPrimitives.be64(Double.doubleToLongBits(e.weight())));
            }
            if (e.attrs() != null) {
                writeValue(out, e.attrs());
            }
        }
        return out.toByteArray();
    }

    /**
     * 以 ext 子类型（标记 0x47）编码图容器——任意 zd 值位置可嵌。
     * Encodes the graph container as the ext subtype (marker 0x47) — embeddable at any
     * zd value position.
     */
    public static byte[] encodeExt(GraphData g) {
        return ZdExt.encode(ZdExt.TAG_GRAPH, encode(g));
    }

    /**
     * 列式承载编码（声明位 2 置位）：节点表与边表按设计案 §6 走列式容器 + 编码族——id 列
     * 升序时自动用 delta（否则回退 plain）、标签列用字典；节点载荷与边属性以稀疏载荷区承载
     * （仅列有值的下标，升序确定性）。
     * <pre>
     * [声明 1B, bit2 置位] [varint 列式容器长度] [列式容器] [varint 载荷区长度] [载荷区]
     * 列式容器 7 列（固定序）：
     *   0 node_id       i64     delta | plain（id 严格升序 → delta，否则 plain）
     *   1 edge_from     i64     plain
     *   2 edge_to       i64     plain
     *   3 edge_label    string  dict（空串 = 无标签）
     *   4 edge_weight   f64     plain（头位无权重时写 0.0，读侧按头位还原 null）
     *   5 edge_dir_ovr  i64     plain（-1 无覆盖 / 0 false / 1 true）
     *   6 edge_head     i64     plain（与紧凑形态同定义）
     * 载荷区 = varint 节点载荷数 + 各 [varint 节点下标(升序) + 值]
     *        + varint 节点属性数 + 各 [varint 节点下标(升序) + 值]
     *        + varint 边属性数   + 各 [varint 边下标(升序) + 值]
     * </pre>
     * 仅支持 varint 索引 id（delta 列只对整型有效——string 显式 id 的图请用紧凑形态，本方法
     * 确定性 IAE）。
     * <p>
     * Columnar carriage (declaration bit 2 set): the node and edge tables ride on the
     * columnar container + encoding family per design §6 — the id column uses delta when
     * the ids ascend (plain otherwise), the label column uses the dictionary; node
     * payloads and edge attributes ride a sparse payload area (only indices that carry a
     * value, ascending, deterministic). See the layout above. Only varint-index ids are
     * supported (the delta column is integer-only — graphs with explicit string ids must
     * use the compact form; this method rejects them deterministically).
     */
    public static byte[] encodeColumnar(GraphData g) {
        validate(g);
        if (g.stringIds()) {
            throw new IllegalArgumentException("zd columnar graph form supports varint-index ids only (string-id graphs use the compact form)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((g.directed() ? DECL_DIRECTED : 0) | DECL_COLUMNAR);

        int n = g.nodes().size();
        long[] ids = new long[n];
        for (int i = 0; i < n; i++) {
            ids[i] = (Long) g.nodes().get(i).id();
        }
        int m = g.edges().size();
        long[] froms = new long[m];
        long[] tos = new long[m];
        String[] labels = new String[m];
        double[] weights = new double[m];
        long[] ovr = new long[m];
        long[] heads = new long[m];
        for (int i = 0; i < m; i++) {
            GEdge e = g.edges().get(i);
            froms[i] = e.from();
            tos[i] = e.to();
            labels[i] = e.label() == null ? "" : e.label();
            weights[i] = e.weight() == null ? 0.0 : e.weight();
            ovr[i] = e.directedOverride() == null ? -1L : (e.directedOverride() ? 1L : 0L);
            heads[i] = headByte(e);
        }
        // 列自动择优：全同值 → RLE；非降序 → delta；否则 plain / auto-pick per column
        List<ZdColumnar.Column> cols = List.of(
                ZdColumnar.Column.ofInts(ids).encoding(pickIntEncoding(ids)),
                ZdColumnar.Column.ofInts(froms).encoding(pickIntEncoding(froms)),
                ZdColumnar.Column.ofInts(tos).encoding(pickIntEncoding(tos)),
                ZdColumnar.Column.ofStrings(labels).encoding(ZdColumnar.ENC_DICT),
                ZdColumnar.Column.ofDoubles(weights).encoding(pickDoubleEncoding(weights)),
                ZdColumnar.Column.ofInts(ovr).encoding(pickIntEncoding(ovr)),
                ZdColumnar.Column.ofInts(heads).encoding(pickIntEncoding(heads)));
        byte[] columnar = ZdColumnar.encode(cols);
        ZdPrimitives.writeVarint(out, columnar.length);
        out.write(columnar, 0, columnar.length);

        // 载荷区（稀疏，下标升序） / payload area (sparse, ascending indices)
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        int pc = 0;
        for (GNode node : g.nodes()) {
            if (node.payload() != null) {
                pc++;
            }
        }
        ZdPrimitives.writeVarint(payload, pc);
        for (int i = 0; i < n; i++) {
            GNode node = g.nodes().get(i);
            if (node.payload() != null) {
                ZdPrimitives.writeVarint(payload, i);
                writeValue(payload, node.payload());
            }
        }
        int ac = 0;
        for (GNode node : g.nodes()) {
            if (node.attrs() != null) {
                ac++;
            }
        }
        ZdPrimitives.writeVarint(payload, ac);
        for (int i = 0; i < n; i++) {
            GNode node = g.nodes().get(i);
            if (node.attrs() != null) {
                ZdPrimitives.writeVarint(payload, i);
                writeValue(payload, node.attrs());
            }
        }
        int ec = 0;
        for (GEdge e : g.edges()) {
            if (e.attrs() != null) {
                ec++;
            }
        }
        ZdPrimitives.writeVarint(payload, ec);
        for (int i = 0; i < m; i++) {
            GEdge e = g.edges().get(i);
            if (e.attrs() != null) {
                ZdPrimitives.writeVarint(payload, i);
                writeValue(payload, e.attrs());
            }
        }
        byte[] pb = payload.toByteArray();
        ZdPrimitives.writeVarint(out, pb.length);
        out.write(pb, 0, pb.length);
        return out.toByteArray();
    }

    /** 列式形态的 ext 编码（标记 0x47）。 / The ext encoding of the columnar form (marker 0x47). */
    public static byte[] encodeColumnarExt(GraphData g) {
        return ZdExt.encode(ZdExt.TAG_GRAPH, encodeColumnar(g));
    }

    /**
     * 整型列自动择优：全同值 → RLE（纯结构图的覆盖列 / 头列极省）、非降序（差分非负）→
     * delta（id 列）、否则 plain。<b>纯表示层</b>——三种编码解码后值域完全一致。
     * Auto-picks the encoding for an integer column: all-equal → RLE (the override / head
     * columns of a pure-structure graph shrink hugely), non-descending (non-negative
     * diffs) → delta (id columns), otherwise plain. <b>A pure representation layer</b> —
     * all three decode to the identical values.
     */
    private static int pickIntEncoding(long[] v) {
        if (allSameInt(v)) {
            return ZdColumnar.ENC_RLE;
        }
        for (int i = 1; i < v.length; i++) {
            if (v[i] < v[i - 1]) {
                return ZdColumnar.ENC_PLAIN;
            }
        }
        return ZdColumnar.ENC_DELTA;
    }

    /** f64 列自动择优：全同位模式 → RLE，否则 plain。 /
     *  Auto-picks for an f64 column: all-equal bit patterns → RLE, otherwise plain. */
    private static int pickDoubleEncoding(double[] v) {
        if (v.length > 1) {
            boolean same = true;
            long b0 = Double.doubleToLongBits(v[0]);
            for (int i = 1; i < v.length; i++) {
                if (Double.doubleToLongBits(v[i]) != b0) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return ZdColumnar.ENC_RLE;
            }
        }
        return ZdColumnar.ENC_PLAIN;
    }

    private static boolean allSameInt(long[] v) {
        if (v.length <= 1) {
            return false;
        }
        for (int i = 1; i < v.length; i++) {
            if (v[i] != v[0]) {
                return false;
            }
        }
        return true;
    }

    /** 图载荷是否为列式形态（声明位 2 置位）。 /
     *  Whether the graph payload uses the columnar form (declaration bit 2 set). */
    public static boolean isColumnarForm(byte[] b, int off, int len) {
        if (off < 0 || len < 1 || off >= b.length) {
            return false;
        }
        return (b[off] & DECL_COLUMNAR) != 0;
    }

    /** 列式形态解码（列式容器 + 稀疏载荷区）。 /
     *  Decodes the columnar form (the columnar container + sparse payload area). */
    private static GraphData decodeColumnar(byte[] b, int[] pos, int end,
                                           boolean directed, boolean stringIds) {
        if (stringIds) {
            throw new IllegalArgumentException("zd columnar graph form supports varint-index ids only");
        }
        long colLen = ZdPrimitives.readVarint(b, pos);
        if (colLen < 0 || colLen > end - pos[0]) {
            throw new IllegalArgumentException("zd columnar graph container overruns the payload");
        }
        List<ZdColumnar.Column> cols = ZdColumnar.decodeV3(b, pos[0], (int) colLen);
        pos[0] += (int) colLen;
        if (cols.size() != 7) {
            throw new IllegalArgumentException("zd columnar graph form expects 7 columns, got " + cols.size());
        }
        for (int i = 0; i < 7; i++) {
            int want = switch (i) {
                case 3 -> ZdColumnar.TY_STRING;
                case 4 -> ZdColumnar.TY_F64;
                default -> ZdColumnar.TY_I64;
            };
            if (cols.get(i).type() != want) {
                throw new IllegalArgumentException("zd columnar graph column " + i + " has the wrong type " + cols.get(i).type());
            }
        }
        long[] ids = cols.get(0).ints();
        long[] froms = cols.get(1).ints();
        long[] tos = cols.get(2).ints();
        String[] labels = cols.get(3).strings();
        double[] weights = cols.get(4).doubles();
        long[] ovr = cols.get(5).ints();
        long[] heads = cols.get(6).ints();
        int n = ids.length;
        int m = froms.length;
        if (tos.length != m || labels.length != m || weights.length != m
                || ovr.length != m || heads.length != m) {
            throw new IllegalArgumentException("zd columnar graph edge columns have mismatched lengths");
        }

        long payLen = ZdPrimitives.readVarint(b, pos);
        if (payLen < 0 || payLen > end - pos[0]) {
            throw new IllegalArgumentException("zd columnar graph payload area overruns the payload");
        }
        int payEnd = pos[0] + (int) payLen;
        ZdNode[] payloads = new ZdNode[n];
        long pCount = ZdPrimitives.readVarint(b, pos);
        if (pCount < 0 || pCount > n) {
            throw new IllegalArgumentException("zd columnar graph node payload count out of range: " + pCount);
        }
        long prev = -1;
        for (long i = 0; i < pCount; i++) {
            long idx = ZdPrimitives.readVarint(b, pos);
            if (idx <= prev || idx >= n) {
                throw new IllegalArgumentException("zd columnar graph node payload index invalid: " + idx);
            }
            prev = idx;
            payloads[(int) idx] = readValue(b, pos, payEnd);
        }
        ZdNode[] nodeAttrs = new ZdNode[n];
        long nAc = ZdPrimitives.readVarint(b, pos);
        if (nAc < 0 || nAc > n) {
            throw new IllegalArgumentException("zd columnar graph node attr count out of range: " + nAc);
        }
        prev = -1;
        for (long i = 0; i < nAc; i++) {
            long idx = ZdPrimitives.readVarint(b, pos);
            if (idx <= prev || idx >= n) {
                throw new IllegalArgumentException("zd columnar graph node attr index invalid: " + idx);
            }
            prev = idx;
            nodeAttrs[(int) idx] = readValue(b, pos, payEnd);
        }
        ZdNode[] attrs = new ZdNode[m];
        long aCount = ZdPrimitives.readVarint(b, pos);
        if (aCount < 0 || aCount > m) {
            throw new IllegalArgumentException("zd columnar graph edge attr count out of range: " + aCount);
        }
        prev = -1;
        for (long i = 0; i < aCount; i++) {
            long idx = ZdPrimitives.readVarint(b, pos);
            if (idx <= prev || idx >= m) {
                throw new IllegalArgumentException("zd columnar graph edge attr index invalid: " + idx);
            }
            prev = idx;
            attrs[(int) idx] = readValue(b, pos, payEnd);
        }
        if (pos[0] != payEnd) {
            throw new IllegalArgumentException("zd columnar graph payload area has trailing bytes");
        }

        List<GNode> nodes = new java.util.ArrayList<>(n);
        Set<Object> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            if (ids[i] < 0 || !seen.add(ids[i])) {
                throw new IllegalArgumentException("zd columnar graph node id invalid or duplicated: " + ids[i]);
            }
            nodes.add(new GNode(ids[i], payloads[i], nodeAttrs[i]));
        }
        List<GEdge> edges = new java.util.ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            long head = heads[i];
            if (froms[i] < 0 || froms[i] >= n || tos[i] < 0 || tos[i] >= n) {
                throw new IllegalArgumentException("zd columnar graph edge references a missing node (from "
                        + froms[i] + ", to " + tos[i] + ", nodes " + n + ")");
            }
            Boolean override = (head & EDGE_DIRECTED_OVERRIDE) != 0
                    ? (ovr[i] == 1L) : null;
            if (override != null && ovr[i] != 0L && ovr[i] != 1L) {
                throw new IllegalArgumentException("zd columnar graph directed override out of range: " + ovr[i]);
            }
            String label = (head & EDGE_HAS_LABEL) != 0 ? labels[i] : null;
            Double weight = (head & EDGE_HAS_WEIGHT) != 0 ? weights[i] : null;
            edges.add(new GEdge((int) froms[i], (int) tos[i], override, label, weight, attrs[i]));
        }
        return new GraphData(directed, false, nodes, edges);
    }

    /** 边头字节（两形态共用）。 / The edge-head byte (shared by both forms). */
    private static int headByte(GEdge e) {
        int head = 0;
        if (e.directedOverride() != null) {
            head |= EDGE_DIRECTED_OVERRIDE;
        }
        if (e.label() != null) {
            head |= EDGE_HAS_LABEL;
        }
        if (e.weight() != null) {
            head |= EDGE_HAS_WEIGHT;
        }
        if (e.attrs() != null) {
            head |= EDGE_HAS_ATTRS;
        }
        return head;
    }

    // ==================== 解码（读侧） / decoding (read side) ====================

    /**
     * 解码图容器字节。畸形 / 约束违约确定性 IAE（含 id 重复与边引用越界）。
     * Decodes the graph container bytes. Malformed / constraint-violating inputs
     * (incl. duplicate ids and out-of-range edge references) are deterministic IAEs.
     */
    public static GraphData decode(byte[] b, int off, int len) {
        if (off < 0 || len < 0 || off > b.length || len > b.length - off) {
            throw new IllegalArgumentException("zd graph container range out of bounds");
        }
        int end = off + len;
        int[] pos = {off};
        int decl = b[pos[0]++] & 0xFF;
        boolean directed = (decl & DECL_DIRECTED) != 0;
        boolean stringIds = (decl & DECL_STRING_IDS) != 0;
        if ((decl & DECL_COLUMNAR) != 0) {
            GraphData g = decodeColumnar(b, pos, end, directed, stringIds);
            if (pos[0] != end) {
                throw new IllegalArgumentException("zd graph container has trailing bytes ("
                        + (end - pos[0]) + " unparsed)");
            }
            return g;
        }
        long nodeCount = ZdPrimitives.readVarint(b, pos);
        if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd graph node count out of range: " + nodeCount);
        }
        java.util.List<GNode> nodes = new java.util.ArrayList<>((int) nodeCount);
        Set<Object> ids = new HashSet<>();
        for (long i = 0; i < nodeCount; i++) {
            Object id = stringIds ? ZdPrimitives.decodeString(b, pos)
                    : (Object) ZdPrimitives.readVarint(b, pos);
            if (!ids.add(id)) {
                throw new IllegalArgumentException("zd graph node id is not unique: " + id);
            }
            ZdNode payload = null;
            int pf = b[pos[0]++] & 0xFF;
            if (pf == 0x01) {
                payload = readValue(b, pos, end);
            } else if (pf != 0x00) {
                throw new IllegalArgumentException("zd graph node payload flag must be 0/1: " + pf);
            }
            ZdNode attrs = null;
            int flag = b[pos[0]++] & 0xFF;
            if (flag == 0x01) {
                attrs = readValue(b, pos, end);
            } else if (flag != 0x00) {
                throw new IllegalArgumentException("zd graph node attrs flag must be 0/1: " + flag);
            }
            nodes.add(new GNode(id, payload, attrs));
        }
        long edgeCount = ZdPrimitives.readVarint(b, pos);
        if (edgeCount < 0 || edgeCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd graph edge count out of range: " + edgeCount);
        }
        java.util.List<GEdge> edges = new java.util.ArrayList<>((int) edgeCount);
        for (long i = 0; i < edgeCount; i++) {
            long from = ZdPrimitives.readVarint(b, pos);
            long to = ZdPrimitives.readVarint(b, pos);
            if (from >= nodeCount || to >= nodeCount) {
                throw new IllegalArgumentException("zd graph edge references a missing node (from " + from + ", to " + to + ", nodes " + nodeCount + ")");
            }
            int head = b[pos[0]++] & 0xFF;
            Boolean override = (head & EDGE_DIRECTED_OVERRIDE) != 0
                    ? (b[pos[0]++] & 0xFF) == 1 : null;
            String label = (head & EDGE_HAS_LABEL) != 0 ? ZdPrimitives.decodeString(b, pos) : null;
            Double weight = null;
            if ((head & EDGE_HAS_WEIGHT) != 0) {
                if (pos[0] + 8 > end) {
                    throw new IllegalArgumentException("zd graph edge weight overruns the container");
                }
                weight = Double.longBitsToDouble(ZdPrimitives.readBe64(b, pos[0]));
                pos[0] += 8;
            }
            ZdNode attrs = (head & EDGE_HAS_ATTRS) != 0 ? readValue(b, pos, end) : null;
            edges.add(new GEdge((int) from, (int) to, override, label, weight, attrs));
        }
        if (pos[0] != end) {
            throw new IllegalArgumentException("zd graph container has trailing bytes ("
                    + (end - pos[0]) + " unparsed)");
        }
        return new GraphData(directed, stringIds, nodes, edges);
    }

    /** 解码 ext 子类型（标记 0x47）为图数据。Decodes the ext subtype (marker 0x47) as graph data. */
    public static GraphData decodeExt(byte[] ext) {
        int[] pos = {0};
        ZdExt.Decoded d = ZdExt.decode(ext, pos);
        if (d.tag() != ZdExt.TAG_GRAPH) {
            throw new IllegalArgumentException("expected graph ext tag 0x47 but got " + d.tag());
        }
        if (pos[0] != ext.length) {
            throw new IllegalArgumentException("graph ext value has trailing bytes");
        }
        return decode(d.payload(), 0, d.payload().length);
    }

    // ==================== 约束校验 / constraint validation ====================

    private static void validate(GraphData g) {
        Set<Object> ids = new HashSet<>();
        for (GNode n : g.nodes()) {
            if (g.stringIds()) {
                if (!(n.id() instanceof String s) || s.isEmpty()) {
                    throw new IllegalArgumentException("zd graph string-id node requires a non-empty String id");
                }
            } else {
                if (!(n.id() instanceof Long l) || l < 0) {
                    throw new IllegalArgumentException("zd graph varint-id node requires a non-negative Long id");
                }
            }
            if (!ids.add(n.id())) {
                throw new IllegalArgumentException("zd graph node id is not unique: " + n.id());
            }
        }
        int n = g.nodes().size();
        for (GEdge e : g.edges()) {
            if (e.from() < 0 || e.from() >= n || e.to() < 0 || e.to() >= n) {
                throw new IllegalArgumentException("zd graph edge references a missing node (from "
                        + e.from() + ", to " + e.to() + ", nodes " + n + ")");
            }
        }
    }

    // ==================== zd 值嵌入 / zd value embedding ====================

    /**
     * zd 值嵌入：{@code varint 行数 + 行数 × 六字段 wire2 记录}（自定界子树）。
     * The zd value embedding: {@code varint rowCount + rowCount × six-field wire2
     * records} (a self-delimiting subtree).
     */
    static void writeValue(ByteArrayOutputStream out, ZdNode value) {
        java.util.List<ZdRow> rows = ZdTrees.flatten(value);
        ZdPrimitives.writeVarint(out, rows.size());
        for (ZdRow r : rows) {
            ZdDocWriter.writeRow(out, r);
        }
    }

    /** 逆嵌入：读自定界子树回 {@link ZdNode}。 / The reverse embedding: reads the self-delimiting subtree back. */
    static ZdNode readValue(byte[] b, int[] pos, int end) {
        long count = ZdPrimitives.readVarint(b, pos);
        if (count < 1 || count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("zd embedded value row count out of range: " + count);
        }
        int save = pos[0];
        int p = save;
        for (long i = 0; i < count; i++) {
            p = skipRecord(b, p, end);
        }
        List<ZdRow> rows = ZdVolume.readRowsRange(b, save, p - save);
        pos[0] = p;
        if (rows.size() != count) {
            throw new IllegalArgumentException("zd embedded value row count mismatch: header " + count + ", parsed " + rows.size());
        }
        return ZdTrees.buildNode(rows);
    }

    /**
     * 跳过一条六字段 wire2 记录：{@link ZdDocWriter#writeRow} 恒写 tag 10/18/26/34/42/50
     * 六字段、顺序固定——逐字段精确消费并校验 tag（嵌入子树由本库写出，形态自洽）。
     * Skips one six-field wire2 record: {@link ZdDocWriter#writeRow} always writes the
     * six fields with tags 10/18/26/34/42/50 in a fixed order — consume exactly six
     * fields with tag verification (embedded subtrees are written by this library, so
     * the form is self-consistent).
     */
    private static int skipRecord(byte[] b, int p, int end) {
        int[] tags = {10, 18, 26, 34, 42, 50};
        for (int tag : tags) {
            int[] q = {p};
            long t = ZdPrimitives.readVarint(b, q);
            long flen = ZdPrimitives.readVarint(b, q);
            if (t != tag) {
                throw new IllegalArgumentException("zd embedded record field tag mismatch (expected " + tag + ", got " + t + ")");
            }
            if (q[0] + flen > end) {
                throw new IllegalArgumentException("zd embedded record overruns the container");
            }
            p = q[0] + (int) flen;
        }
        return p;
    }

    private static void writeAll(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }
}
