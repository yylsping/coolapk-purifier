# Architecture Invariants

`tools/release_invariant_check.py` 检查 release merged manifest、release APK
及其 R8 DEX、生产源码与依赖。设置恢复为宿主内页面后，下列约束继续生效。

## 1. NO_BACKGROUND_WORK

- 模块 APK 不声明 Activity、Provider、Service 或 Receiver，不启动模块进程。
- 用户从酷安原生设置最上方进入配置页。`SettingsHooks` 在宿主进程内创建
  Activity 所属的 Dialog，不替换原生设置 Activity、Fragment 或列表。
- 16.7.0 隐藏“我的”入口时，顶部头像增加长按打开同一配置页的备用入口，
  原有单击行为保留。该 Dialog 仍由宿主 Activity 持有并随其销毁释放。
- 返回时关闭 Dialog 并保留原生设置；Activity 销毁时关闭所属页面；重复进入
  不叠加页面或入口。无需跨应用启动权限、自启动或后台白名单。
- 配置页背景覆盖整个窗口，系统栏与刘海边距施加于页面内部，导航栏区域不得
  透出底层原生设置。进入与返回复用宿主动画资源，并遵循系统过渡动画缩放。
- 业务 hook 由酷安生命周期驱动，无后台服务或常驻通知。
- Gate 的组件 allowlist 为空，禁止后台运行、唤醒、定时任务相关权限。

### 宿主私有文件与迁移

- 设置：酷安 `files/coolapk_purifier_config.json`。
- 解析缓存：酷安 `files/coolapk_purifier_resolver_cache.json`，最多 5 个身份
  条目、总计 1 MiB。诊断仅使用有界内存与框架日志。
- `HostPrivateBlobStore` 限定文件名、原因和大小。同目录临时文件写完并
  `FileDescriptor.sync()` 后原子替换旧快照；失败保留旧文件并回滚开关。
- 宿主文件缺失时，通过 API 102 `getRemotePreferences` 只读迁移旧框架值。
  宿主文件存在后始终优先；损坏配置不回退到陈旧的框架值。
- 远程存储不可用不影响宿主设置；新配置使用默认值。schema 1 → 2 的旧键
  规则保留，历史 `remove_related_data` 不会自动重新开启。
- 移除 libxposed service 依赖、框架偏好写入器、跨应用配置 Activity。
  Gate 检查旧组件和写入接口不会重新引入。
- `HostDataMutationGuard` 记录预期的宿主文件写入、临时文件与 rename，框架
  写入应为 0。旧 `ZERO_HOST_PRIVATE_WRITE` 已由本次存储设计替代。

## 2. NO_PERIODIC_POLLING

禁止无限循环、固定周期心跳、后台轮询和不终止的重试。允许以下有界机制：

| 机制 | 上限与终止条件 |
| --- | --- |
| resolver watchdog | 8 s / 20 s 两个 one-shot；terminal 后清除，不自重排 |
| 设置入口重试 | 0 / 100 / 250 / 500 / 1000 ms，最多 5 次；成功、暂停或销毁即取消 |
| bootstrap retire / recovery toast | 一次性 callback；终态清理 |
| RuntimeDexObserver | loadClass 事件驱动；terminal 时 close + unhook |
| resolver worker / DexKit | 按需执行，terminal 时 shutdown / closeSession |
| BootstrapTrace | 有界内存，终态冻结 |
| 业务 hook | 功能事件回调，关闭时不安装或透传原调用 |

Gate 禁止周期调度、重复 Alarm 与 `while(true)`，并检查 watchdog 恰为两个
one-shot 且不自重排。

## 3. NO_SELF_UPDATE_NETWORKING

- 模块及其注入代码不联网检查版本、不下载或安装 APK，不周期访问远程服务，
  也不借用宿主网络权限替模块联网。
- 模块无 INTERNET / ACCESS_NETWORK_STATE 权限。更新由用户手动完成。
- Gate 同时检查 manifest、生产源码、R8 DEX 和依赖；不能只依赖模块没有网络
  权限，因为注入代码具备宿主 UID 的能力。
- 生产依赖仅允许 libxposed API、compileOnly Kotlin 标准库和 DexKit。
- 2.9.0 的 `hostUpgrade` 目标用于拦截酷安自身的升级链路。仅检查酷安时
  返回宿主 Rx 包装的本地成功空结果；混合列表透传其余应用。它不为模块增加
  联网、下载、安装或后台能力，现有 gate 保持不变。

## Gate 用法

```powershell
python tools/release_invariant_check.py app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml app/build/outputs/apk/release/app-release.apk
```

退出码 0 表示全部通过。脚本只报告，不修改源码。
