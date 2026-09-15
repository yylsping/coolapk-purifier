# Architecture Invariants

本文件固化 coolapk-purifier 的三项架构原则及其工程定义、审计方式与当前审计结论。
这些原则由 `tools/release_invariant_check.py` 作为 release gate 自动检查
（检查对象为 release merged manifest、release APK 及其 R8 DEX、生产源码与生产依赖）。

## 1. NO_BACKGROUND_WORK

工程定义：

- 普通 Coolapk 启动/使用不能为了模块配置或缓存而启动模块 app process。
- 模块 APK 不声明、也不运行：自定义 background Service、Foreground Service、
  JobService、WorkManager Worker、Alarm-driven worker、BOOT_COMPLETED receiver、
  package-change/update receiver、silent custom BroadcastReceiver、
  自定义后台 ContentProvider IPC bridge、常驻 notification。
- `ModuleConfigActivity` 只由用户从 Coolapk 设置页主动进入（导出，但用平台提供的
  `getCallingPackage()` 校验，非 `com.coolapk.market` 调用立即结束）。
- Activity 退出后模块自建 executor 停止（`onDestroy()` 中 `executor.shutdownNow()`）。
  Android 短时保留空闲 cached process 不算后台工作；不使用 `killProcess()`
  追求 pid 立即消失。
- libxposed 官方 `XposedProvider` 是 framework integration surface，予以保留，
  但不被本模块当作后台任务调度器。
- injected Coolapk process 内的业务 Hook 不等于"模块后台进程"。

Release gate（自动）：

- component allowlist：Activity 仅 `ModuleConfigActivity`；Provider 仅
  `io.github.libxposed.service.XposedProvider`；不得存在任何 service/receiver。
