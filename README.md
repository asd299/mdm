<p align="center">
  <img src="docs/app-icon.png" width="128" alt="Mister 下载管理器应用图标">
</p>

<h1 align="center">Mister Download Manager · Android</h1>

<p align="center">
  iOS 风格界面 · 多线程分段下载 · 断点续传 · 自定义保存目录
  <br>
  当前版本 <b>v1.7.0</b>（versionCode 18）
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
| 链接拦截 | 注册 `http/https` 的 VIEW 与文本分享：浏览器/聊天工具点链接或分享链接时可直接选本应用，悬浮弹出下载确认窗 |
| 剪贴板检测 | 新建任务时自动检测剪贴板中的链接 |
| 文件名智能解析 | 支持 `Content-Disposition`、URL 路径、重定向后的真实名字；探测到新名字会**实时同步到列表**；手动改过名字则锁定不被覆盖 |
| 重复任务检测 | 添加前检查同链接 / 同文件名 / 目录同名文件，弹窗提示并可选择跳过、自动编号改名或覆盖下载 |
| 自定义保存目录 | 侧边栏可改保存路径（SAF）；选择时立即试写校验，完成后按所选目录落盘 |
| 保存位置可追溯 | 任务属性显示文件真正落地位置；写入失败会提示原因并支持「重新保存」 |
| 下载历史持久化 | 任务列表保存为 JSON，重启后不丢失 |
| 双层筛选 | 侧边栏「任务」与「按类型分类」是两个独立维度，默认都是「全部」，可组合成「已完成 + 视频」这类筛选，互不覆盖 |
| 下拉刷新 | 列表下拉即重新读取任务记录并重绘，不打断正在下载的任务 |
| iOS 风格界面 | 极光渐变应用图标、卡片式任务列表、自定义可折叠侧边栏 |
| 顶部快捷操作 | 标题与侧边栏按钮同排；下方一行「全部暂停 / 全部继续 / 清除 / 排序」，作用于整个列表 |
| 内联排序面板 | 点「排序」就地展开，四种排序方式 + 「降序 / 增序」开关，不弹窗；滚动列表自动收起 |

> 原项目的 BT 下载、yt-dlp 视频解析、浏览器扩展拦截等能力依赖桌面环境，本版本聚焦其最核心的 **多线程 HTTP 下载引擎 + 任务管理**，适合移动端使用。

## 项目结构

```
app/src/main/java/com/misterdownloadmanager/mobile/
├── MdmApp.kt              # Application，初始化任务仓库
├── DownloadTask.kt        # 任务/分段模型 + JSON 持久化（Repo）
├── DownloadEngine.kt      # 核心：多线程分段下载引擎（探测/分段/续传/重试/合并/保存）
├── DownloadService.kt     # 前台服务：任务队列与进度通知
├── AddCoordinator.kt      # 添加任务的统一入口：冲突检测 → 弹窗选择 → 入队
├── Conflict.kt            # 同链接 / 同文件名 / 磁盘同名文件的判定与自动编号
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

.github/workflows/
└── release.yml            # 推送 v* 标签后自动构建、签名并发布 Release

tools/
└── gen_icon.py            # 生成应用图标（squircle / 各密度 / 圆形 / 界面用位图）
```

## 构建方法

环境要求：JDK 17+，Android SDK（platform 34 / build-tools 34.0.0）。

