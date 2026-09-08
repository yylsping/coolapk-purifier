# 酷安净化

基于 libxposed Modern API 102 的酷安去广告模块，在酷安原生设置中提供独立的净化开关。

## 功能与默认设置

打开酷安“设置”→“酷安净化”，可分别调整以下 7 项功能：

| 功能 | 全新配置默认值 |
| --- | --- |
| 去除启动/开屏广告和全屏广告 | 开启 |
| 去除首页信息流广告与赞助卡片 | 开启 |
| 去除帖子回复区及评论中的赞助内容 | 开启 |
| 去除自动评论提示 | 关闭 |
| 去除话题与机型推荐 | 关闭 |
| 去除同话题动态 | 关闭 |
| 去除帖子内推广 | 关闭 |

已有配置中的明确开关值会保留，包括 2.2.2 保存的关闭状态。保存失败时会回滚开关状态。调整选项后，强制停止酷安并重新打开。

“去除帖子相关推荐”已移除。“去除同话题动态”仍保留，因此详情页仍可能显示其他相关推荐。

仍可能小概率触发账号风控。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 目标应用 | 酷安，包名 `com.coolapk.market` |
| 酷安版本 | 16.6.1（2608212）可完整使用全部 7 项功能 |
| Android | 6.0 及以上 |
| 框架 | 支持 libxposed Modern API 102 的 LSPosed |
| 模块版本 | 2.3.0 |

开屏和首页信息流保留动态适配；回复区专用赞助卡及四项可选功能目前仅适配酷安 16.6.1（2608212）。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/yylsping/coolapk-purifier/releases) 下载并安装 APK。
2. 在 LSPosed 中启用模块，作用域只选择“酷安”。
3. 强制停止酷安后重新打开。
4. 在酷安“设置”→“酷安净化”中调整选项，按提示重新启动酷安。

模块没有独立桌面配置入口。首次适配时可能显示一次开屏广告。

## 构建

需要 JDK 17 和 Android SDK 35。

运行单元测试并生成调试包：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest testCompatibleUnitTest testReleaseUnitTest assembleDebug
```

调试包输出到 `app/build/outputs/apk/debug/`。生成 release 包可使用 `assembleRelease`，签名配置放在本地 `signing-private/` 中。

## 问题反馈

请在 [Issues](https://github.com/yylsping/coolapk-purifier/issues) 中提供模块、酷安、Android 与 LSPosed 版本，以及开关状态、复现步骤和必要的脱敏日志。

酷安应用目录中的 `files/coolapk_purifier_bootstrap.log` 和 LSPosed 模块日志可用于排查问题。请勿上传账号凭据或未经脱敏的完整设备日志。

## 许可证

本项目采用 [MIT License](LICENSE)。

本项目用于学习、研究和个人设备使用，与酷安及 LSPosed 项目无隶属或认可关系。
