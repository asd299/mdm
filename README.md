<p align="center">
  <img src="docs/app-icon.png" width="128" alt="Mister 下载管理器应用图标">
</p>

<h1 align="center">Mister Download Manager · Android</h1>

<p align="center">
  iOS 风格界面 · 多线程分段下载 · 断点续传 · 自定义保存目录
  <br>
  当前版本 <b>v1.5.2</b>（versionCode 13）
</p>

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
| iOS 风格界面 | 超椭圆应用图标、卡片式任务列表、自定义可折叠侧边栏 |
| 顶部快捷操作 | 「全部暂停 / 全部继续 / 清除已完成」固定在搜索按钮左侧，一键作用于整个列表 |

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

key/
├── mdm-release.keystore   # 安装包签名密钥（PKCS12，与所有已发布 APK 一致）
├── sign-apk.sh            # zipalign + apksigner 一键签名脚本
└── README.md              # 证书信息、密码、签名方法与风险说明

docs/
├── app-icon.png           # 应用图标（512px，本说明页使用）
└── app-icon-1024.png      # 应用图标（1024px 原始尺寸）
```

## 构建方法

环境要求：JDK 17+，Android SDK（platform 34 / build-tools 34.0.0）。

```bash
./gradlew assembleRelease            # 产物：app/build/outputs/apk/release/
./key/sign-apk.sh \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  MisterDownloadManager-Android-v1.5.2.apk
```

`local.properties` 需指向你的 SDK 路径（仓库未包含该文件，首次构建请由 Android Studio 生成或手动创建）。

## 签名与密钥

所有安装包使用 `key/mdm-release.keystore` 签名，证书 SHA-256 为
`44:05:EE:13:56:C4:7A:DF:95:4F:A1:89:BB:BD:62:C0:45:32:8F:66:F7:98:A5:4C:8E:95:0A:70:BC:D1:9F:86`，
因此各版本之间可直接覆盖升级。

密钥库密码、别名、签名命令与注意事项见 **[key/README.md](key/README.md)**。

> 该证书由 Android 调试证书导出（密码 `android`），仅用于个人分发；如需上架应用商店，请另建正式密钥，
> 并注意换证书后旧版本需卸载重装。

## 使用说明

1. 打开应用，点击右下角 ➕ 按钮输入链接，开始下载；
2. 或在浏览器里长按链接 →「分享」→ 选择「Mister 下载管理器」；
3. 下载中可暂停 / 继续；完成后点「打开」，文件按侧边栏「保存路径」保存。
4. 侧边栏「保存路径」→「自定义目录」可换到任意文件夹（含 SD 卡）；选择后会立即试写校验，
   不通过会提示原因，不会出现「路径改了、文件却没进去」的情况。
5. 若某个任务写入所选目录失败，列表会标为「已完成 · 未写入所选目录」，长按可「重新保存到所选目录」。
6. 列表项**左滑 = 选中**（进入多选，底栏可全选 / 暂停 / 继续 / 分享 / 删除）；
   已有选中项时再左滑另一项，会把它与上一项之间的所有项一起选中（区间多选）。
7. 列表项**右滑 = 直接打开侧边栏**（与空白处右滑一致，卡片本身不跟随滑动、不触发任何任务动作）；
   暂停 / 继续 / 删除可用列表项上的按钮或长按菜单。
8. 顶部工具栏（依次为菜单、全部暂停、全部继续、清除已完成、搜索、排序）：清除已完成会二次确认；
   多选状态下这些按钮自动隐藏，改用底部批量操作条。

## 图标

<p>
  <img src="docs/app-icon.png" width="96" alt="应用图标">
</p>

按 Apple HIG 设计：1024×1024、超椭圆（squircle）造型、蓝色垂直渐变、单一白色下载符号（实心箭头 + 托线），
符号位于内 80% 安全区。Android 侧同时提供自适应图标（渐变背景层 + 向量前景层）、各密度 PNG 与圆形版本。

## 许可

原项目采用 GPL-3.0 许可；本项目同样以 GPL-3.0 发布。