```bash
./gradlew assembleRelease            # 产物：app/build/outputs/apk/release/
./key/sign-apk.sh \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  MisterDownloadManager-Android-v1.6.0.apk
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
2. 在浏览器/聊天工具里点链接或长按分享，选择「Mister 下载管理器」，会在当前界面上方**弹出下载确认窗**
   （链接、文件名、大小、是否支持续传都能先看到），确认后再开始，不会打断你正在用的应用；
3. 下载中可暂停 / 继续；完成后点「打开」，文件按侧边栏「保存路径」保存。
4. 侧边栏「保存路径」→「自定义目录」可换到任意文件夹（含 SD 卡）；选择后会立即试写校验，
   不通过会提示原因，不会出现「路径改了、文件却没进去」的情况。
5. 若某个任务写入所选目录失败，列表会标为「已完成 · 未写入所选目录」，长按可「重新保存到所选目录」。
6. 添加任务时会先查重：**同一下载链接**、**同名文件**、**保存目录里已有同名文件**都会弹窗提示，
   并给出三个选择：跳过下载 / 换个文件名（自动加 `(1)`、`(2)`）/ 覆盖下载（删掉原任务与原文件后重下）。
7. 侧边栏「任务」和「按类型分类」是两个**独立**的筛选维度，两组第一行都是「全部」，默认都不筛选。
   例如先点「视频」、再点「已完成」，列表就是「已完成的视频」，标题显示「视频 · 已完成」；
   再点其中一组的「全部」只会解除该维度，另一维度保持不变。
8. 列表**下拉即可刷新**：重新读取任务记录并重绘列表，正在下载的任务不会被中断；刷新完成指示器会自动收起。
   顶部搜索框展开后：**已经输入关键字时**，点输入框以外的位置只收起键盘、保留关键字与筛选结果，
   不会误清；**输入框为空时**点外部则直接退出搜索。任何时候点 ✕ 或按返回键都能退出搜索并清空。
9. 列表项**左滑 = 选中**（进入多选，底栏可全选 / 暂停 / 继续 / 分享 / 删除）；
   已有选中项时再左滑另一项，会把它与上一项之间的所有项一起选中（区间多选）。
10. 列表项**右滑 = 直接打开侧边栏**（与空白处右滑一致，卡片本身不跟随滑动、不触发任何任务动作）；
   暂停 / 继续 / 删除可用列表项上的按钮或长按菜单。
11. 标题「全部下载」与侧边栏按钮在同一行；其下方一行是「全部暂停 / 全部继续 / 清除 / 排序」四个胶囊按钮。
    点「排序」会在其下方就地展开排序面板（添加时间 / 文件名 / 文件大小 / 下载状态），右侧「↓ 降序 / ↑ 增序」
    点一下即可切换方向，选中项蓝底高亮；滚动列表或切换分类会自动收起。清除已完成会二次确认。
    多选状态下操作行自动隐藏，改用底部批量操作条。

## 图标

<p>
  <img src="docs/app-icon.png" width="96" alt="应用图标">
</p>

1024×1024 超椭圆（squircle），深紫到亮紫的对角渐变叠加右上紫色、左下青色两团极光光晕，
中央是圆角笔画的白色 **M** 品牌符号（Mister 的首字母），带一层很浅的投影让符号浮起来。

同一套图标的产物：

- 桌面 PNG：`mipmap-mdpi` ~ `mipmap-xxxhdpi`，另附圆形版本 `ic_launcher_round`
- 自适应图标：`ic_launcher_background`（渐变层）+ `ic_launcher_foreground`（M 矢量，缩放到 66% 安全区）
- 界面内图标：`drawable-nodpi/app_icon.png`，侧边栏顶部直接引用它——
  自适应图标在普通 `ImageView` 里不会被系统裁成 squircle，必须用烘好的位图才不会「桌面圆角、侧边栏方块」

图标由 [`tools/gen_icon.py`](tools/gen_icon.py) 一次生成（需要 `cairosvg`、`numpy`、`Pillow`）：

```bash
python3 tools/gen_icon.py
```

## 发布

推送 `v*` 标签即可触发 [`.github/workflows/release.yml`](.github/workflows/release.yml)：
自动用 JDK 17 + Android SDK 构建 `assembleRelease`，再用 `key/mdm-release.keystore` 签名，
最后创建 GitHub Release 并附上 APK。

```bash
git tag -a v1.6.0 -m "Mister 下载管理器 v1.6.0"
git push origin v1.6.0
```

标签已存在、想补发或重跑时，在 Actions 页面选择 **Release APK → Run workflow**，
填入标签名（如 `v1.6.0`）即可，效果与推送标签一致。

流水线跑完会在仓库里留一个 `ci/<标签>-<success|failure>` 标签：

- 有标签 → 流水线确实执行过，用它判断成功还是失败；
- 标签不存在 → 说明 Actions 没被触发（多为仓库 Settings → Actions 被禁用）。

如果 CI 不可用，可本地构建后手动上传 APK 到 Release：

```bash
./gradlew assembleRelease
./key/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk \
  MisterDownloadManager-Android-v1.6.0.apk
```

## 许可

原项目采用 GPL-3.0 许可；本项目同样以 GPL-3.0 发布。
