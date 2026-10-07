# UEE 自测包 — Universal Element Exporter 0.1.0

## 跑在哪个版本

**Minecraft 1.21.1**，仅此一个版本。

jar 元数据里声明的范围已收紧为 `>=1.21.1 <1.21.2`，所以装到别的版本上会被**加载器直接拒绝**并给出
版本不符的提示，而不是装上去之后神秘崩溃。

四个加载器各自一个 jar：

| 加载器 | jar | 该加载器版本 |
|---|---|---|
| Fabric | `uee-fabric-0.1.0.jar` | Fabric Loader ≥ 0.15 |
| NeoForge | `uee-neoforge-0.1.0.jar` | NeoForge 21.1+（对应 MC 1.21.1） |
| Forge | `uee-forge-0.1.0.jar` | Forge 52+（对应 MC 1.21.1） |
| Quilt | `uee-quilt-0.1.0.jar` | Quilt Loader ≥ 0.26 |

**一个实例只放你实际用的那一个 jar**，不要四个全丢进 `mods/`。

校验和（sha256），供你核对拿到的文件没损坏：

```
47c91b373a9d67088df9056695c3c1b9769d7416b55f7b1148a5f5367a5ed8f8  uee-fabric-0.1.0.jar
30a5838718f042d803ce04ea30918efd21f2d2e200d2dce010b5d193bc905ff3  uee-neoforge-0.1.0.jar
d3e7e46c4761fff0edf6bc9fe4f64a9b1c9e39cc5be0044027bf353f3d6ad609  uee-forge-0.1.0.jar
08afa558acbe210c32be242d4b55ba2a3c796b1ec342023503af5ce01f5a3f4a  uee-quilt-0.1.0.jar
```

**这批 jar 修掉了一个真机故障**（见文末「已修」）。旧的 jar 请删掉再放新的。

> 注意：**改代码后重新构建，这些值就会变**。它们是这次构建的指纹，不是永久标识。

## 安装

把对应 jar 放进实例的 `mods/` 目录，启动游戏。**客户端或服务端都能装**，但两者能看到的东西不同（见下）。

## 先做这一件事

进游戏后执行：

```
/uee status
```

它会告诉你模组有没有加载、看到多少东西、当前配置是什么。**如果这条命令不存在，就是没装上。**

## 基本用法

```
/uee export                    启动一次导出（异步，不卡游戏；用 /uee jobs 看进度）
/uee export items,blocks       只导出这些类目（异步）
/uee export sync items         小规模时同步跑完，直接看结果
/uee kinds                     列出所有可用的类目名
/uee listformats               列出所有可用的输出格式
```

**默认输出位置**：`<实例目录>/exports/uee/`

改位置：

```
/uee set output <目录>
```

### 类目

`items` `blocks` `entities` `recipes` `tags` `loot_tables` `advancements` `worldgen` `functions`
`lang` `effects` `fluids` `enchantments` `damage_types` `biomes` `dimensions` `structures` `sounds`
`particles` `attributes` `creative_tabs` `mods` `debug` `namespaces` `dependencies` `conflicts` `mixins`

（单复数都认；`/uee kinds` 给准确清单。）

### 输出格式

`json` `ndjson` `yaml` `toml` `xml` `td` `zd` `wiki`

```
/uee export formats ndjson,json
```

### 长式设置（一次设一项，可随时改）

```
/uee set output <目录>          输出目录
/uee set formats <格式>         输出格式
/uee set namespaces <命名空间>   只导这些命名空间
/uee set skip-mods <模组>       跳过这些模组
/uee set dry-run                只演练、不写文件
/uee set assets                 导出资产（贴图/模型/音效/语言）—— 见下
/uee set no-assets              关掉
/uee set quiet / noisy          安静 / 啰嗦
/uee set icons / no-icons       导出图标（较慢，默认关）
/uee set shards <条数>          每个分片多少条记录
/uee set max-file-mb <MB>       单文件上限
/uee config show                看当前生效的配置
/uee config template            写出带注释的配置模板
/uee config save                把当前生效配置写进实例配置文件
```

## 三件容易踩的事

### 1. 资产只有客户端有

`/uee set assets` 打开后，会**原样拷贝** `assets/<命名空间>/` 下的贴图、模型、音效和语言文件。

但 **资产在 `assets/` 下，专用服务器看不见**（服务器的资源管理器只看得到 `data/`）。
在服务端跑会**明确报告"看不到资产"**，这是正常的，不是坏了——**要导资产请在客户端跑**。

### 2. 默认什么都不自动做

**自动运行默认关**。打开：

```
/uee set auto-run
```

之后每次启动游戏会自动跑一次导出（走后台作业，不卡启动）。

### 3. 设置默认不跨实例保存

**默认每次启动都是干净的。** 如果你在多个整合包之间来回跑，想要"设一次就一直有效"：

```
/uee set persist formats,output,assets
```

`persist` 是一个**白名单**：只有你在这里列出的设置会被记住，并且记在
**实例之外**的一个文件里，所以换整合包、换启动器都不用重设。

