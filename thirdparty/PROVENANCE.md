# Third-party components / 第三方组件

This document records what is vendored into this repository, where it came from, and how to re-sync it.

本文档记录本仓库内嵌的第三方代码、来源，以及重新同步的方法。

---

## zd-java / the zd binary codec

| Field / 字段 | Value / 值 |
| --- | --- |
| Source / 来源 | `tie-lang/zd-java` (local: `F:/Projects/tie-repo/zd-java`) |
| Commit / 提交 | `256d8b65a51306e53aa9d66be5f5003b360bc788` (2026-09-29) |
| Declared version / 声明版本 | `0.2.0` |
| License / 许可 | TPL-2.3 — the same license as this project |
| Vendored into / 内嵌位置 | `common/src/main/java/org/tielang/zd/` (18 files), probe at `common/src/test/java/org/tielang/zd/ZdProbe.java` |
| Package / 包名 | `org.tielang.zd` — kept unchanged, so the copy stays byte-identical to upstream |
| Used by / 用途 | the `zd` output format, and reading it back in the round-trip check |

### Why vendored / 为什么内嵌

Not yet published to Maven Central (upstream roadmap item `p.1.8`), and a Minecraft mod cannot ask
users to configure a snapshot repository. The sources are therefore carried in-tree.

尚未发布到 Maven Central（上游 `p.1.8` 才计划发布），而 Minecraft 模组不能要求用户去配 snapshot 仓库，
因此将源码随仓携带。

### Sync procedure / 同步流程

The copy is byte-identical to upstream except `ZdProbe.java`, which is a `main`-bearing probe and
therefore lives in the test source set rather than the library source set.

内嵌副本与上游逐字节一致，唯一例外是 `ZdProbe.java`（它含 `main`，属于探针，故放在 test 源集而非库源集）。

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

Then update the commit hash in the table above. 随后更新上表的提交号。

### Notes / 说明

* Pure JDK 17+, no third-party dependencies, so vendoring costs nothing in dependency surface.
* `verifyCore` excludes this tree from `-Werror`: an upstream source drop has to stay byte-identical,
  so its warnings are not ours to fix and editing it would break the sync procedure. `compileVendored`
  still builds it, proving it compiles under this project's target.
* `ZdProbe` is the upstream KAT/self-check entry point, retained so the codec's invariants can be
  re-verified here rather than only upstream.

* 纯 JDK 17+、无第三方依赖，内嵌不增加依赖面。
* `verifyCore` 把此树排除在 `-Werror` 之外：上游源码投放须保持逐字节一致，它的警告不该由我们修，改动
  会破坏同步流程；`compileVendored` 仍编译它，以证明在本项目目标下可编译。
* `ZdProbe` 是上游的 KAT 自检入口，保留它使编解码器的不变量可在本仓复核。

---

## td-java / the `tie:data` reader and writer

| Field / 字段 | Value / 值 |
| --- | --- |
| Source / 来源 | `tie-lang/td-java` (local: `F:/Projects/tie-repo/td-java`) |
| Commit / 提交 | `ff0d46494393f2b3cfdc2580893c2b0ab6b4a9f3` (2026-09-27) |
| Declared version / 声明版本 | `0.1.0` |
| License / 许可 | TPL-2.3 — the same license as this project |
| Vendored into / 内嵌位置 | `common/src/main/java/org/tielang/td/` (4 files, 560 lines) |
| Package / 包名 | `org.tielang.td` — kept unchanged, so the copy stays byte-identical to upstream |
| Used by / 用途 | parsing and writing the configuration file (`uee.data.tie`), and the `td` output format |

### Why vendored / 为什么内嵌

Same reason as zd-java: not published to Maven Central, and a mod cannot require a snapshot
repository. 与 zd-java 同因：未发布 Maven Central，模组不能要求用户配 snapshot 仓库。

### Why the reference parser rather than a private one / 为什么用参考实现而非自写

The configuration file has to be readable by tie tooling, so its grammar has to be tie's grammar
exactly — not a UEE-specific dialect that happens to look similar. Using the reference implementation
means the accepted syntax is defined by the ecosystem rather than by us, and removes the risk of a
second parser drifting from the first.

配置文件必须能被 tie 工具链读，所以语法必须**恰好是** tie 的语法，而不是一个长得像的 UEE 方言。用参考
实现意味着语法由生态定义而非我们定义，也消除了第二个解析器与第一个漂移的风险。

### Sync procedure / 同步流程

```sh
SRC=/f/Projects/tie-repo/td-java/src/main/java/org/tielang/td
DST=F:/Projects/uee/common/src/main/java/org/tielang/td
cp "$SRC"/*.java "$DST"/
diff -rq "$SRC" "$DST"   # must be empty
```

### Verified grammar / 已实测的语法

Established empirically against this copy rather than assumed, because the shape of a td document is
easy to get wrong. `ConfigTest` asserts each row, including the rejections, so the constraint is
locked in rather than left to a comment.

下表是对本副本实测得出（而非假定），因为 td 文档的形态很容易弄错。`ConfigTest` 逐行断言，包括两处拒绝，
使约束被锁定而不是只写在注释里。

| Shape / 形态 | Accepted / 接受 |
| --- | --- |
| `type tie<data>` header line | yes / 是（被剥除） |
| `uee = [ ... ]` named table | yes / 是（名字被丢弃；td 文档本身就是一个表） |
| `[ ... ]` bare table | yes / 是 |
| flat `key = value` at top level | **no / 否** — a td document *is* a table |
| `name = "scalar"` prefix | **no / 否** — only a table value works as the prefix |
| `//` comments, whole-line and trailing | yes / 是 |
| `#` comments | **no / 否** — not part of the syntax, deliberately not added |
| arrays `["a","b"]`, nested tables, ints, floats, bools, strings with escapes | yes / 是 |
