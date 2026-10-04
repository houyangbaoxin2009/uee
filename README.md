# Universal Element Exporter / 通用元素导出器

效率·性能·轻量·通用

## 目录 / Table of contents

* [简介 / Introduction](#简介--introduction)
* [为什么做 / Why this exists](#为什么做--why-this-exists)
* [支持矩阵 / Support matrix](#支持矩阵--support-matrix)
* [输出格式 / Output formats](#输出格式--output-formats)
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
| Forge | 随后 / follow-up |
| Quilt | 随后 / follow-up |

导出能力按**版本分层**设计，而不是按版本号清单硬编码：

Export capability is designed in **version layers**, never hardcoded against a version list:

* **A 层 · 版本无关 / Layer A — version independent**：模组列表与元数据、类加载器、Mixin、
  依赖图、冲突检测。只读加载器元数据、不调用任何 Minecraft 代码，因此一套代码覆盖全部版本。
* **B 层 · 版本适配 / Layer B — version adapted**：注册表、数据包、资产。通过公共源集加
  版本差异层实现，首发锚定 1.21.x。

## 输出格式 / Output formats

| 格式 / Format | 说明 / Notes |
| --- | --- |
| `json` | 通用交换格式；另支持逐行流式变体 / general interchange; a line-streaming variant is also provided |
| `tie:data` | tie 生态的文本数据格式（td）/ the tie-ecosystem text data format (td) |
| `zd` | tie 生态的二进制序列化格式，列式编码加内容指纹 / the tie-ecosystem binary serialization, columnar with content fingerprints |
| `yaml` | 便于人工阅读与编辑 / for human reading and editing |
| `toml` | 配置风格的结构表达 / configuration-style structure |
| `xml` | 与传统工具链对接 / interop with legacy toolchains |

输出模式可配置：选择格式、选择元素类目、按命名空间或模组过滤、投影字段、设置分片与内存上限。

Output mode is configurable: pick formats and element categories, filter by namespace or mod,
project fields, and set sharding and memory limits.

## 架构 / Architecture

分层为：加载器适配层（自写薄 SPI）· 采集引擎 · 规范化 · 序列化写端 · 触发层。
导出走两阶段流水线：主线程在一帧内完成只读快照，随后由工作线程并行编码并按模组分片落盘。

The layers are: loader adapter (a hand-written thin SPI) · collection engine · normalization ·
serialization writers · triggers. Export runs as a two-phase pipeline: a read-only snapshot
completed within a single frame on the main thread, then parallel encoding and per-mod sharded
output on worker threads.

## 开发状态 / Status

**设计阶段**：架构与契约已定案，尚未进入实现。

**Design stage**: architecture and contracts are settled; implementation has not started.

## License

This work is released under the **Tie Public License, version 2.3 ("TPL-2.3")**.
The full text is available in [LICENSE](LICENSE) at the root of this distribution, and online at
<https://github.com/tie-lang/TPL/blob/main/tpl.txt> (the canonical, always-current text) and
<https://tpl.franj2.top/>.
