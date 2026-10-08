package org.tielang.zd;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 树 ⇄ 行 平铺/逆推（DFS 先序，与 {@code tdzd.flatten} / profiler 语义一致：kind 0 = 表/
 * 数组、1 = 字符串、2 = 整数与 bool、3 = 浮点，bool 折叠 0/1；child = 具名条目 + 裸元素数，
 * 子序 = 先具名后裸元素）。
 * <p>
 * Tree ⇄ rows flattening / reconstruction (DFS preorder, matching {@code tdzd.flatten} /
 * the profiler semantics: kind 0 = table/array, 1 = string, 2 = integer &amp; bool, 3 =
 * float; bool folded to 0/1; child = named entries + bare elements; child order = named
 * then bare).
 */
public final class ZdTrees {

    private ZdTrees() {
    }

    /**
     * 用显式栈按 DFS 先序把一棵 {@link ZdNode} 树平铺为 {@link ZdRow} 列表。
     * Flattens a {@link ZdNode} tree into a list of {@link ZdRow} in DFS preorder with
     * an explicit stack.
     */
    public static List<ZdRow> flatten(ZdNode root) {
        List<ZdRow> rows = new ArrayList<>();
        Deque<ZdNode> nodes = new ArrayDeque<>();
        Deque<String> keys = new ArrayDeque<>();
        nodes.push(root);
        keys.push("");
        while (!nodes.isEmpty()) {
            ZdNode node = nodes.pop();
            String key = keys.pop();
            if (node instanceof ZdNode.Table t) {
                List<String> named = t.keys();
                List<ZdNode> elems = t.elements();
                long child = (long) t.childCount();
                rows.add(new ZdRow(0, key, 0L, 0.0, "", child));
                // Push children in reverse so the first desired child pops first.
                for (int i = t.childCount() - 1; i >= 0; i--) {
                    if (i < named.size()) {
                        String k = named.get(i);
                        nodes.push(t.get(k));
                        keys.push(k);
                    } else {
                        nodes.push(elems.get(i - named.size()));
                        keys.push("");
                    }
                }
            } else {
                rows.add(scalarRow(key, (ZdNode.Scalar) node));
            }
        }
        return rows;
    }

    /**
     * 由行列表逆推树：根必须是表（kind 0），否则确定性 IAE。
     * Reconstructs the tree from rows; the root must be a table (kind 0), otherwise a
     * deterministic IAE.
     */
    public static ZdNode unflatten(List<ZdRow> rows) {
        ZdNode root = buildNode(rows);
        if (root instanceof ZdNode.Table t) {
            return t;
        }
        throw new IllegalArgumentException("zd root is not a table (scalar root)");
    }

    /**
     * 由行列表逆推任意根的值（标量根合法——图容器节点载荷等值位置用）。
     * Reconstructs a value with any root kind (scalar roots legal — for value positions
     * like graph node payloads).
     */
    public static ZdNode buildNode(List<ZdRow> rows) {
        int[] idx = {0};
        return build(rows, idx);
    }

    private static ZdNode build(List<ZdRow> rows, int[] idx) {
        ZdRow r = rows.get(idx[0]++);
        switch (r.kind()) {
            case 1:
                return ZdNode.Scalar.str(r.valueStr() == null ? "" : r.valueStr());
            case 2:
                return ZdNode.Scalar.of(r.valueI64());
            case 3:
                return ZdNode.Scalar.of(r.valueF64());
            default: {
                int n = (int) r.childCount();
                ZdNode.Table.Builder b = ZdNode.Table.builder();
                for (int i = 0; i < n; i++) {
                    ZdRow childRow = rows.get(idx[0]); // peek for the child's key
                    ZdNode childVal = build(rows, idx);
                    String ck = childRow.key();
                    if (ck != null && !ck.isEmpty()) {
                        b.put(ck, childVal);
                    } else {
                        b.element(childVal);
                    }
                }
                return b.build();
            }
        }
    }

    private static ZdRow scalarRow(String key, ZdNode.Scalar s) {
        return switch (s.kind()) {
            case STRING -> new ZdRow(1, key, 0L, 0.0, s.str(), 0);
            case INT -> new ZdRow(2, key, s.i(), 0.0, "", 0);
            case FLOAT -> new ZdRow(3, key, 0L, s.f(), "", 0);
            case BOOL -> new ZdRow(2, key, s.b() ? 1L : 0L, 0.0, "", 0);
        };
    }
}
