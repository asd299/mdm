# Mister Download Manager 安卓版（MDM Mobile）

基于开源项目 [anefzaoui/misterdownloadmanager](https://github.com/anefzaoui/misterdownloadmanager)（Mister Download Manager，纯 Rust 桌面下载管理器）的**核心设计思想**，使用 **Kotlin** 从零实现的 Android 版下载管理器。

## 功能特性（对标原项目核心能力）

| 功能 | 说明 |
|---|---|
| 多线程分段下载 | 自动探测文件大小与 `Accept-Ranges` 支持，将文件拆分为 4–12 个分段并发下载（大文件提速） |
| 断点续传 | 每个分段独立记录已下载偏移并持久化，暂停 / 崩溃 / 网络中断后可从断点继续 |
| 自动重试 | 网络错误自动重试（最多 30 次，指数退避） |
| 暂停 / 恢复 / 删除 | 界面直接操作，状态实时刷新 |
| 前台服务 + 进度通知 | 下载在后台持续进行，通知栏显示总进度 |
| 浏览器接入 | 在浏览器中选择「分享」链接即可呼出本应用添加任务 |
| 剪贴板检测 | 新建任务时自动检测剪贴板中的链接 |
| 文件名智能解析 | 支持 `Content-Disposition`、URL 路径、重名自动加 `(1)` |
| 自定义保存目录 | 侧边栏可改保存路径（SAF）；选择时立即试写校验，完成后按所选目录落盘 |
| 保存位置可追溯 | 任务属性显示文件真正落地位置；写入失败会提示原因并支持「重新保存」 |
| 下载历史持久化 | 任务列表保存为 JSON，重启后不丢失 |
| Material 3 界面 | 中文界面，卡片式任务列表 |

> 原项目的 BT 下载、yt-dlp 视频解析、浏览器扩展拦截等能力依赖桌面环境，本版本聚焦其最核心的 **多线程 HTTP 下载引擎 + 任务管理**，适合移动端使用。

## 项目结构

```
app/src/main/java/com/misterdownloadmanager/mobile/
├── MdmApp.kt              # Application，初始化任务仓库
├── DownloadTask.kt        # 任务/分段模型 + JSON 持久化（Repo）
├── DownloadEngine.kt      # 核心：多线程分段下载引擎（探测/分段/续传/重试/合并/保存）
├── DownloadService.kt     # 前台服务：任务队列与进度通知
├── MainActivity.kt        # 主界面：任务列表、分享链接接入、文件打开
├── SaveStore.kt           # 保存到所选目录：SAF / MediaStore / 默认下载目录（含写入校验）
├── DownloadsAdapter.kt    # 列表适配器（进度、速度、状态）
└── AddDownloadDialog.kt   # 新建下载弹窗（含剪贴板检测）
```

## 构建方法

环境要求：JDK 17+，Android SDK（platform 34 / build-tools 34.0.0）。

```bash
cd MisterDownloadManagerAndroid
./gradlew assembleDebug     # 或 assembleRelease
```

产物位于 `app/build/outputs/apk/`。`local.properties` 需指向你的 SDK 路径（本仓库内已填沙箱路径，本机构建请修改）。

## 使用说明

1. 打开应用，点击右下角 ➕ 按钮输入链接，开始下载；
2. 或在浏览器里长按链接 →「分享」→ 选择「Mister 下载管理器」；
3. 下载中可暂停 / 继续；完成后点「打开」，文件按侧边栏「保存路径」保存。
4. 侧边栏「保存路径」→「自定义目录」可换到任意文件夹（含 SD 卡）；选择后会立即试写校验，
   不通过会提示原因，不会出现「路径改了、文件却没进去」的情况。
5. 若某个任务写入所选目录失败，列表会标为「已完成 · 未写入所选目录」，长按可「重新保存到所选目录」。
6. 列表项左滑 = 删除（会二次确认，点「取消」卡片自动回位）；右滑 = 暂停 / 继续；
   在左 1/3 或列表空白处右滑可拉出侧边栏。

## 许可

原项目采用 GPL-3.0 许可；本项目同样以 GPL-3.0 发布。
