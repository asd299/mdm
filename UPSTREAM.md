# 上游项目跟踪

本项目是 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager)
的 Android 端重写（Kotlin），沿用其多线程分段下载与任务模型的设计思路。
上游是纯 Rust 桌面下载管理器、发布节奏较快，这里记录我们对照的版本与核对时间，便于后续跟进。

## 当前基线

| 项 | 内容 |
|---|---|
| 上游仓库 | https://github.com/anefzaoui/misterdownloadmanager |
| 参考版本 | v0.5.2 |
| 上游发布日期 | 2026-11-09 |
| 最近核对日期 | 2026-11-24 |
| 对应本应用版本 | 0.5.2.1001（项目第一版） |

## 核对记录

| 核对日期 | 上游版本 | 上游发布日期 | 说明 |
|---|---|---|---|
| 2026-11-24 | v0.5.2 | 2026-11-09 | 建立基线，作为本应用第一个版本 |

## 版本号规则

`<上游版本号>.<迭代号>`，迭代号从 `1001` 起：

- 同一上游基线内每次发版 +1：`0.5.2.1001` → `0.5.2.1002` → `0.5.2.1003` …
- 上游升到新版本后重置：上游 `v0.6.0` → 本应用 `0.6.0.1001`

这样只看版本号就知道对应上游哪一版、在其之上迭代了多少次。

## 跟进流程

1. 查看上游 [Releases](https://github.com/anefzaoui/misterdownloadmanager/releases) 与提交记录
2. 有新版本时先读 Release Notes，判断是否涉及下载引擎、分段策略、断点续传等核心逻辑
3. 需要跟进的，把 `app/build.gradle.kts` 的 `versionName` 改为 `<新上游版本>.1001`（`versionCode` 继续 +1），
   并在 `Changelog.kt` 与 `CHANGELOG.md` 增加一条记录
4. 核对完成后在下方「核对记录」追加一行，同时更新 `Changelog.upstream` 的 `version` / `released` / `checkedAt`，
   关于页会自动显示新的基线
5. 只做界面或本地体验优化、不涉及上游逻辑的迭代，基线不动，迭代号 +1

> 上游是桌面程序（Windows / macOS / Linux），BT 下载、yt-dlp 解析、浏览器扩展等能力依赖桌面环境，
> 不做强行移植；移动端只对齐其 HTTP 下载引擎与任务模型。
