package org.tielang.zd;

import java.util.ArrayList;
import java.util.List;

/**
 * zd 树值模型：wire2 记录层的树形态（与 tie 的 td 表同构）——kind 0 = 表/数组
 * （具名条目 + 裸元素，具名在前）、1 = 字符串、2 = 整数与 bool（bool 折叠 0/1）、
 * 3 = 浮点。图容器节点载荷、属性 map、树级往返都以此为准。
 * <p>
 * The zd tree value model: the tree shape of the wire2 record layer (isomorphic to
 * the tie td table) — kind 0 = table/array (named entries + bare elements, named
 * first), 1 = string, 2 = integer &amp; bool (bool folded to 0/1), 3 = float. Graph
 * node payloads, attribute maps and tree-level round-trips all use this model.
 */
public sealed interface ZdNode permits ZdNode.Scalar, ZdNode.Table {

    /** 标量类型。 / Scalar kinds. */
    enum Kind { STRING, INT, FLOAT, BOOL }

    /** 标量节点（四类之一）。 / A scalar node (one of the four kinds). */
    record Scalar(Kind kind, String str, long i, double f, boolean b) implements ZdNode {

        /** 字符串标量。 / A string scalar. */
        public static Scalar str(String value) {
            return new Scalar(Kind.STRING, value == null ? "" : value, 0L, 0.0, false);
        }

        /** 整数标量。 / An integer scalar. */
        public static Scalar of(long value) {
            return new Scalar(Kind.INT, "", value, 0.0, false);
        }

        /** 浮点标量。 / A float scalar. */
        public static Scalar of(double value) {
            return new Scalar(Kind.FLOAT, "", 0L, value, false);
        }

        /** 布尔标量（wire 层折叠 0/1）。 / A bool scalar (folded to 0/1 on the wire). */
        public static Scalar of(boolean value) {
            return new Scalar(Kind.BOOL, "", value ? 1L : 0L, 0.0, value);
        }
    }

    /** 表节点：具名条目（{@code keys()} 序）+ 裸元素（具名在前）。 /
     *  A table node: named entries (in {@code keys()} order) + bare elements (named first). */
    final class Table implements ZdNode {

        private final List<String> keys;
        private final List<ZdNode> named;
        private final List<ZdNode> elements;

        private Table(List<String> keys, List<ZdNode> named, List<ZdNode> elements) {
            this.keys = keys;
            this.named = named;
            this.elements = elements;
        }

        /** 具名键列表（构造序）。 / The named keys (in construction order). */
        public List<String> keys() {
            return keys;
        }

        /** 裸元素列表。 / The bare elements. */
        public List<ZdNode> elements() {
            return elements;
        }

        /** 按键取具名条目；无则 null。 / The named entry for the key, or null. */
        public ZdNode get(String key) {
            for (int i = 0; i < keys.size(); i++) {
                if (keys.get(i).equals(key)) {
                    return named.get(i);
                }
            }
            return null;
        }

        /** 是否完全为空（无具名无裸）。 / Whether the table is completely empty. */
        public boolean isEmpty() {
            return keys.isEmpty() && elements.isEmpty();
        }

        /** 总子数 = 具名 + 裸（wire 层 child_count）。 / Total children = named + bare. */
        public int childCount() {
            return keys.size() + elements.size();
        }

        /** 新建构建器。 / A new builder. */
        public static Builder builder() {
            return new Builder();
        }

        /** 表构建器。 / The table builder. */
        public static final class Builder {
            private final List<String> keys = new ArrayList<>();
            private final List<ZdNode> named = new ArrayList<>();
            private final List<ZdNode> elements = new ArrayList<>();

            /** 追加具名条目（同键后者胜出，保持首插序）。 / Appends a named entry (later same-key wins, first-insert order kept). */
            public Builder put(String key, ZdNode value) {
                for (int i = 0; i < keys.size(); i++) {
                    if (keys.get(i).equals(key)) {
                        named.set(i, value);
                        return this;
                    }
                }
                keys.add(key);
                named.add(value);
                return this;
            }

            /** 追加字符串具名条目。 / Appends a named string entry. */
            public Builder put(String key, String value) {
                return put(key, ZdNode.Scalar.str(value));
            }

            /** 追加整数具名条目。 / Appends a named int entry. */
            public Builder put(String key, long value) {
                return put(key, ZdNode.Scalar.of(value));
            }

            /** 追加浮点具名条目。 / Appends a named float entry. */
            public Builder put(String key, double value) {
                return put(key, ZdNode.Scalar.of(value));
            }

            /** 追加布尔具名条目。 / Appends a named bool entry. */
            public Builder put(String key, boolean value) {
                return put(key, ZdNode.Scalar.of(value));
            }

            /** 追加裸元素。 / Appends a bare element. */
            public Builder element(ZdNode value) {
                elements.add(value);
                return this;
            }

            /** 构建（不可变快照）。 / Builds (an immutable snapshot). */
            public Table build() {
                return new Table(List.copyOf(keys), List.copyOf(named), List.copyOf(elements));
            }
        }
    }
}