那个文件是：`~/.uee/user.ueeuser`（Windows：`C:\Users\<你>\.uee\user.ueeuser`）

- 它跟着**人**走，不跟着实例走，抄到别的机器上继续用即可。
- 想换位置：`/uee set user-dir <目录>`，或设环境变量 `UEE_USER_DIR`。
- 里面也可以放**声明表**（见下）。

## 声明表（老手用）

工具**没有办法**知道你的整合包把东西放在哪些目录——资源 API 不能枚举一个命名空间，
问"全部"是非法路径且拒绝会被吞掉。**只有你知道**，所以由你声明：

```
/uee declare asset-kind <目录名>        额外扫一个资产目录（如 cutscenes）
/uee declare no-asset-kind <目录名>     去掉
/uee declare asset-root-file <文件名>   额外取一个命名空间根下的文件
/uee declare no-asset-root-file <名>    去掉
/uee declare list                      看声明了什么
/uee declare where                     看这些声明存在哪
```

声明存在持久化文件里，跟着你走。**加错了没代价**（多扫一个空目录而已），
**漏了才有代价**（静默丢文件），所以可以放心积累。

## 配置优先级（从低到高）

```
内置默认  <  持久化文件（~/.uee）  <  实例配置文件  <  命令行
```

越具体的越优先。实例配置文件在 `<实例目录>/config/uee.data.tie`，可以用 `/uee config template` 生成。

## 出问题时请带上这些

```
/uee status                      基本状态
/uee config show                 当前生效配置
/uee debug                       环境、类加载器、注册顺序、注册表规模、模组列表
/uee jobs                        作业历史与失败原因
```

`/uee debug` 的输出是排障时最有用的东西——特别是**类加载器链**和**注册顺序**，
"某个模组的类找不到"这类问题基本都在那里。

## 语言

**默认跟随游戏**：模组自己的消息会按**客户端选择的语言**显示，每个玩家各看各的，服务器不用配任何东西。

想强制成一种语言（例如让日志和屏幕一致）：

```
/uee set language zh_cn
/uee set language            # 不带参数 = 恢复「跟随游戏」
```

★ 填一个**没有消息**的语言会被**拒绝**并列出可用语言——因为静默回退到英文会让人以为设置生效了。
★ 目前随包提供 **`en_us`** 与 **`zh_cn`**，**全部 182 条消息都已翻译**（含 status、帮助、各类列表、声明表、配置文件相关的全部输出）。命令字面量、类目名、格式名、配置键名**不翻译**（它们是机器词汇，
同一个词必须同时能在文件、命令和 API 里用）。

## 语言

**默认跟随游戏**：模组自己的消息会按**客户端选择的语言**显示，每个玩家各看各的，服务器不用配任何东西。

想强制成一种语言（例如让日志和屏幕一致）：

```
/uee set language zh_cn
/uee set language            # 不带参数 = 恢复「跟随游戏」
```

★ 填一个**没有消息**的语言会被**拒绝**并列出可用语言——因为静默回退到英文会让人以为设置生效了。
★ 目前随包提供 **`en_us`** 与 **`zh_cn`**。命令字面量、类目名、格式名、配置键名**不翻译**（它们是机器词汇，
同一个词必须同时能在文件、命令和 API 里用）。

## 路径可以点击复制

消息里的路径**点一下会复制到剪贴板**（悬停显示「copy / 复制」），然后你可以粘到文件管理器里。

> 早先版本里路径的点击是「打开文件」——而**服务端发的聊天消息不允许这个动作**，于是**整条消息都发不出去**，
> 客户端只显示"无法发送聊天信息，请检查服务器日志"。现已改为允许的动作，并加了断言：消息必须**真的能编码**。

## 已知未在真机验证的部分

- **图标渲染**（`/uee set icons`）：代码就位、逻辑有测试，但**没有在真实客户端上跑过**。
  第一次开它建议配 `/uee set dry-run` 先看会不会炸。
- **自动运行**：走作业提交，逻辑有测试，但**没有在真实启动流程上跑过**。

## 已修（真机反馈）

**现象**：`/uee status` 里带路径的那一行不显示，客户端提示「无法发送聊天信息，请检查服务器日志」；
服务器日志里是 `Failed to encode packet 'clientbound/minecraft:system_chat'`。

**原因**：路径上挂的点击动作是 `OPEN_FILE`，而它是**唯一**不被允许由服务端发送的点击动作。
拒绝不是局部的——**整条消息编码失败**，所以带路径的提示一条都发不出来。

**影响范围**：只是"回话"。**命令本身照常执行**，导出也确实写出来了（那一次是 174 个文件、8394 条记录）。
**没有数据损失**。

**修法**：改用 `COPY_TO_CLIPBOARD`（点击复制路径），并且**是问 API 要答案而不是写死名字**——
`ClickEvent.Action.isAllowedFromServer()`，因为这个允许列表**随加载器而变**
（有一个加载器打补丁让 `OPEN_FILE` 在集成服务器上可用，所以这个 bug 在某个加载器上"看起来是好的"）。
另加了一条断言：**消息必须真的能用网络用的那个编解码器编码成功**。