- permission denylist：`FOREGROUND_SERVICE`、`RECEIVE_BOOT_COMPLETED`、
  `SCHEDULE_EXACT_ALARM`、`USE_EXACT_ALARM`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`、
  `WAKE_LOCK` 全部禁止出现。
- 依赖未来若自动合并新组件/权限：gate FAIL，需人工审查后才能更新 allowlist。

### libxposed service 102 AAR 审计（v5.4.1）

审计对象：本地 Gradle 缓存 `io.github.libxposed:service:102.0.0` AAR。

- manifest 仅声明 `XposedProvider`（ContentProvider，exported，
  authority `${applicationId}.XposedService`），无 service/receiver/job/权限。
- `XposedProvider` 为普通 `ContentProvider` 子类（query/call 等），
  接收 framework binder 调用。
- classes.jar 全量类列表：`HookedTarget`、`HotReloadResult`、`RemotePreferences`、
  `XposedProvider`、`XposedService`、`XposedServiceHelper` 及内部类。
- 字节码常量池扫描：无 `java/net/`、`javax/net/`、OkHttp、Socket、Timer、
  ScheduledExecutor、AlarmManager、Handler.postDelayed、WorkManager、
  JobScheduler、Service、BroadcastReceiver、DownloadManager 引用。
- 结论：它是 ContentProvider 形态的 framework binder 入口，无网络代码、
  无周期调度器、无 service/receiver/job，符合 framework-owned surface 定位。
  不 fork、不修改官方 AAR。

### 配置页进程生命周期收口（v5.4.1 静态复核）

- `ModuleConfigActivity`：单线程 daemon executor；`onDestroy()` 调用
  `shutdownNow()`；无 Handler/postDelayed；无自建 receiver/service；无网络。
- `ModuleStorageServiceCore`：仅注册 libxposed 官方
  `XposedServiceHelper.OnServiceListener`（static binder listener），
  `awaitService` 有 2500 ms 上限，无无限等待、无重试循环。
- Activity 关闭后不存在本模块自建的在跑 worker 或 pending periodic task。
  若 Android 暂时保留 cached process：无任务执行，记为
  `NO_BACKGROUND_WORK_VIOLATION = false`。

## 2. NO_PERIODIC_POLLING

项目禁止的是：无限循环、固定周期心跳、后台轮询、反复查询更新、
反复查询 RemotePreferences、永不终止的 retry。

允许的有限机制（全部一次性、生命周期绑定、明确终止）：

| surface | delay/cadence | max count | cancel/terminal condition | periodic? | network? |
|---|---|---|---|---|---|
| resolver startup watchdog | 8 s + 20 s 两个 one-shot postDelayed | 2 | terminal 后 `removeCallbacksAndMessages(null)`；`watchdog()` 内 terminal 直接返回（不产生新 session）；不自重排 | no | no |
| settings page injection retry (`PageInjectionRetry`) | 0/100/250/500/1000 ms | 5 次 | 成功即 cancel；Activity pause/destroy cancel；exhaust 后停止不重排 | no | no |
| bootstrap retire delayed action | 一次性短延迟 | 1 | 终态后不再触发；terminal cleanup 清空 mainHandler 队列 | no | no |
| recovery toast window (`RecoveryController`) | 1200 ms one-shot | 1 | 一次性 | no | no |
| RuntimeDexObserver | 事件触发（loadClass） | n/a | terminal `close()` + unhook；无 timer | no | no |
| resolver worker | 单线程 executor | n/a | terminal `worker.shutdown()`；无任务时不运行 | no | no |
| DexKitBridge | 按需打开 | n/a | terminal `closeSession("terminal")` 物理关闭 | no | no |
| diagnostic BootstrapTrace | 启动期有界内存记录 | 有界 | `traceFrozen=true` 后冻结 | no | no |
| 业务 Hook（feed/splash/reply/...） | 事件拦截 | n/a | 配置关闭则不安装；属模块功能本身 | no | no |
| ModuleConfigActivity executor | 用户操作触发 | n/a | `onDestroy()` → `shutdownNow()` | no | no |
| libxposed XposedProvider | framework binder 回调 | n/a | framework 生命周期管理 | no | no |

Release gate（自动）：源码 denylist 命中即 FAIL 并转人工审查：
`scheduleAtFixedRate`、`scheduleWithFixedDelay`、`Timer.scheduleAtFixedRate`、
`PeriodicWorkRequest`、`setRepeating`、`setInexactRepeating`、`while(true)`；
并要求 watchdog 恰好 2 个 one-shot 调度点且 `watchdog()` 内无重排。

文档措辞约定：推荐表述为"无后台常驻、无周期轮询"；不写"完全没有任何
定时/延迟任务"（存在上述有限生命周期 callback）。

## 3. NO_SELF_UPDATE_NETWORKING

产品原则：

1. 模块 app process 不主动联网检查版本。
2. 模块不下载 APK。
3. 模块不静默安装/请求安装更新。
4. 模块不周期访问 GitHub/API/CDN。
5. injected Coolapk process 不借用宿主网络权限替模块联网。
6. 更新由用户从 GitHub Releases / 可信分发渠道手动完成。

安全边界（重要）：仅"模块 manifest 无 INTERNET"不够——Xposed 模块代码运行在
`com.coolapk.market` 进程中时继承宿主 UID/网络能力。因此必须同时满足：

- A. 模块 APK 自身无 `INTERNET` / `ACCESS_NETWORK_STATE` 权限；
- B. injected source 与 R8 后的 release DEX 都不存在任何主动网络实现。

Release gate（自动）：

- manifest：`INTERNET`、`ACCESS_NETWORK_STATE` 禁止出现。
- 生产依赖 allowlist：仅 libxposed api/service、kotlin-stdlib（compileOnly）、
  DexKit；禁止 OkHttp/Retrofit/Volley/Ktor client/Cronet/Apache HttpClient/
  update/download/analytics/telemetry SDK。
- 生产源码 denylist：`java.net.URL`、`URLConnection`、`HttpURLConnection`、
  `java.net.Socket`、`javax.net.ssl`、`OkHttpClient`、`Retrofit`、
  `DownloadManager`、`Cronet`、`SocketChannel`、`raw.githubusercontent`、
  `api.github.com`。
- release DEX denylist：对上述 API 的 DEX 类型描述符/字符串做二进制扫描。
- updater absence：不得存在 `UpdateActivity`/`UpdateService`/`UpdateWorker`/
  `UpdateReceiver`/`checkForUpdates`/`downloadApk`/`installApk`/`selfUpdate`。

注：DexKit native 的普通字符串与 README 中的 GitHub Releases 文档 URL 不属于
运行时网络实现；静态 gate 针对 production classes 与 release DEX，
README 文档 URL 不应导致假失败。联网策略的主要 proof 是
manifest / source / R8 DEX / dependency graph，不靠抓包
（injected code 在宿主进程内，抓包无法干净区分且引入环境变量）。

## Gate 用法

```bash
python tools/release_invariant_check.py \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml \
  <release.apk>
```

退出码 0 = 全部 PASS。任何命中都只报告并转人工审查，不自动改代码。
