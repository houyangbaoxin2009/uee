package org.tielang.zd;

/**
 * zd wire2 记录（p.2.3.1）：一条记录的固定六字段，与字段号
 * 1=kind / 2=key / 3=value_i64 / 4=value_f64 / 5=value_str / 6=child_count 一一对应。
 * kind 0=表/数组、1=字符串、2=整数与 bool（bool 折叠 0/1）、3=浮点。
 * <p>
 * A zd wire2 record (p.2.3.1): the six fixed fields of one record, mapped one-to-one
 * to field numbers 1=kind / 2=key / 3=value_i64 / 4=value_f64 / 5=value_str /
 * 6=child_count. kind 0 = table/array, 1 = string, 2 = integer and bool (bool folded
 * to 0/1), 3 = float.
 *
 * @param kind       节点类型 kind (0..3)，见上 / the node kind (0..3), see above.
 * @param key        具名条目键；裸元素为空串 / named-entry key; "" for a bare element.
 * @param valueI64   整数值（kind 2）或 bool 折叠值 / the integer value (kind 2) or the folded bool.
 * @param valueF64   浮点值（kind 3）/ the float value (kind 3).
 * @param valueStr   字符串值（kind 1）/ the string value (kind 1).
 * @param childCount 子节点数（kind 0）/ the number of children (kind 0).
 */
public record ZdRow(int kind, String key, long valueI64, double valueF64, String valueStr, long childCount) {
}