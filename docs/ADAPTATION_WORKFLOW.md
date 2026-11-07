# 新版本适配工作流（ADAPTATION_WORKFLOW）

本文档面向维护者，说明当酷安发布新版本时，如何为 `MANIFEST_EXACT` 功能
（D1 帖子内推广、D2 回复区赞助、D3 同话题动态、D5 话题与机型推荐、
D6 自动评论提示、RELATED_DATA 帖子相关推荐、SEARCH_HOT_WORDS 热门搜索
与热榜热词、SEARCH_HOT_RANKINGS 搜索页热榜）生成并验证新的 manifest
profile。

## 架构速览

| 功能 | 解析来源 | 未知宿主版本行为 |
| --- | --- | --- |
| 开屏（SPLASH）、首页信息流（FEED_SPONSOR） | `DYNAMIC_TRUSTED`（成熟的运行时 resolver + DexKit 缓存） | 照常走自身动态策略 |
| D1 / D2 / D3 / D5 / D6 / RELATED_DATA / SEARCH_HOT_WORDS / SEARCH_HOT_RANKINGS | `MANIFEST_EXACT`（`assets/coolapk_target_manifest.json` 中按 versionCode 精确命中的 validated profile） | **fail-closed**：不安装对应 Hook，`InstallResult.UNSUPPORTED_VERSION`，不做 nearest fallback，不做模糊 DexKit 猜测 |

SEARCH_HOT_WORDS 对应两个 UI target（`searchHotCapsuleUi` 首页热榜热词
chips、`searchHotWordsUi` 搜索页「热门搜索」），SEARCH_HOT_RANKINGS 对应
`searchHotRankingsUi`。三者都是 terminal UI target：offline diff 只比较
UI target，不允许用 hot-search API / ViewModel / getter / shouldShow 之类
上游点代替。

manifest 解析本身也是 fail-closed 的：schema 不符、JSON 损坏、versionCode
缺失/重复、target 缺关键字段、profile 含未知 target key，都会让整个
manifest 不可用，而不是静默降级为部分可用。`status=draft` 的 profile 永
远不会进入生产安装路径。注意两个层级的区别：

- validated profile 的**核心 target**（D1/D2/D3/D5/D6 与 relatedData
  observe-only getter）必须完整，缺失会让整个 manifest parse 失败；
- UI 子类 target（relatedIconListUi / relatedContentUi / 三个搜索 UI
  target）是 feature-level 可选的：缺失不会让 profile 失效，而是对应
  feature/层级单独 fail-closed（`TARGET_MISSING` 或诚实的 `PARTIAL`）。

## 重要前提：酷安是壳 APK

酷安安装包内的 `classes.dex` 只有壳 stub（约 30 个类），业务代码在运行时
解密加载。因此 offline adapter 的输入**不能直接用原始 APK**，必须使用运
行时 dump 出的业务 dex：

1. 用 `build/ra-tools/dump_dex.py`（或等效手段）在真机上 dump 运行中的酷
   安业务 dex；注意目标类可能分布在多个运行时 chunk（例如 holder 父类
   `i5` 与 holder 本身在不同 chunk），建议把多份 dump 放进同一个目录。
2. adapter 支持三种输入：APK/ZIP、单个 `.dex`、包含多个 `.dex` 的目录。

## 适配步骤

当前 generator 扫描 8 个 target key，并不覆盖一个完整 profile 的全部
14 个 target。`detailSponsorUi`、`topicDeviceRecommendUi`、
`autoCommentPrompt`、`relatedData`、`relatedIconListUi`、`relatedContentUi`
仍须分别审查。不要把生成的 draft 直接当作完整 profile，尤其不能漏掉
observe-only 层对应的 UI terminal。16.7.0 的静态与实机验证概况见
[适配说明](ADAPTATION_16_7_0.md)。

2.8.0 为上述五个支持版本分别增加了可选 `bottomNavigation` 目标，各 profile
因此为 15 个目标。导航未纳入 generator，需独立核验原生玻璃布局、重组、
点击和入口恢复；不能只复制到其他版本或放宽 versionCode。16.6.1 使用九参
展示入口和独立的玻璃 panel 契约，16.6.2 及以后使用十五参展示入口。
初始原理见[底栏研究记录](ISSUE_26_BOTTOM_NAVIGATION_16_7_0.md)，跨版本差异
与回归范围见 [2.8.0 适配记录](ADAPTATION_2_8_0.md)。

默认 baseline 是 manifest 中第一个 validated profile。需要与最新已适配
版本比较时，应先导出该版本为独立 manifest，再显式传入 `--baseline`；
`diff --old` 同样使用该独立 manifest，避免实际比较的是更早版本。

### 1. 生成 draft

```bash
./gradlew :tools:manifest-generator:run --args="generate --apk <dex目录或dex文件> --output build/adapter-<version> --version-code <新versionCode> --version-name <新versionName>"
```

输出：

- `adapter-report.txt`：每个 feature 的判定、候选数、新旧 descriptor、
  验证结果、证据摘要；
- `manifest-draft.json`：`status=draft` 的 profile 片段。

### 2. 阅读报告判定

| 判定 | 含义 | 动作 |
| --- | --- | --- |
| `UNCHANGED` | 旧 owner/方法名/shape 仍通过 strict contract | draft 沿用旧 spec |
| `CHANGED` | 唯一候选且 strict contract 通过 | draft 写入候选 spec，仍需人工复核 |
| `AMBIGUOUS` | 多个候选或唯一候选未过 strict contract | target 不写入 draft，人工用 `probe` 定位 |
| `MISSING` | 零候选 | target 不写入 draft，人工排查（可能是 dump 不全） |

