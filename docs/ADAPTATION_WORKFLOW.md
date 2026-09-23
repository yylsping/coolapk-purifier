# 新版本适配工作流（ADAPTATION_WORKFLOW）

本文档面向维护者，说明当酷安发布新版本时，如何为 `MANIFEST_EXACT` 功能
（D1 帖子内推广、D2 回复区赞助、D3 同话题动态、D5 话题与机型推荐、
D6 自动评论提示、RELATED_DATA 帖子相关推荐）生成并验证新的 manifest profile。

## 架构速览

| 功能 | 解析来源 | 未知宿主版本行为 |
| --- | --- | --- |
| 开屏（SPLASH）、首页信息流（FEED_SPONSOR） | `DYNAMIC_TRUSTED`（成熟的运行时 resolver + DexKit 缓存） | 照常走自身动态策略 |
| D1 / D2 / D3 / D5 / D6 / RELATED_DATA | `MANIFEST_EXACT`（`assets/coolapk_target_manifest.json` 中按 versionCode 精确命中的 validated profile） | **fail-closed**：不安装对应 Hook，`InstallResult.UNSUPPORTED_VERSION`，不做 nearest fallback，不做模糊 DexKit 猜测 |

manifest 解析本身也是 fail-closed 的：schema 不符、JSON 损坏、versionCode
缺失/重复、target 缺关键字段，都会让整个 manifest（或整个 profile）不可
用，而不是静默降级为部分可用。`status=draft` 的 profile 永远不会进入生
产安装路径。

## 重要前提：酷安是壳 APK

酷安安装包内的 `classes.dex` 只有壳 stub（约 30 个类），业务代码在运行时
解密加载。因此 offline adapter 的输入**不能直接用原始 APK**，必须使用运
行时 dump 出的业务 dex：

1. 用 `build/ra-tools/dump_dex.py`（或等效手段）在真机上 dump 运行中的酷
   安业务 dex；注意目标类可能分布在多个运行时 chunk（例如 holder 父类
   `i5` 与 holder 本身在不同 chunk），建议把多份 dump 放进同一个目录。
2. adapter 支持三种输入：APK/ZIP、单个 `.dex`、包含多个 `.dex` 的目录。

## 适配步骤

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
`manifest-dynamic-goal-v4.2.md` §20 执行实机回归（冷启动、八功能、
toggle 双向、fail-closed 日志取证）。

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
