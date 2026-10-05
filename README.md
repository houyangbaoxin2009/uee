# Universal Element Exporter / 通用元素导出器

效率·性能·轻量·通用

## 目录 / Table of contents

* [简介 / Introduction](#简介--introduction)
* [为什么做 / Why this exists](#为什么做--why-this-exists)
* [支持矩阵 / Support matrix](#支持矩阵--support-matrix)
* [四个接口 / Four interfaces](#四个接口--four-interfaces)
* [输出格式 / Output formats](#输出格式--output-formats)
* [输出布局 / Output layout](#输出布局--output-layout)
* [搜集与分析 / Collection and analysis](#搜集与分析--collection-and-analysis)
* [架构 / Architecture](#架构--architecture)
* [开发状态 / Status](#开发状态--status)
* [License](#license)

## 简介 / Introduction

**UEE 是一个面向 Minecraft 的多加载器元素导出器**：把当前实例里能拿到的模组信息、注册表数据、
数据包内容与游戏资产，按可配置的模式一次性导出为结构化的机器可读产物，供 MC百科编辑等自动化流程消费。

**UEE is a multi-loader element exporter for Minecraft.** It exports mod metadata, registry data,
datapack contents and game assets from the current instance in one configurable pass, as
machine-readable artifacts meant to feed automation such as MC百科 editing workflows.

它不是"某个加载器上的一个小工具"，而是**单一工具覆盖四加载器矩阵**，并把导出性能与内存占用
当作第一约束来设计。

It is not "another small utility for one loader": it is **one tool covering the four-loader matrix**,
designed with export speed and memory footprint as the first-order constraints.

## 为什么做 / Why this exists

现有工具生态是碎片化的：物品、方块、实体、合成表、状态效果、实体动图、数据包、图标，
各自有各自的模组，各自只覆盖一段版本区间与一两个加载器。填一份完整的导入包，编者往往要装好几个模组。

The existing tool landscape is fragmented: items, blocks, entities, recipes, effects, entity
animations, datapacks and icons each have their own mod, each covering only a slice of versions
and one or two loaders. Filling a single complete import package often takes several mods.

UEE 的靶子：工具碎片化 · 加载器与版本矩阵断层 · 隐藏物品导不出 · 图标白底或材质错乱 ·
无崩溃隔离 · 无差量更新 · 提交闭环全手工。

UEE targets: fragmented tooling · gaps in the loader/version matrix · hidden items not exported ·
broken or white-backed icons · no crash isolation · no incremental export · a fully manual
submission loop.

## 支持矩阵 / Support matrix

| 加载器 / Loader | 状态 / Status |
| --- | --- |
| Fabric | 首发 / first release |
| NeoForge | 首发 / first release |
| Forge | 首发 / first release |
| Quilt | 首发 / first release |

导出能力按**版本分层**设计，而不是按版本号清单硬编码：

Export capability is designed in **version layers**, never hardcoded against a version list:

* **A 层 · 版本无关 / Layer A — version independent**：模组列表与元数据、类加载器、Mixin、
  依赖图、冲突检测。只读加载器元数据、不调用任何 Minecraft 代码，因此一套代码覆盖全部版本。
* **B 层 · 版本适配 / Layer B — version adapted**：注册表、数据包、资产。通过公共源集加
  版本差异层实现，首发锚定 1.21.x。

## 四个接口 / Four interfaces

同一套能力有四个入口。四者都汇聚到**同一个已解析的配置对象**，因此一个设置在哪个入口写，
含义都一样。

The same capability has four entry points. All four converge on **one resolved configuration
object**, so a setting means the same thing whichever way it is expressed.

| 接口 / Interface | 入口 / Entry |
| --- | --- |
| 函数 API / function API | `org.uee.Uee` 的静态方法 / the static methods on `org.uee.Uee` |
| 命令 / command | 游戏内 `/uee …` / `/uee …` in game |
| 配置文件 / config file | `<游戏目录>/config/uee.data.tie` |
| 数据驱动 / data-driven | 数据包里的 `data/<命名空间>/uee/` |

数据驱动这一层让**数据包定义新的流程、新的采集对象、新的分析策略**，不必改代码：

The data-driven layer lets a datapack define **new flows, new collection targets and new analysis
strategies** without touching code:

```text
data/<namespace>/uee/flows/<id>.json       一条具名流程 / a named flow
data/<namespace>/uee/targets/<id>.json     一个声明式采集对象 / a declarative collection target
data/<namespace>/uee/analyses/<id>.json    一组声明式分析规则 / declarative analysis rules
```

同时接受 `.td`（tie 生态惯例）与 `.json`（数据包惯例）。同名两者都存在时会报告并取 JSON。

Both `.td` (the tie convention) and `.json` (the datapack convention) are accepted; a name present in
both is reported and the JSON one is used.

**一条流程就是一份具名部分配置** —— 它不新开解析路径，而是作为配置之下的一个分层交给同一个解析器。
所以 `/uee flow wiki export items` 的意思是「用 wiki 流程，但只要物品」。

**A flow is a named partial configuration** — it does not add a resolution path; it is layered
underneath the caller's arguments and resolved by the same resolver. So `/uee flow wiki export items`
means "the wiki flow, but only items".

**边界要讲清楚**：数据包不能新增记录类型（元素模型是编译好的 Java）⇒ 采集对象是「在既有类目里
**选择与投影**」，不是定义新类型。分析策略是一组**固定规则**（require/forbid/together/exclusive/
mixin target/namespace/count），不是通用表达式语言——通用谓词语言会成为随数据文件发布的小型编程
语言，需要求值上限、错误语义与版本策略。

**The boundary, stated rather than worked around**: a datapack cannot add a record type, so a target
selects and projects within an existing category. Analysis strategies are a **fixed rule set**, not a
general expression language — a general predicate language would be a small programming language
shipped inside a data file.

### 全局数据包 / Global datapacks

原版数据包按**世界**存放，所以「每个世界都要用」就得每个世界放一份，而且之后新建的世界不会有。
UEE 因此提供全局数据包：`config/uee/datapacks/` 下的数据包对所有世界生效。

Vanilla datapacks live per world, so using one everywhere means copying it into every world — and a
world created later will not have it. UEE therefore provides global datapacks: packs under
`config/uee/datapacks/` apply to every world.

★ **检测到别人已提供此功能时，UEE 自动关闭自己的并让位**，因为两个模组各自加载同一批数据包会
重复注册：

★ **When another mod already provides this, UEE disables its own and stands down**, because two mods
registering the same packs would double-load:

| 设置 / Setting | 行为 / Behaviour |
| --- | --- |
| `auto`（默认） | 有提供者就让位，并**报告让位给了谁** / stand down if a provider is loaded, and say so |
| `on` | 总是自己提供，即使别人也有 / always provide, even alongside a provider |
| `off` | 从不提供 / never provide |

* 检测按**模组 id**（OpenLoader · Paxi · Global Packs 等）：只有已加载的模组才能注册，所以只
  留下一个空目录不构成让位理由。
* **只让位「加载」，不让位「内容」**：UEE 的数据包定义仍照读，包括别人加载的全局包里的定义。
* 让位时若 UEE 目录里还有数据包，会**额外警告**——那些包没人会加载，而这正是最难自己发现的情形。
* `/uee globalpack` 汇报当前决策与理由。

* Detection is by **mod id**: only a loaded mod can register, so a leftover directory is not a reason
  to stand down.
* Only **loading** is delegated, not content: UEE's definitions are still read from wherever found.
* Packs left in UEE's directory while deferred are **warned about** — nobody would load them, which is
  the hardest case to notice unaided.
* `/uee globalpack` reports the decision and the reason.

配置文件用 **`tie:data`（td）语法**，与 tie 生态其它文件一致；**支持 `//` 注释**，
且生成的配置文件**带逐键说明**——这是选择该格式而非 JSON 的核心原因。

The config file is written in **`tie:data` (td) syntax**, as elsewhere in the tie ecosystem. It
**supports `//` comments**, and a generated config **documents every key in place** — which is the
main reason this format was chosen over JSON.

```text
type tie<data>

uee = [
    // ─── 输出在哪 / where ──────────────────────────────────────────────
    output = "exports/uee"                  // 输出根目录 / output root
    formats = ["json"]                      // 格式可多选 / any number of formats
    kinds = ["common"]                      // 内容类别 / which categories
    analyze = true                          // 是否分析 / run the analysis
    analysis_separate = true                // 分析独立成包 / analysis in its own bundle
]
```

配置文件是**部分描述**：只写想改的键，没写的保持默认。层次为
`内置默认 → 配置文件 → 命令行 → 程序调用`。键名拼错会被**报告**而不是静默忽略。

The file is **partial**: name only the keys you want to change. The layering is
`built-in defaults → config file → command line → programmatic call`. An unrecognised key is
**reported**, never silently ignored.

### 命令 / Commands

```text
/uee                              一键导出：默认设置直接跑
/uee export [类别]                选择导出哪些内容
/uee formats <格式>               选择输出格式
/uee data | analysis              只跑其中一个半场
/uee analyze                      只做检查，不导出
/uee flow <名字>                  运行数据包定义的流程
/uee flows | targets | strategies | datapacks
/uee globalpack                   谁在提供全局数据包
/uee kinds | formats              列出可用词表
/uee status                       加载器、版本、生效默认值
/uee config show|path|save|template|reload
```

一键导出的默认行为：**`json` 格式 + n 个数据包 + 1 个分析包**（n = 所选数据类目数）。

The one-key default is **`json`, n data packages, and one analysis package** (n being the number of
data categories selected).

## 输出格式 / Output formats

| 格式 / Format | 说明 / Notes |
| --- | --- |
| `json` | 通用分组文档 / a grouped document, readable anywhere |
| `ndjson` | 逐行一条，流式友好；百科导入端吃这个形状 / one record per line, streaming; the shape importers consume |
| `wiki` | 百科投影，导入端直接可用 / the projection an importer reads directly |
| `td` | tie 生态的文本数据格式 / the tie-ecosystem text data format |
| `zd` | tie 生态的二进制序列化，列式编码加内容指纹 / the tie-ecosystem binary serialization |
| `yaml` | 便于人工阅读与编辑 / for human reading and editing |
| `toml` | 配置风格的结构表达 / configuration-style structure |
| `xml` | 与传统工具链对接 / interop with legacy toolchains |

可选元素类目：`mods items blocks entities recipes effects fluids enchantments damage_types
biomes dimensions structures sounds particles attributes creative_tabs namespaces dependencies
conflicts mixins`，另有组词 `all` / `data` / `analysis` / `common`。

Available categories, plus the group tokens `all` / `data` / `analysis` / `common`.

## 输出布局 / Output layout

```text
exports/uee/                     数据包 / data bundles
  items/example/example-items.json
  items/minecraft/minecraft-items.json
  recipes/example/example-recipes.json
  ...
exports/uee-analysis/            分析包 / the analysis bundle
  conflicts/_global/_global-conflicts.json
  dependencies/example/example-dependencies.json
  ...
```

三个独立选项控制布局：**分析是否独立成包**、**每个类目是否一个子目录**、**是否按命名空间分片**。
把分析放在旁路，是为了让数据那半可以单独交给下游，不必带着诊断一起走。

Three independent options control this: whether the analysis is its own bundle, whether each
category gets a sub-directory, and whether shards are grouped by namespace. Keeping the analysis
aside lets the data half be handed on by itself, without the diagnostics riding along.

## 搜集与分析 / Collection and analysis

**两者解耦。** 搜集只把观察到的**事实**记录到一个上下文，不知道分析的存在；分析只读这个上下文，
不知道事实是怎么来的。管线把两者接起来，并且可以只跑其中一半。

**The two are decoupled.** Collection records what it saw into a context and knows nothing about
analyses; the analyses read that context and know nothing about how it was gathered. The pipeline
joins them, and can run either half alone.

分析被明确分成两个**阶段**，这不是装饰：

Analyses declare one of two **stages**, and this is not decoration:

| 阶段 / Stage | 需要 / Needs | 例 / Examples |
| --- | --- | --- |
| 搜集前 / pre-collection | 加载器元数据、模组容器 / loader metadata, mod containers | 依赖图与冲突 · 容器可读性 · Mixin 目标 · 命名空间认领 |
| 搜集后 / post-collection | 注册表已被遍历过 / the registry walk to have happened | 孤儿命名空间 · 覆盖度报告 |

一个读注册表观察的检查若在搜集前运行，会报"什么都没发现"——那读起来像一份健康证明，实际是
"没问过"。阶段划分让这种情况不可能发生：没搜集就不跑后置检查。

A check that reads registry observations and runs before collection reports "nothing found", which
reads as a clean bill of health rather than as "not asked". The stage split makes that impossible:
no collection, no post-collection checks.

## 架构 / Architecture

分层为：加载器适配层（自写薄 SPI）· 采集引擎 · 规范化 · 分析模块 · 序列化写端 · 配置层 · 触发层。
导出走两阶段流水线：主线程在一帧内完成只读快照，随后由工作线程并行编码并按分片落盘。

The layers are: loader adapter (a hand-written thin SPI) · collection engine · normalization ·
analysis module · serialization writers · configuration · triggers. Export runs as a two-phase
pipeline: a read-only snapshot within a single frame on the main thread, then parallel encoding and
sharded output on worker threads.

单个元素采集失败**不会中断导出**；失败项单独列出。

One failing element **never aborts the export**; failures are listed separately.

## 开发状态 / Status

**实现中**：核心（模型、SPI、八种写端、两阶段分片流水线、崩溃隔离、分析模块、配置层、数据包层）已落地并通过验证；
四个加载器的构建脚本、适配层、命令树与全局数据包钩子已就位，尚未在联网环境编译过。

**In progress**: the core — model, SPI, eight writers, the two-phase sharded pipeline, crash
isolation, the analysis module, the configuration layer and the datapack layer — is implemented and
verified; the four loaders' build scripts, adapters, command tree and global-pack hooks are in place
and have not yet been compiled against the game.

## License

This work is released under the **Tie Public License, version 2.3 ("TPL-2.3")**.
The full text is available in [LICENSE](LICENSE) at the root of this distribution, and online at
<https://github.com/tie-lang/TPL/blob/main/tpl.txt> (the canonical, always-current text) and
<https://tpl.franj2.top/>.