即使报告全绿，**也必须人工抽查**：方法名没变不代表语义没变（设计红线）。

### 3. 用 probe 辅助人工定位

```bash
./gradlew :tools:manifest-generator:run --args="probe --apk <dex目录> --class Lfn4;"
```

输出该类在 dex 中的真实 access flags、父类、接口、字段和方法签名。

### 4. 对比新旧 profile

```bash
./gradlew :tools:manifest-generator:run --args="diff --old app/src/main/assets/coolapk_target_manifest.json --new build/adapter-<version>/manifest-draft.json"
```

按 feature 输出 `UNCHANGED / CHANGED / MISSING / NEW`，第一眼看清这次到
底哪几个 Hook 目标变了。

### 5. 人工 promote

- 把验证过的 target 合并进 `app/src/main/assets/coolapk_target_manifest.json`
  的一个新 profile（新 versionCode），**手动**把 `status` 写为
  `validated`；
- 工具永远不会自动 promote，也不会改动已有 validated profile；
- 缺失/存疑的 target 宁可不写入 profile（fail-closed），也不要写入猜
  测值。

### 6. 回归

```bash
./gradlew testDebugUnitTest testCompatibleUnitTest testReleaseUnitTest assembleDebug
```

单元测试会直接读取真实打包的 manifest 资产做契约回归；然后按
`manifest-dynamic-goal-v4.2.md` §20 执行既有十项净化功能的实机回归
（冷启动、toggle 双向、fail-closed 日志取证）。第 11 项“精简底部导航”
另需覆盖五个子选项的全部合法组合、末页保护、点击映射、设置持久化及
原生玻璃表现，具体矩阵见 [2.8.0 适配记录](ADAPTATION_2_8_0.md)。

## Dynamic promotion requirements

一个 `MANIFEST_EXACT` target 只有满足以下**全部**条件，才允许未来改为
`DYNAMIC_TRUSTED`：

1. 至少跨多个独立酷安版本验证；
2. 经过真实 APK / 实机验证，不只是单测；
3. resolver 每个版本均唯一命中；
4. structural verifier 足够强；
5. false positive 的后果可接受；
6. 在混淆名变化时仍能靠语义证据命中；
7. 没有依赖特定壳加载时序才能偶然找到；
8. 动态失败可以安全 fail-closed；
9. 有针对 drift / ambiguity 的单元测试；
10. 维护者明确人工批准晋升。

尤其：**"连续几版 descriptor 没变"不等于成熟动态。**

## 证据分层约定（adapter resolver 设计）

- strong evidence：旧 owner + 旧名字 + 完整 strict contract 仍然通过；
- weak evidence：结构合约候选（签名、父类/接口、字段结构、ctor 形状、
  稳定业务字符串引用、事件 payload 结构）；
- 不把"方法名没变"当成充分证据；
- 不使用用户可控 UI 文案作为唯一判定依据；
- 每个 feature 使用独立 resolver，没有横跨所有业务的巨大 query。

## probe 的 Unicode 安全与消歧模式（16.6.4 适配新增）

混淆名含高位 Unicode（如 U+0788 `ވ`），经 bash/Gradle 传参会按控制台
codepage 损坏。probe 支持以下参数规避与消歧：

- `--class-file <utf8 file>`：从 UTF-8 文件读类 descriptor；
- `--out <utf8 file>`：结果写 UTF-8 文件，避免控制台乱码；
- `--list-prefix <Lcom/example/;>`：按前缀列类；
- `--find-method <(params)ret[,static]>`：按签名全库找方法
  （用于 Compose assembler 类 target）；
- `--find-clinit-layout <layout_name>`：扫全库 clinit，找
  `sget R$layout-><name>` 的类与落点字段（layout → holder 反查）；
- `--find-field-type <Ltype;>`：按字段类型反查持有者
  （用于 callback/内部类链路）；
- `--clinit`：附带 dump 该类 clinit 指令（确认 layout 字段映射）。

以上参数的值均可写 `@<file>` 从 UTF-8 文件读取。

## 16.6.4 教训（写入裁决纪律）

1. **layoutName 消歧不是万能的**：同一 layout 可被多个 holder 共用
   （16.6.4 的 search_hot 同时被 Card 持有者 wne 与 HolderItem 持有者
   e66 引用）。layout 命中 + 结构合约通过后，仍必须核对**数据字段类型**
   （Card vs HolderItem）与运行时判型链路一致，否则 hook 静默不触发。
   offline resolver 已把「holder 必须带 cardClass 类型的实例字段」纳入
   strict contract（缺失时报 WARNING 并移出 strict 候选集，裁决降级为
   AMBIGUOUS 强制人工复核），并有 smali fixture 单测防止回退。
2. **obfuscated 名字禁止手工转写**：manifest 里的 Unicode 名字必须程序化
   搬运并对 codepoint 做断言（曾发生 U+0814 误写 U+0B94 导致
   TARGET_MISSING）。
3. **Android 15 内存布局**：匿名区全部带 `[anon:...]` 名字，dump 脚本过滤
   条件必须接受该前缀；业务 dex 在 rw-p 区且 magic 被整体抹除（区域起点
   即 version 字段），恢复 = 前置 "dex\n" + 按 header file_size 截断。
