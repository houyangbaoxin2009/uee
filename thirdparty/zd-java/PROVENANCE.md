# Third-party components / 第三方组件

This directory records what is vendored into this repository and where it came from.

本目录记录本仓库内嵌的第三方代码及其来源。

## zd-java / Zelda binary codec

| Field / 字段 | Value / 值 |
| --- | --- |
| Source / 来源 | `tie-lang/zd-java` (local: `F:/Projects/tie-repo/zd-java`) |
| Commit / 提交 | `256d8b65a51306e53aa9d66be5f5003b360bc788` (2026-09-29) |
| Declared version / 声明版本 | `0.2.0` |
| License / 许可 | TPL-2.3 — the same license as this project |
| Vendored into / 内嵌位置 | `common/src/main/java/org/tielang/zd/` (18 files), test probe at `common/src/test/java/org/tielang/zd/ZdProbe.java` |
| Package / 包名 | `org.tielang.zd` — kept unchanged, so the copy stays byte-identical to upstream |

### Why vendored / 为什么内嵌

`org.tielang:zd-java` is not yet published to Maven Central (upstream roadmap item `p.1.8`), and a
Minecraft mod cannot ask users to fetch a snapshot repository. The sources are therefore carried
in-tree.

`org.tielang:zd-java` 尚未发布到 Maven Central（上游 `p.1.8` 才计划发布），而 Minecraft 模组不能要求
用户去取 snapshot 仓库，因此将源码随仓携带。

### Sync procedure / 同步流程

The copy is byte-identical to upstream except `ZdProbe.java`, which is a `main`-bearing probe and
therefore lives in the test source set rather than the library source set. To re-sync:

内嵌副本与上游逐字节一致，唯一例外是 `ZdProbe.java`（它含 `main`，属于探针，故放在 test 源集而非
库源集）。重新同步：

```sh
SRC=/f/Projects/tie-repo/zd-java/src/main/java/org/tielang/zd
DST=F:/Projects/uee/common/src/main/java/org/tielang/zd
for f in "$SRC"/*.java; do
  b=$(basename "$f")
  [ "$b" = "ZdProbe.java" ] && continue
  cp "$f" "$DST/"
done
cp "$SRC/ZdProbe.java" F:/Projects/uee/common/src/test/java/org/tielang/zd/
diff -rq "$SRC" "$DST"   # only ZdProbe.java should differ
```

Then update the commit hash in the table above.

随后更新上表的提交号。

### Notes / 说明

* The library is pure JDK 17+ with no third-party dependencies, which is why vendoring costs
  nothing in dependency surface. It compiles under `--release 21` for this project's target.
* `ZdProbe` is the upstream KAT/self-check entry point. It is retained so the codec's invariants can
  be re-verified in this repository rather than only upstream.

* 该库为纯 JDK 17+、无第三方依赖，因此内嵌不会增加任何依赖面；在本项目目标下以 `--release 21` 编译。
* `ZdProbe` 是上游的 KAT 自检入口，保留它使编解码器的不变量可在本仓复核，而不必只依赖上游。
